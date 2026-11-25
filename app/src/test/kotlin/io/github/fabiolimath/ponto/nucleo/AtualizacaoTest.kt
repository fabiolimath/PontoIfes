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

    @Test
    fun teste_da_resposta_le_versao_do_titulo_e_commit() {
        val json = """{"tag_name": "teste", "name": "Teste 2.6.5 (PR #43: Atualiza (2.6.5))",
            "target_commitish": "abc1234def"}"""
        assertEquals(Atualizacao.Teste("2.6.5", "abc1234def"), Atualizacao.testeDaResposta(json))
        assertEquals(Atualizacao.Teste("", ""), Atualizacao.testeDaResposta("""{"name": "Teste"}"""))
    }

    private val t = Atualizacao.Oferta.TESTE
    private val o = Atualizacao.Oferta.OFICIAL
    private val n = Atualizacao.Oferta.NENHUMA
    private fun teste(versao: String, commit: String) = Atualizacao.Teste(versao, commit)

    @Test
    fun oferta_fora_da_versao_de_teste_ignora_o_teste() {
        assertEquals(n, Atualizacao.oferta("2.6.4", "", "2.6.4", teste("2.6.5", "b")))
        assertEquals(o, Atualizacao.oferta("2.6.4", "", "2.6.5", teste("2.6.6", "b")))
    }

    @Test
    fun oferta_na_versao_de_teste() {
        // Mesmo commit: nada.
        assertEquals(n, Atualizacao.oferta("2.6.5", "a", "2.6.4", teste("2.6.5", "a")))
        // Novo push no mesmo PR (mesma versão, outro commit).
        assertEquals(t, Atualizacao.oferta("2.6.5", "a", "2.6.4", teste("2.6.5", "b")))
        // PR mais novo.
        assertEquals(t, Atualizacao.oferta("2.6.5", "a", "2.6.4", teste("2.6.6", "c")))
        // Teste de versão menor não instala por cima.
        assertEquals(n, Atualizacao.oferta("2.6.5", "a", "2.6.4", teste("2.6.4", "c")))
        // Sem pré-release ou título sem versão.
        assertEquals(n, Atualizacao.oferta("2.6.5", "a", "2.6.4", null))
        assertEquals(n, Atualizacao.oferta("2.6.5", "a", "2.6.4", teste("", "c")))
        // Oficial saiu depois do merge: ganha do teste de versão menor ou igual.
        assertEquals(o, Atualizacao.oferta("2.6.5", "a", "2.6.6", teste("2.6.6", "c")))
        assertEquals(o, Atualizacao.oferta("2.6.5", "a", "2.6.6", teste("2.6.5", "c")))
        assertEquals(o, Atualizacao.oferta("2.6.5", "a", "2.6.6", null))
        // Teste de versão maior que o oficial.
        assertEquals(t, Atualizacao.oferta("2.6.5", "a", "2.6.6", teste("2.6.7", "c")))
        // Oficial ilegível (falhou): só o teste.
        assertEquals(t, Atualizacao.oferta("2.6.5", "a", null, teste("2.6.5", "b")))
    }
}
