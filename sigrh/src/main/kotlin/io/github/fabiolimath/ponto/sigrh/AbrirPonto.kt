package io.github.fabiolimath.ponto.sigrh

/**
 * Abre o ponto (registra a entrada), como o `abrir_ponto.py`.
 *
 * Depois do login, o SIGRH mostra a tela do ponto eletrônico com o botão "Registrar Entrada".
 * O envio só conta como registrado se a resposta trouxer "Operação realizada com sucesso" (ou já
 * mostrar o botão "Registrar Saída"). Fora da rede do campus o SIGRH já mostra, na tela do ponto,
 * "O Endereço IP de seu computador não tem autorização para registrar o Ponto Eletrônico": aí
 * nada é enviado e a tentativa se repete (a Wi-Fi pode estar conectando).
 *
 * Com o ponto já aberto, o login leva ao portal do servidor (o RH), e não à tela do ponto. Aí a
 * tela do ponto é aberta pelo link do portal, como o navegador e o fechamento fazem, para ler a
 * hora de entrada; se ela não abrir, o portal já basta para dizer que o ponto estava aberto.
 */
internal object AbrirPonto {
    const val FORM = "idFormDadosEntradaSaida"
    const val BTN_ENTRADA = "$FORM:idBtnRegistrarEntrada"
    const val BTN_SAIDA = "$FORM:idBtnRegistrarSaida"

    const val FORA_DA_REDE = DICA_WIFI

    /**
     * Mensagens do SIGRH quando o IP não está liberado: "O Endereço IP de seu computador não tem
     * autorização..." ou "...não foi encontrada a configuração que permita o acesso ao registro
     * do ponto eletrônico para o seu endereço IP...".
     */
    private val ERRO_DE_IP = Regex("endere.o ip", RegexOption.IGNORE_CASE)

    /** Palavras que, numa mensagem de erro do SIGRH, indicam restrição de rede. */
    private val SINAIS_DE_REDE = listOf(
        "rede", " ip", "endereço", "local", "computador", "máquina", "permitid", "autorizad")

    suspend fun abrir(sessao: Sessao, usuario: String, senha: String, cancelamento: Cancelamento): String {
        val depoisDoLogin = entrar(sessao, usuario, senha, ::erros)
        if (depoisDoLogin.form(FORM) == null && FecharPonto.linkDoPonto(depoisDoLogin) != null) {
            return peloPortal(sessao, depoisDoLogin, cancelamento)
        }
        val pagina = telaDoPonto(sessao, depoisDoLogin)
        return registrarEntrada(sessao, pagina, cancelamento)
    }

    /**
     * O login caiu no portal: o SIGRH faz isso quando há entrada aberta. Abre a tela do ponto pelo
     * link do portal; com ela, segue como sempre (o "Registrar Saída" diz a hora de entrada). Sem
     * ela, inclusive com o IP recusado, o ponto já estava aberto e não há o que registrar.
     * Erros de rede seguem para o laço de tentativas.
     */
    private suspend fun peloPortal(sessao: Sessao, portal: Pagina, cancelamento: Cancelamento): String {
        val (form, link) = FecharPonto.linkDoPonto(portal)!!
        val pagina = sessao.post(PORTAL, formulario(linkedMapOf(
            form to form,
            "javax.faces.ViewState" to viewstate(portal, form),
            link to link,
        ))).pagina
        val semIp = erros(pagina).any { ERRO_DE_IP.containsMatchIn(it) }
        if (pagina.form(FORM) != null && !semIp && (temBotao(pagina, BTN_SAIDA) || temBotao(pagina, BTN_ENTRADA))) {
            return registrarEntrada(sessao, pagina, cancelamento)
        }
        return "🔓ℹ️ SIGRH: o ponto já estava aberto"
    }

    fun erros(pagina: Pagina) = mensagens(pagina, "erros") + mensagens(pagina, "warning")

