package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FechamentosTest {
    private val arquivo = File(createTempDirectory().toFile(), "f.txt")

    @Test
    fun fechamento_vale_so_no_dia() {
        assertFalse(Fechamentos.fechouNoDia(arquivo))
        Fechamentos.registrar(arquivo, LocalDate.of(2026, 10, 3))
        assertFalse(Fechamentos.fechouNoDia(arquivo, LocalDate.of(2026, 10, 4)))
        assertTrue(Fechamentos.fechouNoDia(arquivo, LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun conta_os_fechamentos_do_dia() {
        assertEquals(1, Fechamentos.registrar(arquivo, LocalDate.of(2026, 10, 5)))
        assertEquals(1, Fechamentos.registrar(arquivo, LocalDate.of(2026, 10, 6)))
        assertEquals(2, Fechamentos.registrar(arquivo, LocalDate.of(2026, 10, 6)))
        assertEquals(2, Fechamentos.noDia(arquivo, LocalDate.of(2026, 10, 6)))
        assertEquals(0, Fechamentos.noDia(arquivo, LocalDate.of(2026, 10, 7)))
        // Arquivo das versões anteriores: só a data.
        arquivo.writeText("2026-10-06")
        assertEquals(1, Fechamentos.noDia(arquivo, LocalDate.of(2026, 10, 6)))
        assertEquals(2, Fechamentos.registrar(arquivo, LocalDate.of(2026, 10, 6)))
        assertEquals("2026-10-06 2", arquivo.readText())
    }
}
