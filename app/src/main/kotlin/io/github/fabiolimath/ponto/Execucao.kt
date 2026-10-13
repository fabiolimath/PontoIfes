package io.github.fabiolimath.ponto

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.fabiolimath.ponto.nucleo.AberturaAutomatica
import io.github.fabiolimath.ponto.nucleo.Acao
import io.github.fabiolimath.ponto.nucleo.Credenciais
import io.github.fabiolimath.ponto.nucleo.DesfechoDoFechamento
import io.github.fabiolimath.ponto.nucleo.Executor
import io.github.fabiolimath.ponto.nucleo.FechamentoAutomatico
import io.github.fabiolimath.ponto.nucleo.Fechamentos
import io.github.fabiolimath.ponto.nucleo.GatilhoWifi
import io.github.fabiolimath.ponto.nucleo.Mascara
import io.github.fabiolimath.ponto.nucleo.Notificacoes
import io.github.fabiolimath.ponto.nucleo.PitAutomatico
import io.github.fabiolimath.ponto.sigrh.Cancelamento
import io.github.fabiolimath.ponto.sigrh.Resultado
import io.github.fabiolimath.ponto.sigrh.codigo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * A execução em andamento (ou a última), fora da tela: continua se a tela for recriada
 * (girar o celular) e a tela só a desenha. Uma ação por vez. Tudo é lido e mudado na thread
 * principal.
 */
object Execucao {
    private const val TAG = "Ponto"
    private val principal = Handler(Looper.getMainLooper())
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cancelamento: Cancelamento? = null

    /** Chamado (na thread principal) a cada mudança de estado ou de saída. */
    var ouvinte: (() -> Unit)? = null

    var rodando = false
        private set
    var cancelando = false
        private set

    /** "Abrir ponto", "Abrir ponto (automático)" ou "Registrar PIT (09/10/2026)". */
    var rotulo = ""
        private set

    /** Quando a ação começou, em [SystemClock.elapsedRealtime]. */
    var inicio = 0L
        private set

    /** A saída ao vivo da ação (a última, depois de terminar). */
    var saida = ""
        private set

    /** O resultado da última ação: "Abrir ponto: concluído." */
    var status = ""
        private set

    /** O PIT de outra data deu certo: a tela limpa o campo da data e zera isto. */
    var pitDeOutraDataFeito = false