    /** Junta as mensagens de erro; se falarem de rede, fica só a dica da Wi-Fi. */
    fun comDica(textos: List<String>): String {
        val texto = textos.joinToString("; ")
        val minusculo = " " + texto.lowercase()
        return if (SINAIS_DE_REDE.any { it in minusculo }) DICA_WIFI else texto
    }

    /** Recusa com as mensagens do SIGRH; a da rede conta como fora da rede. */
    private fun recusa(textos: List<String>): Recusa {
        val texto = comDica(textos)
        return Recusa(texto, foraDaRede = texto == DICA_WIFI)
    }

    private fun temBotao(pagina: Pagina, nome: String): Boolean =
        pagina.form(FORM)?.let { input(it, nome) } != null

    /** Hora de entrada mostrada na tela do ponto, "" se faltar. */
    fun horaEntrada(pagina: Pagina): String {
        val form = pagina.form(FORM) ?: return ""
        for (th in form.select("th")) {
            if (th.text().trim().lowercase().startsWith("hora de entrada")) {
                return th.nextElementSiblings().firstOrNull { it.tagName() == "td" }?.text()?.trim() ?: ""
            }
        }
        return ""
    }

    private fun descrever(pagina: Pagina): String =
        horaEntrada(pagina).let { if (it.isNotEmpty()) "às $it" else "" }

    /** Garante que estamos na tela do ponto; se o login levou a outro lugar, abre-a. */
    private suspend fun telaDoPonto(sessao: Sessao, depoisDoLogin: Pagina): Pagina {
        if (depoisDoLogin.form(FORM) != null) return depoisDoLogin
        val pagina = sessao.get(PONTO)
        if (pagina.form(FORM) != null) return pagina
        val problemas = erros(pagina)
        if (problemas.isNotEmpty()) throw recusa(problemas)
        // Sem formulário e sem mensagem: pode ser a rede (ou a Wi-Fi ainda conectando),
        // então vale tentar de novo.
        throw Exception("A tela do ponto eletrônico não apareceu. $DICA_WIFI")
    }

    /** Clica em "Registrar Entrada" e confere a resposta; devolve a mensagem de sucesso. */
    private suspend fun registrarEntrada(sessao: Sessao, pagina: Pagina, cancelamento: Cancelamento): String {
        if (temBotao(pagina, BTN_SAIDA)) {
            return compactar("🔓ℹ️ SIGRH: o ponto já estava aberto ${descrever(pagina)}")
        }
        if (erros(pagina).any { ERRO_DE_IP.containsMatchIn(it) }) throw ForaDaRedeAinda(FORA_DA_REDE)
        if (!temBotao(pagina, BTN_ENTRADA)) {
            val problemas = erros(pagina)
            if (problemas.isNotEmpty()) throw recusa(problemas)
            throw Recusa("O botão Registrar Entrada não está disponível. $DICA_WIFI")
        }

        val corpo = formulario(linkedMapOf(
            FORM to FORM,
            "$FORM:observacoes" to "",
            BTN_ENTRADA to "Registrar Entrada",
            "javax.faces.ViewState" to viewstate(pagina),
        ))
        // Última chance de cancelar: depois do envio final, o registro segue até o fim.
        cancelamento.conferir()
        val resposta = enviarFinal("falha ao enviar o registro") { sessao.enviar(PONTO, corpo) }

        val final = resposta.pagina
        val problemas = erros(final)
        if (problemas.any { ERRO_DE_IP.containsMatchIn(it) }) throw Recusa(FORA_DA_REDE, foraDaRede = true)
        if (problemas.isNotEmpty()) throw recusa(problemas)
        val sucesso = mensagens(final, "info").any { "sucesso" in it.lowercase() }
        if (!sucesso && !temBotao(final, BTN_SAIDA)) {
            throw Incerto("o SIGRH não confirmou a entrada; confira no SIGRH")
        }
        return compactar("✅🔓🕑 SIGRH: entrada registrada ${descrever(final)}")
    }
}
