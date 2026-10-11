package io.github.fabiolimath.ponto.nucleo

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Máscara dd/mm/aaaa da data do PIT. */
object Mascara {
    private val completa = Regex("""\d{1,2}/\d{1,2}/\d{4}""")
    private val formato = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)

    /**
     * Põe as barras enquanto o usuário digita a data.
     *
     * Ao apagar, o texto fica como está, para a barra poder ser apagada.
     * Uma data já completa (ex.: colada como 2/10/2026) também fica como está.
     */
    fun formatarData(texto: String, apagando: Boolean = false): String {
        if (apagando || completa.matches(texto.trim())) return texto
        // "2/" vira "02/": quem digita a barra depois de um só dígito quis dizer 0X.
        val pedacos = texto.split("/")
        val ajustados = pedacos.dropLast(1).map { if (it.length == 1) it.padStart(2, '0') else it } + pedacos.last()
        val digitos = ajustados.joinToString("").filter { it in '0'..'9' }.take(8)
        var resultado = digitos.take(2)
        if (digitos.length >= 2) resultado += "/" + digitos.drop(2).take(2)
        if (digitos.length >= 4) resultado += "/" + digitos.drop(4)
        return resultado
    }

    /** A data "dd/mm/aaaa" (ou "d/m/aaaa"), ou null se não existir. */
    fun lerData(texto: String): LocalDate? =
        try { LocalDate.parse(texto.trim(), formato) } catch (e: java.time.DateTimeException) { null }

    fun escreverData(dia: LocalDate): String = dia.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))
}
