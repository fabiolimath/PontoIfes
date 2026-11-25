package io.github.fabiolimath.ponto.nucleo

import org.json.JSONObject

/** Comparação de versões e endereços da verificação de atualizações (GitHub). */
object Atualizacao {
    const val REPO = "fabiolimath/PontoIfes"
    const val API_ULTIMA = "https://api.github.com/repos/$REPO/releases/latest"
    const val URL_SITE = "https://fabiolimath.github.io/PontoIfes/"
    const val URL_APPS_EXTERNOS = "${URL_SITE}#apps-externos"
    const val URL_DOWNLOAD = "https://github.com/$REPO/releases/latest/download/ponto-ifes.apk"

    /** O pré-release "teste", que o CI refaz a cada push num PR (pr.yml). */
    const val API_TESTE = "https://api.github.com/repos/$REPO/releases/tags/teste"
    const val URL_DOWNLOAD_TESTE = "https://github.com/$REPO/releases/download/teste/ponto-ifes.apk"

    /** "v1.0.10" -> [1, 0, 10]; partes não numéricas são ignoradas. */
    fun numeros(versao: String?): List<Int> =
        Regex("\\d+").findAll(versao.orEmpty()).map { it.value.toInt() }.toList()

    /** Se [publicada] é mais nova que [instalada] (como a comparação de tuplas do Python). */
    fun maisNova(publicada: String?, instalada: String?): Boolean {
        val a = numeros(publicada)
        val b = numeros(instalada)
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return a.size > b.size
    }

    /** A versão ("1.0.6") da resposta da API do GitHub para o último release. */
    fun versaoDaResposta(json: String): String =
        JSONObject(json).getString("tag_name").trimStart('v', 'V')

    /** O APK de teste publicado: versão (do título "Teste 2.6.5 (PR #43: ...)") e commit. */
    data class Teste(val versao: String, val commit: String)

    /** Lê o pré-release "teste" da resposta da API do GitHub. */
    fun testeDaResposta(json: String): Teste {
        val objeto = JSONObject(json)
        val versao = Regex("\\d+(\\.\\d+)+").find(objeto.optString("name"))?.value.orEmpty()
        return Teste(versao, objeto.optString("target_commitish"))
    }

    /** O que oferecer: nada, o release oficial ou o APK de teste. */
    enum class Oferta { NENHUMA, OFICIAL, TESTE }

    /**
     * Decide a oferta. Fora da versão de teste ([commitInstalado] vazio), só o oficial conta. Na de
     * teste, o APK de teste serve se tem versão maior ou a mesma versão com outro commit (novo push no
     * PR); uma versão menor não instala por cima. Se os dois servem, ganha o de versão maior, e o
     * oficial no empate.
     */
    fun oferta(instalada: String, commitInstalado: String, oficial: String?, teste: Teste?): Oferta {
        val oficialServe = oficial != null && maisNova(oficial, instalada)
        val testeServe = commitInstalado.isNotEmpty() && teste != null && teste.versao.isNotEmpty() &&
            teste.commit.isNotEmpty() && !maisNova(instalada, teste.versao) &&
            (maisNova(teste.versao, instalada) || teste.commit != commitInstalado)
        return when {
            oficialServe && (!testeServe || !maisNova(teste!!.versao, oficial!!)) -> Oferta.OFICIAL
            testeServe -> Oferta.TESTE
            else -> Oferta.NENHUMA
        }
    }
}
