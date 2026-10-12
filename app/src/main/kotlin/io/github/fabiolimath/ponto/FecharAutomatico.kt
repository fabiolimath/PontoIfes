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
import io.github.fabiolimath.ponto.nucleo.FechamentoAutomatico
import io.github.fabiolimath.ponto.nucleo.GatilhoWifi
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Fechar o ponto sozinho nos horários das Configurações: um alarme exato do AlarmManager para o
 * próximo horário (segunda a sexta, fixos ou os de cada dia), que ao tocar agenda o seguinte e põe o fechamento na fila do
 * WorkManager, como a abertura pela Wi-Fi. Funciona com o app fechado.
 */
object FecharAutomatico {
    private const val TAG = "Ponto"
    private const val TRABALHO = "fechar_automatico"
    const val EXTRA_PREVISTO = "previsto"

    private fun alarme(contexto: Context, previsto: LocalDateTime? = null): PendingIntent {
        val intent = Intent(contexto, FecharAutomaticoReceiver::class.java)
        if (previsto != null) intent.putExtra(EXTRA_PREVISTO, previsto.toString())
        return PendingIntent.getBroadcast(
            contexto, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Agenda o alarme do próximo horário depois de [depoisDe] (ou o desfaz, com a opção desligada).
     * Chamado ao abrir o app, ao salvar as Configurações, ao reiniciar o celular, ao atualizar o app
     * e a cada alarme; refazer o mesmo alarme não muda nada.
     *
     * Exato quando o Android deixa (USE_EXACT_ALARM, dado na instalação no Android 13+); senão, o
     * alarme comum, que pode atrasar minutos com o celular em repouso.
     */
    fun agendar(contexto: Context, depoisDe: LocalDateTime = LocalDateTime.now()) {
        val app = contexto.applicationContext
        val alarmes = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val quando = FechamentoAutomatico.proximo(depoisDe, Dados.preferencias(app))
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

    /** Põe o fechamento na fila; com um já na fila ou rodando, este é descartado. */
    fun executar(contexto: Context, previsto: LocalDateTime) {
        val trabalho = OneTimeWorkRequestBuilder<FecharAutomaticoWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(EXTRA_PREVISTO to previsto.toString()))
            .build()
        WorkManager.getInstance(contexto).enqueueUniqueWork(TRABALHO, ExistingWorkPolicy.KEEP, trabalho)
    }

    fun anotar(contexto: Context, texto: String) =
        Dados.registro(contexto).anotar(FechamentoAutomatico.NOME_NO_LOG, LocalDateTime.now(), texto)

    fun lerPrevisto(texto: String?): LocalDateTime? =
        try {
            texto?.let { LocalDateTime.parse(it) }
        } catch (e: DateTimeParseException) {
            null
        }
}

/** O alarme de um horário de fechamento: agenda o próximo e põe este na fila. */
class FecharAutomaticoReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        val app = contexto.applicationContext
        try {
            val agora = LocalDateTime.now()
            val previsto = FecharAutomatico.lerPrevisto(intent.getStringExtra(FecharAutomatico.EXTRA_PREVISTO)) ?: agora
            // O seguinte conta a partir do previsto, mesmo se o alarme tocar um pouco antes.
            FecharAutomatico.agendar(app, if (previsto.isAfter(agora)) previsto else agora)
            if (Dados.preferencias(app).fecharAutomatico) FecharAutomatico.executar(app, previsto)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro no alarme do fechamento automático", e)
        }
    }
}

/**
 * O fechamento automático (trabalho expedito do WorkManager): roda o mesmo cliente do botão. Se o
 * sistema o parar, o WorkManager o refaz depois, mas não muito depois do horário.
 */
class FecharAutomaticoWorker(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!Dados.preferencias(app).fecharAutomatico) return Result.success()
        val agora = LocalDateTime.now()
        val previsto = FecharAutomatico.lerPrevisto(inputData.getString(FecharAutomatico.EXTRA_PREVISTO)) ?: agora
        if (FechamentoAutomatico.atrasado(previsto, agora)) {
            FecharAutomatico.anotar(app, "Ignorado: o horário das ${previsto.toLocalTime()} passou há mais de 30 min.")
            return Result.success()
        }
        val credenciais = Dados.credenciais(app)
        if (credenciais == null || !credenciais.completas) {
            FecharAutomatico.anotar(app, "Ignorado: usuário e senha do SIGRH não definidos.")
            return Result.success()
        }
        val aberto = GatilhoWifi.pontoAberto(agora, Wifi.estado(app))
        if (!Execucao.fecharAutomatico(app, credenciais, aberto)) {
            FecharAutomatico.anotar(app, "Ignorado: outra ação em andamento.")
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = Wifi.emAndamento(applicationContext, "Fechando o ponto…")
}
