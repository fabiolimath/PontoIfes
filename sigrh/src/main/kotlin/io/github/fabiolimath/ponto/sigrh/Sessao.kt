package io.github.fabiolimath.ponto.sigrh

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** O timeout por requisição, em segundos. */
const val TIMEOUT = 30L
/** O timeout por requisição na abertura automática (Wi-Fi): o tempo de um job expedito é limitado. */
const val TIMEOUT_AUTOMATICO = 15L
/** O envio final do fechamento e do PIT espera mais (como o `timeout=60` dos scripts). */
internal const val TIMEOUT_ENVIO = 60L

private val FORM_URLENCODED = "application/x-www-form-urlencoded".toMediaType()

/** Cliente HTTP compartilhado (o pool de conexões); cada sessão ganha os próprios cookies. */
internal val clienteBase: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor { cadeia ->
            cadeia.proceed(cadeia.request().newBuilder().header("User-Agent", "Mozilla/5.0").build())
        }
        .build()
}

/** O SIGRH respondeu com erro HTTP (o `raise_for_status` dos scripts). */
internal class ErroHttp(val codigo: Int) : Exception("erro HTTP $codigo do SIGRH")

/** Página HTML já lida: o documento e o HTML reserializado (para buscas no texto dos scripts). */
internal class Pagina(val doc: Document) {
    val html: String by lazy { doc.outerHtml() }

    fun form(id: String): Element? = doc.getElementById(id)?.takeIf { it.tagName() == "form" }

    companion object {
        fun ler(texto: String): Pagina = Pagina(Jsoup.parse(texto))

        /** Como o BeautifulSoup com os bytes: o charset do cabeçalho ou, sem ele, o da página. */
        fun ler(bytes: ByteArray, tipo: MediaType?): Pagina =
            Pagina(Jsoup.parse(ByteArrayInputStream(bytes), tipo?.charset()?.name(), ""))
    }
}

/** Primeiro `<input name="nome">` dentro de [raiz]. */
internal fun input(raiz: Element, nome: String): Element? =
    raiz.getElementsByAttributeValue("name", nome).firstOrNull { it.tagName() == "input" }

/** Resposta crua: o código, o corpo e o tipo. */
internal class Resposta(val codigo: Int, val corpo: ByteArray, val tipo: MediaType?) {
    val pagina: Pagina get() = Pagina.ler(corpo, tipo)
    val texto: String get() = String(corpo, tipo?.charset() ?: Charsets.UTF_8)

    /** O `raise_for_status` do requests. */
    fun conferir(): Resposta {
        if (codigo >= 400) throw ErroHttp(codigo)
        return this
    }
}

/**
 * Corpo `x-www-form-urlencoded` como o `urlencode` do Python (espaço vira "+").
 * `encode(String, String)`: a versão com `Charset` só existe a partir do Android 13.
 */
internal fun formulario(campos: Map<String, String>, charset: Charset = Charsets.UTF_8): RequestBody =
    campos.entries.joinToString("&") { (nome, valor) ->
        URLEncoder.encode(nome, charset.name()) + "=" + URLEncoder.encode(valor, charset.name())
    }.toByteArray(Charsets.US_ASCII).toRequestBody(FORM_URLENCODED)

/** Corpo multipart (o `files=` do requests), na ordem dos campos. */
internal fun multipart(build: MultipartBody.Builder.() -> Unit): RequestBody =
    MultipartBody.Builder().setType(MultipartBody.FORM).apply(build).build()

/**
 * Uma sessão no SIGRH (o `requests.Session` dos scripts): cookies próprios, um login.
 * Cada tentativa usa uma sessão nova. [timeout] vale para cada requisição, menos o envio final
 * que pede outro.
 */
internal class Sessao(
    private val base: String,
    private val cancelamento: Cancelamento,
    private val timeout: Long = TIMEOUT,
) {
    private val http = clienteBase.newBuilder().cookieJar(Biscoitos()).apply {
        if (timeout != TIMEOUT) {
            connectTimeout(timeout, TimeUnit.SECONDS)
            readTimeout(timeout, TimeUnit.SECONDS)
            writeTimeout(timeout, TimeUnit.SECONDS)
        }
    }.build()

    suspend fun get(caminho: String): Pagina =
        chamar(http, Request.Builder().url(base + caminho).build(), cancelavel = true).pagina

    suspend fun post(caminho: String, corpo: RequestBody): Resposta =
        chamar(http, Request.Builder().url(base + caminho).post(corpo).build(), cancelavel = true)

    /**
     * O envio final: o Cancelar não o interrompe. Quem chama confere o cancelamento logo antes.
     * Sem a repetição automática do OkHttp numa queda de conexão: ele reenviaria o POST, e o
     * registro poderia sair duas vezes (o `requests` dos scripts não repete).
     */
    suspend fun enviar(caminho: String, corpo: RequestBody, timeoutDoEnvio: Long = timeout): Resposta {
        val cliente = http.newBuilder()
            .retryOnConnectionFailure(false)
            .connectTimeout(timeoutDoEnvio, TimeUnit.SECONDS)
            .readTimeout(timeoutDoEnvio, TimeUnit.SECONDS)
            .writeTimeout(timeoutDoEnvio, TimeUnit.SECONDS)
            .build()
        return chamar(cliente, Request.Builder().url(base + caminho).post(corpo).build(), cancelavel = false)
    }

    private suspend fun chamar(cliente: OkHttpClient, pedido: Request, cancelavel: Boolean): Resposta {
        val chamada = cliente.newCall(pedido)
        if (cancelavel) cancelamento.acompanhar(chamada)
        try {
            return chamada.aguardar()
        } finally {
            if (cancelavel) cancelamento.largar(chamada)
        }
    }
}

/** Executa a chamada sem prender a thread; o corpo é lido na thread do OkHttp. */
private suspend fun Call.aguardar(): Resposta = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val resposta = try {
                response.use { Resposta(it.code, it.body!!.bytes(), it.body!!.contentType()) }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(resposta)
        }
    })
}

/** Cookies em memória, como os de uma sessão do requests. */
private class Biscoitos : CookieJar {
    private val guardados = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (novo in cookies) {
            guardados.removeAll { it.name == novo.name && it.domain == novo.domain && it.path == novo.path }
            guardados += novo
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val agora = System.currentTimeMillis()
        guardados.removeAll { it.expiresAt < agora }
        return guardados.filter { it.matches(url) }
    }
}
