package io.github.fabiolimath.ponto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.fabiolimath.ponto.nucleo.Lembrete
import io.github.fabiolimath.ponto.nucleo.Notificacoes
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * O lembrete de fechar o ponto: um alarme do AlarmManager (`setAndAllowWhileIdle`, funciona com o app
 * fechado; no Doze pode atrasar minutos) e o horário em `lembrete.txt`.
 */
object Lembretes {
    const val EXTRA_TITULO = "titulo"
    const val EXTRA_TEXTO = "texto"

    private fun arquivo(contexto: Context) = Dados.arquivo(contexto, "lembrete.txt")

    /** O mesmo PendingIntent para agendar e cancelar (igual ao da versão Python: o alarme antigo vale). */
    private fun alarme(contexto: Context, titulo: String = "", texto: String = ""): PendingIntent {
        val intent = Intent(contexto, LembreteReceiver::class.java)
            .putExtra(EXTRA_TITULO, titulo)
            .putExtra(EXTRA_TEXTO, texto)
        return PendingIntent.getBroadcast(
            contexto, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun agendar(contexto: Context, quando: LocalDateTime, texto: String) {
        val alarmes = contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val instante = quando.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmes.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instante, alarme(contexto, Lembrete.TITULO, texto))
    }

    /**
     * Depois de abrir o ponto com sucesso: agenda o lembrete para o tempo configurado depois de [agora].
     * Um lembrete já pendente fica como está (abrir de novo não o adia).
     */
    fun agendarAposAbrir(contexto: Context, agora: LocalDateTime = LocalDateTime.now()) {
        val quando = Lembrete.quando(Dados.preferencias(contexto), arquivo(contexto), agora) ?: return
        agendar(contexto, quando, Lembrete.texto(agora))
        Lembrete.registrar(arquivo(contexto), quando)
    }

    /** Desfaz o alarme, esquece o horário e apaga a notificação do lembrete, se estiver na tela. */
    fun cancelar(contexto: Context) {
        Lembrete.apagar(arquivo(contexto))
        val alarmes = contexto.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmes.cancel(alarme(contexto))
        Notificador.cancelar(contexto, Notificacoes.IDENT_LEMBRETE)
    }

    /**
     * Agenda de novo o lembrete pendente de `lembrete.txt`, que o Android perde ao reiniciar o
     * celular e ao atualizar o app. Já passou (celular desligado na hora) ou desligado: esquece.
     */
    fun reagendar(contexto: Context) {
        val quando = Lembrete.pendente(arquivo(contexto))
        if (quando == null || !Dados.preferencias(contexto).lembreteFechar) {
            Lembrete.apagar(arquivo(contexto))
            return
        }
        agendar(contexto, quando, Lembrete.texto(null))
    }
}

/** Reagenda o lembrete pendente depois de reiniciar o celular ou de atualizar o app. */
class InicioReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Lembretes.reagendar(contexto)
        }
    }
}
