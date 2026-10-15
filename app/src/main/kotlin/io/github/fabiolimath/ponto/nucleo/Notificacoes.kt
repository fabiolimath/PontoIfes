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
     * Uma notificação de sucesso por ação (1 a 3; o 4 é o do lembrete): a nova substitui a anterior.
     */
    fun ident(acao: Acao) = acao.ordinal + 1

    /**
     * Cada falha tem o seu ident (a partir de 1000, pelo relógio em segundos): uma falha nova, por
     * exemplo ao tentar de novo, não substitui a anterior, e um sucesso também não a apaga.
     */
    fun identDeFalha(agoraMs: Long) = 1000 + (agoraMs / 1000 % 1_000_000_000L).toInt()

    fun diaUtil(dia: LocalDate) = dia.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /**
     * As notificações de sucesso a apagar antes de executar [acao]: a da própria ação e, no PIT, a do
     * fechamento (que tem o botão "Registrar PIT"). A de falha fica, para não sumir o registro do erro.
     */
    fun apagarAntes(acao: Acao): List<Int> =
        listOf(ident(acao)) + if (acao == Acao.REGISTRAR_PIT) listOf(ident(Acao.FECHAR_PONTO)) else emptyList()

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
        agoraMs: Long = System.currentTimeMillis(),
    ): Aviso? {
        if (!preferencias.notificacoes) return null
        val botao = when {
            codigo != 0 -> BotaoDeAviso(
                "Tentar de novo", acao,
                if (acao == Acao.REGISTRAR_PIT && dia != null) Mascara.escreverData(dia) else null,
            )
            acao == Acao.FECHAR_PONTO && diaUtil(hoje) && !preferencias.pitAutomatico ->
                BotaoDeAviso(Acao.REGISTRAR_PIT.rotulo, Acao.REGISTRAR_PIT)
            else -> null
        }
        val texto = Executor.mensagemFinal(saida).ifEmpty { titulo }
        return Aviso(titulo, texto, if (codigo != 0) identDeFalha(agoraMs) else ident(acao), botao)
    }
}
