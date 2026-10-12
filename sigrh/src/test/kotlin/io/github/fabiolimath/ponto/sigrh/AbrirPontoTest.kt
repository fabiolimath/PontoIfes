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

/** Porte do `test_abrir_ponto.py`: abrir o ponto contra um SIGRH falso (páginas mínimas no formato do real). */
class AbrirPontoTest {
    private companion object {
        const val LOGIN_PAGINA = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
        const val LOGIN_ERRO = """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + LOGIN_PAGINA

        // Tela do ponto logo após o login, com o ponto fechado (como no navegador).
        const val PONTO_FECHADO = """<form id="idFormDadosEntradaSaida">
<textarea name="idFormDadosEntradaSaida:observacoes"></textarea>
<input name="idFormDadosEntradaSaida:idBtnRegistrarEntrada" type="submit" value="Registrar Entrada"/>
<input name="idFormDadosEntradaSaida:idBtnContinuar" type="submit"/>
<input name="javax.faces.ViewState" value="j_id1"/></form>"""

        // Resposta da captura: mensagem de sucesso e a tela já com "Registrar Saída".
        const val PONTO_ABERTO = """<form id="idFormDadosEntradaSaida"><table>
<tbody id="idFormDadosEntradaSaida:registroPontoSaida">
<tr><th class="rotulo">Hora de Entrada:</th><td>07:46:40</td></tr>
<tr><th class="rotulo">Hora de Saída Prevista:</th><td>
<span id="idFormDadosEntradaSaida:horaSaidaPrevista">14:53:40</span></td></tr></tbody></table>
<input name="idFormDadosEntradaSaida:idBtnRegistrarSaida" type="submit" value="Registrar Saída"/>
<input name="javax.faces.ViewState" value="j_id2"/></form>"""
        const val SUCESSO = """<ul class="info"><li>Operação realizada com sucesso!</li></ul>""" + PONTO_ABERTO
        const val PORTAL_PAGINA = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>"""
        const val FORA_DA_REDE = """<ul class="erros"><li>Registro de ponto não permitido a partir desta rede.</li></ul>""" + PORTAL_PAGINA

        // Como na captura sigrh.loginFora: o erro já vem na tela do ponto, logo após o login.
        const val ERRO_IP = """<ul class="erros"><li>O Endereço IP de seu computador não tem autorização para registrar """ +
            """o Ponto Eletrônico. Em caso de dúvidas entrar em contato com a Administração do Sistema.</li></ul>"""

        const val FALHA = "🔓❌ SIGRH: entrada não registrada: "
    }

    private val paginas = mutableMapOf<String, String>()
    private lateinit var falso: SigrhFalso

    @BeforeTest
    fun ligar() {
        paginas.putAll(mapOf("login" to LOGIN_PAGINA, "apos_login" to PONTO_FECHADO, "ponto" to PONTO_FECHADO,
            "registro" to SUCESSO))
        falso = SigrhFalso { p ->
            paginas.getValue(when {
                p.metodo == "GET" -> if (p.caminho == LOGIN) "login" else "ponto"
                else -> if (p.caminho == LOGIN) "apos_login" else "registro"
            })
        }
    }

    @AfterTest
    fun desligar() = falso.close()

    private fun registros() = falso.chamadas("POST", PONTO)
    private fun logins() = falso.chamadas("POST", LOGIN).size
    private fun abrir(cancelamento: Cancelamento = Cancelamento()) =
        runBlocking { falso.cliente(cancelamento = cancelamento).abrirPonto() }

    @Test
    fun `registra a entrada como o navegador`() {
        val r = abrir()

        assertEquals(0, r.codigo)
        assertEquals(listOf(mapOf(
            "idFormDadosEntradaSaida" to "idFormDadosEntradaSaida",
            "idFormDadosEntradaSaida:observacoes" to "",
            "idFormDadosEntradaSaida:idBtnRegistrarEntrada" to "Registrar Entrada",
            "javax.faces.ViewState" to "j_id1",
        )), registros().map { it.campos() })
        assertTrue("entrada registrada às 07:46:40" in falso.saida)
        assertFalse("prevista" in falso.saida)
    }

    @Test
    fun `sem mensagem de sucesso nao diz que registrou`() {
        paginas["registro"] = PONTO_FECHADO

        val r = abrir()

        assertIs<Resultado.EnvioIncerto>(r)
        assertEquals(1, registros().size) // não repete: poderia registrar duas vezes
        assertTrue("não confirmou" in falso.saida && "registrada às" !in falso.saida)
    }

    @Test
    fun `erro do SIGRH no registro`() {
        paginas["registro"] = """<ul class="erros"><li>Não é permitido registrar o ponto a partir deste computador.</li></ul>""" +
            PONTO_FECHADO

        val r = abrir()

        assertEquals(1, r.codigo)
        assertEquals(1, registros().size)
        assertEquals(FALHA + "Confira se está conectado à Wi-Fi do campus.", falso.linhas.last())
    }

    @Test
    fun `login recusado nao repete`() {
        paginas["apos_login"] = LOGIN_ERRO

        val r = abrir()

        assertIs<Resultado.Recusado>(r)
        assertEquals(1, logins())
        assertTrue("Usuário e/ou senha inválidos" in falso.saida)
    }

