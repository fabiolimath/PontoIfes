package io.github.fabiolimath.ponto.sigrh

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Confere o certificado do servidor como o sistema e, se a cadeia vier incompleta (o servidor
 * não manda o certificado intermediário, ou manda um antigo), busca o que falta pelo endereço
 * "Emissor da AC" (AIA) do próprio certificado e confere de novo, como o Chrome faz.
 *
 * Quem decide continua sendo o sistema, com as raízes dele: o intermediário baixado só completa
 * o caminho, e um certificado falso não fica válido por isso. A verificação do nome do servidor
 * é a do OkHttp, como antes.
 */
internal class ConfiancaComAia(
    private val sistema: X509TrustManager,
    private val baixar: (String) -> ByteArray? = ::baixarCertificado,
) : X509ExtendedTrustManager() {
    private val raizes by lazy { sistema.acceptedIssuers.map { it.subjectX500Principal }.toSet() }

    /** Certificados baixados, por endereço (vale enquanto o processo vive). */
    private val baixados = ConcurrentHashMap<String, List<X509Certificate>>()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        conferir(chain) { cadeia ->
            if (sistema is X509ExtendedTrustManager) sistema.checkServerTrusted(cadeia, authType, socket)
            else sistema.checkServerTrusted(cadeia, authType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        conferir(chain) { cadeia ->
            if (sistema is X509ExtendedTrustManager) sistema.checkServerTrusted(cadeia, authType, engine)
            else sistema.checkServerTrusted(cadeia, authType)
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        conferir(chain) { sistema.checkServerTrusted(it, authType) }

    private fun conferir(chain: Array<X509Certificate>, sistema: (Array<X509Certificate>) -> Unit) {
        try {
            sistema(chain)
        } catch (e: CertificateException) {
            val completa = completar(chain) ?: throw e
            try {
                sistema(completa)
            } catch (e2: CertificateException) {
                e.addSuppressed(e2)
                throw e
            }
        }
    }

    /**
     * A cadeia refeita a partir do certificado do servidor: cada emissor vem da cadeia recebida,
     * se estiver nela, ou do endereço AIA. Para na raiz ou quando não acha o emissor; null se
     * nada foi baixado (não adianta conferir de novo).
     */
    internal fun completar(chain: Array<X509Certificate>): Array<X509Certificate>? {
        if (chain.isEmpty()) return null
        val cadeia = mutableListOf(chain[0])
        var baixou = false
        for (passo in 1..MAX_INTERMEDIARIOS) {
            val atual = cadeia.last()
            if (atual.subjectX500Principal == atual.issuerX500Principal) break
            if (atual.issuerX500Principal in raizes) break // o sistema já tem o emissor
            var emissor = chain.drop(1).firstOrNull { emitiu(it, atual) }
            if (emissor == null) {
                emissor = enderecosAia(atual).asSequence()
                    .flatMap { enderecosParaBaixar(it) }
                    .flatMap { baixados.getOrPut(it) { ler(baixar(it)) }.asSequence() }
                    .firstOrNull { emitiu(it, atual) }
                    ?: break
                baixou = true
            }
            if (emissor in cadeia) break
            cadeia += emissor
        }
        return if (baixou) cadeia.toTypedArray() else null
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        sistema.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        sistema.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        sistema.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = sistema.acceptedIssuers

    companion object {
        private const val MAX_INTERMEDIARIOS = 4

        /** Com as raízes do sistema. */
        fun doSistema(): ConfiancaComAia {
            val fabrica = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            fabrica.init(null as KeyStore?)
            return ConfiancaComAia(fabrica.trustManagers.filterIsInstance<X509TrustManager>().first())
        }

        fun contexto(confianca: X509TrustManager): SSLContext =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(confianca), null) }
    }
}

/**
 * O endereço AIA costuma ser http, que o Android bloqueia por padrão nos apps: tenta antes o
 * mesmo endereço em https (as ACs grandes servem os dois). O certificado baixado é conferido
 * pela assinatura, então o http não abre brecha.
 */
internal fun enderecosParaBaixar(endereco: String): Sequence<String> =
    if (endereco.startsWith("http://")) sequenceOf("https://" + endereco.removePrefix("http://"), endereco)
    else sequenceOf(endereco)

/** [emissor] assinou [cert]? */
private fun emitiu(emissor: X509Certificate, cert: X509Certificate): Boolean =
    emissor.subjectX500Principal == cert.issuerX500Principal &&
        runCatching { cert.verify(emissor.publicKey) }.isSuccess

/** Certificados de um arquivo .crt/.cer (DER ou PEM) ou .p7c (PKCS#7); vazio se não der. */
private fun ler(bytes: ByteArray?): List<X509Certificate> {
    if (bytes == null) return emptyList()
    return runCatching {
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(bytes))
            .filterIsInstance<X509Certificate>()
    }.getOrDefault(emptyList())
}

/** Baixa um certificado de AC; null se falhar (inclusive o http bloqueado pelo Android). */
private fun baixarCertificado(endereco: String): ByteArray? = runCatching {
    val conexao = URL(endereco).openConnection() as HttpURLConnection
    conexao.connectTimeout = 10_000
    conexao.readTimeout = 10_000
    try {
        if (conexao.responseCode != 200) return null
        val saida = java.io.ByteArrayOutputStream()
        conexao.inputStream.use { entrada ->
            val buffer = ByteArray(8192)
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos < 0) break
                saida.write(buffer, 0, lidos)
                if (saida.size() > LIMITE_DOWNLOAD) return null
            }
        }
        saida.toByteArray()
    } finally {
        conexao.disconnect()
    }
}.getOrNull()

