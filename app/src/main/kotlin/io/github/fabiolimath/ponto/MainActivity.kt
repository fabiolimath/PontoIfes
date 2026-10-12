package io.github.fabiolimath.ponto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.fabiolimath.ponto.nucleo.Acao
import io.github.fabiolimath.ponto.nucleo.Credenciais
import io.github.fabiolimath.ponto.nucleo.Fechamentos
import io.github.fabiolimath.ponto.nucleo.Mascara
import java.time.LocalDate

/** A tela principal: os três botões, o campo da data do PIT, o andamento e a saída ao vivo. */
class MainActivity : AppCompatActivity() {
    private lateinit var botoesDeAcao: Map<Acao, MaterialButton>
    private lateinit var configuracoes: MaterialButton
    private lateinit var log: MaterialButton
    private lateinit var cancelar: MaterialButton
    private lateinit var dataPit: EditText
    private lateinit var progresso: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var saida: TextView
    private lateinit var rolagem: ScrollView

    private val relogio = Handler(Looper.getMainLooper())
    private val tique = object : Runnable {
        override fun run() {
            desenhar()
            if (Execucao.rodando) relogio.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        botoesDeAcao = mapOf(
            Acao.ABRIR_PONTO to findViewById<MaterialButton>(R.id.abrir_ponto),
            Acao.FECHAR_PONTO to findViewById<MaterialButton>(R.id.fechar_ponto),
            Acao.REGISTRAR_PIT to findViewById<MaterialButton>(R.id.registrar_pit),
        )
        configuracoes = findViewById(R.id.configuracoes)
        log = findViewById(R.id.log)
        cancelar = findViewById(R.id.cancelar)
        dataPit = findViewById(R.id.data_pit)
        progresso = findViewById(R.id.progresso)
        status = findViewById(R.id.status)
        saida = findViewById(R.id.saida)
        rolagem = findViewById(R.id.rolagem)

        for ((acao, botao) in botoesDeAcao) botao.setOnClickListener { pedir(acao) }
        cancelar.setOnClickListener { Execucao.cancelar() }
        configuracoes.setOnClickListener { abrirConfiguracoes() }
        log.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        dataPit.addTextChangedListener(MascaraDeTexto(dataPit) { texto, apagando ->
            Mascara.formatarData(texto, apagando)
        })

        // Só na abertura (não ao girar a tela): sem credenciais, vai direto às Configurações; com
        // elas, pede a permissão de notificar (no Android 13+) se as notificações estão ligadas.
        if (savedInstanceState == null) {
            Legado.apagarPython(this)
            val acao = acaoDoIntent()
            if (acao != null) {
                // Aberto por um botão de notificação, atalho ou Tasker: executa já.
                if (acao == Acao.REGISTRAR_PIT) intent.getStringExtra(EXTRA_DATA)?.let { dataPit.setText(it) }
                intent.removeExtra(EXTRA_DATA)
                pedir(acao)
            } else if (Dados.credenciais(this)?.completas != true) {
                abrirConfiguracoes()
            } else {
                val preferencias = Dados.preferencias(this)
                if ((preferencias.notificacoes || preferencias.lembreteFechar) &&
                    PermissaoDeNotificacao.faltando(this)
                ) PermissaoDeNotificacao.pedir(this)
                if (preferencias.verificarAtualizacoes) {
                    Atualizador.verificar(this, avisarSemNovidade = false)
                }
            }
        }
    }

    /**
     * A ação pedida pelo intent que abriu o app: o extra `acao` ou, se faltar, o dado (URI) do intent
     * ("abrir_ponto" ou "ponto://abrir_ponto", o campo Dado do Tasker). Abrir o app pela tela de
     * recentes reentrega o intent original; nesse caso a ação é ignorada para não executar de novo.
     */
    private fun acaoDoIntent(): Acao? {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val chave = intent.getStringExtra(EXTRA_ACAO)
            ?: intent.dataString?.removePrefix("ponto:")?.trim('/', ' ')
        intent.removeExtra(EXTRA_ACAO)
        return Acao.da(chave)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.principal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.sobre) {
            Atualizador.sobre(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun abrirConfiguracoes() = startActivity(Intent(this, ConfiguracoesActivity::class.java))

    override fun onStart() {
        super.onStart()
        Execucao.ouvinte = ::desenhar
        desenhar()
        if (Execucao.rodando) relogio.postDelayed(tique, 1000)
    }

    override fun onStop() {
        super.onStop()
        Execucao.ouvinte = null
        relogio.removeCallbacks(tique)
    }

    /** Põe na tela o estado da [Execucao]. */
    private fun desenhar() {
        val rodando = Execucao.rodando
        // Verdes (disponíveis) fora da execução, cinza durante ela; o Cancelar é o contrário.
        for (botao in botoesDeAcao.values) botao.isEnabled = !rodando
        configuracoes.isEnabled = !rodando
        log.isEnabled = !rodando
        cancelar.isEnabled = rodando && !Execucao.cancelando
        progresso.visibility = if (rodando) View.VISIBLE else View.INVISIBLE

        status.text = if (rodando) {
            val segundos = (SystemClock.elapsedRealtime() - Execucao.inicio) / 1000
            val fazendo = if (Execucao.cancelando) "Cancelando" else "Executando"
            val tempo = if (segundos > 0) " ${segundos / 60}:${"%02d".format(segundos % 60)}" else ""
            "$fazendo: ${Execucao.rotulo}…$tempo"
        } else {
            Execucao.status
        }
        if (saida.text.toString() != Execucao.saida) {
            saida.text = Execucao.saida
            rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }
        }
        if (Execucao.pitDeOutraDataFeito) {
            Execucao.pitDeOutraDataFeito = false
            dataPit.setText("")
        }
    }

    /** Toque num botão de ação: confere credenciais, data e fechamento e executa. */
    private fun pedir(acao: Acao) {
        if (Execucao.rodando) return
        val credenciais = Dados.credenciais(this)
        if (credenciais == null || !credenciais.completas) {
            abrirConfiguracoes()
            return
        }
        var dia: LocalDate? = null
        val texto = dataPit.text.toString().trim()
        if (acao == Acao.REGISTRAR_PIT && texto.isNotEmpty()) {
            dia = Mascara.lerData(texto)
            if (dia == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Data inválida")
                    .setMessage("'$texto' não é uma data no formato dd/mm/aaaa.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
        }
        // PIT do dia (sem data ou com a data de hoje) só depois de fechar o ponto.
        val hoje = dia == null || dia == LocalDate.now()
        if (acao == Acao.REGISTRAR_PIT && hoje &&
            !Fechamentos.fechouNoDia(Dados.arquivo(this, "ultimo_fechamento.txt"))
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Ponto não fechado")
                .setMessage(
                    "O app não registrou o fechamento do ponto hoje.\n" +
                        "Feche o ponto antes de registrar o PIT do dia, ou informe a data " +
                        "para registrar o PIT de outro dia."
                )
                .setPositiveButton("Registrar mesmo assim") { _, _ -> executar(acao, dia, credenciais) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        executar(acao, dia, credenciais)
    }

    private fun executar(acao: Acao, dia: LocalDate?, credenciais: Credenciais) {
        Execucao.iniciar(this, acao, dia, credenciais)
    }

    companion object {
        /** Extra do intent com a ação a executar ao abrir o app, ex.: `acao=abrir_ponto`. */
        const val EXTRA_ACAO = "acao"

        /** Extra com a data do PIT (dd/mm/aaaa), no botão "Tentar de novo". */
        const val EXTRA_DATA = "data"

        /** O intent que abre o app e executa [acao] (com a [data] do PIT, se houver). */
        fun intentDaAcao(contexto: Context, acao: Acao, data: String? = null): Intent =
            Intent(contexto, MainActivity::class.java).apply {
                putExtra(EXTRA_ACAO, acao.chave)
                if (data != null) putExtra(EXTRA_DATA, data)
            }
    }
}
