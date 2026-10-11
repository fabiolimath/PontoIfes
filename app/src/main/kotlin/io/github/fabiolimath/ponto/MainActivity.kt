package io.github.fabiolimath.ponto

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.fabiolimath.ponto.nucleo.Acao
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
        configuracoes.setOnClickListener { pedirCredenciais(obrigatorio = false) }
        log.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        dataPit.addTextChangedListener(MascaraDeData(dataPit))

        if (Credenciais.carregar(this)?.completas != true) pedirCredenciais(obrigatorio = true)
    }

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
        val credenciais = Credenciais.carregar(this)
        if (credenciais == null || !credenciais.completas) {
            pedirCredenciais(obrigatorio = true)
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

    /**
     * Pede o usuário e a senha do SIGRH. Provisório (etapa 3): a tela de Configurações completa
     * vem na etapa 4. [obrigatorio]: na 1ª execução não há como cancelar.
     */
    private fun pedirCredenciais(obrigatorio: Boolean) {
        val visao = layoutInflater.inflate(R.layout.dialog_credenciais, null)
        val usuario = visao.findViewById<EditText>(R.id.usuario)
        val senha = visao.findViewById<EditText>(R.id.senha)
        val atuais = Credenciais.carregar(this)
        usuario.setText(atuais?.usuario ?: "")
        senha.setText(atuais?.senha ?: "")
        visao.findViewById<View>(R.id.aviso).visibility = if (atuais == null) View.VISIBLE else View.GONE
        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle("Configurações")
            .setView(visao)
            .setCancelable(!obrigatorio)
            .setPositiveButton(R.string.salvar, null)
            .also { if (!obrigatorio) it.setNegativeButton(R.string.cancelar, null) }
            .create()
        dialogo.show()
        dialogo.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val novas = Credenciais(usuario.text.toString(), senha.text.toString())
            val falta = buildList {
                if (novas.usuario.isBlank()) add("Usuário do SIGRH")
                if (novas.senha.isBlank()) add("Senha do SIGRH")
            }
            if (falta.isNotEmpty()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Credenciais incompletas")
                    .setMessage("Preencha: ${falta.joinToString(", ")}.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Credenciais.salvar(this, novas)
                dialogo.dismiss()
            }
        }
    }
}

/** Aplica a máscara dd/mm/aaaa no campo enquanto se digita, com o cursor no fim. */
private class MascaraDeData(private val campo: EditText) : TextWatcher {
    private var anterior = ""
    private var ajustando = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable) {
        if (ajustando) return
        val novo = s.toString()
        val apagando = novo.length < anterior.length
        val formatado = Mascara.formatarData(novo, apagando)
        anterior = formatado
        if (formatado != novo) {
            ajustando = true
            s.replace(0, s.length, formatado)
            ajustando = false
            campo.setSelection(formatado.length)
        }
    }
}
