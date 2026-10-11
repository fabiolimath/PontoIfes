package io.github.fabiolimath.ponto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

/** A permissão de notificar, exigida a partir do Android 13. */
object PermissaoDeNotificacao {
    const val CODIGO = 1

    /** Verdadeiro se este Android exige a permissão e o app ainda não a tem. */
    fun faltando(atividade: Activity): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(atividade, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Pergunta se o app pode notificar; a resposta chega a `onRequestPermissionsResult` com
     * [CODIGO]. Se o usuário já recusou duas vezes, o Android não pergunta mais e responde
     * na hora (negado); aí só pelas configurações do sistema.
     */
    fun pedir(atividade: Activity) {
        ActivityCompat.requestPermissions(atividade, arrayOf(Manifest.permission.POST_NOTIFICATIONS), CODIGO)
    }
}
