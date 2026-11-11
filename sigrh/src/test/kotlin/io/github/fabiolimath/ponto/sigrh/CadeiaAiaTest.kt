package io.github.fabiolimath.ponto.sigrh

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AccessDescription
import org.bouncycastle.asn1.x509.AuthorityInformationAccess
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CadeiaAiaTest {
    private class Ac(val cert: X509Certificate, val chaves: KeyPair)

    private var serie = 1L

    private fun chaves() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun emitir(
        nome: String,
        emissor: Ac?,
        ac: Boolean,
        aia: String? = null,
        san: String? = null,
    ): Ac {
        val par = chaves()
        val agora = System.currentTimeMillis()
        val construtor = JcaX509v3CertificateBuilder(
            X500Name(emissor?.let { X500Name.getInstance(it.cert.subjectX500Principal.encoded).toString() } ?: "CN=$nome"),
            BigInteger.valueOf(serie++),
            Date(agora - 60_000), Date(agora + 86_400_000),
            X500Name("CN=$nome"), par.public,
        )
        construtor.addExtension(Extension.basicConstraints, true, BasicConstraints(ac))
        if (aia != null) {
            construtor.addExtension(Extension.authorityInfoAccess, false, AuthorityInformationAccess(
                AccessDescription(AccessDescription.id_ad_caIssuers,
                    GeneralName(GeneralName.uniformResourceIdentifier, aia))))
        }
        if (san != null) {
            construtor.addExtension(Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, san)))
        }
        val assinante = JcaContentSignerBuilder("SHA256withECDSA").build((emissor?.chaves ?: par).private)
        return Ac(JcaX509CertificateConverter().getCertificate(construtor.build(assinante)), par)
    }

    private val raiz = emitir("Raiz", null, ac = true)
    private val intermediaria = emitir("Intermediaria", raiz, ac = true, aia = "http://ac.exemplo/raiz.crt")
    private val antiga = emitir("Intermediaria antiga", raiz, ac = true)
    private val servidor = emitir("localhost", intermediaria, ac = false,
        aia = "http://ac.exemplo/intermediaria.crt", san = "localhost")

    private fun sistema(vararg raizes: X509Certificate): X509TrustManager {
        val loja = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        raizes.forEachIndexed { i, c -> loja.setCertificateEntry("raiz$i", c) }
        val fabrica = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        fabrica.init(loja)
        return fabrica.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val pedidos = mutableListOf<String>()
    private fun baixar(endereco: String): ByteArray? {
        pedidos += endereco
        return if (endereco == "http://ac.exemplo/intermediaria.crt") intermediaria.cert.encoded else null
    }

    @Test
    fun prefere_https() {
        val confianca = ConfiancaComAia(sistema(raiz.cert)) { pedidos += it; intermediaria.cert.encoded }
        confianca.checkServerTrusted(arrayOf(servidor.cert), "ECDHE_ECDSA")
        assertEquals(listOf("https://ac.exemplo/intermediaria.crt"), pedidos)
    }

    @Test
    fun le_o_endereco_aia() {
        assertEquals(listOf("http://ac.exemplo/intermediaria.crt"), enderecosAia(servidor.cert))
        assertEquals(emptyList(), enderecosAia(raiz.cert))
    }

    @Test
    fun cadeia_completa_nao_baixa_nada() {
        ConfiancaComAia(sistema(raiz.cert), ::baixar)
            .checkServerTrusted(arrayOf(servidor.cert, intermediaria.cert), "ECDHE_ECDSA")
        assertEquals(emptyList(), pedidos)
    }

    @Test
    fun sem_intermediaria_baixa_pelo_aia() {
        val confianca = ConfiancaComAia(sistema(raiz.cert), ::baixar)
        confianca.checkServerTrusted(arrayOf(servidor.cert), "ECDHE_ECDSA")
        confianca.checkServerTrusted(arrayOf(servidor.cert), "ECDHE_ECDSA")
        assertEquals(listOf("https://ac.exemplo/intermediaria.crt", "http://ac.exemplo/intermediaria.crt"), pedidos,
            "tenta https e depois http, uma vez só")
    }

    @Test
    fun intermediaria_errada_baixa_a_certa() {
        ConfiancaComAia(sistema(raiz.cert), ::baixar)
            .checkServerTrusted(arrayOf(servidor.cert, antiga.cert), "ECDHE_ECDSA")
    }

    @Test
    fun sem_download_continua_recusando() {
        assertFailsWith<CertificateException> {
            ConfiancaComAia(sistema(raiz.cert)) { null }.checkServerTrusted(arrayOf(servidor.cert), "ECDHE_ECDSA")
        }
    }

    @Test
    fun raiz_desconhecida_continua_recusando() {
        val outra = emitir("Outra raiz", null, ac = true)
        assertFailsWith<CertificateException> {
            ConfiancaComAia(sistema(outra.cert), ::baixar).checkServerTrusted(arrayOf(servidor.cert), "ECDHE_ECDSA")
        }
    }

    @Test
    fun conexao_tls_com_cadeia_incompleta() {
        val loja = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        loja.setKeyEntry("servidor", servidor.chaves.private, CharArray(0), arrayOf(servidor.cert))
        val chaves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(loja, CharArray(0)) }
        val contextoServidor = SSLContext.getInstance("TLS").apply { init(chaves.keyManagers, null, null) }
        val web = MockWebServer()
        web.useHttps(contextoServidor.socketFactory, false)
        web.enqueue(MockResponse().setBody("ok"))
        web.enqueue(MockResponse().setBody("ok"))
        web.start()
        try {
            val url = web.url("/").newBuilder().host("localhost").build()
            fun cliente(confianca: X509TrustManager) = OkHttpClient.Builder()
                .sslSocketFactory(ConfiancaComAia.contexto(confianca).socketFactory, confianca)
                .build()
            val sem = cliente(ConfiancaComAia(sistema(raiz.cert)) { null })
            assertFailsWith<javax.net.ssl.SSLHandshakeException> { sem.newCall(Request.Builder().url(url).build()).execute() }
            val com = cliente(ConfiancaComAia(sistema(raiz.cert), ::baixar))
            com.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ok", it.body!!.string()) }
        } finally {
            web.shutdown()
        }
    }

    @Test
    fun detalhe_do_erro_de_rede() {
        val e = javax.net.ssl.SSLHandshakeException("cadeia recusada")
        assertEquals("SSLHandshakeException: cadeia recusada", detalhe(e))
        assertEquals(null, detalhe(java.net.SocketTimeoutException("x")))
        assertTrue(detalhe(java.net.UnknownHostException("sigrh.ifes.edu.br"))!!.startsWith("UnknownHostException"))
    }
}
