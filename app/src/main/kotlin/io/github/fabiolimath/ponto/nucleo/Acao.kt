package io.github.fabiolimath.ponto.nucleo

/** As três funções do app. [chave] é o valor do extra `acao` e o nome gravado no log. */
enum class Acao(val chave: String, val rotulo: String) {
    ABRIR_PONTO("abrir_ponto", "Abrir ponto"),
    FECHAR_PONTO("fechar_ponto", "Fechar ponto"),
    REGISTRAR_PIT("registrar_pit", "Registrar PIT");

    companion object {
        fun da(chave: String?): Acao? = entries.firstOrNull { it.chave == chave }
    }
}
