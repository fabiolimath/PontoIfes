package io.github.fabiolimath.ponto.sigrh

import java.time.Clock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Fecha o ponto (registra a saída), como o `fechar_ponto.py`.
 *
 * Passos, como o navegador faz: login, tela do ponto eletrônico (direto após o login ou pelo
 * link do portal) e o botão "Registrar Saída". O sucesso é a mensagem "Operação realizada com
 * sucesso!" (`<ul class="info">`); sem entrada aberta, a tela só oferece "Registrar Entrada" e
 * não há o que fechar. Fora da rede do campus, o SIGRH nega a tela do ponto pelo IP; aí para.
 */
internal object FecharPonto {
    const val FORM_PONTO = "idFormDadosEntradaSaida"
    const val BTN_SAIDA = "$FORM_PONTO:idBtnRegistrarSaida"
    const val BTN_ENTRADA = "$FORM_PONTO:idBtnRegistrarEntrada"

    // Link "Ponto Eletrônico" do portal; usados se não forem achados na página.
    const val FORM_PAINEL = "painelAcessoDadosServidor"
    const val LINK_PONTO = "$FORM_PAINEL:linkPontoEletronicoAntigo"

    /** Trecho da mensagem do SIGRH quando o acesso não vem da rede do campus. */
    const val BLOQUEIO_IP = "não tem autorização para registrar o Ponto"

    private val HORA = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun fechar(
        sessao: Sessao,
        usuario: String,
        senha: String,
        cancelamento: Cancelamento,
        saida: (String) -> Unit,
        relogio: Clock,
    ): String {
        var pagina = entrar(sessao, usuario, senha)
        pagina = abrirPontoEletronico(sessao, pagina)
        registrarSaida(sessao, pagina, cancelamento, saida).forEach(saida)
        return "✅🔐📌 SIGRH: saída registrada às ${LocalTime.now(relogio).format(HORA)}"
    }

    private fun botao(pagina: Pagina, nome: String) = input(pagina.doc, nome) != null

    /** Hora de Entrada mostrada na tela do ponto ("" se não houver). */
    fun horaDeEntrada(pagina: Pagina): String {
        for (th in pagina.doc.select("th")) {
            if (th.text().trim().startsWith("Hora de Entrada")) {
                return th.nextElementSiblings().firstOrNull { it.tagName() == "td" }?.text()?.trim() ?: ""
            }
        }
        return ""
    }

    /** (form, link) do "Ponto Eletrônico" no portal do servidor, ou null. */
    fun linkDoPonto(portal: Pagina): Pair<String, String>? {
        for (a in portal.doc.select("a[id]")) {
            if ("linkPontoEletronico" in a.id()) {
                val form = a.parents().firstOrNull { it.tagName() == "form" }
                if (form != null && form.id().isNotEmpty()) return form.id() to a.id()
            }
        }
        return null
    }

    /** Lança [Recusa] se o SIGRH negou a tela do ponto (ex.: IP fora do campus). */
    private fun recusa(pagina: Pagina) {
        var erros = mensagens(pagina, "erros").map { compactar(it) }
        if (erros.isEmpty() && pagina.form(FORM_PONTO) == null) {
            val texto = compactar(pagina.doc.text())
            if (BLOQUEIO_IP in texto) {
                erros = texto.split(".").filter { BLOQUEIO_IP in it }.take(1)
            }
        }
        if (erros.isEmpty()) return
        var mensagem = erros.joinToString("; ") { it.trim() }
        val foraDaRede = erros.any { BLOQUEIO_IP in it }
        if (foraDaRede) mensagem += " (o celular está fora da rede do campus?)"
        throw Recusa("O SIGRH negou o acesso ao ponto: $mensagem", foraDaRede)
    }

    /** Tela do ponto: a própria página do login ou o link do portal. */
    private suspend fun abrirPontoEletronico(sessao: Sessao, depoisDoLogin: Pagina): Pagina {
        if (depoisDoLogin.form(FORM_PONTO) != null) return depoisDoLogin
        val portal = if (linkDoPonto(depoisDoLogin) != null) depoisDoLogin else sessao.get(PORTAL)
        val (form, link) = linkDoPonto(portal) ?: (FORM_PAINEL to LINK_PONTO)
        var pagina = sessao.post(PORTAL, formulario(linkedMapOf(
            form to form,
            "javax.faces.ViewState" to viewstate(portal, form),
            link to link,
        ))).pagina
        recusa(pagina)
        if (pagina.form(FORM_PONTO) == null) {
            pagina = sessao.get(PONTO)
            recusa(pagina)
        }
        if (pagina.form(FORM_PONTO) == null) throw Exception("A tela do ponto eletrônico não abriu")
        return pagina
    }

    /** Clica em Registrar Saída e confere a resposta; devolve as mensagens do SIGRH. */
    private suspend fun registrarSaida(
        sessao: Sessao,
        pagina: Pagina,
        cancelamento: Cancelamento,
        saida: (String) -> Unit,
    ): List<String> {
        if (!botao(pagina, BTN_SAIDA)) {
            if (botao(pagina, BTN_ENTRADA)) {
                throw Recusa("não há entrada aberta no SIGRH (o ponto já foi fechado " +
                    "ou não foi aberto hoje): nada a fechar")
            }
            throw Exception("O botão Registrar Saída não apareceu na tela do ponto")
        }

        val entrada = horaDeEntrada(pagina)
        if (entrada.isNotEmpty()) saida("Hora de entrada: $entrada")

        // O formulário declara accept-charset ISO-8859-1 ("Registrar Sa%EDda" na captura).
        val corpo = formulario(linkedMapOf(
            FORM_PONTO to FORM_PONTO,
            "$FORM_PONTO:observacoes" to "",
            BTN_SAIDA to "Registrar Saída",
            "javax.faces.ViewState" to viewstate(pagina, FORM_PONTO),
        ), Charsets.ISO_8859_1)
        // Última chance de cancelar: depois do envio final, o registro segue até o fim.
        cancelamento.conferir()
        val resposta = enviarFinal("falha ao registrar a saída") {
            sessao.enviar(PONTO, corpo, TIMEOUT_ENVIO)
        }

        val final = resposta.pagina
        val erros = mensagens(final, "erros") + mensagens(final, "warning")
        if (erros.isNotEmpty()) throw Recusa(erros.joinToString("; "))
        val info = mensagens(final, "info")
        if (info.none { "sucesso" in it.lowercase() }) {
            throw Incerto("o SIGRH não confirmou a saída; confira no SIGRH")
        }
        return info
    }
}
