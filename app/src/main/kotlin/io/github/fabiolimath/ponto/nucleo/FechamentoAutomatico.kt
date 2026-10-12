package io.github.fabiolimath.ponto.nucleo

import io.github.fabiolimath.ponto.sigrh.Resultado
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** O que fazer depois de um fechamento automático. */
enum class DesfechoDoFechamento {
    /** Fechou: segue como o botão (notificação, lembrete cancelado, PIT automático). */
    FECHADO,

    /** Notifica a falha, com Tentar de novo. */
    NOTIFICAR_FALHA,

    /** Só o log. */
    SILENCIO,
}

/**
 * Fechar o ponto sozinho nos horários das Configurações (`fechar_horario1` e `fechar_horario2`),
 * de segunda a sexta, se ele estiver aberto: quem diz se está aberto é o SIGRH.
 */
object FechamentoAutomatico {
    const val ROTULO = "Fechar ponto (automático)"

    /** O nome no log: `fechar_ponto (automático)`. */
    val NOME_NO_LOG = "${Acao.FECHAR_PONTO.chave} (automático)"

    /** Uma repetição (o sistema parou o trabalho e o refez) depois disto não fecha mais. */
    val ATRASO_MAXIMO: Duration = Duration.ofMinutes(30)

    private val formato = Regex("""(\d{1,2}):(\d{2})""")

    /** O horário "hh:mm" (de 00:00 a 23:59), ou null se inválido ou em branco. */
    fun lerHorario(texto: String): LocalTime? {
        val (h, m) = formato.matchEntire(texto.trim())?.destructured ?: return null
        val hora = h.toInt()
        val minuto = m.toInt()
        if (hora !in 0..23 || minuto !in 0..59) return null
        return LocalTime.of(hora, minuto)
    }

    /** Os horários válidos das Configurações, sem repetição; vazio com a opção desligada. */
    fun horarios(preferencias: Preferencias): List<LocalTime> =
        if (!preferencias.fecharAutomatico) emptyList()
        else listOfNotNull(lerHorario(preferencias.fecharHorario1), lerHorario(preferencias.fecharHorario2))
            .distinct().sorted()

    /** O próximo dos [horarios] depois de [agora] (estritamente), de segunda a sexta; null se não há. */
    fun proximo(agora: LocalDateTime, horarios: List<LocalTime>): LocalDateTime? {
        if (horarios.isEmpty()) return null
        var dia = agora.toLocalDate()
        while (true) {
            if (Notificacoes.diaUtil(dia)) {
                horarios.sorted().map { dia.atTime(it) }.firstOrNull { it.isAfter(agora) }?.let { return it }
            }
            dia = dia.plusDays(1)
        }
    }

    /** Se o fechamento previsto para [previsto] já passou há mais de [ATRASO_MAXIMO]. */
    fun atrasado(previsto: LocalDateTime, agora: LocalDateTime) =
        Duration.between(previsto, agora) > ATRASO_MAXIMO

    /** Se o SIGRH disse que não há entrada aberta (o ponto não estava aberto). */
    fun nadaAFechar(mensagem: String) = "não há entrada aberta" in mensagem

    /**
     * [resultado] null é um erro inesperado. [pontoAbertoNoApp]: o app sabe que o ponto está aberto
     * (abriu hoje, pelo app, depois do último fechamento).
     *
     * - Fechou: como o botão.
     * - Não havia entrada aberta ou cancelado: em silêncio.
     * - Envio incerto: notifica (o registro pode ter saído).
     * - Outra falha (fora da rede, sem conexão, recusa): notifica só se o app sabe que o ponto está
     *   aberto; senão, num dia sem ponto aberto, cada horário daria um aviso de falha.
     */
    fun desfecho(resultado: Resultado?, pontoAbertoNoApp: Boolean): DesfechoDoFechamento = when {
        resultado is Resultado.Sucesso -> DesfechoDoFechamento.FECHADO
        resultado == Resultado.Cancelado -> DesfechoDoFechamento.SILENCIO
        resultado is Resultado.Recusado && nadaAFechar(resultado.mensagem) -> DesfechoDoFechamento.SILENCIO
        resultado is Resultado.EnvioIncerto -> DesfechoDoFechamento.NOTIFICAR_FALHA
        pontoAbertoNoApp -> DesfechoDoFechamento.NOTIFICAR_FALHA
        else -> DesfechoDoFechamento.SILENCIO
    }
}
