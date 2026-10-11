package io.github.fabiolimath.ponto.sigrh

/** Resultado de uma ação no SIGRH (abrir ou fechar o ponto, registrar o PIT). */
sealed interface Resultado {
    /** Última linha da saída: o texto da notificação. */
    val mensagem: String

    /** Inclui "o ponto já estava aberto". */
    data class Sucesso(override val mensagem: String) : Resultado

    /** O SIGRH recusou o IP: o celular está fora da rede do campus. */
    data class ForaDaRede(override val mensagem: String) : Resultado

    /** Timeout, DNS, sem conexão, SIGRH fora do ar. */
    data class FalhaDeConexao(override val mensagem: String) : Resultado

    /** Senha errada ou erro informado pelo SIGRH. */
    data class Recusado(override val mensagem: String) : Resultado

    /** O envio final saiu, mas a confirmação não veio: conferir no SIGRH. */
    data class EnvioIncerto(override val mensagem: String) : Resultado
}
