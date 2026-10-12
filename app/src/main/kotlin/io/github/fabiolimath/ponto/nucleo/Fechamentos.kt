package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Os fechamentos do dia, em `ultimo_fechamento.txt` ("aaaa-mm-dd N hh:mm:ss", a hora do último). */
object Fechamentos {
    private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Anota um fechamento com sucesso no [dia], às [hora], e devolve quantos já houve no dia, contando este. */
    fun registrar(arquivo: File, dia: LocalDate = LocalDate.now(), hora: LocalTime = LocalTime.now()): Int {
        val vezes = noDia(arquivo, dia) + 1
        arquivo.parentFile?.mkdirs()
        arquivo.writeText("$dia $vezes ${hora.format(HORA)}")
        return vezes
    }

    private fun partes(arquivo: File): List<String> =
        if (!arquivo.exists()) emptyList()
        else arquivo.readText().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Quantas vezes o ponto foi fechado com sucesso no [dia]. */
    fun noDia(arquivo: File, dia: LocalDate = LocalDate.now()): Int {
        // "2026-10-06 2 17:30:00"; as versões antigas gravavam só a data (uma vez) ou a data e o número.
        val partes = partes(arquivo)
        if (partes.firstOrNull() != dia.toString()) return 0
        return if (partes.size > 1) partes[1].toIntOrNull() ?: 1 else 1
    }

    fun fechouNoDia(arquivo: File, dia: LocalDate = LocalDate.now()) = noDia(arquivo, dia) > 0

    /** Data e hora do último fechamento, ou null se não houver ou o arquivo for de antes da hora (2.0.x). */
    fun ultimo(arquivo: File): LocalDateTime? {
        val partes = partes(arquivo)
        if (partes.size < 3) return null
        return try {
            LocalDateTime.of(LocalDate.parse(partes[0]), LocalTime.parse(partes[2], HORA))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