    @Test
    fun `ponto ja aberto nao registra de novo`() {
        paginas["apos_login"] = PONTO_ABERTO

        val r = abrir()

        assertIs<Resultado.Sucesso>(r)
        assertTrue(registros().isEmpty())
        assertTrue("já estava aberto às 07:46:40" in falso.saida)
    }

    @Test
    fun `fora da rede com mensagem`() {
        paginas["apos_login"] = PORTAL_PAGINA
        paginas["ponto"] = FORA_DA_REDE

        val r = abrir()

        assertIs<Resultado.ForaDaRede>(r)
        assertTrue(registros().isEmpty())
        assertTrue(falso.linhas.last().endsWith(": Confira se está conectado à Wi-Fi do campus."))
    }

    @Test
    fun `sem tela do ponto tenta de novo e avisa da Wi-Fi`() {
        paginas["apos_login"] = PORTAL_PAGINA
        paginas["ponto"] = PORTAL_PAGINA

        val r = abrir()

        assertEquals(1, r.codigo)
        assertEquals(TENTATIVAS, logins())
        assertTrue(registros().isEmpty())
        assertTrue("Wi-Fi do campus" in falso.linhas.last())
    }

    @Test
    fun `login cai no portal mas a tela do ponto abre`() {
        paginas["apos_login"] = PORTAL_PAGINA

        val r = abrir()

        assertIs<Resultado.Sucesso>(r)
        assertEquals(1, registros().size)
    }

    @Test
    fun `sem rede tenta de novo`() {
        falso.servidor.shutdown()

        val r = abrir()

        assertIs<Resultado.FalhaDeConexao>(r)
        assertEquals(TENTATIVAS,
            falso.linhas.count { it.endsWith("falhou: sem conexão. Confira se está conectado à Wi-Fi do campus.") })
        assertEquals(FALHA + "sem conexão. Confira se está conectado à Wi-Fi do campus.", falso.linhas.last())
    }

    @Test
    fun `sem rede espera cerca de 2 minutos`() {
        assertEquals(listOf<Long>(10, 20, 30, 60), ESPERAS)
        assertTrue(ESPERAS.sum() >= 120)
    }

    @Test
    fun `cancelar interrompe a espera`() {
        paginas["login"] = QUEDA
        val cancelamento = Cancelamento()
        val cliente = Sigrh("usuario", "senha", { if (it.startsWith("Tentando de novo")) cancelamento.cancelar() },
            cancelamento, base = falso.servidor.url("").toString().trimEnd('/'), esperas = listOf(60, 60, 60, 60))

        val r = runBlocking { cliente.abrirPonto() }

        assertEquals(Resultado.Cancelado, r)
        assertEquals(1, falso.chamadas("GET", LOGIN).size)
    }

    @Test
    fun `falha ao enviar o registro nao repete`() {
        paginas["registro"] = QUEDA

        val r = abrir()

        assertIs<Resultado.EnvioIncerto>(r)
        assertEquals(1, registros().size)
        assertTrue("confira no SIGRH" in falso.saida)
    }

    @Test
    fun `sem credenciais`() {
        val r = runBlocking { falso.cliente(usuario = "", senha = "").abrirPonto() }

        assertIs<Resultado.Recusado>(r)
        assertEquals(1, r.codigo)
        assertTrue(falso.pedidos.isEmpty())
    }

    @Test
    fun `fora do campus nao envia e tenta de novo`() {
        paginas["apos_login"] = ERRO_IP + PONTO_FECHADO

        val r = abrir()

        assertIs<Resultado.ForaDaRede>(r)
        assertTrue(registros().isEmpty())
        assertEquals(TENTATIVAS, logins())
        assertEquals(FALHA + "Confira se está conectado à Wi-Fi do campus.", falso.linhas.last())
    }

    @Test
    fun `fora do campus na resposta do registro`() {
        paginas["registro"] = ERRO_IP + PONTO_FECHADO

        val r = abrir()

        assertIs<Resultado.ForaDaRede>(r)
        assertEquals(1, registros().size)
        assertTrue(falso.linhas.last().endsWith("Confira se está conectado à Wi-Fi do campus."))
    }

    @Test
    fun `IP sem configuracao de acesso vira so a dica da Wi-Fi`() {
        paginas["apos_login"] = """<ul class="erros"><li>Caro usuário, não foi encontrada a configuração que permita """ +
            """o acesso ao registro do ponto eletrônico para o seu endereço IP. Em caso de dúvidas """ +
            """entrar em contato com a Administração do Sistema.</li></ul>""" + PONTO_FECHADO

        val r = abrir()

        assertIs<Resultado.ForaDaRede>(r)
        assertTrue(registros().isEmpty())
        assertFalse("Caro usuário" in falso.saida)
        assertEquals(FALHA + "Confira se está conectado à Wi-Fi do campus.", falso.linhas.last())
    }

    @Test
    fun `cancelar antes do envio final nao registra`() {
        // O Cancelar chega enquanto o SIGRH responde ao login (a tela do ponto).
        val cancelamento = Cancelamento()
        falso.close()
        falso = SigrhFalso { p ->
            when {
                p.metodo == "GET" -> LOGIN_PAGINA
                p.caminho == LOGIN -> PONTO_FECHADO.also { cancelamento.cancelar() }
                else -> SUCESSO
            }
        }

        val r = abrir(cancelamento)

        assertEquals(Resultado.Cancelado, r)
        assertEquals(130, r.codigo)
        assertTrue(registros().isEmpty())
    }
}
