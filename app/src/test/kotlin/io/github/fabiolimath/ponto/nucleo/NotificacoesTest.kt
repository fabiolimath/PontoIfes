package io.github.fabiolimath.ponto.nucleo

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificacoesTest {
    private val terca = LocalDate.of(2026, 10, 6)
    private val sabado = LocalDate.of(2026, 10, 10)

    @Test
    fun idents_de_sucesso_e_de_falha() {
        assertEquals(listOf(1, 2, 3), Acao.entries.map { Notificacoes.ident(it) })
        // Falhas: um ident por falha, para uma não substituir a outra.
        assertEquals(1000 + 5, Notificacoes.identDeFalha(5_000))
        assertTrue(Notificacoes.identDeFalha(5_000) != Notificacoes.identDeFalha(65_000))
        assertEquals(4, Notificacoes.IDENT_LEMBRETE)
    }

    @Test
    fun notifica_o_resultado_com_o_botao_do_pit_ao_fechar() {
        val aviso = Notificacoes.aviso(
            Acao.FECHAR_PONTO, "Fechar ponto: concluído.",
            "Tentativa 1...\n✅🔐📌 SIGRH: saída registrada às 17:00\n", 0, null, Preferencias(), terca,
        )
        assertEquals(
            Aviso(
                Acao.FECHAR_PONTO, "Fechar ponto: concluído.", "✅🔐📌 SIGRH: saída registrada às 17:00", 2,
                BotaoDeAviso("Registrar PIT", Acao.REGISTRAR_PIT),
            ),
            aviso,
        )
    }

    @Test
    fun sem_notificacao_se_desligada() {
        assertNull(Notificacoes.aviso(Acao.ABRIR_PONTO, "t", "ok", 0, null, Preferencias(notificacoes = false), terca))
    }

    @Test
    fun sem_botao_do_pit_no_fim_de_semana_nem_em_outras_acoes() {
        assertNull(Notificacoes.aviso(Acao.FECHAR_PONTO, "t", "ok", 0, null, Preferencias(), sabado)!!.botao)
        assertNull(Notificacoes.aviso(Acao.ABRIR_PONTO, "t", "ok", 0, null, Preferencias(), terca)!!.botao)
        assertNull(Notificacoes.aviso(Acao.REGISTRAR_PIT, "t", "ok", 0, null, Preferencias(), terca)!!.botao)
    }

    @Test
    fun pit_automatico_tira_o_botao_da_notificacao() {
        val preferencias = Preferencias(pitAutomatico = true)
        assertNull(Notificacoes.aviso(Acao.FECHAR_PONTO, "t", "ok", 0, null, preferencias, terca)!!.botao)
    }

    @Test
    fun falha_notifica_com_tentar_de_novo() {
        val dia = LocalDate.of(2026, 10, 2)
        val aviso = Notificacoes.aviso(Acao.REGISTRAR_PIT, "t", "falhou", 1, dia, Preferencias(), terca)!!
        assertEquals(Notificacoes.identDeFalha(70_000), Notificacoes.aviso(
            Acao.REGISTRAR_PIT, "t", "falhou", 1, dia, Preferencias(), terca, 70_000)!!.ident)
        assertTrue(aviso.ident >= 1000)
        assertTrue(aviso.falhou)
        assertEquals(BotaoDeAviso("Tentar de novo", Acao.REGISTRAR_PIT, "02/10/2026"), aviso.botao)
        // Sem data (PIT do dia) e em outras ações, o botão não leva data.
        assertNull(Notificacoes.aviso(Acao.REGISTRAR_PIT, "t", "x", 1, null, Preferencias(), terca)!!.botao!!.data)
        assertEquals(
            BotaoDeAviso("Tentar de novo", Acao.ABRIR_PONTO),
            Notificacoes.aviso(Acao.ABRIR_PONTO, "t", "x", 1, null, Preferencias(), terca)!!.botao,
        )
    }

    @Test
    fun sem_saida_o_texto_e_o_titulo() {
        assertEquals("Abrir ponto: falhou (código 1).",
            Notificacoes.aviso(Acao.ABRIR_PONTO, "Abrir ponto: falhou (código 1).", "\n  \n", 1, null, Preferencias(), terca)!!.texto)
    }

    @Test
    fun apaga_so_as_de_sucesso() {
        assertEquals(listOf(1), Notificacoes.apagarAntes(Acao.ABRIR_PONTO))
        // O PIT apaga também a do fechamento (a que tem o botão Registrar PIT).
        assertEquals(listOf(3, 2), Notificacoes.apagarAntes(Acao.REGISTRAR_PIT))
    }
}
