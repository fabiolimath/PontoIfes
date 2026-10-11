package io.github.fabiolimath.ponto.sigrh

import kotlin.test.Test
import kotlin.test.assertEquals

class ResultadoTest {
    @Test
    fun `cada resultado guarda a mensagem`() {
        val resultados: List<Resultado> = listOf(
            Resultado.Sucesso("ok"),
            Resultado.ForaDaRede("rede"),
            Resultado.FalhaDeConexao("conexão"),
            Resultado.Recusado("recusa"),
            Resultado.EnvioIncerto("incerto"),
        )
        assertEquals(
            listOf("ok", "rede", "conexão", "recusa", "incerto"),
            resultados.map { it.mensagem },
        )
    }
}
