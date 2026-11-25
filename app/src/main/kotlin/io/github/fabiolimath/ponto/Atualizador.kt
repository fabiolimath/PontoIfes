package io.github.fabiolimath.ponto

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.fabiolimath.ponto.nucleo.Atualizacao
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Verificação de atualizações no GitHub, a tela "Sobre" e a abertura de endereços. */
object Atualizador {
    private val principal = Handler(Looper.getMainLooper())

    fun abrirUrl(tela: AppCompatActivity, url: String) {
        try {
            tela.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Sem navegador: nada a fazer.
        }
    }

    /**
     * Consulta o último release e oferece baixá-lo se for mais novo. Na versão de teste (APK do CI de
     * um PR, com BuildConfig.COMMIT_TESTE), consulta também o pré-release "teste" e oferece um APK de
     * teste mais novo (outro commit). Na checagem automática (ao abrir o app), falhas e "sem
     * novidade" ficam em silêncio; com [avisarSemNovidade], tudo é dito.
     */
    fun verificar(tela: AppCompatActivity, avisarSemNovidade: Boolean) {
        val instalada = BuildConfig.VERSION_NAME
        val commit = BuildConfig.COMMIT_TESTE
        thread {
            val oficial = runCatching { Atualizacao.versaoDaResposta(baixar(Atualizacao.API_ULTIMA)) }
            val teste = if (commit.isEmpty()) null
            else runCatching { Atualizacao.testeDaResposta(baixar(Atualizacao.API_TESTE)) }
            principal.post {
                if (tela.isFinishing || tela.isDestroyed) return@post
                val falha = oficial.exceptionOrNull()
                    ?.takeIf { teste == null || teste.isFailure }
                if (falha != null) {
                    if (avisarSemNovidade) {
                        aviso(tela, "Atualizações", "Não foi possível verificar: ${falha.message ?: falha}")
                    }
                    return@post
                }
                val apkTeste = teste?.getOrNull()
                when (Atualizacao.oferta(instalada, commit, oficial.getOrNull(), apkTeste)) {
                    Atualizacao.Oferta.OFICIAL -> oferecer(
                        tela, "Nova versão disponível",
                        "A versão ${oficial.getOrNull()} está disponível (instalada: ${descricao()}).",
                        Atualizacao.URL_DOWNLOAD,
                    )
                    Atualizacao.Oferta.TESTE -> oferecer(
                        tela, "Nova versão de teste disponível",
                        "A versão de teste ${apkTeste!!.versao} (commit ${apkTeste.commit.take(7)}) está " +
                            "disponível (instalada: ${descricao()}).",
                        Atualizacao.URL_DOWNLOAD_TESTE,
                    )
                    Atualizacao.Oferta.NENHUMA -> if (avisarSemNovidade) {
                        aviso(tela, "Atualizações", "Você já tem a versão mais recente (${descricao()}).")
                    }
                }
            }
        }
    }

    /** "2.6.5" ou, na versão de teste, "2.6.5, teste do commit abc1234". */
    private fun descricao(): String {
        val commit = BuildConfig.COMMIT_TESTE
        return if (commit.isEmpty()) BuildConfig.VERSION_NAME
        else "${BuildConfig.VERSION_NAME}, teste do commit ${commit.take(7)}"
    }

    private fun oferecer(tela: AppCompatActivity, titulo: String, mensagem: String, url: String) {
        MaterialAlertDialogBuilder(tela)
            .setTitle(titulo)
            .setMessage(mensagem)
            .setPositiveButton("Baixar") { _, _ -> abrirUrl(tela, url) }
            .setNegativeButton("Agora não", null)
            .show()
    }

    fun sobre(tela: AppCompatActivity) {
        val nome = tela.getString(R.string.app_name)
        val texto = "$nome ${descricao()}\nAutor: Fabio Lima\n\n" +
            "${tela.getString(R.string.descricao)}\n\n${tela.getString(R.string.nao_oficial)}\n\n" +
            "Site: ${Atualizacao.URL_SITE}"
        // QR code do site, abaixo do texto do link (para abrir o site em outro celular).
        val densidade = tela.resources.displayMetrics.density
        val qr = android.widget.ImageView(tela).apply {
            setImageResource(R.drawable.qr_site)
            contentDescription = "QR code do site"
            adjustViewBounds = true
            val margem = (16 * densidade).toInt()
            setPadding(margem, margem / 2, margem, 0)
        }
        MaterialAlertDialogBuilder(tela)
            .setTitle(tela.getString(R.string.sobre))
            .setMessage(texto)
            .setView(qr)
            .setPositiveButton("Abrir o site") { _, _ -> abrirUrl(tela, Atualizacao.URL_SITE) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun aviso(tela: AppCompatActivity, titulo: String, mensagem: String) {
        MaterialAlertDialogBuilder(tela).setTitle(titulo).setMessage(mensagem)
            .setPositiveButton("OK", null).show()
    }

    private fun baixar(endereco: String): String {
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = 10_000
            conexao.readTimeout = 10_000
            conexao.setRequestProperty("Accept", "application/vnd.github+json")
            // A API do GitHub recusa pedidos sem User-Agent.
            conexao.setRequestProperty("User-Agent", "PontoIfes")
            if (conexao.responseCode != 200) throw IOException("HTTP ${conexao.responseCode}")
            return conexao.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexao.disconnect()
        }
    }
}
