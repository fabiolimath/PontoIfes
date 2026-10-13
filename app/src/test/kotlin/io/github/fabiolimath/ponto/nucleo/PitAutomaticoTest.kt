package io.github.fabiolimath.ponto.nucleo

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PitAutomaticoTest {
    private val ligado = Preferencias(pitAutomatico = true, pitHorario = "17:00")

    // 06/10/2026 é uma terça; 09/10, sexta; 12/10, segunda.
    private fun em(dia: Int, hora: Int, minuto: Int = 0) = LocalDateTime.of(2026, 10, dia, hora, minuto)

    @Test
    fun desligado_ou_horario_invalido_nao_agenda() {
        assertNull(PitAutomatico.proximo(em(6, 8), Preferencias()))
        assertNull(PitAutomatico.proximo(em(6, 8), ligado.copy(pitHorario = "")))
    }

    @Test
    fun proximo_horario_de_segunda_a_sexta() {
        assertEquals(em(6, 17), PitAutomatico.proximo(em(6, 8), ligado))
        // Na hora exata ou depois, o do dia seguinte.
        assertEquals(em(7, 17), PitAutomatico.proximo(em(6, 17), ligado))
        assertEquals(em(7, 17), PitAutomatico.proximo(em(6, 18), ligado))
        // Da sexta depois do horário, e do fim de semana, vai para segunda.
        assertEquals(em(12, 17), PitAutomatico.proximo(em(9, 17, 30), ligado))
        assertEquals(em(12, 17), PitAutomatico.proximo(em(10, 9), ligado))
        assertEquals(em(12, 8, 30), PitAutomatico.proximo(em(11, 9), ligado.copy(pitHorario = "8:30")))
    }

    @Test
    fun pit_depois_de_todos_os_fechamentos_automaticos() {
        val fixos = Preferencias(fecharModo = Preferencias.FECHAR_FIXOS, fecharHorario1 = "12:00", fecharHorario2 = "18:00")
        assertNull(PitAutomatico.fechamentoDepoisDoPit(fixos, LocalTime.of(18, 30)))
        assertEquals(LocalTime.of(18, 0), PitAutomatico.fechamentoDepoisDoPit(fixos, LocalTime.of(18, 0)))
        assertEquals(LocalTime.of(18, 0), PitAutomatico.fechamentoDepoisDoPit(fixos, LocalTime.of(17, 0)))
        // Desligado, qualquer horário serve.
        assertNull(PitAutomatico.fechamentoDepoisDoPit(Preferencias(), LocalTime.of(8, 0)))
        // Avançado: vale o maior de todos os dias.
        val avancado = Preferencias(
            fecharModo = Preferencias.FECHAR_AVANCADO,
            fecharDias = listOf(listOf("12:00", ""), listOf("", ""), listOf("", "21:00"), listOf("", ""), listOf("", "")),
        )
        assertEquals(LocalTime.of(21, 0), PitAutomatico.fechamentoDepoisDoPit(avancado, LocalTime.of(18, 30)))
        assertNull(PitAutomatico.fechamentoDepoisDoPit(avancado, LocalTime.of(21, 30)))
    }

    @Test
    fun dia_do_pit() {
        val hoje = LocalDate.of(2026, 10, 6)
        assertNull(PitAutomatico.dia(em(6, 17), hoje))
        assertEquals(LocalDate.of(2026, 10, 5), PitAutomatico.dia(em(5, 17), hoje))
    }
}
