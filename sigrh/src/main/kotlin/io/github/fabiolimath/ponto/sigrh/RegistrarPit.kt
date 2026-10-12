package io.github.fabiolimath.ponto.sigrh

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Registra o PIT (Solicitações > Ausências > Informar Ausência), como o `registrar_pit.py`.
 *
 * O SIGRH é JSF + RichFaces: o estado do formulário fica no servidor, então aqui se repetem as
 * mesmas requisições AJAX que o navegador faz ao escolher o tipo e a data, e só então o
 * formulário é enviado.
 */
internal object RegistrarPit {
    const val OBSERVACAO_PADRAO = "Conforme PIT docente."

    /** Valor da opção "REGISTRO DO PIT - DOCENTE"; usado se a opção não for achada pelo texto. */
    const val TIPO_PIT_PADRAO = "600337"

    /** O navegador manda o combo "Tipo de documento" (desabilitado) com este valor no AJAX. */
    const val TIPO_DOCUMENTO = "104"

    private val ZERO = setOf("00:00", "0:00")
    private val DIAS = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")
    private val DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private val A4J = Regex("""'parameters':\{'([^']+)':'[^']*'\}\s*,\s*'containerId':'([^']+)'""")
    private val PERIODO = Regex("""atualizaPeriodoAusencia=function\(\)\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)

    fun formatar(dia: LocalDate): String = dia.format(DATA)

    fun diaDaSemana(dia: LocalDate): String = DIAS[dia.dayOfWeek.value - 1]

    suspend fun registrar(
        sessao: Sessao,
        dia: String,
        observacao: String,
        usuario: String,
        senha: String,
        cancelamento: Cancelamento,
        saida: (String) -> Unit,
    ): String {
        val portal = entrarNoPortal(sessao, usuario, senha)
        val pagina = abrirFormulario(sessao, portal)
        val dados = preencher(sessao, pagina, dia, observacao, saida)
        cadastrar(sessao, dados, cancelamento)
        return "📋✅ SIGRH: PIT registrado"
    }

    /** (AJAXREQUEST, parâmetro) de uma chamada `A4J.AJAX.Submit(...)` do RichFaces. */
    fun a4j(js: String?): Pair<String, String> {
        val m = A4J.find(js ?: "") ?: throw Exception("Chamada AJAX do formulário não encontrada")
        return m.groupValues[2] to m.groupValues[1]
    }

    /** Valor da opção de Registro de PIT no combo Tipo. */
    private fun tipoPit(pagina: Pagina): String {
        val combo = combo(pagina)
        return combo?.select("option")?.firstOrNull { "PIT" in it.text().uppercase() }?.attr("value")
            ?: TIPO_PIT_PADRAO
    }

    private fun combo(pagina: Pagina) = pagina.form("cadastroAusencia")
        ?.getElementsByAttributeValue("name", "cadastroAusencia:ausencia")
        ?.firstOrNull { it.tagName() == "select" }

    /** Quantidade de Horas devolvida numa resposta AJAX ("" se não vier). */
    fun horas(resposta: String): String =
        Pagina.ler(resposta).let { input(it.doc, "cadastroAusencia:horasAusente") }
            ?.attr("value")?.trim() ?: ""

    /** Faz o login e devolve a página do portal do servidor. */
    private suspend fun entrarNoPortal(sessao: Sessao, usuario: String, senha: String): Pagina {
        var pagina = entrar(sessao, usuario, senha)

        // Com o ponto pendente, o SIGRH mostra antes a tela do ponto; o botão
        // "Continuar Acessando o Sistema" (e o OK do diálogo) leva ao portal.
        if ("idBtnContinuar" in pagina.html) {
            pagina = sessao.post(PONTO, formulario(linkedMapOf(
                "idFormDadosEntradaSaida" to "idFormDadosEntradaSaida",
                "idFormDadosEntradaSaida:observacoes" to "",
                "idFormDadosEntradaSaida:idBtnContinuar" to "Continuar Acessando o Sistema >>",
                "javax.faces.ViewState" to viewstate(pagina),
            ))).pagina
        }

        if (pagina.form("menu:FormMenuServidor") == null) pagina = sessao.get(PORTAL)
        return pagina
    }

    /** Menu Solicitações > Ausências > Informar Ausência. */
    private suspend fun abrirFormulario(sessao: Sessao, portal: Pagina): Pagina {
        val pagina = sessao.post(PORTAL, formulario(linkedMapOf(
            "menu:FormMenuServidor" to "menu:FormMenuServidor",
            "javax.faces.ViewState" to viewstate(portal),
            "menu:InformarAusencia" to "menu:InformarAusencia",
        ))).pagina
        if (pagina.form("cadastroAusencia") == null) throw Exception("O formulário de ausência não abriu")
        return pagina
    }

    private fun campos(
        estado: String,
        tipo: String,
        dia: String = "",
        horasAusente: String? = null,
        observacao: String = "",
    ): LinkedHashMap<String, String> {
        val dados = linkedMapOf(
            "cadastroAusencia" to "cadastroAusencia",
            "cadastroAusencia:idAusencia" to "0",
            "confirmButton" to "Cadastrar",
            "cadastroAusencia:ausencia" to tipo,
            "cadastroAusencia:DataInicio" to dia,
        )
        if (horasAusente != null) dados["cadastroAusencia:horasAusente"] = horasAusente
        dados["cadastroAusencia:observacao"] = observacao
        dados["javax.faces.ViewState"] = estado
        return dados
    }

    private suspend fun ajax(sessao: Sessao, chamada: Pair<String, String>, dados: Map<String, String>): String {
        val (container, parametro) = chamada
        val corpo = linkedMapOf("AJAXREQUEST" to container)
        corpo.putAll(dados)
        corpo["cadastroAusencia:tipoDocumento"] = TIPO_DOCUMENTO
        corpo["cadastroAusencia:arquivo"] = ""
        corpo[parametro] = parametro
        return sessao.post(AUSENCIA, formulario(corpo)).conferir().texto
    }

    /** Escolhe o tipo e a data como o navegador faz; devolve os campos finais. */
    private suspend fun preencher(
        sessao: Sessao,
        pagina: Pagina,
        dia: String,
        observacao: String,
        saida: (String) -> Unit,
    ): Map<String, String> {
        val form = pagina.form("cadastroAusencia")!!
        val estado = viewstate(pagina)
        val tipo = tipoPit(pagina)

        val combo = combo(pagina) ?: throw Exception("O campo Tipo não está no formulário de ausência")
        ajax(sessao, a4j(combo.attr("onchange")),
            campos(estado, tipo).apply { put("cadastroAusencia:DataTermino", "") })

        // Ao mudar a data, a página dispara duas chamadas: atualizaPeriodoAusencia() e outra
        // ligada ao campo. Na captura, a primeira devolveu as horas (03:47) e a segunda 00:00;
        // o formulário ficou com o valor calculado.
        val periodo = PERIODO.find(pagina.html)
        val dataInicio = input(form, "cadastroAusencia:DataInicio")
            ?: throw Exception("O campo da data não está no formulário de ausência")
        val respostas = mutableListOf<String>()
        if (periodo != null) {
            respostas += ajax(sessao, a4j(periodo.groupValues[1]), campos(estado, tipo, dia, ""))
        }
        respostas += ajax(sessao, a4j(dataInicio.attr("onchange")), campos(estado, tipo, dia, ""))
        val valores = respostas.map(::horas).filter { it.isNotEmpty() }
        if (valores.isEmpty()) throw Exception("O SIGRH não devolveu a Quantidade de Horas")
        val horasAusente = valores.firstOrNull { it !in ZERO } ?: valores[0]
        saida("Quantidade de horas calculada pelo SIGRH: $horasAusente")
        if (horasAusente in ZERO) throw Recusa("O SIGRH calculou 00:00 horas para $dia: nada a registrar")

        val textarea = form.getElementsByAttributeValue("name", "cadastroAusencia:observacao")
            .firstOrNull { it.tagName() == "textarea" }
        if (textarea != null && textarea.attr("onchange").isNotEmpty()) {
            ajax(sessao, a4j(textarea.attr("onchange")), campos(estado, tipo, dia, horasAusente, observacao))
        }
        return campos(estado, tipo, dia, horasAusente, observacao)
    }

    /** Envia o formulário (multipart, como o navegador) e confere a resposta. */
    private suspend fun cadastrar(sessao: Sessao, dados: Map<String, String>, cancelamento: Cancelamento) {
        val corpo = multipart {
            for ((nome, valor) in dados) {
                if (nome != "javax.faces.ViewState") addFormDataPart(nome, valor)
            }
            addFormDataPart("cadastroAusencia:arquivo", "",
                ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
            addFormDataPart("cadastroAusencia:cadastrarAusencia", "Cadastrar")
            addFormDataPart("javax.faces.ViewState", dados.getValue("javax.faces.ViewState"))
        }
        // Última chance de cancelar: depois do envio final, o registro segue até o fim.
        cancelamento.conferir()
        val resposta = enviarFinal("falha ao enviar o formulário") {
            sessao.enviar(AUSENCIA, corpo, TIMEOUT_ENVIO)
        }

        val pagina = resposta.pagina
        val erros = mensagens(pagina, "erros") + mensagens(pagina, "warning")
        if (erros.isNotEmpty()) throw Recusa(erros.joinToString("; "))
        val info = mensagens(pagina, "info")
        if (info.none { "sucesso" in it.lowercase() }) {
            throw Incerto("o SIGRH não confirmou o cadastro; confira no SIGRH")
        }
    }
}
