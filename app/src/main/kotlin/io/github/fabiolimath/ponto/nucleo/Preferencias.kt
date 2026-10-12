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
 *
 * [wifiAbrir]: abrir o ponto ao conectar na Wi-Fi [wifiRede], menos nas [wifiEspera] ("hh:mm")
 * depois de fechar; [avisoBateria]: avisar ao salvar se a bateria do app não está em Sem restrições.
 */
data class Preferencias(
    val notificacoes: Boolean = true,
    val lembreteFechar: Boolean = true,
    val lembreteTempo: String = "01:00",
    val verificarAtualizacoes: Boolean = true,
    val observacaoPit: String = OBSERVACAO_PADRAO,
    val pitAutomatico: Int = 0,
    val wifiAbrir: Boolean = false,
    val wifiRede: String = GatilhoWifi.REDE_PADRAO,
    val wifiEspera: String = "01:00",
    val avisoBateria: Boolean = true,
) {
    /** O tempo do lembrete como [Duration], ou null se [lembreteTempo] for inválido. */
    val duracaoDoLembrete get() = tempoLembrete(lembreteTempo)

    /** A espera da abertura automática depois de fechar; inválida vira o padrão (01:00). */
    val duracaoDaEsperaWifi get() = tempoLembrete(wifiEspera) ?: GatilhoWifi.ESPERA_PADRAO

    fun salvar(arquivo: File) {
        val json = JSONObject()
            .put("notificacoes", notificacoes)
            .put("lembrete_fechar", lembreteFechar)
            .put("lembrete_tempo", lembreteTempo)
            .put("verificar_atualizacoes", verificarAtualizacoes)
            .put("observacao_pit", observacaoPit)
            .put("pit_automatico", pitAutomatico)
            .put("wifi_abrir", wifiAbrir)
            .put("wifi_rede", wifiRede)
            .put("wifi_espera", wifiEspera)
            .put("aviso_bateria", avisoBateria)
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(json.toString())
    }

    companion object {
        const val OBSERVACAO_PADRAO = "Conforme PIT docente."

        /** O maior tempo aceito no lembrete (06:00). */
        val MAXIMO_DO_LEMBRETE: Duration = Duration.ofHours(6)

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
                lembreteTempo = limitarTempo(json.optString("lembrete_tempo", padrao.lembreteTempo)),
                verificarAtualizacoes = json.optBoolean("verificar_atualizacoes", padrao.verificarAtualizacoes),
                observacaoPit = json.optString("observacao_pit", padrao.observacaoPit),
                pitAutomatico = if (opcao in 0..2) opcao else 0,
                wifiAbrir = json.optBoolean("wifi_abrir", padrao.wifiAbrir),
                wifiRede = json.optString("wifi_rede", padrao.wifiRede).trim().ifEmpty { padrao.wifiRede },
                wifiEspera = json.optString("wifi_espera", padrao.wifiEspera).let {
                    if (tempoLembrete(it) != null) it else padrao.wifiEspera
                },
                avisoBateria = json.optBoolean("aviso_bateria", padrao.avisoBateria),
            )
        }

        /**
         * A versão Python aceitava até 23:59: um tempo salvo acima de [MAXIMO_DO_LEMBRETE] vira 06:00,
         * em vez de deixar o lembrete parar de funcionar sem aviso. O resto fica como está.
         */
        private fun limitarTempo(texto: String): String {
            val partes = texto.trim().split(":")
            val horas = partes.getOrNull(0)?.toIntOrNull()
            val minutos = partes.getOrNull(1)?.toIntOrNull()
            if (partes.size != 2 || horas == null || minutos == null || minutos !in 0..59) return texto
            val total = Duration.ofHours(horas.toLong()).plusMinutes(minutos.toLong())
            return if (total > MAXIMO_DO_LEMBRETE) "06:00" else texto
        }

        /**
         * "hh:mm" (ex.: "01:40", "6:00") como [Duration]; null se inválido, zero ou acima de 06:00.
         * Vale para o lembrete e para a espera da Wi-Fi depois de fechar.
         */
        fun tempoLembrete(texto: String): Duration? {
            val partes = texto.trim().split(":")
            if (partes.size != 2) return null
            val horas = partes[0].toIntOrNull() ?: return null
            val minutos = partes[1].toIntOrNull() ?: return null
            if (horas < 0 || minutos !in 0..59) return null
            val total = Duration.ofHours(horas.toLong()).plusMinutes(minutos.toLong())
            return total.takeIf { it > Duration.ZERO && it <= MAXIMO_DO_LEMBRETE }
        }
    }
}
