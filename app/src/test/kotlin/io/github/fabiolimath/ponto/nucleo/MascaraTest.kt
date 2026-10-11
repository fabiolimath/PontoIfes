package io.github.fabiolimath.ponto.nucleo

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MascaraTest {
    @Test
    fun mascara_da_data() {
        val casos = mapOf(
            "0" to "0", "04" to "04/", "04/1" to "04/1", "0410" to "04/10/", "2/" to "02/",
            "04102026" to "04/10/2026", "04/10/20261" to "04/10/2026", "2/10/2026" to "2/10/2026",
        )
        for ((texto, esperado) in casos) assertEquals(esperado, Mascara.formatarData(texto), texto)
        assertEquals("04/", Mascara.formatarData("04/", apagando = true))
    }

    @Test
    fun mascara_da_hora() {
        val casos = mapOf(
            "0" to "0", "01" to "01:", "01:4" to "01:4", "0140" to "01:40", "01:40" to "01:40",
            "8:" to "08:", "8:00" to "8:00", "01:405" to "01:40", "1h" to "1",
        )
        for ((texto, esperado) in casos) assertEquals(esperado, Mascara.formatarHora(texto), texto)
        assertEquals("01", Mascara.formatarHora("01", apagando = true))
    }

    @Test
    fun le_a_data() {
        assertEquals(LocalDate.of(2026, 10, 4), Mascara.lerData("04/10/2026"))
        assertEquals(LocalDate.of(2026, 10, 2), Mascara.lerData(" 2/10/2026 "))
        assertNull(Mascara.lerData("31/02/2026"))
        assertNull(Mascara.lerData("04/10/26"))
        assertNull(Mascara.lerData("04/10"))
        assertNull(Mascara.lerData(""))
    }

    @Test
    fun escreve_a_data() {
        assertEquals("02/10/2026", Mascara.escreverData(LocalDate.of(2026, 10, 2)))
    }
}
