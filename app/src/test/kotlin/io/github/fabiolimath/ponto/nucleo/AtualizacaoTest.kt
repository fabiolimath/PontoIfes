package io.github.fabiolimath.ponto.nucleo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtualizacaoTest {
    @Test
    fun numeros_ignoram_prefixo_e_sufixo() {
        assertEquals(listOf(1, 0, 10), Atualizacao.numeros("v1.0.10"))
        assertEquals(listOf(2, 0, 0), Atualizacao.numeros("2.0.0-teste"))
        assertEquals(emptyList(), Atualizacao.numeros(null))
    }

    @Test
    fun mais_nova_compara_numericamente() {
        assertTrue(Atualizacao.maisNova("1.0.10", "1.0.9"))
        assertTrue(Atualizacao.maisNova("2.0.0", "1.0.15"))
        assertFalse(Atualizacao.maisNova("1.0.15", "1.0.15"))
        assertFalse(Atualizacao.maisNova("1.0.9", "1.0.10"))
        assertTrue(Atualizacao.maisNova("1.1", "1.0.9"))
        assertTrue(Atualizacao.maisNova("1.0.1", "1.0"))
        assertFalse(Atualizacao.maisNova("1.0", "1.0.1"))
    }

    @Test
    fun versao_da_resposta_tira_o_v() {
        assertEquals("1.0.6", Atualizacao.versaoDaResposta("""{"tag_name": "v1.0.6", "name": "x"}"""))
        assertEquals("2.0.0", Atualizacao.versaoDaResposta("""{"tag_name": "2.0.0"}"""))
    }
}
