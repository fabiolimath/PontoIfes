package io.github.fabiolimath.ponto.sigrh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call

/**
 * Pedido de cancelamento de uma execução (o botão Cancelar).
 *
 * É atendido antes de cada tentativa, durante as esperas e as requisições, e logo antes do
 * envio final, nunca depois dele: o envio final segue até o fim e o resultado é informado.
 * Um sinal próprio, e não o cancelamento da corrotina, para que o app sempre receba o
 * [Resultado] (inclusive o de um envio que já tinha saído quando o usuário cancelou).
 */
class Cancelamento {
    private val sinal = CompletableDeferred<Unit>()
    private val chamadas = mutableSetOf<Call>()

    val pedido: Boolean get() = sinal.isCompleted

    /** Pede para parar; interrompe a requisição em andamento, exceto o envio final. */
    fun cancelar() {
        sinal.complete(Unit)
        synchronized(chamadas) { chamadas.toList() }.forEach { it.cancel() }
    }

    internal fun conferir() {
        if (pedido) throw Cancelado()
    }

    /** Espera [segundos], parando na hora se o cancelamento for pedido. */
    internal suspend fun esperar(segundos: Long) {
        withTimeoutOrNull(segundos * 1000) { sinal.await() }
        conferir()
    }

    internal fun acompanhar(chamada: Call) {
        synchronized(chamadas) { chamadas += chamada }
        if (pedido) chamada.cancel()
    }

    internal fun largar(chamada: Call) {
        synchronized(chamadas) { chamadas -= chamada }
    }
}

/** O usuário pediu para cancelar: a execução para e o resultado é [Resultado.Cancelado]. */
internal class Cancelado : Exception("cancelado")
