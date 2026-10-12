package io.github.fabiolimath.ponto

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.core.app.ActivityCompat
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
    private lateinit var wifiAbrir: SwitchMaterial
    private lateinit var wifiRede: EditText
    private lateinit var wifiEspera: EditText
    private lateinit var wifiEstado: TextView
    private lateinit var wifiDetalhes: View
    private lateinit var lembreteDetalhes: View

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
        wifiAbrir = findViewById(R.id.wifi_abrir)
        wifiRede = findViewById(R.id.wifi_rede)
        wifiEspera = findViewById(R.id.wifi_espera)
        wifiEspera.addTextChangedListener(MascaraDeTexto(wifiEspera, Mascara::formatarHora))
        wifiEstado = findViewById(R.id.wifi_estado)
        wifiDetalhes = findViewById(R.id.wifi_detalhes)
        lembreteDetalhes = findViewById(R.id.lembrete_detalhes)

        // Os detalhes da Wi-Fi e do lembrete só aparecem com o interruptor ligado.
        wifiAbrir.setOnCheckedChangeListener { _, ligado -> wifiDetalhes.visibility = if (ligado) View.VISIBLE else View.GONE }
        lembreteFechar.setOnCheckedChangeListener { _, ligado ->
            lembreteDetalhes.visibility = if (ligado) View.VISIBLE else View.GONE
        }

        // Ao ligar (toque do usuário, não ao preencher nem ao girar a tela): o aviso da localização.
        wifiAbrir.setOnClickListener {
            if (wifiAbrir.isChecked) avisoDeLocalizacao()
            mostrarEstadoDaWifi()
        }
        wifiEstado.setOnClickListener { avisoDeLocalizacao() }

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
        wifiDetalhes.visibility = if (wifiAbrir.isChecked) View.VISIBLE else View.GONE
        lembreteDetalhes.visibility = if (lembreteFechar.isChecked) View.VISIBLE else View.GONE
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
        wifiAbrir.isChecked = preferencias.wifiAbrir
        wifiRede.setText(preferencias.wifiRede)
        wifiEspera.setText(preferencias.wifiEspera)
    }

    override fun onResume() {
        super.onResume()
        // A localização pode ter mudado nas configurações do sistema.
        mostrarEstadoDaWifi()
    }

    /** A linha abaixo do nome da rede: se o app consegue lê-lo. Só com a opção ligada. */
    private fun mostrarEstadoDaWifi() {
        if (!wifiAbrir.isChecked) {
            wifiEstado.visibility = View.GONE
            return
        }
        wifiEstado.visibility = View.VISIBLE
        wifiEstado.setText(
            when (Wifi.leitura(this)) {
                Wifi.Leitura.RECONHECIDO -> R.string.wifi_reconhecido
                Wifi.Leitura.SEM_PERMISSAO -> R.string.wifi_sem_permissao
                Wifi.Leitura.LOCALIZACAO_DESLIGADA -> R.string.wifi_localizacao_desligada
            }
        )
    }

    // ---------------- localização (para ler o nome da rede) ----------------

    /**
     * Recomendada, não obrigatória: sem ela o app tenta em qualquer Wi-Fi e o SIGRH recusa fora do
     * campus. Com a permissão já dada, só confere se a localização do celular está ligada.
     */
    private fun avisoDeLocalizacao() {
        when (Wifi.leitura(this)) {
            Wifi.Leitura.RECONHECIDO -> Unit
            Wifi.Leitura.LOCALIZACAO_DESLIGADA -> avisoDeLocalizacaoDesligada()
            Wifi.Leitura.SEM_PERMISSAO -> MaterialAlertDialogBuilder(this)
                .setTitle("Reconhecer a rede")
                .setMessage(
                    "Para reconhecer a rede pelo nome, o Android exige acesso à localização o tempo todo " +
                        "e a localização ligada. O app não usa sua localização, só o nome da rede.\n\n" +
                        "Sem isso, ele tenta abrir o ponto em qualquer Wi-Fi, e o SIGRH recusa fora do " +
                        "campus, em silêncio."
                )
                .setPositiveButton("Permitir") { _, _ -> pedirLocalizacao() }
                .setNegativeButton("Agora não", null)
                .show()
        }
    }

    private fun avisoDeLocalizacaoDesligada() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Localização desligada")
            .setMessage("A localização do celular está desligada. Sem ela, o Android não mostra o nome da rede.")
            .setPositiveButton("Ligar") { _, _ ->
                abrirTela(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    /**
     * Primeiro a localização precisa; no Android 10 o próprio diálogo oferece "Permitir o tempo
     * todo". Do Android 11 em diante, o "tempo todo" fica numa tela à parte ([pedirSegundoPlano]).
     */
    private fun pedirLocalizacao() {
        val precisa = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        when {
            !Wifi.temLocalizacaoPrecisa(this) && Build.VERSION.SDK_INT == 29 -> ActivityCompat.requestPermissions(
                this, precisa + Manifest.permission.ACCESS_BACKGROUND_LOCATION, CODIGO_LOCALIZACAO,
            )
            !Wifi.temLocalizacaoPrecisa(this) -> ActivityCompat.requestPermissions(this, precisa, CODIGO_LOCALIZACAO)
            !Wifi.temLocalizacaoEmSegundoPlano(this) -> pedirSegundoPlano()
            else -> depoisDaLocalizacao()
        }
    }

    private fun pedirSegundoPlano() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Localização o tempo todo")
            .setMessage("Na próxima tela, escolha \"Permitir o tempo todo\".")
            .setPositiveButton("OK") { _, _ ->
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), CODIGO_SEGUNDO_PLANO,
                )
            }
            .setNegativeButton("Agora não") { _, _ -> mostrarEstadoDaWifi() }
            .show()
    }

    private fun depoisDaLocalizacao() {
        mostrarEstadoDaWifi()
        if (Wifi.leitura(this) == Wifi.Leitura.LOCALIZACAO_DESLIGADA) avisoDeLocalizacaoDesligada()
    }

    private fun abrirTela(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("Ponto", "Sem a tela ${intent.action}", e)
        }
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
        val espera = wifiEspera.text.toString().trim()
        val esperaValida = Preferencias.tempoLembrete(espera) != null
        if (wifiAbrir.isChecked && !esperaValida) {
            erro(
                "Tempo inválido",
                "'$espera' não é um tempo de espera no formato hh:mm (ex.: 01:00), entre 00:01 e 06:00.",
            )
            return
        }
        val padrao = Preferencias()
        val anteriores = Dados.preferencias(this)
        val preferencias = Preferencias(
            notificacoes = notificacoes.isChecked,
            lembreteFechar = lembreteFechar.isChecked,
            lembreteTempo = if (tempoValido) tempo else padrao.lembreteTempo,
            verificarAtualizacoes = verificarAtualizacoes.isChecked,
            observacaoPit = observacaoPit.text.toString().trim().ifEmpty { padrao.observacaoPit },
            pitAutomatico = pitAutomatico.selectedItemPosition,
            wifiAbrir = wifiAbrir.isChecked,
            wifiRede = wifiRede.text.toString().trim().ifEmpty { padrao.wifiRede },
            wifiEspera = if (esperaValida) espera else padrao.wifiEspera,
            avisoBateria = anteriores.avisoBateria,
        )
        Dados.salvarCredenciais(this, novas)
        Dados.salvarPreferencias(this, preferencias)
        if (!preferencias.lembreteFechar) Lembretes.cancelar(this)
        // Ao ligar a opção (ou trocar a rede), o pedido é refeito: dentro do campus, abre o ponto em seguida.
        val wifiMudou = preferencias.wifiAbrir && (!anteriores.wifiAbrir || anteriores.wifiRede != preferencias.wifiRede)
        try {
            Wifi.registrar(this, forcar = wifiMudou)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro ao registrar o gatilho da Wi-Fi", e)
        }
        if ((preferencias.notificacoes || preferencias.lembreteFechar) && PermissaoDeNotificacao.faltando(this)) {
            // Segue ao receber a resposta; fechar já levaria o diálogo do sistema junto.
            PermissaoDeNotificacao.pedir(this)
        } else {
            avisoDeBateria()
        }
    }

    /**
     * Depois de salvar com a abertura pela Wi-Fi ligada: se a bateria do app não está em Sem
     * restrições (e o aviso não foi dispensado), o aviso com os três botões. Senão, fecha.
     */
    private fun avisoDeBateria() {
        val preferencias = Dados.preferencias(this)
        if (!preferencias.wifiAbrir || !preferencias.avisoBateria || Wifi.bateriaLiberada(this)) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bateria")
            .setMessage("Para abrir o ponto com o app fechado, a bateria do Ponto IFES precisa estar em Sem restrições.")
            .setPositiveButton("Liberar agora") { _, _ -> Wifi.liberarBateria(this) }
            .setNegativeButton("Não avisar mais") { _, _ ->
                Dados.salvarPreferencias(this, Dados.preferencias(this).copy(avisoBateria = false))
            }
            .setNeutralButton("Instruções") { _, _ ->
                abrirTela(Intent(Intent.ACTION_VIEW, Uri.parse(Wifi.URL_INSTRUCOES)))
            }
            .setOnDismissListener { finish() }
            .show()
    }

    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) {
        super.onRequestPermissionsResult(codigo, permissoes, resultados)
        when (codigo) {
            PermissaoDeNotificacao.CODIGO -> avisoDeBateria()
            CODIGO_LOCALIZACAO ->
                if (Wifi.temLocalizacaoPrecisa(this) && !Wifi.temLocalizacaoEmSegundoPlano(this)) pedirSegundoPlano()
                else depoisDaLocalizacao()
            CODIGO_SEGUNDO_PLANO -> depoisDaLocalizacao()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val CODIGO_LOCALIZACAO = 2
        private const val CODIGO_SEGUNDO_PLANO = 3
    }
}
