package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDate

/** Os fechamentos do dia, em `ultimo_fechamento.txt` ("aaaa-mm-dd N"). */
object Fechamentos {
    /** Anota um fechamento com sucesso no [dia] e devolve quantos já houve no dia, contando este. */
    fun registrar(arquivo: File, dia: LocalDate = LocalDate.now()): Int {
        val vezes = noDia(arquivo, dia) + 1
        arquivo.parentFile?.mkdirs()
        arquivo.writeText("$dia $vezes")
        return vezes
    }

    /** Quantas vezes o ponto foi fechado com sucesso no [dia]. */
    fun noDia(arquivo: File, dia: LocalDate = LocalDate.now()): Int {
        if (!arquivo.exists()) return 0
        // "2026-10-06 2"; as versões antigas gravavam só a data (uma vez).
        val partes = arquivo.readText().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (partes.firstOrNull() != dia.toString()) return 0
        return if (partes.size > 1) partes[1].toIntOrNull() ?: 1 else 1
    }

    fun fechouNoDia(arquivo: File, dia: LocalDate = LocalDate.now()) = noDia(arquivo, dia) > 0
}
