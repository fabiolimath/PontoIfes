package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** O log das execuções (`ponto.log`): data e hora, a saída de cada ação e o código de saída. */
class Registro(private val arquivo: File) {
    /** Acrescenta uma execução ao log, mantendo só as últimas [LINHAS_MAX] linhas. */
    fun gravar(acao: String, inicio: LocalDateTime, saida: String, codigo: Int) {
        var bloco = "=== ${inicio.format(HORA)} · $acao ===\n$saida"
        if (!bloco.endsWith("\n")) bloco += "\n"
        bloco += "saída: $codigo\n"
        val anterior = if (arquivo.exists()) arquivo.readText() else ""
        val linhas = (anterior + bloco).linhasComFim()
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(linhas.takeLast(LINHAS_MAX).joinToString(""))
    }

    /** O texto do log, ou o aviso de que não há execuções. */
    fun ler(): String = if (arquivo.exists()) arquivo.readText() else VAZIO

    fun limpar() {
        arquivo.delete()
    }

    private fun String.linhasComFim(): List<String> {
        val linhas = mutableListOf<String>()
        var inicio = 0
        while (inicio < length) {
            val fim = indexOf('\n', inicio).let { if (it < 0) length else it + 1 }
            linhas += substring(inicio, fim)
            inicio = fim
        }
        return linhas
    }

    companion object {
        const val LINHAS_MAX = 1000
        const val VAZIO = "Nenhuma execução registrada ainda."
        private val HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
