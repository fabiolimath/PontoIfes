package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistroTest {
    private val arquivo = File(createTempDirectory().toFile(), "ponto.log")
    private val registro = Registro(arquivo)
    private val inicio = LocalDateTime.of(2026, 10, 13, 17, 15, 2)

    @Test
    fun sem_execucoes_avisa() {
        assertEquals("Nenhuma execução registrada ainda.", registro.ler())
    }

    @Test
    fun formato_do_bloco() {
        registro.gravar("abrir_ponto", inicio, "a\nb\n", 0)
        assertEquals("=== 2026-10-13 17:15:02 · abrir_ponto ===\na\nb\nsaída: 0\n", registro.ler())
        registro.gravar("fechar_ponto", inicio, "sem quebra", 1)
        assertTrue(registro.ler().endsWith("=== 2026-10-13 17:15:02 · fechar_ponto ===\nsem quebra\nsaída: 1\n"))
    }

    @Test
    fun mantem_as_ultimas_linhas() {
        for (i in 1..400) registro.gravar("abrir_ponto", inicio, "x\n", 0)
        val linhas = registro.ler().lines().dropLast(1)
        assertEquals(Registro.LINHAS_MAX, linhas.size)
        assertEquals("saída: 0", linhas.last())
    }

    @Test
    fun limpar_apaga_tudo() {
        registro.gravar("abrir_ponto", inicio, "x\n", 0)
        registro.limpar()
        assertEquals(Registro.VAZIO, registro.ler())
    }
}
