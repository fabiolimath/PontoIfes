package io.github.fabiolimath.ponto.nucleo

import io.github.fabiolimath.ponto.sigrh.Cancelamento
import io.github.fabiolimath.ponto.sigrh.Resultado
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutorTest {
    private val arquivo = File(createTempDirectory().toFile(), "ponto.log")
    private val registro = Registro(arquivo)
    private val executor = Executor(registro, agora = { LocalDateTime.of(2026, 10, 13, 17, 15, 2) })

    @Test
    fun sem_credenciais_grava_o_log_e_a_saida_ao_vivo() = runBlocking<Unit> {
        val pedacos = mutableListOf<String>()
        val resultado = executor.executar(Acao.ABRIR_PONTO, "", "", aoEscrever = { pedacos += it })
        assertIs<Resultado.Recusado>(resultado)
        assertEquals("Usuário e senha do SIGRH não definidos.\n", pedacos.joinToString(""))
        assertEquals(
            "=== 2026-10-13 17:15:02 · abrir_ponto ===\nUsuário e senha do SIGRH não definidos.\nsaída: 1\n",
            registro.ler(),
        )
    }

    @Test
    fun abertura_automatica_tem_marca_no_log() = runBlocking<Unit> {
        executor.executar(Acao.ABRIR_PONTO, "", "", automatico = true)
        assertTrue(registro.ler().startsWith("=== 2026-10-13 17:15:02 · abrir_ponto (automático) ===\n"))
    }

    @Test
    fun cancelado_tem_codigo_130_e_nao_repete_a_mensagem() = runBlocking<Unit> {
        val cancelamento = Cancelamento().also { it.cancelar() }
        val resultado = executor.executar(Acao.FECHAR_PONTO, "123", "senha", cancelamento = cancelamento)
        assertEquals(Resultado.Cancelado, resultado)
        val log = registro.ler()
        assertTrue(log.endsWith("⏹️ Cancelado.\nsaída: 130\n"), log)
        assertEquals(1, Regex("Cancelado").findAll(log).count())
    }

    @Test
    fun cabecalho_do_pit_leva_a_data_mas_nao_a_observacao() = runBlocking<Unit> {
        // Sábado: o cliente não faz nada e o resultado é sucesso, sem rede.
        executor.executar(Acao.REGISTRAR_PIT, "123", "senha", dia = LocalDate.of(2026, 10, 10),
            observacao = "Conforme PIT docente.")
        val log = registro.ler()
        assertTrue("· registrar_pit 10/10/2026 ===" in log, log)
        assertTrue("saída: 0" in log, log)
        assertFalse("Conforme PIT docente.\nsaída" in log)
    }

    @Test
    fun mensagem_final() {
        assertEquals("✅ entrada registrada", Executor.mensagemFinal("Tentativa 1\n✅ entrada registrada\n\n"))
        assertEquals("", Executor.mensagemFinal(""))
    }
}
