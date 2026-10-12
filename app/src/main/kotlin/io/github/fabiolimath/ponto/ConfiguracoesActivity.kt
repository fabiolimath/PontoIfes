package io.github.fabiolimath.ponto

import android.Manifest
import android.content.Intent
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.app.ActivityCompat
import io.github.fabiolimath.ponto.nucleo.Credenciais
import io.github.fabiolimath.ponto.nucleo.FechamentoAutomatico
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
    private lateinit var fecharModo: Spinner
    private lateinit var fecharExplicacao: TextView
    private lateinit var fecharHorario1: EditText
    private lateinit var fecharHorario2: EditText
    private lateinit var fecharDetalhes: View
    private lateinit var fecharDias: View

    /** Os horários de cada dia (Avançado) em edição: vêm da tela própria e são gravados no Salvar. */
    private var dias: List<List<String>> = Preferencias.DIAS_PADRAO

    private val horariosDeCadaDia = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) dias = HorariosActivity.ler(r.data)
    }

    private val explicacoesDoFechamento = intArrayOf(R.string.fechar_expl_0, R.string.fechar_expl_1, R.string.fechar_expl_2)

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
        fecharModo = findViewById(R.id.fechar_modo)
        fecharExplicacao = findViewById(R.id.fechar_explicacao)
        fecharHorario1 = findViewById(R.id.fechar_horario1)
        fecharHorario2 = findViewById(R.id.fechar_horario2)
        fecharDetalhes = findViewById(R.id.fechar_detalhes)
        fecharDias = findViewById(R.id.fechar_dias)
        fecharDias.setOnClickListener { horariosDeCadaDia.launch(HorariosActivity.abrir(this, dias)) }
        for (campo in listOf(fecharHorario1, fecharHorario2)) {
            campo.addTextChangedListener(MascaraDeTexto(campo, Mascara::formatarHora))
        }

        // Os detalhes da Wi-Fi e do lembrete só aparecem com o interruptor ligado; os do fechamento
        // automático, conforme a opção (os dois horários fixos ou o botão dos horários de cada dia).
        wifiAbrir.setOnCheckedChangeListener { _, ligado -> wifiDetalhes.visibility = if (ligado) View.VISIBLE else View.GONE }
        lembreteFechar.setOnCheckedChangeListener { _, ligado ->
            lembreteDetalhes.visibility = if (ligado) View.VISIBLE else View.GONE
        }
        fecharModo.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(R.string.fechar_modo_0, R.string.fechar_modo_1, R.string.fechar_modo_2).map { getString(it) },
        )
        fecharModo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(pai: AdapterView<*>?, visao: View?, posicao: Int, id: Long) = mostrarFechamento()
            override fun onNothingSelected(pai: AdapterView<*>?) = Unit
        }

        // Ao ligar (toque do usuário, não ao preencher nem ao girar a tela): o aviso da localização.
        wifiAbrir.setOnClickListener {
            if (wifiAbrir.isChecked) avisoDeLocalizacao()
            mostrarEstadoDaWifi()
        }
        wifiEstado.setOnClickListener { avisoDeLocalizacao() }
        findViewById<MaterialButton>(R.id.wifi_rede_atual).setOnClickListener { definirRedeAtual() }

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
        else dias = HorariosActivity.dividir(savedInstanceState.getStringArrayList(ESTADO_DIAS))
        wifiDetalhes.visibility = if (wifiAbrir.isChecked) View.VISIBLE else View.GONE
        lembreteDetalhes.visibility = if (lembreteFechar.isChecked) View.VISIBLE else View.GONE
        mostrarFechamento()
        val atuais = Dados.credenciais(this)
        val temCredenciais = atuais?.completas == true
        findViewById<View>(R.id.aviso).visibility = if (atuais == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cancelar).apply {
            visibility = if (temCredenciais) View.VISIBLE else View.GONE
            setOnClickListener { finish() }
        }
        findViewById<View>(R.id.salvar).setOnClickListener { salvar() }
        findViewById<TextView>(R.id.versao_instalada).text =
            getString(R.string.versao_instalada, BuildConfig.VERSION_NAME)
        findViewById<MaterialButton>(R.id.verificar_agora).apply {
            text = getString(R.string.verificar_agora)
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
        fecharModo.setSelection(preferencias.fecharModo)
        dias = preferencias.fecharDias
        fecharHorario1.setText(preferencias.fecharHorario1)
        fecharHorario2.setText(preferencias.fecharHorario2)
    }

    /** A explicação e os detalhes da opção escolhida em Fechar automaticamente. */
    private fun mostrarFechamento() {
        val modo = fecharModo.selectedItemPosition.coerceIn(0, 2)
        fecharExplicacao.setText(explicacoesDoFechamento[modo])
        fecharDetalhes.visibility = if (modo == Preferencias.FECHAR_FIXOS) View.VISIBLE else View.GONE
        fecharDias.visibility = if (modo == Preferencias.FECHAR_AVANCADO) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putStringArrayList(ESTADO_DIAS, ArrayList(dias.flatten()))
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

    // ---------------- rede atual ----------------

    /** Se o botão "Definir rede atual como padrão" espera o pedido de localização terminar. */
    private var redeAtualPendente = false

    /** Sem a localização, pede "o tempo todo" (a mesma da abertura automática) e depois preenche. */
    private fun definirRedeAtual() {
        if (Wifi.leitura(this) == Wifi.Leitura.SEM_PERMISSAO) {
            redeAtualPendente = true
            avisoDeLocalizacao()
        } else {
            preencherRedeAtual()
        }
    }

    /** Com o app aberto basta a localização precisa: o "tempo todo" é para o app fechado. */
    private fun preencherRedeAtual() {
        redeAtualPendente = false
        when {
            !Wifi.temLocalizacaoPrecisa(this) ->
                erro("Rede atual", "Sem acesso à localização, o Android não mostra o nome da rede.")
            !Wifi.localizacaoLigada(this) -> avisoDeLocalizacaoDesligada()
            else -> {
                val nome = Wifi.nomeDaRede(this)
                if (nome == null) {
                    erro("Rede atual", "O celular não está conectado a uma Wi-Fi.")
                } else {
                    wifiRede.setText(nome)
                    Toast.makeText(this, "Rede \"$nome\". Toque em Salvar para guardar.", Toast.LENGTH_LONG).show()
                }
            }
        }
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
                .setNegativeButton("Agora não") { _, _ -> if (redeAtualPendente) preencherRedeAtual() }
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
            .setNegativeButton("Agora não") { _, _ ->
                if (redeAtualPendente) preencherRedeAtual() else mostrarEstadoDaWifi()
            }
            .show()
    }

    private fun depoisDaLocalizacao() {
        mostrarEstadoDaWifi()
        when {
            redeAtualPendente -> preencherRedeAtual()
            Wifi.leitura(this) == Wifi.Leitura.LOCALIZACAO_DESLIGADA -> avisoDeLocalizacaoDesligada()
        }
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
        val modo = fecharModo.selectedItemPosition.coerceIn(0, 2)
        val horarios = listOf(fecharHorario1, fecharHorario2).map { it.text.toString().trim() }
        val horarioInvalido = horarios.firstOrNull { it.isNotEmpty() && FechamentoAutomatico.lerHorario(it) == null }
        if (modo == Preferencias.FECHAR_FIXOS && horarioInvalido != null) {
            erro(
                "Horário inválido",
                "'$horarioInvalido' não é um horário no formato hh:mm (ex.: 18:00), entre 00:00 e 23:59.",
            )
            return
        }
        if (modo == Preferencias.FECHAR_FIXOS && horarios.all { it.isEmpty() }) {
            erro("Sem horário", "Preencha ao menos um horário para fechar o ponto automaticamente.")
            return
        }
        if (modo == Preferencias.FECHAR_AVANCADO && dias.flatten().all { it.isEmpty() }) {
            erro("Sem horário", "Preencha ao menos um horário em Horários de cada dia.")
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
            fecharModo = modo,
            // Os horários da opção que não está em uso ficam guardados, se forem válidos.
            fecharHorario1 = horarios[0].takeIf { FechamentoAutomatico.lerHorario(it) != null } ?: "",
            fecharHorario2 = horarios[1].takeIf { FechamentoAutomatico.lerHorario(it) != null } ?: "",
            fecharDias = dias,
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
        try {
            FecharAutomatico.agendar(this)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro ao agendar o fechamento automático", e)
        }
        if ((preferencias.notificacoes || preferencias.lembreteFechar) && PermissaoDeNotificacao.faltando(this)) {
            // Segue ao receber a resposta; fechar já levaria o diálogo do sistema junto.
            PermissaoDeNotificacao.pedir(this)
        } else {
            avisoDeBateria()
        }
    }

    /**
     * Depois de salvar com a abertura pela Wi-Fi ou o fechamento automático ligados: se a bateria
     * do app não está em Sem restrições (e o aviso não foi dispensado), o aviso com os dois botões.
     * Senão, fecha.
     */
    private fun avisoDeBateria() {
        val preferencias = Dados.preferencias(this)
        val automatico = preferencias.wifiAbrir || preferencias.fecharAutomatico
        if (!automatico || !preferencias.avisoBateria || Wifi.bateriaLiberada(this)) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bateria")
            .setMessage("Para abrir ou fechar o ponto com o app fechado, a bateria do Ponto IFES precisa estar em Sem restrições.")
            .setPositiveButton("Liberar agora") { _, _ -> Wifi.liberarBateria(this) }
            .setNegativeButton("Não avisar mais") { _, _ ->
                Dados.salvarPreferencias(this, Dados.preferencias(this).copy(avisoBateria = false))
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
        private const val ESTADO_DIAS = "fechar_dias"
        private const val CODIGO_LOCALIZACAO = 2
        private const val CODIGO_SEGUNDO_PLANO = 3
    }
}
