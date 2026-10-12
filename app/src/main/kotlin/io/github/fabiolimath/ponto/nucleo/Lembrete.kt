package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** O lembrete de fechar o ponto agendado, em `lembrete.txt` (o horário, ex.: "2026-10-07T09:00:00"). */
object Lembrete {
    const val TITULO = "Ponto ainda aberto"

    private val hora = DateTimeFormatter.ofPattern("HH:mm")

    /** O texto do lembrete; sem [abertura] (lembrete reagendado após reiniciar), uma versão genérica. */
    fun texto(abertura: LocalDateTime?) =
        "O ponto foi aberto" + (abertura?.let { " às ${hora.format(it)}" } ?: "") + " e ainda não foi fechado."

    fun registrar(arquivo: File, quando: LocalDateTime) {
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(quando.truncatedTo(ChronoUnit.SECONDS)))
    }

    /** O horário do lembrete agendado, se ainda não passou; senão null. */
    fun pendente(arquivo: File, agora: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (!arquivo.exists()) return null
        val quando = try {
            LocalDateTime.parse(arquivo.readText().trim())
        } catch (e: DateTimeParseException) {
            return null
        }
        return quando.takeIf { it.isAfter(agora) }
    }

    fun apagar(arquivo: File) {
        arquivo.delete()
    }

    /**
     * Quando agendar o lembrete depois de abrir o ponto às [abertura] (padrão: [agora]), ou null se
     * não há o que agendar: lembrete desligado, tempo inválido, já há um pendente (abrir de novo não
     * o adia) ou a hora já passou (abertura automática de um ponto aberto bem antes).
     */
    fun quando(
        preferencias: Preferencias,
        arquivo: File,
        agora: LocalDateTime = LocalDateTime.now(),
        abertura: LocalDateTime = agora,
    ): LocalDateTime? {
        val tempo = preferencias.duracaoDoLembrete
        if (!preferencias.lembreteFechar || tempo == null) return null
        if (pendente(arquivo, agora) != null) return null
        return abertura.plus(tempo).takeIf { it.isAfter(agora) }
    }
}
