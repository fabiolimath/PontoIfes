package io.github.fabiolimath.ponto.sigrh

import org.jsoup.nodes.Element
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

const val BASE = "https://sigrh.ifes.edu.br"
internal const val LOGIN = "/sigrh/login.jsf"
internal const val PONTO = "/sigrh/frequencia/ponto_eletronico/cadastro_ponto_eletronico.jsf"
internal const val PORTAL = "/sigrh/servidor/portal/servidor.jsf"
internal const val AUSENCIA = "/sigrh/dap/ausencia/form.jsf"

/**
 * Espera antes de cada nova tentativa, em segundos: cresce para aguentar a rede sumir por até
 * uns 2 minutos (troca de Wi-Fi ao chegar no campus, DNS ainda sem resposta).
 */
val ESPERAS: List<Long> = listOf(10, 20, 30, 60)

internal const val DICA_WIFI = "Confira se está conectado à Wi-Fi do campus."

/**
 * Cliente do SIGRH: abre e fecha o ponto e registra o PIT, como os scripts Python
 * (`abrir_ponto.py`, `fechar_ponto.py`, `registrar_pit.py`).
 *
 * Cada linha que os scripts imprimiam vai para [saida], com os mesmos textos; a última é a
 * [Resultado.mensagem]. Uma ação por vez em cada instância.
 *
 * @param cancelamento o botão Cancelar (ver [Cancelamento]).
 * @param base, esperas, relogio trocados só nos testes.
 */
class Sigrh(
    private val usuario: String,
    private val senha: String,
    private val saida: (String) -> Unit = {},
    private val cancelamento: Cancelamento = Cancelamento(),
    private val base: String = BASE,
    private val esperas: List<Long> = ESPERAS,
    private val relogio: Clock = Clock.systemDefaultZone(),
) {
    private val tentativas get() = esperas.size + 1

    /** Registra a entrada. "Já estava aberto" também é sucesso. */
    suspend fun abrirPonto(): Resultado =
        executar(falha = { "🔓❌ SIGRH: entrada não registrada: $it" }) { sessao ->
            AbrirPonto.abrir(sessao, usuario, senha, cancelamento)
        }

    /** Registra a saída. */
    suspend fun fecharPonto(): Resultado =
        executar(falha = { "🔐❌ SIGRH: saída não registrada: $it" }) { sessao ->
            FecharPonto.fechar(sessao, usuario, senha, cancelamento, saida, relogio)
        }

    /**
     * Registra o PIT de [dia] (padrão: hoje) com a [observacao] (vazia vira a padrão).
     * Sábado e domingo: nada é feito, e o resultado é sucesso.
     */
    suspend fun registrarPit(
        dia: LocalDate = LocalDate.now(relogio),
        observacao: String = RegistrarPit.OBSERVACAO_PADRAO,
    ): Resultado {
        val data = RegistrarPit.formatar(dia)
        val semana = RegistrarPit.diaDaSemana(dia)
        if (dia.dayOfWeek.value >= 6) {
            return fim(Resultado.Sucesso(
                "$data é $semana: o PIT só é registrado de segunda a sexta. Nada feito."))
        }
        if (usuario.isEmpty() || senha.isEmpty()) return semCredenciais()
        val obs = observacao.trim().ifEmpty { RegistrarPit.OBSERVACAO_PADRAO }
        saida("Registrando o PIT de $data ($semana), observação: ${repr(obs)}")
        return executar(falha = { "📋❌ SIGRH: PIT de $data não registrado: $it" }, conferirCredenciais = false) {
            RegistrarPit.registrar(it, data, obs, usuario, senha, cancelamento, saida)
        }
    }

    /**
     * O laço de tentativas dos scripts: [acao] devolve a mensagem de sucesso. Recusas e envios
     * incertos param na hora; os outros erros (rede, página inesperada) são repetidos depois
     * das [esperas].
     */
    private suspend fun executar(
        falha: (String) -> String,
        conferirCredenciais: Boolean = true,
        acao: suspend (Sessao) -> String,
    ): Resultado {
        if (conferirCredenciais && (usuario.isEmpty() || senha.isEmpty())) return semCredenciais()
        var erro: Exception? = null
        try {
            for (tentativa in 1..tentativas) {
                cancelamento.conferir()
                try {
                    return fim(Resultado.Sucesso(acao(Sessao(base, cancelamento))))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Cancelado) {
                    throw e
                } catch (e: Recusa) {
                    erro = e
                    break
                } catch (e: Incerto) {
                    erro = e
                    break
                } catch (e: Exception) {
                    // Uma requisição interrompida pelo Cancelar chega aqui como erro de rede.
                    cancelamento.conferir()
                    erro = e
                    saida("Tentativa $tentativa falhou: ${resumir(e)}")
                    if (tentativa < tentativas) {
                        val espera = esperas[tentativa - 1]
                        saida("Tentando de novo em ${espera}s...")
                        cancelamento.esperar(espera)
                    }
                }
            }
        } catch (e: Cancelado) {
            return fim(Resultado.Cancelado)
        }
        val mensagem = falha(resumir(erro!!))
        return fim(when (val e = erro) {
            is Recusa -> if (e.foraDaRede) Resultado.ForaDaRede(mensagem) else Resultado.Recusado(mensagem)
            is Incerto -> Resultado.EnvioIncerto(mensagem)
            is ForaDaRedeAinda -> Resultado.ForaDaRede(mensagem)
            is IOException -> Resultado.FalhaDeConexao(mensagem)
            is ErroHttp -> if (e.codigo >= 500) Resultado.FalhaDeConexao(mensagem) else Resultado.Recusado(mensagem)
            else -> Resultado.Recusado(mensagem)
        })
    }

    private fun semCredenciais() = fim(Resultado.Recusado("Usuário e senha do SIGRH não definidos."))

    private fun fim(resultado: Resultado): Resultado {
        saida(resultado.mensagem)
        return resultado
    }
}

