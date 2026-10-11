package io.github.fabiolimath.ponto.sigrh

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Testes de fumaça do cliente contra um SIGRH falso (MockWebServer), com as páginas mínimas
 * dos testes Python. A bateria completa, portada do pytest, vem na etapa 2b.
 */
class SigrhTest {
    private lateinit var servidor: MockWebServer
    private val linhas = CopyOnWriteArrayList<String>()
    private val pedidos = CopyOnWriteArrayList<Pair<String, String>>()

    /** Página por "MÉTODO caminho" (ou por AJAXREQUEST, no PIT). */
    private var paginas: Map<String, String> = emptyMap()
    private var demoraNoEnvio = 0L
    private val relogio = Clock.fixed(Instant.parse("2026-10-13T20:15:00Z"), ZoneId.of("America/Sao_Paulo"))

    @BeforeTest
    fun ligar() {
        servidor = MockWebServer()
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val corpo = request.body.readUtf8()
                val chave = "${request.method} ${request.path}"
                pedidos += chave to corpo
                val ajax = Regex("AJAXREQUEST=([^&]+)").find(corpo)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                val pagina = paginas["AJAX $ajax"] ?: paginas[chave] ?: ""
                val resposta = MockResponse().setBody(pagina).setHeader("Content-Type", "text/html; charset=UTF-8")
                if (chave == "POST $PONTO" || (chave == "POST $AUSENCIA" && ajax == null)) {
                    resposta.setBodyDelay(demoraNoEnvio, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return resposta
            }
        }
        servidor.start()
    }

    @AfterTest
    fun desligar() = servidor.shutdown()

    private fun sigrh(cancelamento: Cancelamento = Cancelamento()) = Sigrh(
        "usuario", "senha", { linhas += it }, cancelamento,
        base = servidor.url("").toString().trimEnd('/'), esperas = listOf(0, 0, 0, 0), relogio = relogio,
    )

    // ---------------- abrir ----------------

    private val login = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
    private val telaEntrada = """<form id="idFormDadosEntradaSaida">
        <input name="idFormDadosEntradaSaida:idBtnRegistrarEntrada" type="submit" value="Registrar Entrada"/>
        <input name="javax.faces.ViewState" value="j_id4"/></form>"""
    private val telaAberta = """<form id="idFormDadosEntradaSaida" accept-charset="ISO-8859-1">
        <table><tbody><tr><th class="rotulo">Hora de Entrada:</th><td>07:52:55</td></tr></tbody></table>
        <input name="idFormDadosEntradaSaida:idBtnRegistrarSaida" type="submit" value="Registrar Saída"/>
        <input name="javax.faces.ViewState" value="j_id3"/></form>"""
    private val sucesso = """<ul class="info"><li>Operação realizada com sucesso!</li></ul>"""

