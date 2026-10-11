package io.github.fabiolimath.ponto.sigrh

import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Porte do `test_registrar_pit.py`: o PIT contra um SIGRH falso (páginas mínimas no formato do real). */
class RegistrarPitTest {
    private companion object {
        const val LOGIN_PAGINA = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
        const val LOGIN_ERRO = """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + LOGIN_PAGINA
        const val PONTO_PAGINA = """<form id="idFormDadosEntradaSaida">
<input id="idFormDadosEntradaSaida:idBtnContinuar" type="submit"/>
<input name="javax.faces.ViewState" value="j_id9"/></form>"""
        const val PORTAL_PAGINA = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>"""
        const val FORM = """<script>atualizaPeriodoAusencia=function(){A4J.AJAX.Submit('cadastroAusencia',null,
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
        const val HORAS = """<input id="cadastroAusencia:horasAusente" name="cadastroAusencia:horasAusente" value="03:47"/>"""
        const val ZERO = """<input id="cadastroAusencia:horasAusente" name="cadastroAusencia:horasAusente" value="00:00"/>"""
        const val SUCESSO = """<ul class="info"><li>Solicitação de ausência enviada com sucesso.</li></ul>""" + FORM
        const val JA_EXISTE = """<ul class="erros"><li>Já existe ausência cadastrada no período.</li></ul>""" + FORM

        /** Segunda-feira. */
        val DIA: LocalDate = LocalDate.of(2026, 10, 5)
    }

    private val paginas = mutableMapOf<String, String>()
    private lateinit var falso: SigrhFalso

    @BeforeTest
    fun ligar() {
        // Como na captura real: atualizaPeriodoAusencia (c0) traz as horas; a outra, 00:00.
        paginas.putAll(mapOf("apos_login" to PORTAL_PAGINA, "login" to LOGIN_PAGINA, "horas" to HORAS,
            "horas_data" to ZERO, "cadastro" to SUCESSO))
        falso = SigrhFalso(::responder)
    }

    private fun responder(p: Pedido): String = when {
        p.metodo == "GET" -> if (p.caminho == LOGIN) paginas.getValue("login") else PORTAL_PAGINA
        p.caminho == LOGIN -> paginas.getValue("apos_login")
        p.caminho == PONTO -> PORTAL_PAGINA
        p.caminho == PORTAL -> FORM
        p.multipart -> paginas.getValue("cadastro")
        else -> when (p.ajax) {
            "c0" -> paginas.getValue("horas")
            "cadastroAusencia:c528" -> paginas.getValue("horas_data")
            else -> ""
        }
    }

    @AfterTest
    fun desligar() = falso.close()

    private fun registrar(dia: LocalDate = DIA, observacao: String = RegistrarPit.OBSERVACAO_PADRAO) =
        runBlocking { falso.cliente().registrarPit(dia, observacao) }

    private fun envios() = falso.pedidos.filter { it.multipart }

    @Test
    fun `registra com a sequencia do navegador`() {
        val r = registrar(observacao = "PIT segundo portaria")

        assertEquals(Resultado.Sucesso("📋✅ SIGRH: PIT registrado"), r)
        val ajax = falso.pedidos.filter { it.metodo == "POST" && !it.multipart && it.ajax != null }
        assertEquals(listOf("cadastroAusencia:c487", "c0", "cadastroAusencia:c528", "cadastroAusencia:c582"),
            ajax.map { it.ajax })
        val final = falso.pedidos.last().partes()
        assertEquals("600337", final["cadastroAusencia:ausencia"])
        assertEquals("05/10/2026", final["cadastroAusencia:DataInicio"])
        assertEquals("03:47", final["cadastroAusencia:horasAusente"])
        assertFalse("cadastroAusencia:tipoDocumento" in final)
        val primeira = ajax.first().campos()
        assertEquals("104", primeira["cadastroAusencia:tipoDocumento"])
        assertEquals("", primeira["cadastroAusencia:DataTermino"])
        assertEquals("PIT segundo portaria", final["cadastroAusencia:observacao"])
        assertEquals("j_id3", final["javax.faces.ViewState"])
        assertFalse("enviada com sucesso" in falso.saida)
        assertEquals("📋✅ SIGRH: PIT registrado", falso.linhas.last())
    }

    @Test
    fun `passa pela tela do ponto`() {
        paginas["apos_login"] = PONTO_PAGINA

        assertIs<Resultado.Sucesso>(registrar())
        val ponto = falso.pedidos.filter { it.caminho == PONTO }
        assertEquals("j_id9", ponto[0].campos()["javax.faces.ViewState"])
        assertEquals("Conforme PIT docente.", falso.pedidos.last().partes()["cadastroAusencia:observacao"])
    }

    @Test
    fun `login recusado nao repete`() {
        paginas["apos_login"] = LOGIN_ERRO

        assertIs<Resultado.Recusado>(registrar())
        assertEquals(1, falso.chamadas("POST", LOGIN).size)
        assertTrue("Usuário e/ou senha inválidos" in falso.saida)
    }

    @Test
    fun `erro do SIGRH no cadastro nao repete`() {
        paginas["cadastro"] = JA_EXISTE

        assertIs<Resultado.Recusado>(registrar())
        assertEquals(1, envios().size)
        assertTrue("Já existe ausência" in falso.saida)
    }

    @Test
    fun `sem confirmacao nao repete`() {
        paginas["cadastro"] = FORM

        assertIs<Resultado.EnvioIncerto>(registrar())
        assertEquals(1, envios().size)
        assertTrue("confira no SIGRH" in falso.saida)
    }

    @Test
    fun `queda de rede ao enviar nao repete`() {
        paginas["cadastro"] = QUEDA

        assertIs<Resultado.EnvioIncerto>(registrar())
        assertEquals(1, envios().size)
        assertTrue("confira no SIGRH" in falso.saida)
    }

    @Test
    fun `zero horas nao envia`() {
        paginas["horas"] = ZERO

        assertEquals(1, registrar().codigo)
        assertTrue(envios().isEmpty())
    }

    @Test
    fun `falha de rede antes do envio repete`() {
        val falhou = AtomicBoolean(false)
        falso.close()
        falso = SigrhFalso { p -> if (falhou.compareAndSet(false, true)) QUEDA else responder(p) }

        assertIs<Resultado.Sucesso>(registrar())
        assertTrue(falso.linhas.any { it.startsWith("Tentativa 1 falhou") })
    }

    @Test
    fun `fim de semana nao faz nada`() {
        val r = registrar(LocalDate.of(2026, 10, 4))

        assertEquals(0, r.codigo)
        assertTrue(falso.pedidos.isEmpty())
        assertTrue("domingo" in falso.saida)
    }

    @Test
    fun `observacao em branco usa o padrao`() {
        registrar(observacao = "  ")

        assertTrue("observação: 'Conforme PIT docente.'" in falso.linhas.first())
        assertEquals("Conforme PIT docente.", falso.pedidos.last().partes()["cadastroAusencia:observacao"])
    }

    @Test
    fun `cancelar logo antes do envio final nao registra`() {
        // As horas calculadas são a última linha antes do envio final.
        val cancelamento = Cancelamento()
        val cliente = falso.cliente(cancelamento = cancelamento) {
            if (it.startsWith("Quantidade de horas")) cancelamento.cancelar()
        }

        val r = runBlocking { cliente.registrarPit(DIA) }

        assertEquals(Resultado.Cancelado, r)
        assertTrue(envios().isEmpty())
    }
}
