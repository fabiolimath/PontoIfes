package io.github.fabiolimath.ponto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.fabiolimath.ponto.nucleo.Acao
import io.github.fabiolimath.ponto.nucleo.Aviso
import io.github.fabiolimath.ponto.nucleo.Lembrete
import io.github.fabiolimath.ponto.nucleo.Notificacoes

/** As notificações do sistema (canal `resultados`): o resultado de cada execução e o lembrete. */
object Notificador {
    private fun gerenciador(contexto: Context) =
        contexto.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun construtor(contexto: Context, titulo: String, texto: String): NotificationCompat.Builder {
        gerenciador(contexto).createNotificationChannel(
            NotificationChannel(Notificacoes.CANAL, Notificacoes.NOME_DO_CANAL, NotificationManager.IMPORTANCE_DEFAULT)
        )
        // Tocar na notificação abre o app (o ícone monocromático próprio, o logo do IFES: o do app,
        // adaptativo, pode derrubar a barra de status em alguns Androids). O verde do app pinta o
        // círculo em volta do ícone (One UI, Android 12+) e o nome do app.
        val abrir = contexto.packageManager.getLaunchIntentForPackage(contexto.packageName)
        val toque = PendingIntent.getActivity(contexto, 0, abrir, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(contexto, Notificacoes.CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setColor(ContextCompat.getColor(contexto, R.color.verde))
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(toque)
            .setAutoCancel(true)
    }

    /**
     * Um botão que abre o app e executa [acao] (com a [data] do PIT), como o "Sempre Iniciar Nova Cópia"
     * do Tasker: com o app já aberto, só trazê-lo para a frente não executaria a ação.
     */
    private fun botao(contexto: Context, codigo: Int, rotulo: String, acao: Acao, data: String?) =
        NotificationCompat.Action.Builder(
            R.drawable.ic_notificacao, rotulo,
            PendingIntent.getActivity(
                contexto, codigo,
                MainActivity.intentDaAcao(contexto, acao, data)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    /** Mostra o resultado de uma execução; um [Aviso] com o mesmo ident substitui o anterior. */
    fun mostrar(contexto: Context, aviso: Aviso) {
        val notificacao = construtor(contexto, aviso.titulo, aviso.texto)
        aviso.botao?.let {
            notificacao.addAction(botao(contexto, 100 + aviso.ident, it.rotulo, it.acao, it.data))
        }
        gerenciador(contexto).notify(aviso.ident, notificacao.build())
    }

    /** O lembrete de fechar o ponto, com o botão "Fechar ponto". */
    fun mostrarLembrete(contexto: Context, titulo: String, texto: String) {
        val notificacao = construtor(contexto, titulo, texto)
            .addAction(botao(contexto, 200, Acao.FECHAR_PONTO.rotulo, Acao.FECHAR_PONTO, null))
        gerenciador(contexto).notify(Notificacoes.IDENT_LEMBRETE, notificacao.build())
    }

    /** Apaga a notificação [ident], se estiver na tela. */
    fun cancelar(contexto: Context, ident: Int) = gerenciador(contexto).cancel(ident)
}

/** Dispara o lembrete agendado pelo AlarmManager, com o app fechado ou suspenso. */
class LembreteReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        Notificador.mostrarLembrete(
            contexto,
            intent.getStringExtra(Lembretes.EXTRA_TITULO) ?: Lembrete.TITULO,
            intent.getStringExtra(Lembretes.EXTRA_TEXTO) ?: Lembrete.texto(null),
        )
    }
}
