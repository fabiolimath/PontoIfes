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
     * Consulta o último release e oferece baixá-lo se for mais novo. Na checagem automática (ao abrir
     * o app), falhas e "sem novidade" ficam em silêncio; com [avisarSemNovidade], tudo é dito.
     */
    fun verificar(tela: AppCompatActivity, avisarSemNovidade: Boolean) {
        val instalada = BuildConfig.VERSION_NAME
        thread {
            val resultado = runCatching { Atualizacao.versaoDaResposta(baixar(Atualizacao.API_ULTIMA)) }
            principal.post {
                if (tela.isFinishing || tela.isDestroyed) return@post
                resultado.onFailure {
                    if (avisarSemNovidade) {
                        aviso(tela, "Atualizações", "Não foi possível verificar: ${it.message ?: it}")
                    }
                }.onSuccess { publicada ->
                    if (Atualizacao.maisNova(publicada, instalada)) {
                        MaterialAlertDialogBuilder(tela)
                            .setTitle("Nova versão disponível")
                            .setMessage("A versão $publicada está disponível (instalada: $instalada).")
                            .setPositiveButton("Baixar") { _, _ -> abrirUrl(tela, Atualizacao.URL_DOWNLOAD) }
                            .setNegativeButton("Agora não", null)
                            .show()
                    } else if (avisarSemNovidade) {
                        aviso(tela, "Atualizações", "Você já tem a versão mais recente ($instalada).")
                    }
                }
            }
        }
    }

    fun sobre(tela: AppCompatActivity) {
        val nome = tela.getString(R.string.app_name)
        val texto = "$nome ${BuildConfig.VERSION_NAME}\nAutor: Fabio Lima\n\n" +
            "${tela.getString(R.string.descricao)}\n\nSite: ${Atualizacao.URL_SITE}"
        MaterialAlertDialogBuilder(tela)
            .setTitle(tela.getString(R.string.sobre))
            .setMessage(texto)
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
