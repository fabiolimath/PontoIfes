package io.github.fabiolimath.ponto

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/** Restos da versão Python (1.0.x), que a atualização para a 2.0 deixa no aparelho. */
object Legado {
    /**
     * Apaga, em segundo plano, o Python que o Chaquopy extraía para `filesDir` e `cacheDir`
     * (dezenas de MB sem uso). Os dados do app, em `filesDir/data`, ficam.
     */
    fun apagarPython(contexto: Context) {
        val pastas = listOf(File(contexto.filesDir, "chaquopy"), File(contexto.cacheDir, "chaquopy"))
        if (pastas.none { it.exists() }) return
        thread { for (pasta in pastas) pasta.deleteRecursively() }
    }
}
