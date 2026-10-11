package io.github.fabiolimath.ponto.sigrh

import io.github.fabiolimath.ponto.sigrh.SigrhFalso.Companion.TENTATIVAS
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Porte do `test_fechar_ponto.py`: fechar o ponto contra um SIGRH falso (páginas mínimas no formato do real). */
class FecharPontoTest {
    private companion object {
        const val LOGIN_PAGINA = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
        const val LOGIN_ERRO = """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + LOGIN_PAGINA
        const val PORTAL_PAGINA = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>
<form id="painelAcessoDadosServidor">
<a id="painelAcessoDadosServidor:linkPontoEletronicoAntigo" href="#">Ponto Eletrônico</a>
<input name="javax.faces.ViewState" value="j_id2"/></form>"""
        const val ABERTO = """<form id="idFormDadosEntradaSaida" accept-charset="ISO-8859-1">
<table><tbody><tr><th class="rotulo">Hora de Entrada:</th><td>17:52:55</td></tr></tbody></table>
<textarea name="idFormDadosEntradaSaida:observacoes"></textarea>
<input name="idFormDadosEntradaSaida:idBtnRegistrarSaida" type="submit" value="Registrar Saída"/>
<input name="javax.faces.ViewState" value="j_id3"/></form>"""
        const val FECHADO = """<form id="idFormDadosEntradaSaida">
<input name="idFormDadosEntradaSaida:idBtnRegistrarEntrada" type="submit" value="Registrar Entrada"/>
<input name="javax.faces.ViewState" value="j_id4"/></form>"""
        const val SUCESSO = """<ul class="info"><li>Operação realizada com sucesso!</li></ul>""" + FECHADO
        const val BLOQUEADO = """<ul class="erros"><li>O Endereço IP de seu computador não tem autorização para registrar
o Ponto Eletrônico. Em caso de dúvidas entrar em contato com a Administração do Sistema.</li></ul>""" + PORTAL_PAGINA
    }

    private val paginas = mutableMapOf<String, String>()
    private lateinit var falso: SigrhFalso

    @BeforeTest
    fun ligar() {
        paginas.putAll(mapOf("login" to LOGIN_PAGINA, "apos_login" to PORTAL_PAGINA, "ponto" to ABERTO,
            "saida" to SUCESSO))
        falso = SigrhFalso { p ->
            if (p.metodo == "GET") {
                when (p.caminho) {
                    LOGIN -> paginas.getValue("login")
                    PONTO -> paginas.getValue("ponto")
                    else -> PORTAL_PAGINA
                }
            } else {
                when (p.caminho) {
                    LOGIN -> paginas.getValue("apos_login")
                    PORTAL -> paginas.getValue("ponto")
                    else -> paginas.getValue("saida")
                }
            }
        }
    }

    @AfterTest
    fun desligar() = falso.close()

    private fun fechar(cancelamento: Cancelamento = Cancelamento(), saida: (String) -> Unit = {}) =
        runBlocking { falso.cliente(cancelamento = cancelamento, saida = saida).fecharPonto() }

    private fun envios() = falso.chamadas("POST", PONTO)
    private fun logins() = falso.pedidos.count { it.caminho == LOGIN }

    @Test
    fun `fecha pelo link do portal como na captura`() {
        val r = fechar()

        assertEquals(Resultado.Sucesso("✅🔐📌 SIGRH: saída registrada às 17:15"), r)
        val menu = falso.pedidos[2]
        assertEquals("POST" to PORTAL, menu.metodo to menu.caminho)
        assertEquals(mapOf(
            "painelAcessoDadosServidor" to "painelAcessoDadosServidor",
            "javax.faces.ViewState" to "j_id2",
            "painelAcessoDadosServidor:linkPontoEletronicoAntigo" to "painelAcessoDadosServidor:linkPontoEletronicoAntigo",
        ), menu.campos())
        val envio = falso.pedidos.last()
        assertEquals("POST" to PONTO, envio.metodo to envio.caminho)
        assertTrue("Registrar+Sa%EDda" in envio.corpo) // ISO-8859-1, como o navegador
        assertEquals(mapOf(
            "idFormDadosEntradaSaida" to "idFormDadosEntradaSaida",
            "idFormDadosEntradaSaida:observacoes" to "",
            "idFormDadosEntradaSaida:idBtnRegistrarSaida" to "Registrar Saída",
            "javax.faces.ViewState" to "j_id3",
        ), envio.campos(Charsets.ISO_8859_1))
        assertTrue("Hora de entrada: 17:52:55" in falso.saida)
        assertTrue("Operação realizada com sucesso!" in falso.saida)
        assertTrue("saída registrada" in falso.saida)
    }

