package io.github.fabiolimath.ponto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.fabiolimath.ponto.nucleo.PitAutomatico
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Registrar o PIT do dia sozinho no horário das Configurações, de segunda a sexta: um alarme exato
 * do AlarmManager, que ao tocar agenda o do dia seguinte e põe o registro na fila do WorkManager,
 * como o fechamento automático. Funciona com o app fechado.
 */
object RegistrarPitAutomatico {
    private const val TAG = "Ponto"
    private const val TRABALHO = "pit_automatico"

    private fun alarme(contexto: Context, previsto: LocalDateTime? = null): PendingIntent {
        val intent = Intent(contexto, PitAutomaticoReceiver::class.java)
        if (previsto != null) intent.putExtra(FecharAutomatico.EXTRA_PREVISTO, previsto.toString())
        return PendingIntent.getBroadcast(
            contexto, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Agenda o alarme do próximo horário depois de [depoisDe] (ou o desfaz, com a opção desligada).
     * Chamado nos mesmos momentos que o do fechamento automático ([FecharAutomatico.agendar]).
     */
    fun agendar(contexto: Context, depoisDe: LocalDateTime = LocalDateTime.now()) {
        val app = contexto.applicationContext
        val alarmes = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val quando = PitAutomatico.proximo(depoisDe, Dados.preferencias(app))
        if (quando == null) {
            alarmes.cancel(alarme(app))
            return
        }
        val instante = quando.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intencao = alarme(app, quando)
        val exato = Build.VERSION.SDK_INT < 31 || alarmes.canScheduleExactAlarms()
        try {
            if (exato) {
                alarmes.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instante, intencao)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sem permissão para o alarme exato", e)
        }
        alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instante, intencao)
    }

    /** Põe o registro na fila; com um já na fila ou rodando, este é descartado. */
    fun executar(contexto: Context, previsto: LocalDateTime) {
        val trabalho = OneTimeWorkRequestBuilder<PitAutomaticoWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(FecharAutomatico.EXTRA_PREVISTO to previsto.toString()))
            .build()
        WorkManager.getInstance(contexto).enqueueUniqueWork(TRABALHO, ExistingWorkPolicy.KEEP, trabalho)
    }

    fun anotar(contexto: Context, texto: String) =
        Dados.registro(contexto).anotar(PitAutomatico.NOME_NO_LOG, LocalDateTime.now(), texto)
}

/** O alarme do horário do PIT: agenda o do dia seguinte e põe este na fila. */
class PitAutomaticoReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        val app = contexto.applicationContext
        try {
            val agora = LocalDateTime.now()
            val previsto = FecharAutomatico.lerPrevisto(intent.getStringExtra(FecharAutomatico.EXTRA_PREVISTO)) ?: agora
            // O seguinte conta a partir do previsto, mesmo se o alarme tocar um pouco antes.
            RegistrarPitAutomatico.agendar(app, if (previsto.isAfter(agora)) previsto else agora)
            if (Dados.preferencias(app).pitAutomatico) RegistrarPitAutomatico.executar(app, previsto)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro no alarme do PIT automático", e)
        }
    }
}

/**
 * O PIT automático (trabalho expedito do WorkManager): roda o mesmo cliente do botão. Com outra ação
 * em andamento (o fechamento automático no mesmo horário, por exemplo), tenta de novo em seguida.
 */
class PitAutomaticoWorker(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!Dados.preferencias(app).pitAutomatico) return Result.success()
        val agora = LocalDateTime.now()
        val previsto = FecharAutomatico.lerPrevisto(inputData.getString(FecharAutomatico.EXTRA_PREVISTO)) ?: agora
        val credenciais = Dados.credenciais(app)
        if (credenciais == null || !credenciais.completas) {
            RegistrarPitAutomatico.anotar(app, "Ignorado: usuário e senha do SIGRH não definidos.")
            return Result.success()
        }
        val dia = PitAutomatico.dia(previsto, agora.toLocalDate())
        return if (Execucao.pitAutomatico(app, credenciais, dia)) Result.success() else Result.retry()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = Wifi.emAndamento(applicationContext, "Registrando o PIT…")
}
