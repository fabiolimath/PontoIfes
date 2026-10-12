package io.github.fabiolimath.ponto

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Aplica uma máscara (como `Mascara::formatarData`) no campo enquanto se digita, com o cursor no fim.
 * [formatar] recebe o texto e se o usuário está apagando.
 */
class MascaraDeTexto(
    private val campo: EditText,
    private val formatar: (String, Boolean) -> String,
) : TextWatcher {
    private var anterior = ""
    private var ajustando = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable) {
        if (ajustando) return
        val novo = s.toString()
        val apagando = novo.length < anterior.length
        val formatado = formatar(novo, apagando)
        anterior = formatado
        if (formatado != novo) {
            ajustando = true
            s.replace(0, s.length, formatado)
            ajustando = false
            campo.setSelection(formatado.length)
        }
    }
}
