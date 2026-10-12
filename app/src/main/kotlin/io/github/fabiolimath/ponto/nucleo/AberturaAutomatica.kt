package io.github.fabiolimath.ponto.nucleo

import io.github.fabiolimath.ponto.sigrh.Resultado
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * O que fazer depois de uma abertura automática (Wi-Fi). [baseDoLembrete]: a hora de abertura a
 * partir da qual o lembrete de fechar é contado (null = não agendar).
 */
data class Desfecho(
    val notificarSucesso: Boolean = false,
    val notificarFalha: Boolean = false,
    val anotarAbertura: Boolean = false,
    val baseDoLembrete: LocalDateTime? = null,
)

/** As regras do resultado da abertura automática, sem Android. */
object AberturaAutomatica {
    const val ROTULO = "Abrir ponto (automático)"

    /** O nome no log: `abrir_ponto (automático)`. */
    val NOME_NO_LOG = "${Acao.ABRIR_PONTO.chave} (automático)"

    private val horaDeEntrada = Regex("""às (\d{1,2}):(\d{2})""")

    /** Se o SIGRH disse que o ponto já estava aberto (sucesso sem registro novo). */
    fun jaEstavaAberto(mensagem: String) = "já estava aberto" in mensagem

    /** A hora de entrada que o SIGRH mostra ("... às 07:46"), no dia de [agora]; null se faltar. */
    fun horaDeEntrada(mensagem: String, agora: LocalDateTime): LocalDateTime? {
        val (h, m) = horaDeEntrada.find(mensagem)?.destructured ?: return null
        val hora = h.toInt()
        val minuto = m.toInt()
        if (hora !in 0..23 || minuto !in 0..59) return null
        return agora.toLocalDate().atTime(LocalTime.of(hora, minuto))
    }

    /**
     * [resultado] null é um erro inesperado (tratado como recusa). [redeConfirmada]: o nome da Wi-Fi
     * foi lido e é o configurado. [erroJaNotificadoHoje]: já houve uma notificação de erro automática hoje.
     *
     * - Entrada registrada: notifica, anota a abertura e agenda o lembrete a partir de agora.
     * - Já estava aberto: em silêncio, anota a abertura e agenda o lembrete a partir da hora de entrada.
     * - Fora da rede ou falha de conexão: em silêncio (só o log).
     * - Cancelado: nada.
     * - Recusado ou envio incerto: notifica a falha só com a rede confirmada e uma vez por dia.
     */
    fun desfecho(
        resultado: Resultado?,
        redeConfirmada: Boolean,
        erroJaNotificadoHoje: Boolean,
        agora: LocalDateTime,
    ): Desfecho = when (resultado) {
        is Resultado.Sucesso ->
            if (jaEstavaAberto(resultado.mensagem)) {
                Desfecho(anotarAbertura = true, baseDoLembrete = horaDeEntrada(resultado.mensagem, agora))
            } else {
                Desfecho(notificarSucesso = true, anotarAbertura = true, baseDoLembrete = agora)
            }
        is Resultado.ForaDaRede, is Resultado.FalhaDeConexao, Resultado.Cancelado -> Desfecho()
        is Resultado.Recusado, is Resultado.EnvioIncerto, null ->
            Desfecho(notificarFalha = redeConfirmada && !erroJaNotificadoHoje)
    }
}
