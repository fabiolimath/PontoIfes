package io.github.fabiolimath.ponto.sigrh

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.net.URLDecoder
import java.nio.charset.Charset
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/** Resposta que derruba a conexão depois de receber o pedido (o `requests.ConnectionError` dos testes Python). */
const val QUEDA = "<queda de conexão>"

/** Um pedido recebido pelo SIGRH falso. */
class Pedido(val metodo: String, val caminho: String, val corpo: String, private val tipo: String) {
    val multipart: Boolean get() = tipo.startsWith("multipart/")

    /** Campos de um corpo `x-www-form-urlencoded`, na ordem do envio. */
    fun campos(charset: Charset = Charsets.UTF_8): Map<String, String> =
        if (corpo.isEmpty()) emptyMap() else corpo.split("&").associate { par ->
            val (nome, valor) = (par.split("=", limit = 2) + "").take(2)
            URLDecoder.decode(nome, charset.name()) to URLDecoder.decode(valor, charset.name())
        }

    /** Partes de um corpo multipart (nome → valor), na ordem do envio. */
    fun partes(): Map<String, String> =
        PARTE.findAll(corpo).associate { it.groupValues[1] to it.groupValues[2] }

    val ajax: String? get() = if (multipart) null else campos()["AJAXREQUEST"]

    private companion object {
        val PARTE = Regex("""name="([^"]+)"[^\r\n]*\r\n(?:[^\r\n]+\r\n)*\r\n(.*?)\r\n--""", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * SIGRH falso (MockWebServer), como o `SigrhFalso` dos testes Python: [responder] escolhe a
 * página de cada pedido; [QUEDA] derruba a conexão.
 */
class SigrhFalso(private val responder: (Pedido) -> String) : AutoCloseable {
    val servidor = MockWebServer()
    val pedidos = CopyOnWriteArrayList<Pedido>()
    val linhas = CopyOnWriteArrayList<String>()

    /** Toda a saída, como o `capsys.readouterr().out`. */
    val saida: String get() = linhas.joinToString("\n")

    init {
        servidor.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val pedido = Pedido(request.method!!, request.path!!, request.body.readUtf8(),
                    request.getHeader("Content-Type") ?: "")
                pedidos += pedido
                val pagina = responder(pedido)
                if (pagina == QUEDA) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                return MockResponse().setBody(pagina).setHeader("Content-Type", "text/html; charset=UTF-8")
            }
        }
        servidor.start()
    }

    /** O cliente apontado para este servidor, sem esperas entre as tentativas. */
    fun cliente(
        usuario: String = "usuario",
        senha: String = "senha",
        cancelamento: Cancelamento = Cancelamento(),
        saida: (String) -> Unit = {},
    ) = Sigrh(usuario, senha, { linhas += it; saida(it) }, cancelamento,
        base = servidor.url("").toString().trimEnd('/'), esperas = listOf(0, 0, 0, 0), relogio = RELOGIO)

    fun chamadas(metodo: String, caminho: String) = pedidos.filter { it.metodo == metodo && it.caminho == caminho }

    override fun close() = servidor.shutdown()

    companion object {
        /** Terça, 13/10/2026, 17:15 em Brasília. */
        val RELOGIO: Clock = Clock.fixed(Instant.parse("2026-10-13T20:15:00Z"), ZoneId.of("America/Sao_Paulo"))
        val TENTATIVAS = ESPERAS.size + 1
    }
}