    @Test
    fun `abre o ponto`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to login, "POST $LOGIN" to telaEntrada, "POST $PONTO" to sucesso + telaAberta)

        val r = sigrh().abrirPonto()

        assertEquals(Resultado.Sucesso("✅🔓🕑 SIGRH: entrada registrada às 07:52:55"), r)
        assertEquals(0, r.codigo)
        assertTrue("Registrar+Entrada" in pedidos.last().second)
        assertEquals(r.mensagem, linhas.last())
    }

    @Test
    fun `ponto ja aberto nao envia nada`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to login, "POST $LOGIN" to telaAberta)

        val r = sigrh().abrirPonto()

        assertEquals(Resultado.Sucesso("🔓ℹ️ SIGRH: o ponto já estava aberto às 07:52:55"), r)
        assertTrue(pedidos.none { it.first == "POST $PONTO" })
    }

    @Test
    fun `fora da rede repete as cinco tentativas e nao envia`() = runBlocking<Unit> {
        val bloqueado = """<ul class="erros"><li>O Endereço IP de seu computador não tem autorização para
            registrar o Ponto Eletrônico.</li></ul>""" + telaEntrada
        paginas = mapOf("GET $LOGIN" to login, "POST $LOGIN" to bloqueado)

        val r = sigrh().abrirPonto()

        assertEquals(Resultado.ForaDaRede("🔓❌ SIGRH: entrada não registrada: $DICA_WIFI"), r)
        assertEquals(5, linhas.count { it.startsWith("Tentativa") })
        assertTrue(pedidos.none { it.first == "POST $PONTO" })
    }

    @Test
    fun `sem conexao e falha de conexao`() = runBlocking<Unit> {
        servidor.shutdown()

        val r = sigrh().abrirPonto()

        assertIs<Resultado.FalhaDeConexao>(r)
        assertEquals("🔓❌ SIGRH: entrada não registrada: sem conexão. $DICA_WIFI", r.mensagem)
    }

    @Test
    fun `login recusado nao repete`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to login,
            "POST $LOGIN" to """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + login)

        val r = sigrh().abrirPonto()

        assertEquals(Resultado.Recusado("🔓❌ SIGRH: entrada não registrada: Login recusado: Usuário e/ou senha inválidos"), r)
        assertEquals(2, pedidos.size)
    }

    // ---------------- fechar ----------------

    private val portal = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>
        <form id="painelAcessoDadosServidor">
        <a id="painelAcessoDadosServidor:linkPontoEletronicoAntigo" href="#">Ponto Eletrônico</a>
        <input name="javax.faces.ViewState" value="j_id2"/></form>"""

    @Test
    fun `fecha o ponto pelo portal, em ISO-8859-1`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to login, "POST $LOGIN" to portal, "POST $PORTAL" to telaAberta,
            "POST $PONTO" to sucesso + telaEntrada)

        val r = sigrh().fecharPonto()

        assertEquals(Resultado.Sucesso("✅🔐📌 SIGRH: saída registrada às 17:15"), r)
        assertEquals("painelAcessoDadosServidor=painelAcessoDadosServidor&javax.faces.ViewState=j_id2&" +
            "painelAcessoDadosServidor%3AlinkPontoEletronicoAntigo=painelAcessoDadosServidor%3AlinkPontoEletronicoAntigo",
            pedidos[2].second)
        assertTrue("Registrar+Sa%EDda" in pedidos.last().second)
        assertEquals(listOf("Hora de entrada: 07:52:55", "Operação realizada com sucesso!", r.mensagem), linhas)
    }

    @Test
    fun `fora do campus no fechamento nao repete`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to login, "POST $LOGIN" to portal,
            "POST $PORTAL" to """<p>O Endereço IP de seu computador não tem autorização para registrar
                o Ponto Eletrônico. Em caso de dúvidas, contate a Administração.</p>""")

        val r = sigrh().fecharPonto()

        assertIs<Resultado.ForaDaRede>(r)
        assertTrue("fora da rede do campus" in r.mensagem)
        assertEquals(1, pedidos.count { it.first == "GET $LOGIN" })
    }

    // ---------------- PIT ----------------

    private val formPit = """<script>atualizaPeriodoAusencia=function(){A4J.AJAX.Submit('cadastroAusencia',null,
        {'status':'s','parameters':{'cadastroAusencia:p431':'cadastroAusencia:p431'} ,'containerId':'c0'} )};</script>
        <form id="cadastroAusencia">
        <select name="cadastroAusencia:ausencia" onchange="A4J.AJAX.Submit('cadastroAusencia',event,
        {'parameters':{'cadastroAusencia:p491':'cadastroAusencia:p491'} ,'containerId':'cadastroAusencia:c487'} )">
        <option value="0">-- SELECIONE --</option><option value="600337">REGISTRO DO PIT - DOCENTE</option></select>
        <input name="cadastroAusencia:DataInicio" onchange="atualizaPeriodoAusencia();A4J.AJAX.Submit('cadastroAusencia',event,
        {'oncomplete':function(request,event,data){x();},'parameters':{'cadastroAusencia:p530':'cadastroAusencia:p530'} ,'containerId':'cadastroAusencia:c528'} )"/>
        <textarea name="cadastroAusencia:observacao" onchange="A4J.AJAX.Submit('cadastroAusencia',event,
        {'parameters':{'cadastroAusencia:p584':'cadastroAusencia:p584'} ,'containerId':'cadastroAusencia:c582'} )"></textarea>
        <input name="javax.faces.ViewState" value="j_id3"/></form>"""
    private val horas = """<input name="cadastroAusencia:horasAusente" value="03:47"/>"""

    private fun paginasPit(cadastro: String = """<ul class="info"><li>Solicitação de ausência enviada com sucesso.</li></ul>""") =
        mapOf("GET $LOGIN" to login, "POST $LOGIN" to portal, "POST $PORTAL" to formPit,
            "AJAX c0" to horas, "AJAX cadastroAusencia:c528" to horas.replace("03:47", "00:00"),
            "POST $AUSENCIA" to cadastro)

    @Test
    fun `registra o PIT`() = runBlocking<Unit> {
        paginas = paginasPit()

        val r = sigrh().registrarPit(LocalDate.of(2026, 10, 13), "  ")

        assertEquals(Resultado.Sucesso("📋✅ SIGRH: PIT registrado"), r)
        assertEquals("Registrando o PIT de 13/10/2026 (terça), observação: 'Conforme PIT docente.'", linhas.first())
        assertTrue("Quantidade de horas calculada pelo SIGRH: 03:47" in linhas)
        val ajax = pedidos.filter { it.first == "POST $AUSENCIA" }
        assertEquals(listOf("cadastroAusencia%3Ac487", "c0", "cadastroAusencia%3Ac528", "cadastroAusencia%3Ac582"),
            ajax.dropLast(1).map { Regex("AJAXREQUEST=([^&]+)").find(it.second)!!.groupValues[1] })
        val envio = ajax.last().second
        assertTrue("name=\"cadastroAusencia:horasAusente\"" in envio && "03:47" in envio)
        assertTrue("filename=\"\"" in envio)
    }

    @Test
    fun `PIT no fim de semana nao faz nada`() = runBlocking<Unit> {
        val r = sigrh().registrarPit(LocalDate.of(2026, 10, 11))

        assertEquals(Resultado.Sucesso("11/10/2026 é domingo: o PIT só é registrado de segunda a sexta. Nada feito."), r)
        assertTrue(pedidos.isEmpty())
    }

    @Test
    fun `PIT ja registrado e recusado`() = runBlocking<Unit> {
        paginas = paginasPit("""<ul class="erros"><li>Já existe ausência cadastrada no período.</li></ul>""")

        val r = sigrh().registrarPit(LocalDate.of(2026, 10, 13))

        assertEquals(Resultado.Recusado("📋❌ SIGRH: PIT de 13/10/2026 não registrado: Já existe ausência cadastrada no período."), r)
    }

    // ---------------- cancelar ----------------

    @Test
    fun `cancelar antes do envio para sem enviar`() = runBlocking<Unit> {
        val cancelamento = Cancelamento()
        cancelamento.cancelar()

        val r = sigrh(cancelamento).abrirPonto()

        assertEquals(Resultado.Cancelado, r)
        assertEquals(130, r.codigo)
        assertEquals(listOf("⏹️ Cancelado."), linhas)
        assertTrue(pedidos.isEmpty())
    }

    @Test
    fun `cancelar durante a espera para na hora`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to "<html>manutenção</html>")
        val cancelamento = Cancelamento()
        val cliente = Sigrh("usuario", "senha", { linhas += it }, cancelamento,
            base = servidor.url("").toString().trimEnd('/'), esperas = listOf(60, 60, 60, 60))

        val execucao = async { cliente.abrirPonto() }
        while (linhas.none { it.startsWith("Tentando de novo") }) delay(10)
        cancelamento.cancelar()

        assertEquals(Resultado.Cancelado, execucao.await())
        assertEquals(1, linhas.count { it.startsWith("Tentativa") })
    }

    @Test
    fun `cancelar durante o envio final nao interrompe o registro`() = runBlocking<Unit> {
        paginas = mapOf("GET $LOGIN" to login, "POST $LOGIN" to telaEntrada, "POST $PONTO" to sucesso + telaAberta)
        demoraNoEnvio = 500
        val cancelamento = Cancelamento()

        val execucao = async { sigrh(cancelamento).abrirPonto() }
        while (pedidos.none { it.first == "POST $PONTO" }) delay(10)
        cancelamento.cancelar()

        assertIs<Resultado.Sucesso>(execucao.await())
    }
}