    /** Executa [acao] (pelo botão, atalho ou notificação). */
    fun iniciar(contexto: Context, acao: Acao, dia: LocalDate?, credenciais: Credenciais) {
        if (rodando) return
        val app = contexto.applicationContext
        val observacao = Dados.preferencias(app).observacaoPit
        // Some a notificação de sucesso anterior (a do resultado novo aparece de novo, em vez de
        // substituir em silêncio a que ainda estava na tela); a de falha fica, com o registro do erro.
        for (ident in Notificacoes.apagarAntes(acao)) {
            try {
                Notificador.cancelar(app, ident)
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao apagar a notificação", e)
            }
        }
        val pedido = comecar(acao.rotulo + comData(dia))
        val inicioDaSaida = saida.length

        escopo.launch {
            val resultado = try {
                withContext(Dispatchers.Default) {
                    Executor(Dados.registro(app)).executar(
                        acao, credenciais.usuario, credenciais.senha, dia, observacao,
                        aoEscrever = { texto -> principal.post { saida += texto; avisar() } },
                        cancelamento = pedido,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saida += "Erro inesperado: ${e.message ?: e}\n"
                null
            }
            terminar(app, acao, dia, resultado, inicioDaSaida)
        }
    }

    /** " (09/10/2026)" no rótulo do PIT de outra data. */
    private fun comData(dia: LocalDate?) = dia?.let { " (${Mascara.escreverData(it)})" } ?: ""

    /** Marca o começo de uma execução com o [rotulo] na tela e devolve o seu Cancelar. */
    private fun comecar(rotulo: String): Cancelamento {
        val pedido = Cancelamento()
        cancelamento = pedido
        rodando = true
        cancelando = false
        this.rotulo = rotulo
        inicio = SystemClock.elapsedRealtime()
        saida = ""
        status = "Executando: $rotulo…"
        avisar()
        return pedido
    }

    /** O status final ("Abrir ponto: concluído.") pelo código de saída. */
    private fun statusFinal(codigo: Int) = when (codigo) {
        0 -> "$rotulo: concluído."
        130 -> "$rotulo: cancelado."
        else -> "$rotulo: falhou (código $codigo)."
    }

    private fun parar() {
        rodando = false
        cancelando = false
        cancelamento = null
        avisar()
    }

    private fun terminar(app: Context, acao: Acao, dia: LocalDate?, resultado: Resultado?, inicioDaSaida: Int) {
        val codigo = resultado?.codigo ?: 1
        status = statusFinal(codigo)
        // Cancelado pelo usuário: sem notificação nem lembrete.
        if (codigo != 130) {
            try {
                depoisDeExecutar(app, acao, dia, codigo, saida.substring(inicioDaSaida))
            } catch (e: Exception) {
                Log.w(TAG, "Erro depois da execução", e)
            }
            if (codigo == 0 && acao == Acao.REGISTRAR_PIT && dia != null) pitDeOutraDataFeito = true
        }
        parar()
    }

    /**
     * Notificação, lembrete e contagem de fechamentos. Cada passo falha sozinho (sem permissão de
     * notificar, por exemplo), sem atrapalhar os outros.
     */
    private fun depoisDeExecutar(app: Context, acao: Acao, dia: LocalDate?, codigo: Int, saidaDaAcao: String) {
        val preferencias = Dados.preferencias(app)
        passo("mostrar a notificação") {
            Notificacoes.aviso(acao, status, saidaDaAcao, codigo, dia, preferencias)?.let { Notificador.mostrar(app, it) }
        }
        if (codigo != 0) return
        if (acao == Acao.ABRIR_PONTO) {
            val agora = LocalDateTime.now()
            passo("anotar a abertura") { GatilhoWifi.registrarAbertura(Dados.arquivo(app, "ultima_abertura.txt"), agora) }
            passo("agendar o lembrete") { Lembretes.agendarAposAbrir(app, agora) }
        }
        if (acao != Acao.FECHAR_PONTO) return
        Fechamentos.registrar(Dados.arquivo(app, "ultimo_fechamento.txt"))
        passo("cancelar o lembrete") { Lembretes.cancelar(app) }
    }

    /**
     * A abertura automática (Wi-Fi), chamada pelo [AbrirAutomaticoWorker]: na tela, aparece como
     * uma execução manual, com o Cancelar. Devolve false, sem executar, se outra ação está rodando.
     * [redeConfirmada]: o nome da Wi-Fi foi lido e é o configurado.
     */
    suspend fun abrirAutomatico(contexto: Context, credenciais: Credenciais, redeConfirmada: Boolean): Boolean =
        withContext(Dispatchers.Main) {
            if (rodando) return@withContext false
            val app = contexto.applicationContext
            for (ident in Notificacoes.apagarAntes(Acao.ABRIR_PONTO)) passo("apagar a notificação") {
                Notificador.cancelar(app, ident)
            }
            val pedido = comecar(AberturaAutomatica.ROTULO)
            val resultado = try {
                withContext(Dispatchers.Default) {
                    Executor(Dados.registro(app)).executar(
                        Acao.ABRIR_PONTO, credenciais.usuario, credenciais.senha,
                        aoEscrever = { texto -> principal.post { saida += texto; avisar() } },
                        cancelamento = pedido,
                        automatico = true,
                    )
                }
            } catch (e: CancellationException) {
                // O sistema parou o trabalho (o WorkManager o refaz depois): a tela volta ao normal.
                status = "$rotulo: interrompido."
                parar()
                throw e
            } catch (e: Exception) {
                saida += "Erro inesperado: ${e.message ?: e}\n"
                null
            }
            val codigo = resultado?.codigo ?: 1
            status = statusFinal(codigo)
            passo("concluir a abertura automática") { depoisDoAutomatico(app, resultado, codigo, redeConfirmada) }
            parar()
            true
        }

    /** A tabela de [AberturaAutomatica.desfecho]: notificação, abertura anotada e lembrete. */
    private fun depoisDoAutomatico(app: Context, resultado: Resultado?, codigo: Int, redeConfirmada: Boolean) {
        val agora = LocalDateTime.now()
        val estado = Dados.arquivo(app, "abertura_automatica.txt")
        val desfecho = AberturaAutomatica.desfecho(
            resultado, redeConfirmada, GatilhoWifi.erroNotificadoNoDia(estado, agora.toLocalDate()), agora,
        )
        if (desfecho.anotarAbertura) {
            GatilhoWifi.registrarAbertura(Dados.arquivo(app, "ultima_abertura.txt"), agora)
        }
        if (desfecho.notificarSucesso || desfecho.notificarFalha) passo("mostrar a notificação") {
            val aviso = Notificacoes.aviso(Acao.ABRIR_PONTO, status, saida, codigo, null, Dados.preferencias(app))
            if (aviso != null) {
                Notificador.mostrar(app, aviso)
                if (desfecho.notificarFalha) GatilhoWifi.registrarErroNotificado(estado, agora.toLocalDate())
            }
        }
        desfecho.baseDoLembrete?.let { abertura ->
            passo("agendar o lembrete") { Lembretes.agendarAposAbrir(app, agora, abertura) }
        }
    }

    /**
     * O fechamento automático nos horários das Configurações, chamado pelo [FecharAutomaticoWorker]:
     * na tela, aparece como uma execução manual, com o Cancelar. Quem diz se o ponto está aberto é
     * o SIGRH; sem entrada aberta, fica só no log. Fechado, segue como o botão: notificação e
     * lembrete cancelado.
     * Devolve false, sem executar, se outra ação está rodando.
     * [pontoAbertoNoApp]: o app sabe que o ponto está aberto (para notificar uma falha).
     */
    suspend fun fecharAutomatico(contexto: Context, credenciais: Credenciais, pontoAbertoNoApp: Boolean): Boolean =
        withContext(Dispatchers.Main) {
            if (rodando) return@withContext false
            val app = contexto.applicationContext
            val resultado = rodarAutomatico(app, Acao.FECHAR_PONTO, comecar(FechamentoAutomatico.ROTULO), credenciais)
            val codigo = resultado?.codigo ?: 1
            status = statusFinal(codigo)
            when (FechamentoAutomatico.desfecho(resultado, pontoAbertoNoApp)) {
                DesfechoDoFechamento.FECHADO -> passo("concluir o fechamento automático") {
                    // A notificação nova aparece de novo, em vez de substituir em silêncio a anterior.
                    Notificador.cancelar(app, Notificacoes.ident(Acao.FECHAR_PONTO))
                    depoisDeExecutar(app, Acao.FECHAR_PONTO, null, codigo, saida)
                }
                DesfechoDoFechamento.NOTIFICAR_FALHA -> passo("mostrar a notificação") {
                    Notificacoes.aviso(Acao.FECHAR_PONTO, status, saida, codigo, null, Dados.preferencias(app))
                        ?.let { Notificador.mostrar(app, it) }
                }
                DesfechoDoFechamento.SILENCIO -> Unit
            }
            parar()
            true
        }

    /**
     * O PIT automático no horário das Configurações, chamado pelo [PitAutomaticoWorker]: na tela,
     * aparece como uma execução manual, com o Cancelar, e notifica como o botão. [dia]: null para o
     * PIT do dia. Devolve false, sem executar, se outra ação está rodando.
     */
    suspend fun pitAutomatico(contexto: Context, credenciais: Credenciais, dia: LocalDate?): Boolean =
        withContext(Dispatchers.Main) {
            if (rodando) return@withContext false
            val app = contexto.applicationContext
            for (ident in Notificacoes.apagarAntes(Acao.REGISTRAR_PIT)) passo("apagar a notificação") {
                Notificador.cancelar(app, ident)
            }
            val pedido = comecar(PitAutomatico.ROTULO + comData(dia))
            val observacao = Dados.preferencias(app).observacaoPit
            val resultado = rodarAutomatico(app, Acao.REGISTRAR_PIT, pedido, credenciais, observacao, dia)
            val codigo = resultado?.codigo ?: 1
            status = statusFinal(codigo)
            // Na falha, o Tentar de novo leva a data: tocado no dia seguinte, ainda registra este dia.
            if (codigo != 130) passo("concluir o PIT automático") {
                depoisDeExecutar(app, Acao.REGISTRAR_PIT, dia ?: LocalDate.now(), codigo, saida)
            }
            parar()
            true
        }

    /**
     * Roda [acao] dentro de um trabalho automático, já marcado na tela por [comecar] ([pedido] é o
     * seu Cancelar). Devolve null num erro inesperado. Se o sistema parar o trabalho, a tela volta ao normal.
     * Leva "(automático)" no log.
     */
    private suspend fun rodarAutomatico(
        app: Context, acao: Acao, pedido: Cancelamento, credenciais: Credenciais,
        observacao: String = "", dia: LocalDate? = null,
    ): Resultado? = try {
        withContext(Dispatchers.Default) {
            Executor(Dados.registro(app)).executar(
                acao, credenciais.usuario, credenciais.senha, dia, observacao,
                aoEscrever = { texto -> principal.post { saida += texto; avisar() } },
                cancelamento = pedido,
                automatico = true,
            )
        }
    } catch (e: CancellationException) {
        status = "$rotulo: interrompido."
        parar()
        throw e
    } catch (e: Exception) {
        saida += "Erro inesperado: ${e.message ?: e}\n"
        null
    }

    private fun passo(descricao: String, bloco: () -> Unit) {
        try {
            bloco()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao $descricao", e)
        }
    }

    /** O botão Cancelar: o cliente para antes da próxima tentativa, nas esperas e antes do envio final. */
    fun cancelar() {
        if (!rodando || cancelando) return
        cancelando = true
        cancelamento?.cancelar()
        avisar()
    }

    private fun avisar() {
        ouvinte?.invoke()
    }
}
