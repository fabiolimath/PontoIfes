package io.github.fabiolimath.ponto.nucleo

import io.github.fabiolimath.ponto.sigrh.Resultado
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AberturaAutomaticaTest {
    private val agora = LocalDateTime.of(2026, 10, 13, 9, 0)

    private fun desfecho(resultado: Resultado?, confirmada: Boolean = true, jaNotificou: Boolean = false) =
        AberturaAutomatica.desfecho(resultado, confirmada, jaNotificou, agora)

    @Test
    fun entrada_registrada_notifica_anota_e_agenda_a_partir_de_agora() {
        assertEquals(
            Desfecho(notificarSucesso = true, anotarAbertura = true, baseDoLembrete = agora),
            desfecho(Resultado.Sucesso("✅🔓🕑 SIGRH: entrada registrada às 09:00")),
        )
    }

    @Test
    fun ja_estava_aberto_fica_em_silencio_e_agenda_a_partir_da_entrada() {
        assertEquals(
            Desfecho(anotarAbertura = true, baseDoLembrete = LocalDateTime.of(2026, 10, 13, 7, 46)),
            desfecho(Resultado.Sucesso("🔓ℹ️ SIGRH: o ponto já estava aberto às 07:46")),
        )
        assertEquals(
            Desfecho(anotarAbertura = true, baseDoLembrete = null),
            desfecho(Resultado.Sucesso("🔓ℹ️ SIGRH: o ponto já estava aberto")),
        )
    }

    @Test
    fun erros_de_rede_e_cancelado_ficam_em_silencio() {
        assertEquals(Desfecho(), desfecho(Resultado.ForaDaRede("x")))
        assertEquals(Desfecho(), desfecho(Resultado.FalhaDeConexao("x")))
        assertEquals(Desfecho(), desfecho(Resultado.Cancelado))
    }

    @Test
    fun outros_erros_notificam_so_com_a_rede_confirmada_e_uma_vez_por_dia() {
        for (r in listOf(Resultado.Recusado("x"), Resultado.EnvioIncerto("x"), null)) {
            assertEquals(Desfecho(notificarFalha = true), desfecho(r))
            assertEquals(Desfecho(), desfecho(r, confirmada = false))
            assertEquals(Desfecho(), desfecho(r, jaNotificou = true))
        }
    }

    @Test
    fun hora_de_entrada() {
        assertEquals(LocalDateTime.of(2026, 10, 13, 7, 5), AberturaAutomatica.horaDeEntrada("aberto às 7:05", agora))
        assertNull(AberturaAutomatica.horaDeEntrada("aberto às 25:00", agora))
        assertNull(AberturaAutomatica.horaDeEntrada("aberto", agora))
    }

    @Test
    fun lembrete_a_partir_da_entrada_que_ja_passou_nao_agenda() {
        val arquivo = kotlin.io.path.createTempDirectory().toFile().resolve("lembrete.txt")
        val prefs = Preferencias(lembreteTempo = "01:00")
        assertEquals(
            LocalDateTime.of(2026, 10, 13, 9, 30),
            Lembrete.quando(prefs, arquivo, agora, abertura = LocalDateTime.of(2026, 10, 13, 8, 30)),
        )
        assertNull(Lembrete.quando(prefs, arquivo, agora, abertura = LocalDateTime.of(2026, 10, 13, 7, 46)))
    }
}
