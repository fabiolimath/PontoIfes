package io.github.fabiolimath.ponto.nucleo

import io.github.fabiolimath.ponto.sigrh.Cancelamento
import io.github.fabiolimath.ponto.sigrh.Resultado
import io.github.fabiolimath.ponto.sigrh.Sigrh
import io.github.fabiolimath.ponto.sigrh.TIMEOUT
import io.github.fabiolimath.ponto.sigrh.TIMEOUT_AUTOMATICO
import io.github.fabiolimath.ponto.sigrh.codigo
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Roda uma ação no SIGRH e grava a execução no log (o que o `executor.py` fazia com os scripts).
 * Uma ação por vez: quem chama garante.
 */
class Executor(
    private val registro: Registro,
    private val agora: () -> LocalDateTime = LocalDateTime::now,
    private val criar: (String, String, (String) -> Unit, Cancelamento, Boolean) -> Sigrh =
        { usuario, senha, saida, cancelamento, automatico ->
            Sigrh(usuario, senha, saida, cancelamento, if (automatico) TIMEOUT_AUTOMATICO else TIMEOUT)
        },
) {
    /**
     * Executa [acao]; [aoEscrever] recebe cada trecho da saída (linhas com "\n"), de qualquer thread.
     * [dia] e [observacao] valem só para o PIT (sem [dia], hoje).
     * O "⏹️ Cancelado." já vem na saída do cliente: não é impresso de novo aqui.
     * [automatico]: a abertura pela Wi-Fi (timeout menor, marca "(automático)" no log).
     */
    suspend fun executar(
        acao: Acao,
        usuario: String,
        senha: String,
        dia: LocalDate? = null,
        observacao: String = "",
        aoEscrever: (String) -> Unit = {},
        cancelamento: Cancelamento = Cancelamento(),
        automatico: Boolean = false,
    ): Resultado {
        val inicio = agora()
        val saida = StringBuilder()
        val sigrh = criar(usuario, senha, { linha ->
            val texto = linha + "\n"
            synchronized(saida) { saida.append(texto) }
            aoEscrever(texto)
        }, cancelamento, automatico)
        val resultado = when (acao) {
            Acao.ABRIR_PONTO -> sigrh.abrirPonto()
            Acao.FECHAR_PONTO -> sigrh.fecharPonto()
            Acao.REGISTRAR_PIT ->
                if (dia != null) sigrh.registrarPit(dia, observacao) else sigrh.registrarPit(observacao = observacao)
        }
        // O log leva a data do PIT, mas não a observação (a saída já a mostra).
        val nome = when {
            acao == Acao.REGISTRAR_PIT && dia != null -> "${acao.chave} ${Mascara.escreverData(dia)}"
            automatico -> "${acao.chave} (automático)"
            else -> acao.chave
        }
        registro.gravar(nome, inicio, synchronized(saida) { saida.toString() }, resultado.codigo)
        return resultado
    }

    companion object {
        /** Última linha não vazia da saída: o resultado que o cliente anuncia. */
        fun mensagemFinal(saida: String): String = saida.lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
    }
}