/** Texto curto do erro: sem rede, não mostra a exceção inteira. */
internal fun resumir(e: Throwable): String = when (e) {
    is InterruptedIOException -> "o SIGRH não respondeu a tempo" // inclui SocketTimeoutException
    is IOException -> "sem conexão. $DICA_WIFI"
    else -> e.message ?: e.toString()
}

/** Como o `repr` do Python para um texto: entre aspas simples, ou duplas se tiver aspas simples. */
internal fun repr(texto: String): String =
    if ('\'' in texto && '"' !in texto) "\"$texto\"" else "'${texto.replace("'", "\\'")}'"

/** Junta os espaços, como o `re.sub(r"\s+", " ", ...).strip()` dos scripts. */
internal fun compactar(texto: String) = texto.replace(Regex("\\s+"), " ").trim()

// -----------------------------------
// ERROS QUE DECIDEM O LAÇO
// -----------------------------------

/** O SIGRH recusou o pedido (senha errada, fora da rede...): não adianta repetir. */
internal class Recusa(mensagem: String, val foraDaRede: Boolean = false) : Exception(mensagem)

/** Falhou depois do envio final: repetir poderia registrar duas vezes. */
internal class Incerto(mensagem: String) : Exception(mensagem)

/** O IP não é do campus antes do envio: repete-se, porque a Wi-Fi pode estar conectando. */
internal class ForaDaRedeAinda(mensagem: String) : Exception(mensagem)

// -----------------------------------
// LEITURA DAS PÁGINAS (comum aos três)
// -----------------------------------

/** O `javax.faces.ViewState` de [form] (ou da página, se o form faltar ou não for dado). */
internal fun viewstate(pagina: Pagina, form: String? = null): String {
    val raiz: Element = form?.let { pagina.form(it) } ?: pagina.doc
    return input(raiz, "javax.faces.ViewState")?.attr("value")
        ?: throw Exception("ViewState não encontrado na página")
}

/** Textos das listas de aviso do SIGRH (`<ul class="info|erros|warning">`). */
internal fun mensagens(pagina: Pagina, classe: String): List<String> =
    pagina.doc.select("ul").filter { it.hasClass(classe) }
        .flatMap { ul -> ul.select("li").map { it.text().trim() } }

/**
 * Faz o login e devolve a página seguinte. [erros] escolhe as mensagens mostradas na recusa
 * (o abrir também mostra os avisos `warning`).
 */
internal suspend fun entrar(
    sessao: Sessao,
    usuario: String,
    senha: String,
    erros: (Pagina) -> List<String> = { mensagens(it, "erros") },
): Pagina {
    val login = sessao.get(LOGIN)
    val pagina = sessao.post(LOGIN, formulario(linkedMapOf(
        "formLogin" to "formLogin",
        "width" to "1920",
        "height" to "1080",
        "urlRedirect" to "",
        "acessibilidade" to "",
        "login" to usuario,
        "senha" to senha,
        "logar" to "Entrar",
        "javax.faces.ViewState" to viewstate(login),
    ))).pagina
    if (pagina.form("formLogin") != null) {
        val motivos = erros(pagina).ifEmpty { listOf("usuário ou senha inválidos") }
        throw Recusa("Login recusado: " + motivos.joinToString("; "))
    }
    return pagina
}

/**
 * O envio final; um erro de rede ou HTTP aqui vira [Incerto] ("[oQue] (motivo); confira no
 * SIGRH"), porque o registro pode ter ficado feito.
 */
internal suspend fun enviarFinal(oQue: String, envio: suspend () -> Resposta): Resposta =
    try {
        envio().conferir()
    } catch (e: IOException) {
        throw Incerto("$oQue (${resumir(e)}); confira no SIGRH")
    } catch (e: ErroHttp) {
        throw Incerto("$oQue (${resumir(e)}); confira no SIGRH")
    }
