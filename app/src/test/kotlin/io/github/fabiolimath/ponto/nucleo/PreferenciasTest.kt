package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferenciasTest {
    private fun arquivo(): File = File(Files.createTempDirectory("ponto").toFile(), "c.json")

    @Test
    fun padrao_e_salvas() {
        val arquivo = arquivo()
        val padrao = Preferencias()
        assertEquals(
            Preferencias(
                notificacoes = true, lembreteFechar = true, lembreteTempo = "01:00",
                verificarAtualizacoes = true, observacaoPit = "Conforme PIT docente.", pitAutomatico = 0,
            ),
            Preferencias.carregar(arquivo),
        )
        padrao.copy(verificarAtualizacoes = false, pitAutomatico = 2).salvar(arquivo)
        assertEquals(padrao.copy(verificarAtualizacoes = false, pitAutomatico = 2), Preferencias.carregar(arquivo))
    }

    @Test
    fun le_o_arquivo_gravado_pela_versao_python() {
        val arquivo = arquivo()
        arquivo.writeText(
            """{"notificacoes": false, "lembrete_fechar": true, "lembrete_tempo": "01:40",
               "verificar_atualizacoes": false, "observacao_pit": "Outra obs.", "pit_automatico": 1}"""
        )
        assertEquals(
            Preferencias(false, true, "01:40", false, "Outra obs.", 1),
            Preferencias.carregar(arquivo),
        )
    }

    @Test
    fun abertura_pela_wifi() {
        val arquivo = arquivo()
        val padrao = Preferencias.carregar(arquivo)
        assertEquals(false, padrao.wifiAbrir)
        assertEquals("ws_wlan", padrao.wifiRede)
        assertEquals("01:00", padrao.wifiEspera)
        assertEquals(Duration.ofHours(1), padrao.duracaoDaEsperaWifi)
        assertEquals(true, padrao.avisoBateria)
        val novas = padrao.copy(wifiAbrir = true, wifiRede = "Campus", wifiEspera = "00:30", avisoBateria = false)
        novas.salvar(arquivo)
        assertEquals(novas, Preferencias.carregar(arquivo))
        assertEquals(Duration.ofMinutes(30), novas.duracaoDaEsperaWifi)
        arquivo.writeText("""{"wifi_rede": "  ", "wifi_espera": "9:99"}""")
        assertEquals("ws_wlan", Preferencias.carregar(arquivo).wifiRede)
        assertEquals("01:00", Preferencias.carregar(arquivo).wifiEspera)
    }

    @Test
    fun fechamento_automatico() {
        val arquivo = arquivo()
        val padrao = Preferencias.carregar(arquivo)
        assertEquals(Preferencias.FECHAR_NAO, padrao.fecharModo)
        assertEquals(false, padrao.fecharAutomatico)
        assertEquals("12:00", padrao.fecharHorario1)
        assertEquals("18:00", padrao.fecharHorario2)
        assertEquals(List(5) { listOf("12:00", "18:00") }, padrao.fecharDias)
        val novas = padrao.copy(
            fecharModo = Preferencias.FECHAR_AVANCADO, fecharHorario1 = "", fecharHorario2 = "18:30",
            fecharDias = listOf(listOf("12:00", ""), listOf("", ""), listOf("8:00", "17:00"), listOf("12:00", "18:00"), listOf("", "16:00")),
        )
        novas.salvar(arquivo)
        assertEquals(novas, Preferencias.carregar(arquivo))
        arquivo.writeText(
            """{"fechar_modo": 1, "fechar_horario1": "25:00", "fechar_horario2": " 8:00 ",
               "fechar_dias": [["12:00", "99:99"], "lixo", ["7:30"]]}"""
        )
        val lidas = Preferencias.carregar(arquivo)
        assertEquals(Preferencias.FECHAR_FIXOS, lidas.fecharModo)
        assertEquals("", lidas.fecharHorario1)
        assertEquals("8:00", lidas.fecharHorario2)
        assertEquals(
            listOf(listOf("12:00", ""), listOf("12:00", "18:00"), listOf("7:30", ""), listOf("12:00", "18:00"), listOf("12:00", "18:00")),
            lidas.fecharDias,
        )
    }

    @Test
    fun fechamento_automatico_do_teste_da_2_2_0_e_modo_invalido() {
        val arquivo = arquivo()
        arquivo.writeText("""{"fechar_automatico": true}""")
        assertEquals(Preferencias.FECHAR_FIXOS, Preferencias.carregar(arquivo).fecharModo)
        arquivo.writeText("""{"fechar_automatico": false}""")
        assertEquals(Preferencias.FECHAR_NAO, Preferencias.carregar(arquivo).fecharModo)
        arquivo.writeText("""{"fechar_modo": 5}""")
        assertEquals(Preferencias.FECHAR_NAO, Preferencias.carregar(arquivo).fecharModo)
    }

    @Test
    fun tempo_da_versao_python_acima_de_6h_vira_6h() {
        val arquivo = arquivo()
        arquivo.writeText("""{"lembrete_tempo": "08:30"}""")
        assertEquals("06:00", Preferencias.carregar(arquivo).lembreteTempo)
        arquivo.writeText("""{"lembrete_tempo": "06:00"}""")
        assertEquals("06:00", Preferencias.carregar(arquivo).lembreteTempo)
        arquivo.writeText("""{"lembrete_tempo": "1h40"}""")
        assertEquals("1h40", Preferencias.carregar(arquivo).lembreteTempo)
    }

    @Test
    fun chaves_que_faltam_ficam_no_padrao() {
        val arquivo = arquivo()
        arquivo.writeText("""{"lembrete_tempo": "02:00"}""")
        assertEquals(Preferencias(lembreteTempo = "02:00"), Preferencias.carregar(arquivo))
    }

    @Test
    fun arquivo_ilegivel_ou_opcao_invalida_volta_ao_padrao() {
        val arquivo = arquivo()
        arquivo.writeText("lixo")
        assertEquals(Preferencias(), Preferencias.carregar(arquivo))
        arquivo.writeText("""{"pit_automatico": 7}""")
        assertEquals(0, Preferencias.carregar(arquivo).pitAutomatico)
    }

    @Test
    fun tempo_do_lembrete() {
        assertEquals(Duration.ofMinutes(100), Preferencias.tempoLembrete("01:40"))
        assertEquals(Duration.ofHours(6), Preferencias.tempoLembrete(" 6:00 "))
        assertEquals(Duration.ofMinutes(359), Preferencias.tempoLembrete("05:59"))
        for (invalido in listOf("", "0:00", "1:60", "06:01", "8:00", "24:00", "1h40", "01:40:00", "-1:30", "1:-5", ":")) {
            assertNull(Preferencias.tempoLembrete(invalido), invalido)
        }
        assertEquals(Duration.ofHours(1), Preferencias().duracaoDoLembrete)
    }
}
