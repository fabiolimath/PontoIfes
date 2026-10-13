package io.github.fabiolimath.ponto.nucleo

import org.json.JSONObject

/** Comparação de versões e endereços da verificação de atualizações (GitHub). */
object Atualizacao {
    const val REPO = "fabiolimath/PontoIfes"
    const val API_ULTIMA = "https://api.github.com/repos/$REPO/releases/latest"
    const val URL_SITE = "https://fabiolimath.github.io/PontoIfes/"
    const val URL_APPS_EXTERNOS = "${URL_SITE}#apps-externos"
    const val URL_DOWNLOAD = "https://github.com/$REPO/releases/latest/download/ponto-ifes.apk"

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
}
