package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * O que o app sabe na hora em que uma Wi-Fi conecta: as últimas abertura e fechamento com sucesso
 * (manuais ou automáticos) e a última tentativa automática.
 */
data class EstadoDoPonto(
    val ultimaAbertura: LocalDateTime? = null,
    val ultimoFechamento: LocalDateTime? = null,
    val ultimaTentativa: LocalDateTime? = null,
)

/** A decisão do filtro do [GatilhoWifi]. */
sealed interface Decisao {
    /** Abrir o ponto; [redeConfirmada]: o nome da rede foi lido e é o configurado. */
    data class Executar(val redeConfirmada: Boolean) : Decisao

    /** Não fazer nada; [motivo] vai para o log se [anotar] (os casos comuns e repetitivos não vão). */
    data class Ignorar(val motivo: String, val anotar: Boolean) : Decisao
}

/**
 * Abrir o ponto ao conectar na Wi-Fi do campus: o filtro rápido (sem rede) que o `RedeReceiver`
 * aplica a cada Wi-Fi que fica disponível, e o estado que ele guarda.
 */
object GatilhoWifi {
    const val REDE_PADRAO = "ws_wlan"

    /** Depois de fechar o ponto, a abertura automática espera isto (sair do campus não reabre). */
    val ESPERA_APOS_FECHAR: Duration = Duration.ofMinutes(30)

    /** Entre duas tentativas automáticas (a conexão cai e volta ao trocar de ponto de acesso). */
    val TRAVA: Duration = Duration.ofMinutes(5)

    /**
     * O nome da rede como o Android o devolve (entre aspas) sem as aspas, ou null se ilegível
     * (sem permissão de localização ou com ela desligada, o Android devolve "<unknown ssid>").
     */
    fun limparNome(ssid: String?): String? {
        val nome = ssid?.trim()?.removeSurrounding("\"")?.trim()
        if (nome.isNullOrEmpty() || nome == "<unknown ssid>" || nome == "0x") return null
        return nome
    }

    /** Se o ponto está aberto, pelo que o app sabe: abriu hoje, depois do último fechamento. */
    fun pontoAberto(agora: LocalDateTime, estado: EstadoDoPonto): Boolean {
        val abertura = estado.ultimaAbertura ?: return false
        if (abertura.toLocalDate() != agora.toLocalDate()) return false
        val fechamento = estado.ultimoFechamento ?: return true
        return abertura.isAfter(fechamento)
    }

    private fun dentroDe(desde: LocalDateTime?, agora: LocalDateTime, prazo: Duration): Boolean =
        desde != null && !desde.isAfter(agora) && Duration.between(desde, agora) < prazo

    /**
     * Se a Wi-Fi [nomeDaRede] (null = ilegível) que acabou de conectar às [agora] deve abrir o ponto.
     * [ligado] e [redeConfigurada] vêm das Configurações.
     */
    fun deveExecutar(
        agora: LocalDateTime,
        nomeDaRede: String?,
        estado: EstadoDoPonto,
        ligado: Boolean = true,
        redeConfigurada: String = REDE_PADRAO,
    ): Decisao {
        if (!ligado) return Decisao.Ignorar("opção desligada", anotar = false)
        if (!Notificacoes.diaUtil(agora.toLocalDate())) return Decisao.Ignorar("fim de semana", anotar = false)
        val confirmada = nomeDaRede != null
        if (confirmada && !nomeDaRede.equals(redeConfigurada.trim(), ignoreCase = true)) {
            return Decisao.Ignorar("rede diferente ($nomeDaRede)", anotar = true)
        }
        if (pontoAberto(agora, estado)) return Decisao.Ignorar("o ponto já está aberto", anotar = true)
        if (dentroDe(estado.ultimoFechamento, agora, ESPERA_APOS_FECHAR)) {
            return Decisao.Ignorar("o ponto foi fechado há menos de 30 min", anotar = true)
        }
        if (dentroDe(estado.ultimaTentativa, agora, TRAVA)) {
            return Decisao.Ignorar("houve uma tentativa há menos de 5 min", anotar = false)
        }
        return Decisao.Executar(confirmada)
    }

    // ---------------- arquivos ----------------

    private fun lerHora(arquivo: File): LocalDateTime? {
        if (!arquivo.exists()) return null
        return try {
            LocalDateTime.parse(arquivo.readText().trim())
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun escreverHora(quando: LocalDateTime) = quando.truncatedTo(ChronoUnit.SECONDS).toString()

    /** `ultima_abertura.txt`: data e hora da última abertura com sucesso (ex.: "2026-10-13T07:46:10"). */
    fun registrarAbertura(arquivo: File, quando: LocalDateTime = LocalDateTime.now()) {
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(escreverHora(quando))
    }

    fun ultimaAbertura(arquivo: File): LocalDateTime? = lerHora(arquivo)

    /**
     * `abertura_automatica.txt`: a hora da última tentativa automática e o dia da última notificação
     * de erro dela, uma por linha ("tentativa=..." e "erro_notificado=aaaa-mm-dd").
     */
    private fun campos(arquivo: File): Map<String, String> =
        if (!arquivo.exists()) emptyMap()
        else arquivo.readLines().mapNotNull { linha ->
            linha.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        }.toMap()

    private fun gravarCampos(arquivo: File, campos: Map<String, String>) {
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(campos.entries.joinToString("") { "${it.key}=${it.value}\n" })
    }

    fun ultimaTentativa(arquivo: File): LocalDateTime? =
        campos(arquivo)["tentativa"]?.let {
            try { LocalDateTime.parse(it) } catch (e: DateTimeParseException) { null }
        }

    fun registrarTentativa(arquivo: File, quando: LocalDateTime = LocalDateTime.now()) =
        gravarCampos(arquivo, campos(arquivo) + ("tentativa" to escreverHora(quando)))

    fun erroNotificadoNoDia(arquivo: File, dia: LocalDate = LocalDate.now()) =
        campos(arquivo)["erro_notificado"] == dia.toString()

    fun registrarErroNotificado(arquivo: File, dia: LocalDate = LocalDate.now()) =
        gravarCampos(arquivo, campos(arquivo) + ("erro_notificado" to dia.toString()))
}
