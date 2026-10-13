package io.github.fabiolimath.ponto.nucleo

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Registrar o PIT do dia sozinho, de segunda a sexta, no horário das Configurações (`pit_horario`),
 * também nos dias sem ponto (trabalho remoto). Não depende de fechar o ponto, mas o SIGRH pede o
 * ponto do dia fechado: o horário deve vir depois do último fechamento.
 */
object PitAutomatico {
    const val ROTULO = "Registrar PIT (automático)"

    /** O nome no log: `registrar_pit (automático)`. */
    val NOME_NO_LOG = "${Acao.REGISTRAR_PIT.chave} (automático)"

    /** O próximo horário depois de [agora] (estritamente), de segunda a sexta; null com a opção desligada. */
    fun proximo(agora: LocalDateTime, preferencias: Preferencias): LocalDateTime? {
        if (!preferencias.pitAutomatico) return null
        val hora = FechamentoAutomatico.lerHorario(preferencias.pitHorario) ?: return null
        return FechamentoAutomatico.proximo(agora) { dia -> if (Notificacoes.diaUtil(dia)) listOf(hora) else emptyList() }
    }

    /**
     * O dia do PIT previsto para [previsto]: null (o do dia) se é hoje; se o sistema só rodou o
     * trabalho num dia seguinte, a data do previsto.
     */
    fun dia(previsto: LocalDateTime, hoje: LocalDate): LocalDate? = previsto.toLocalDate().takeIf { it != hoje }
}
