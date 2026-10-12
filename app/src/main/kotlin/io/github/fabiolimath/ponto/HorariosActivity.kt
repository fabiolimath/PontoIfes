package io.github.fabiolimath.ponto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.fabiolimath.ponto.nucleo.FechamentoAutomatico
import io.github.fabiolimath.ponto.nucleo.Mascara

/**
 * Os horários de cada dia do fechamento automático (Avançado), de segunda a sexta. Recebe e devolve
 * os horários às Configurações, que os gravam no Salvar; o OK daqui só confere e volta.
 */
class HorariosActivity : AppCompatActivity() {
    private lateinit var campos: List<List<EditText>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_horarios)
        title = getString(R.string.horarios_de_cada_dia)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        campos = IDS.map { dia -> dia.map { findViewById<EditText>(it) } }
        for (campo in campos.flatten()) campo.addTextChangedListener(MascaraDeTexto(campo, Mascara::formatarHora))
        // Ao girar a tela o Android devolve o que foi digitado; só a 1ª vez parte do que veio.
        if (savedInstanceState == null) {
            val dias = ler(intent)
            campos.forEachIndexed { i, dia -> dia.forEachIndexed { j, campo -> campo.setText(dias[i][j]) } }
        }
        findViewById<android.view.View>(R.id.cancelar).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.ok).setOnClickListener { confirmar() }
    }

    private fun confirmar() {
        val dias = campos.map { dia -> dia.map { it.text.toString().trim() } }
        val invalido = dias.flatten().firstOrNull { it.isNotEmpty() && FechamentoAutomatico.lerHorario(it) == null }
        if (invalido != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Horário inválido")
                .setMessage("'$invalido' não é um horário no formato hh:mm (ex.: 18:00), entre 00:00 e 23:59.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(EXTRA_DIAS, ArrayList(dias.flatten())))
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_DIAS = "dias"

        private val IDS = listOf(
            listOf(R.id.horario_0_0, R.id.horario_0_1),
            listOf(R.id.horario_1_0, R.id.horario_1_1),
            listOf(R.id.horario_2_0, R.id.horario_2_1),
            listOf(R.id.horario_3_0, R.id.horario_3_1),
            listOf(R.id.horario_4_0, R.id.horario_4_1),
        )

        /** A tela com os [dias] (cinco dias, dois horários cada). */
        fun abrir(contexto: Context, dias: List<List<String>>): Intent =
            Intent(contexto, HorariosActivity::class.java)
                .putStringArrayListExtra(EXTRA_DIAS, ArrayList(dias.flatten()))

        /** Os horários de cada dia de um intent (o de abrir ou o resultado do OK). */
        fun ler(intent: Intent?): List<List<String>> = dividir(intent?.getStringArrayListExtra(EXTRA_DIAS))

        /** Dez horários em fila (segunda 1º, segunda 2º, terça 1º...) de volta em cinco dias. */
        fun dividir(lista: List<String>?): List<List<String>> =
            List(5) { i -> List(2) { j -> lista?.getOrNull(i * 2 + j) ?: "" } }
    }
}
