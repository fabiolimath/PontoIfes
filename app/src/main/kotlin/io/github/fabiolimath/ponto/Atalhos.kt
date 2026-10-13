package io.github.fabiolimath.ponto

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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

    /** A lista das três ações; tocar numa pede ao launcher para fixar o seu atalho. */
    fun escolher(tela: AppCompatActivity) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(tela)) {
            MaterialAlertDialogBuilder(tela)
                .setTitle(R.string.atalho_adicionar)
                .setMessage(R.string.atalho_sem_suporte)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val acoes = Acao.entries
        MaterialAlertDialogBuilder(tela)
            .setTitle(R.string.atalho_adicionar)
            .setItems(acoes.map { it.rotulo }.toTypedArray()) { _, i -> fixar(tela, acoes[i]) }
            .setNegativeButton(R.string.cancelar, null)
            .show()
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
