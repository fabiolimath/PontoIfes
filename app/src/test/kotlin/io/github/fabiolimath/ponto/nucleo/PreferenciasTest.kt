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
        assertEquals(Duration.ofHours(8), Preferencias.tempoLembrete(" 8:00 "))
        for (invalido in listOf("", "0:00", "1:60", "24:00", "1h40", "01:40:00", "-1:30", "1:-5", ":")) {
            assertNull(Preferencias.tempoLembrete(invalido), invalido)
        }
        assertEquals(Duration.ofHours(1), Preferencias().duracaoDoLembrete)
    }
}
