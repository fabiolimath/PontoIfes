package io.github.fabiolimath.ponto

import android.content.Intent
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.fabiolimath.ponto.nucleo.Acao

/**
 * Fixa na tela inicial um dos atalhos do ícone (Configurações > Atalhos e apps externos). O launcher
 * pede confirmação de cada um. Os ids, a atividade e o extra são os de `xml/shortcuts.xml`, para o
 * atalho fixado aqui ser o mesmo de quem arrasta pelo ícone.
 */
object Atalhos {
    /** O nome da atividade da versão Python (o activity-alias do manifesto), alvo dos atalhos. */
    private const val ATIVIDADE = "org.beeware.android.MainActivity"

    private fun icone(acao: Acao): Int = when (acao) {
        Acao.ABRIR_PONTO -> R.drawable.atalho_abrir
        Acao.FECHAR_PONTO -> R.drawable.atalho_fechar
        Acao.REGISTRAR_PIT -> R.drawable.atalho_pit
    }

    /** Um botão por ação; tocar num pede ao launcher para fixar o seu atalho. */
    fun escolher(tela: AppCompatActivity) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(tela)) {
            MaterialAlertDialogBuilder(tela)
                .setTitle(R.string.atalho_adicionar)
                .setMessage(R.string.atalho_sem_suporte)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        // Um botão verde por ação (uma lista de itens parecia só texto).
        val margem = (24 * tela.resources.displayMetrics.density).toInt()
        val botoes = LinearLayout(tela).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margem, margem / 2, margem, 0)
        }
        val dialogo = MaterialAlertDialogBuilder(tela)
            .setTitle(R.string.atalho_adicionar)
            .setView(botoes)
            .setNegativeButton(R.string.cancelar, null)
            .create()
        for (acao in Acao.entries) {
            botoes.addView(
                MaterialButton(tela).apply {
                    text = acao.rotulo
                    setOnClickListener {
                        dialogo.dismiss()
                        fixar(tela, acao)
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        dialogo.show()
    }

    private fun fixar(tela: AppCompatActivity, acao: Acao) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setClassName(tela.packageName, ATIVIDADE)
            .putExtra(MainActivity.EXTRA_ACAO, acao.chave)
        val atalho = ShortcutInfoCompat.Builder(tela, acao.chave)
            .setShortLabel(acao.rotulo)
            .setIcon(IconCompat.createWithResource(tela, icone(acao)))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(tela, atalho, null)
    }
}
