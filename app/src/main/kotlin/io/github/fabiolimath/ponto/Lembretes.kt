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
     * Depois de abrir o ponto com sucesso: agenda o lembrete para o tempo configurado depois da
     * [abertura] (padrão: [agora]; na abertura automática de um ponto já aberto, a hora de entrada).
     * Um lembrete já pendente fica como está (abrir de novo não o adia), e um que já passou não é agendado.
     */
    fun agendarAposAbrir(contexto: Context, agora: LocalDateTime = LocalDateTime.now(), abertura: LocalDateTime = agora) {
        val quando = Lembrete.quando(Dados.preferencias(contexto), arquivo(contexto), agora, abertura) ?: return
        agendar(contexto, quando, Lembrete.texto(abertura))
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

/**
 * Reagenda o lembrete pendente e refaz o gatilho da Wi-Fi depois de reiniciar o celular ou de
 * atualizar o app (o Android perde os dois); na atualização, apaga também os restos da versão Python.
 */
class InicioReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Lembretes.reagendar(contexto)
            try {
                Wifi.registrar(contexto, forcar = true)
            } catch (e: Exception) {
                android.util.Log.w("Ponto", "Erro ao registrar o gatilho da Wi-Fi", e)
            }
        }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Legado.apagarPython(contexto)
    }
}
