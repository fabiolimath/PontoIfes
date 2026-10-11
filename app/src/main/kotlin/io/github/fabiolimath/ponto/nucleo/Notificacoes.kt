package io.github.fabiolimath.ponto.nucleo

import java.time.DayOfWeek
import java.time.LocalDate

/** O botão de uma notificação: abre o app e executa [acao] (com a [data] do PIT, se houver). */
data class BotaoDeAviso(val rotulo: String, val acao: Acao, val data: String? = null)

/** Uma notificação do resultado de uma execução. */
data class Aviso(val titulo: String, val texto: String, val ident: Int, val botao: BotaoDeAviso?)

/** As regras das notificações de resultado, sem Android. */
object Notificacoes {
    const val CANAL = "resultados"
    const val NOME_DO_CANAL = "Resultado das execuções"

    /** O ident do lembrete de fechar o ponto (o `LembreteReceiver`). */
    const val IDENT_LEMBRETE = 4

    /**
     * Uma notificação de sucesso e uma de falha por ação: a nova substitui a anterior do mesmo tipo
     * e o sucesso não apaga a falha. Sucesso: 1 a 3; falha: 11 a 13 (o 4 é o do lembrete).
     */
    fun ident(acao: Acao, falha: Boolean = false) = acao.ordinal + 1 + if (falha) 10 else 0

    fun diaUtil(dia: LocalDate) = dia.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /**
     * As notificações de sucesso a apagar antes de executar [acao]: a da própria ação e, no PIT pelo
     * botão, a do fechamento (que tem o botão "Registrar PIT"). O PIT automático ([encadeado]) mantém
     * a do fechamento. A de falha fica, para não sumir o registro do erro.
     */
    fun apagarAntes(acao: Acao, encadeado: Boolean): List<Int> =
        listOf(ident(acao)) +
            if (acao == Acao.REGISTRAR_PIT && !encadeado) listOf(ident(Acao.FECHAR_PONTO)) else emptyList()

    /**
     * A notificação do resultado de [acao], ou null se as notificações estão desligadas.
     * [titulo] é o status ("Abrir ponto: concluído."), [codigo] o código de saída (0 = sucesso) e
     * [dia] a data do PIT, se foi de outro dia.
     *
     * Na falha: botão "Tentar de novo" (com a mesma data do PIT). Ao fechar o ponto com sucesso num dia
     * útil: botão "Registrar PIT" do dia, a menos que o PIT automático esteja ligado.
     */
    fun aviso(
        acao: Acao,
        titulo: String,
        saida: String,
        codigo: Int,
        dia: LocalDate?,
        preferencias: Preferencias,
        hoje: LocalDate = LocalDate.now(),
    ): Aviso? {
        if (!preferencias.notificacoes) return null
        val botao = when {
            codigo != 0 -> BotaoDeAviso(
                "Tentar de novo", acao,
                if (acao == Acao.REGISTRAR_PIT && dia != null) Mascara.escreverData(dia) else null,
            )
            acao == Acao.FECHAR_PONTO && diaUtil(hoje) && preferencias.pitAutomatico == 0 ->
                BotaoDeAviso(Acao.REGISTRAR_PIT.rotulo, Acao.REGISTRAR_PIT)
            else -> null
        }
        val texto = Executor.mensagemFinal(saida).ifEmpty { titulo }
        return Aviso(titulo, texto, ident(acao, falha = codigo != 0), botao)
    }

    /** Se o fechamento nº [vezes] do dia é o que registra o PIT sozinho (segunda a sexta). */
    fun pitAutomaticoAgora(preferencias: Preferencias, vezes: Int, hoje: LocalDate = LocalDate.now()) =
        preferencias.pitAutomatico in 1..2 && vezes == preferencias.pitAutomatico && diaUtil(hoje)
}
