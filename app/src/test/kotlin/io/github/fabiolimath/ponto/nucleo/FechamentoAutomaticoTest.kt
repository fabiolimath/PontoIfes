package io.github.fabiolimath.ponto.nucleo

import io.github.fabiolimath.ponto.sigrh.Resultado
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FechamentoAutomaticoTest {
    private val meioDia = LocalTime.of(12, 0)
    private val seis = LocalTime.of(18, 0)

    @Test
    fun le_horarios_validos() {
        assertEquals(LocalTime.of(8, 5), FechamentoAutomatico.lerHorario("8:05"))
        assertEquals(LocalTime.of(0, 0), FechamentoAutomatico.lerHorario(" 00:00 "))
        assertEquals(LocalTime.of(23, 59), FechamentoAutomatico.lerHorario("23:59"))
        assertNull(FechamentoAutomatico.lerHorario(""))
        assertNull(FechamentoAutomatico.lerHorario("24:00"))
        assertNull(FechamentoAutomatico.lerHorario("12:60"))
        assertNull(FechamentoAutomatico.lerHorario("1200"))
        assertNull(FechamentoAutomatico.lerHorario("12:0"))
    }

    @Test
    fun horarios_das_configuracoes() {
        val ligada = Preferencias(fecharAutomatico = true, fecharHorario1 = "18:00", fecharHorario2 = "12:00")
        assertEquals(listOf(meioDia, seis), FechamentoAutomatico.horarios(ligada))
        assertEquals(listOf(seis), FechamentoAutomatico.horarios(ligada.copy(fecharHorario2 = "")))
        assertEquals(listOf(seis), FechamentoAutomatico.horarios(ligada.copy(fecharHorario2 = "18:00")))
        assertEquals(emptyList(), FechamentoAutomatico.horarios(ligada.copy(fecharAutomatico = false)))
    }

    @Test
    fun proximo_no_mesmo_dia_ou_no_seguinte() {
        val horarios = listOf(seis, meioDia)
        // Terça, 13/10/2026.
        assertEquals(
            LocalDateTime.of(2026, 10, 13, 12, 0),
            FechamentoAutomatico.proximo(LocalDateTime.of(2026, 10, 13, 8, 0), horarios),
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 13, 18, 0),
            FechamentoAutomatico.proximo(LocalDateTime.of(2026, 10, 13, 12, 0), horarios),
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 14, 12, 0),
            FechamentoAutomatico.proximo(LocalDateTime.of(2026, 10, 13, 18, 0), horarios),
        )
    }

    @Test
    fun proximo_pula_o_fim_de_semana() {
        // Sexta, 16/10/2026, depois do último horário: segunda, 19/10.
        assertEquals(
            LocalDateTime.of(2026, 10, 19, 12, 0),
            FechamentoAutomatico.proximo(LocalDateTime.of(2026, 10, 16, 19, 0), listOf(meioDia, seis)),
        )
        assertEquals(
            LocalDateTime.of(2026, 10, 19, 12, 0),
            FechamentoAutomatico.proximo(LocalDateTime.of(2026, 10, 17, 10, 0), listOf(meioDia)),
        )
    }

    @Test
    fun sem_horarios_nao_ha_proximo() {
        assertNull(FechamentoAutomatico.proximo(LocalDateTime.of(2026, 10, 13, 8, 0), emptyList()))
    }

    @Test
    fun atraso_maximo_de_30_min() {
        val previsto = LocalDateTime.of(2026, 10, 13, 18, 0)
        assertFalse(FechamentoAutomatico.atrasado(previsto, previsto))
        assertFalse(FechamentoAutomatico.atrasado(previsto, previsto.plusMinutes(30)))
        assertTrue(FechamentoAutomatico.atrasado(previsto, previsto.plusMinutes(31)))
    }

    @Test
    fun fechou_segue_como_o_botao() {
        assertEquals(
            DesfechoDoFechamento.FECHADO,
            FechamentoAutomatico.desfecho(Resultado.Sucesso("✅🔐📌 SIGRH: saída registrada às 18:00"), false),
        )
    }

    @Test
    fun sem_entrada_aberta_ou_cancelado_fica_em_silencio() {
        val nada = Resultado.Recusado(
            "❌ não há entrada aberta no SIGRH (o ponto já foi fechado ou não foi aberto hoje): nada a fechar"
        )
        assertEquals(DesfechoDoFechamento.SILENCIO, FechamentoAutomatico.desfecho(nada, true))
        assertEquals(DesfechoDoFechamento.SILENCIO, FechamentoAutomatico.desfecho(Resultado.Cancelado, true))
    }

    @Test
    fun falha_notifica_so_com_o_ponto_aberto_pelo_app() {
        for (falha in listOf(Resultado.ForaDaRede("x"), Resultado.FalhaDeConexao("x"), Resultado.Recusado("x"), null)) {
            assertEquals(DesfechoDoFechamento.NOTIFICAR_FALHA, FechamentoAutomatico.desfecho(falha, true))
            assertEquals(DesfechoDoFechamento.SILENCIO, FechamentoAutomatico.desfecho(falha, false))
        }
    }

    @Test
    fun envio_incerto_sempre_notifica() {
        assertEquals(
            DesfechoDoFechamento.NOTIFICAR_FALHA,
            FechamentoAutomatico.desfecho(Resultado.EnvioIncerto("x"), false),
        )
    }
}
