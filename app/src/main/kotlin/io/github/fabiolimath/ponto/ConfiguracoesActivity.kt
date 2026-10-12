package io.github.fabiolimath.ponto

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.fabiolimath.ponto.nucleo.Credenciais
import io.github.fabiolimath.ponto.nucleo.Mascara
import io.github.fabiolimath.ponto.nucleo.Preferencias

/**
 * Credenciais e preferências. Sem credenciais salvas (1ª execução) mostra o aviso e não tem
 * Cancelar: é preciso salvar para usar o app.
 */
class ConfiguracoesActivity : AppCompatActivity() {
    private lateinit var usuario: EditText
    private lateinit var senha: EditText
    private lateinit var observacaoPit: EditText
    private lateinit var pitAutomatico: Spinner
    private lateinit var explicacaoPit: TextView
    private lateinit var notificacoes: SwitchMaterial
    private lateinit var lembreteFechar: SwitchMaterial
    private lateinit var lembreteTempo: EditText
    private lateinit var verificarAtualizacoes: SwitchMaterial

    private val explicacoes = intArrayOf(R.string.pit_auto_expl_0, R.string.pit_auto_expl_1, R.string.pit_auto_expl_2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_configuracoes)
        title = getString(R.string.configuracoes)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        usuario = findViewById(R.id.usuario)
        senha = findViewById(R.id.senha)
        observacaoPit = findViewById(R.id.observacao_pit)
        pitAutomatico = findViewById(R.id.pit_automatico)
        explicacaoPit = findViewById(R.id.explicacao_pit)
        notificacoes = findViewById(R.id.notificacoes)
        lembreteFechar = findViewById(R.id.lembrete_fechar)
        lembreteTempo = findViewById(R.id.lembrete_tempo)
        verificarAtualizacoes = findViewById(R.id.verificar_atualizacoes)

        pitAutomatico.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(R.string.pit_auto_0, R.string.pit_auto_1, R.string.pit_auto_2).map { getString(it) },
        )
        pitAutomatico.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(pai: AdapterView<*>?, visao: View?, posicao: Int, id: Long) {
                explicacaoPit.setText(explicacoes[posicao])
            }

            override fun onNothingSelected(pai: AdapterView<*>?) = Unit
        }
        lembreteTempo.addTextChangedListener(MascaraDeTexto(lembreteTempo, Mascara::formatarHora))

        // Ao girar a tela o Android devolve o que foi digitado; só a 1ª vez parte dos arquivos salvos.
        if (savedInstanceState == null) preencher()
        val atuais = Dados.credenciais(this)
        val temCredenciais = atuais?.completas == true
        findViewById<View>(R.id.aviso).visibility = if (atuais == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cancelar).apply {
            visibility = if (temCredenciais) View.VISIBLE else View.GONE
            setOnClickListener { finish() }
        }
        findViewById<View>(R.id.salvar).setOnClickListener { salvar() }
        findViewById<MaterialButton>(R.id.verificar_agora).apply {
            text = getString(R.string.verificar_agora, BuildConfig.VERSION_NAME)
            setOnClickListener { Atualizador.verificar(this@ConfiguracoesActivity, avisarSemNovidade = true) }
        }
    }

    private fun preencher() {
        val credenciais = Dados.credenciais(this)
        usuario.setText(credenciais?.usuario ?: "")
        senha.setText(credenciais?.senha ?: "")
        val preferencias = Dados.preferencias(this)
        observacaoPit.setText(preferencias.observacaoPit)
        pitAutomatico.setSelection(preferencias.pitAutomatico)
        explicacaoPit.setText(explicacoes[preferencias.pitAutomatico])
        notificacoes.isChecked = preferencias.notificacoes
        lembreteFechar.isChecked = preferencias.lembreteFechar
        lembreteTempo.setText(preferencias.lembreteTempo)
        verificarAtualizacoes.isChecked = preferencias.verificarAtualizacoes
    }

    private fun erro(titulo: String, mensagem: String) {
        MaterialAlertDialogBuilder(this).setTitle(titulo).setMessage(mensagem)
            .setPositiveButton("OK", null).show()
    }

    private fun salvar() {
        val novas = Credenciais(usuario.text.toString(), senha.text.toString())
        val falta = novas.faltando()
        if (falta.isNotEmpty()) {
            erro("Credenciais incompletas", "Preencha: ${falta.joinToString(", ")}.")
            return
        }
        val tempo = lembreteTempo.text.toString().trim()
        val tempoValido = Preferencias.tempoLembrete(tempo) != null
        if (lembreteFechar.isChecked && !tempoValido) {
            erro(
                "Tempo inválido",
                "'$tempo' não é um tempo no formato hh:mm (ex.: 01:40), entre 00:01 e 06:00.",
            )
            return
        }
        val padrao = Preferencias()
        val preferencias = Preferencias(
            notificacoes = notificacoes.isChecked,
            lembreteFechar = lembreteFechar.isChecked,
            lembreteTempo = if (tempoValido) tempo else padrao.lembreteTempo,
            verificarAtualizacoes = verificarAtualizacoes.isChecked,
            observacaoPit = observacaoPit.text.toString().trim().ifEmpty { padrao.observacaoPit },
            pitAutomatico = pitAutomatico.selectedItemPosition,
        )
        Dados.salvarCredenciais(this, novas)
        Dados.salvarPreferencias(this, preferencias)
        if (!preferencias.lembreteFechar) Lembretes.cancelar(this)
        if ((preferencias.notificacoes || preferencias.lembreteFechar) && PermissaoDeNotificacao.faltando(this)) {
            // Fecha ao receber a resposta; fechar já levaria o diálogo do sistema junto.
            PermissaoDeNotificacao.pedir(this)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados)
        if (codigo == PermissaoDeNotificacao.CODIGO) finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