private const val LIMITE_DOWNLOAD = 64 * 1024

// -----------------------------------
// ENDEREÇOS AIA (RFC 5280, 4.2.2.1)
// -----------------------------------

private const val OID_AIA = "1.3.6.1.5.5.7.1.1"
/** id-ad-caIssuers (1.3.6.1.5.5.7.48.2) codificado em DER. */
private val CA_ISSUERS = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02)

/** Os endereços http(s) "Emissor da AC" do certificado. */
internal fun enderecosAia(cert: X509Certificate): List<String> {
    val extensao = cert.getExtensionValue(OID_AIA) ?: return emptyList()
    return runCatching {
        // OCTET STRING { SEQUENCE OF AccessDescription { OID, GeneralName } }
        val octeto = Der.elementos(extensao).first()
        val sequencia = Der.elementos(extensao, octeto.inicio, octeto.fim).first()
        Der.elementos(extensao, sequencia.inicio, sequencia.fim).mapNotNull { descricao ->
            val (metodo, nome) = Der.elementos(extensao, descricao.inicio, descricao.fim)
            val ehCaIssuers = metodo.tag == 0x06 &&
                extensao.copyOfRange(metodo.inicio, metodo.fim).contentEquals(CA_ISSUERS)
            // GeneralName uniformResourceIdentifier: [6] IA5String
            if (ehCaIssuers && nome.tag == 0x86) {
                String(extensao, nome.inicio, nome.fim - nome.inicio, Charsets.US_ASCII)
            } else null
        }.filter { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrDefault(emptyList())
}

/** Leitor mínimo de DER: os elementos de um trecho, com a tag e onde fica o conteúdo. */
private object Der {
    class Elemento(val tag: Int, val inicio: Int, val fim: Int)

    fun elementos(b: ByteArray, de: Int = 0, ate: Int = b.size): List<Elemento> {
        val lista = mutableListOf<Elemento>()
        var p = de
        while (p < ate) {
            val tag = b[p++].toInt() and 0xFF
            var tamanho = b[p++].toInt() and 0xFF
            if (tamanho >= 0x80) {
                val bytes = tamanho and 0x7F
                require(bytes in 1..4)
                tamanho = 0
                repeat(bytes) { tamanho = (tamanho shl 8) or (b[p++].toInt() and 0xFF) }
            }
            require(tamanho >= 0 && p + tamanho <= ate)
            lista += Elemento(tag, p, p + tamanho)
            p += tamanho
        }
        return lista
    }
}
