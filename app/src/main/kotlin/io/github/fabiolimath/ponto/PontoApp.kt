package io.github.fabiolimath.ponto

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * Cada vez que o processo do app sobe (abrir qualquer tela, um alarme, um trabalho), confere os
 * gatilhos das ações automáticas e refaz os que sumiram. Antes isso só acontecia ao criar a tela
 * principal: com o app parado nas Configurações, a Wi-Fi perdida só voltava ao sair delas.
 */
class PontoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        refazerGatilhos(this)
    }

    companion object {
        /**
         * O gatilho da Wi-Fi (só se sumiu, por exemplo com o "Forçar parada") e os alarmes do
         * fechamento e do PIT automáticos (refazer o mesmo alarme não muda nada).
         */
        fun refazerGatilhos(contexto: Context) {
            try {
                Wifi.registrar(contexto, forcar = false)
            } catch (e: Exception) {
                Log.w("Ponto", "Erro ao registrar o gatilho da Wi-Fi", e)
            }
            try {
                FecharAutomatico.agendar(contexto)
            } catch (e: Exception) {
                Log.w("Ponto", "Erro ao agendar o fechamento automático", e)
            }
            try {
                RegistrarPitAutomatico.agendar(contexto)
            } catch (e: Exception) {
                Log.w("Ponto", "Erro ao agendar o PIT automático", e)
            }
        }
    }
}
