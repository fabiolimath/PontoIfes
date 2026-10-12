package io.github.fabiolimath.ponto.sigrh

/** Resultado de uma ação no SIGRH (abrir ou fechar o ponto, registrar o PIT). */
sealed interface Resultado {
    /** Última linha da saída: o texto da notificação. */
    val mensagem: String

    /** Inclui "o ponto já estava aberto" e o PIT pedido para sábado ou domingo (nada feito). */
    data class Sucesso(override val mensagem: String) : Resultado

    /** O SIGRH recusou o IP: o celular está fora da rede do campus. */
    data class ForaDaRede(override val mensagem: String) : Resultado

    /** Timeout, DNS, sem conexão, SIGRH fora do ar. */
    data class FalhaDeConexao(override val mensagem: String) : Resultado

    /** Senha errada, erro informado pelo SIGRH ou página inesperada. */
    data class Recusado(override val mensagem: String) : Resultado

    /** O envio final saiu, mas a confirmação não veio: conferir no SIGRH. */
    data class EnvioIncerto(override val mensagem: String) : Resultado

    /** O usuário cancelou antes do envio final (botão Cancelar). */
    data object Cancelado : Resultado {
        override val mensagem = "⏹️ Cancelado."
    }
}

/** Código de saída que vai para o log, como o dos scripts Python: 0, 1 ou 130 (cancelado). */
val Resultado.codigo: Int
    get() = when (this) {
        is Resultado.Sucesso -> 0
        Resultado.Cancelado -> 130
        else -> 1
    }