    @Test
    fun `usa a tela do ponto que aparece logo apos o login`() {
        paginas["apos_login"] = ABERTO

        assertIs<Resultado.Sucesso>(fechar())
        assertEquals(listOf(LOGIN, LOGIN, PONTO), falso.pedidos.map { it.caminho })
    }

    @Test
    fun `sem entrada aberta nao clica nem repete`() {
        paginas["ponto"] = FECHADO

        val r = fechar()

        assertIs<Resultado.Recusado>(r)
        assertTrue(envios().isEmpty())
        assertEquals(2, logins()) // uma tentativa só
        assertTrue("não há entrada aberta" in falso.saida)
    }

    @Test
    fun `login recusado nao repete`() {
        paginas["apos_login"] = LOGIN_ERRO

        assertIs<Resultado.Recusado>(fechar())
        assertEquals(2, falso.pedidos.size)
        assertTrue("Usuário e/ou senha inválidos" in falso.saida)
    }

    private fun foraDoCampus(pagina: String) {
        paginas["ponto"] = pagina

        val r = fechar()

        assertIs<Resultado.ForaDaRede>(r)
        assertEquals(2, logins()) // uma tentativa só
        assertTrue(envios().isEmpty())
        assertTrue("não tem autorização para registrar" in falso.saida)
        assertTrue("fora da rede do campus" in falso.saida)
    }

    @Test
    fun `fora do campus avisa e nao repete`() = foraDoCampus(BLOQUEADO)

    @Test
    fun `fora do campus sem a lista de erros avisa e nao repete`() =
        foraDoCampus(BLOQUEADO.replace("class=\"erros\"", "class=\"x\""))

    @Test
    fun `erro do SIGRH na saida e mostrado`() {
        paginas["saida"] = """<ul class="erros"><li>Horário fora do permitido.</li></ul>""" + ABERTO

        assertIs<Resultado.Recusado>(fechar())
        assertTrue("Horário fora do permitido." in falso.saida)
    }

    @Test
    fun `sem confirmacao nao repete`() {
        paginas["saida"] = ABERTO

        assertIs<Resultado.EnvioIncerto>(fechar())
        assertEquals(1, falso.pedidos.count { it.caminho == PONTO })
        assertTrue("confira no SIGRH" in falso.saida)
    }

    @Test
    fun `queda de rede ao enviar nao repete`() {
        paginas["saida"] = QUEDA

        assertIs<Resultado.EnvioIncerto>(fechar())
        assertEquals(1, falso.pedidos.count { it.caminho == PONTO })
        assertTrue("confira no SIGRH" in falso.saida)
    }

    @Test
    fun `falha antes de enviar repete`() {
        paginas["login"] = "<html>manutenção</html>"

        assertEquals(1, fechar().codigo)
        assertEquals(TENTATIVAS, falso.linhas.count { "falhou" in it })
    }

    @Test
    fun `sem credenciais`() {
        val r = runBlocking { falso.cliente(usuario = "", senha = "").fecharPonto() }

        assertIs<Resultado.Recusado>(r)
        assertTrue("não definidos" in falso.saida)
        assertTrue(falso.pedidos.isEmpty())
    }

    @Test
    fun `cancelar logo antes do envio final nao registra`() {
        // "Hora de entrada" é a última linha antes do envio final.
        val cancelamento = Cancelamento()

        val r = fechar(cancelamento) { if (it.startsWith("Hora de entrada")) cancelamento.cancelar() }

        assertEquals(Resultado.Cancelado, r)
        assertTrue(envios().isEmpty())
        assertFalse("saída registrada" in falso.saida)
    }
}
