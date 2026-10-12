package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatilhoWifiTest {
    // Terça-feira, 13/10/2026, 07:40.
    private val agora = LocalDateTime.of(2026, 10, 13, 7, 40)
    private val vazio = EstadoDoPonto()

    private fun decidir(
        nome: String? = "ws_wlan",
        estado: EstadoDoPonto = vazio,
        quando: LocalDateTime = agora,
        ligado: Boolean = true,
        rede: String = "ws_wlan",
    ) = GatilhoWifi.deveExecutar(quando, nome, estado, ligado, rede)

    @Test
    fun nome_da_rede_sem_aspas_e_ilegivel_vira_null() {
        assertEquals("ws_wlan", GatilhoWifi.limparNome("\"ws_wlan\""))
        assertEquals("Casa 5G", GatilhoWifi.limparNome("\"Casa 5G\""))
        assertNull(GatilhoWifi.limparNome("<unknown ssid>"))
        assertNull(GatilhoWifi.limparNome("\"\""))
        assertNull(GatilhoWifi.limparNome(null))
    }

    @Test
    fun desligado_nao_executa() {
        val d = decidir(ligado = false)
        assertIs<Decisao.Ignorar>(d)
        assertFalse(d.anotar)
    }

    @Test
    fun so_de_segunda_a_sexta() {
        assertIs<Decisao.Executar>(decidir(quando = LocalDateTime.of(2026, 10, 12, 7, 40))) // segunda
        assertIs<Decisao.Executar>(decidir(quando = LocalDateTime.of(2026, 10, 16, 7, 40))) // sexta
        val sabado = decidir(quando = LocalDateTime.of(2026, 10, 17, 7, 40))
        assertIs<Decisao.Ignorar>(sabado)
        assertFalse(sabado.anotar)
        assertIs<Decisao.Ignorar>(decidir(quando = LocalDateTime.of(2026, 10, 18, 7, 40))) // domingo
    }

    @Test
    fun nome_legivel_igual_confirma_a_rede_sem_diferenciar_maiusculas() {
        assertEquals(Decisao.Executar(redeConfirmada = true), decidir(nome = "WS_WLAN"))
        assertEquals(Decisao.Executar(redeConfirmada = true), decidir(nome = "Campus", rede = " campus "))
    }

    @Test
    fun nome_diferente_e_ignorado_e_anotado() {
        val d = decidir(nome = "Casa")
        assertIs<Decisao.Ignorar>(d)
        assertTrue(d.anotar)
        assertEquals("rede diferente (Casa)", d.motivo)
    }

    @Test
    fun nome_ilegivel_tenta_sem_confirmar() {
        assertEquals(Decisao.Executar(redeConfirmada = false), decidir(nome = null))
    }

    @Test
    fun ponto_ja_aberto_hoje_nao_executa() {
        val aberto = EstadoDoPonto(ultimaAbertura = agora.minusHours(1))
        assertIs<Decisao.Ignorar>(decidir(estado = aberto))
        // Aberto e depois fechado (há mais de 30 min): abre de novo (volta do almoço).
        val fechado = aberto.copy(ultimoFechamento = agora.minusMinutes(31))
        assertEquals(Decisao.Executar(true), decidir(estado = fechado))
        // Fechado e aberto de novo.
        val reaberto = EstadoDoPonto(ultimaAbertura = agora.minusMinutes(10), ultimoFechamento = agora.minusHours(1))
        assertIs<Decisao.Ignorar>(decidir(estado = reaberto))
    }

    @Test
    fun virada_do_dia_esquece_a_abertura_de_ontem() {
        val ontem = EstadoDoPonto(ultimaAbertura = LocalDateTime.of(2026, 10, 12, 23, 50))
        assertIs<Decisao.Ignorar>(decidir(estado = ontem, quando = LocalDateTime.of(2026, 10, 12, 23, 55)))
        assertEquals(Decisao.Executar(true), decidir(estado = ontem, quando = LocalDateTime.of(2026, 10, 13, 0, 5)))
    }

    @Test
    fun espera_de_30_min_depois_de_fechar() {
        val fechou = EstadoDoPonto(ultimoFechamento = agora.minusMinutes(29))
        val d = decidir(estado = fechou)
        assertIs<Decisao.Ignorar>(d)
        assertTrue(d.anotar)
        assertEquals(Decisao.Executar(true), decidir(estado = EstadoDoPonto(ultimoFechamento = agora.minusMinutes(30))))
        // Fechamento de ontem à noite, perto da meia-noite: a espera vale na virada.
        val noite = EstadoDoPonto(ultimoFechamento = LocalDateTime.of(2026, 10, 12, 23, 50))
        assertIs<Decisao.Ignorar>(decidir(estado = noite, quando = LocalDateTime.of(2026, 10, 13, 0, 10)))
    }

    @Test
    fun trava_de_5_min_entre_tentativas() {
        val d = decidir(estado = EstadoDoPonto(ultimaTentativa = agora.minusMinutes(4)))
        assertIs<Decisao.Ignorar>(d)
        assertFalse(d.anotar)
        assertEquals(Decisao.Executar(true), decidir(estado = EstadoDoPonto(ultimaTentativa = agora.minusMinutes(5))))
        // Uma hora no futuro (relógio mudou) não trava.
        assertEquals(Decisao.Executar(true), decidir(estado = EstadoDoPonto(ultimaTentativa = agora.plusHours(1))))
    }

    @Test
    fun arquivos_de_estado() {
        val pasta = createTempDirectory().toFile()
        val abertura = File(pasta, "ultima_abertura.txt")
        assertNull(GatilhoWifi.ultimaAbertura(abertura))
        GatilhoWifi.registrarAbertura(abertura, LocalDateTime.of(2026, 10, 13, 7, 46, 10, 999))
        assertEquals("2026-10-13T07:46:10", abertura.readText())
        assertEquals(LocalDateTime.of(2026, 10, 13, 7, 46, 10), GatilhoWifi.ultimaAbertura(abertura))

        val auto = File(pasta, "abertura_automatica.txt")
        assertNull(GatilhoWifi.ultimaTentativa(auto))
        assertFalse(GatilhoWifi.erroNotificadoNoDia(auto, LocalDate.of(2026, 10, 13)))
        GatilhoWifi.registrarTentativa(auto, agora)
        GatilhoWifi.registrarErroNotificado(auto, LocalDate.of(2026, 10, 13))
        assertEquals(agora, GatilhoWifi.ultimaTentativa(auto))
        assertTrue(GatilhoWifi.erroNotificadoNoDia(auto, LocalDate.of(2026, 10, 13)))
        assertFalse(GatilhoWifi.erroNotificadoNoDia(auto, LocalDate.of(2026, 10, 14)))
        GatilhoWifi.registrarTentativa(auto, agora.plusHours(1))
        assertEquals(agora.plusHours(1), GatilhoWifi.ultimaTentativa(auto))
        assertTrue(GatilhoWifi.erroNotificadoNoDia(auto, LocalDate.of(2026, 10, 13)))
        auto.writeText("lixo")
        assertNull(GatilhoWifi.ultimaTentativa(auto))
    }
}
