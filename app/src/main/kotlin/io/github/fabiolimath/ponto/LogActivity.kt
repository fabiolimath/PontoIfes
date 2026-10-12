package io.github.fabiolimath.ponto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** O log das execuções, com Voltar, Copiar e Limpar. */
class LogActivity : AppCompatActivity() {
    private lateinit var texto: TextView
    private lateinit var rolagem: ScrollView
    private val registro by lazy { Dados.registro(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        title = getString(R.string.log)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        texto = findViewById(R.id.texto_log)
        rolagem = findViewById(R.id.rolagem)

        findViewById<View>(R.id.voltar).setOnClickListener { finish() }
        val copiar = findViewById<MaterialButton>(R.id.copiar)
        copiar.setOnClickListener {
            val area = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            area.setPrimaryClip(ClipData.newPlainText("Log do Ponto IFES", registro.ler()))
            copiar.setText(R.string.copiado)
        }
        findViewById<View>(R.id.limpar).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Limpar log")
                .setMessage("Apagar todo o histórico de execuções?")
                .setPositiveButton("Apagar") { _, _ ->
                    registro.limpar()
                    mostrar()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        mostrar()
    }

    private fun mostrar() {
        texto.text = registro.ler()
        rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
