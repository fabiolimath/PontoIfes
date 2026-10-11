package io.github.fabiolimath.ponto.nucleo

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Duration

/**
 * As preferências do app (`configuracoes.json`, o mesmo formato da versão Python).
 *
 * [pitAutomatico]: 0 = não, 1 = no 1º fechamento do dia (quem não faz almoço),
 * 2 = no 2º (quem fecha no almoço).
 */
data class Preferencias(
    val notificacoes: Boolean = true,
    val lembreteFechar: Boolean = true,
    val lembreteTempo: String = "01:00",
    val verificarAtualizacoes: Boolean = true,
    val observacaoPit: String = OBSERVACAO_PADRAO,
    val pitAutomatico: Int = 0,
) {
    /** O tempo do lembrete como [Duration], ou null se [lembreteTempo] for inválido. */
    val duracaoDoLembrete get() = tempoLembrete(lembreteTempo)

    fun salvar(arquivo: File) {
        val json = JSONObject()
            .put("notificacoes", notificacoes)
            .put("lembrete_fechar", lembreteFechar)
            .put("lembrete_tempo", lembreteTempo)
            .put("verificar_atualizacoes", verificarAtualizacoes)
            .put("observacao_pit", observacaoPit)
            .put("pit_automatico", pitAutomatico)
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(json.toString())
    }

    companion object {
        const val OBSERVACAO_PADRAO = "Conforme PIT docente."

        /** Preferências salvas, completadas com os valores padrão (também se o arquivo for ilegível). */
        fun carregar(arquivo: File): Preferencias {
            val padrao = Preferencias()
            if (!arquivo.exists()) return padrao
            val json = try {
                JSONObject(arquivo.readText())
            } catch (e: JSONException) {
                return padrao
            }
            val opcao = json.optInt("pit_automatico", 0)
            return Preferencias(
                notificacoes = json.optBoolean("notificacoes", padrao.notificacoes),
                lembreteFechar = json.optBoolean("lembrete_fechar", padrao.lembreteFechar),
                lembreteTempo = json.optString("lembrete_tempo", padrao.lembreteTempo),
                verificarAtualizacoes = json.optBoolean("verificar_atualizacoes", padrao.verificarAtualizacoes),
                observacaoPit = json.optString("observacao_pit", padrao.observacaoPit),
                pitAutomatico = if (opcao in 0..2) opcao else 0,
            )
        }

        /** "hh:mm" (ex.: "01:40", "8:00") como [Duration]; null se inválido ou zero. */
        fun tempoLembrete(texto: String): Duration? {
            val partes = texto.trim().split(":")
            if (partes.size != 2) return null
            val horas = partes[0].toIntOrNull() ?: return null
            val minutos = partes[1].toIntOrNull() ?: return null
            if (horas !in 0..23 || minutos !in 0..59 || (horas == 0 && minutos == 0)) return null
            return Duration.ofHours(horas.toLong()).plusMinutes(minutos.toLong())
        }
    }
}
