package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.time.LocalDateTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LembreteTest {
    private val arquivo = File(createTempDirectory().toFile(), "lembrete.txt")
    private val agora = LocalDateTime.of(2026, 10, 6, 7, 55)

    @Test
    fun lembrete_pendente() {
        assertNull(Lembrete.pendente(arquivo, agora))
        Lembrete.registrar(arquivo, LocalDateTime.of(2026, 10, 6, 9, 0))
        assertEquals("2026-10-06T09:00:00", arquivo.readText())
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 0), Lembrete.pendente(arquivo, agora))
        // Já passou: não está pendente.
        assertNull(Lembrete.pendente(arquivo, LocalDateTime.of(2026, 10, 6, 9, 1)))
        Lembrete.apagar(arquivo)
        assertFalse(arquivo.exists())
        arquivo.writeText("lixo")
        assertNull(Lembrete.pendente(arquivo, agora))
    }

    @Test
    fun le_o_arquivo_da_versao_python() {
        arquivo.writeText("2026-10-06T09:30:15")
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 30, 15), Lembrete.pendente(arquivo, agora))
    }

    @Test
    fun lembrete_conta_do_horario_de_abertura() {
        val tempo = Preferencias(lembreteTempo = "02:00")
        assertEquals(LocalDateTime.of(2026, 10, 6, 9, 55), Lembrete.quando(tempo, arquivo, agora))
        assertEquals("O ponto foi aberto às 07:55 e ainda não foi fechado.", Lembrete.texto(agora))
        assertEquals("O ponto foi aberto e ainda não foi fechado.", Lembrete.texto(null))
    }

    @Test
    fun abrir_de_novo_nao_adia_o_lembrete_pendente() {
        val preferencias = Preferencias()
        val primeiro = Lembrete.quando(preferencias, arquivo, agora)!!
        Lembrete.registrar(arquivo, primeiro)
        assertNull(Lembrete.quando(preferencias, arquivo, agora.plusMinutes(10)))
        // Passado o horário, um novo lembrete pode ser agendado.
        assertEquals(
            LocalDateTime.of(2026, 10, 6, 10, 0),
            Lembrete.quando(preferencias, arquivo, LocalDateTime.of(2026, 10, 6, 9, 0)),
        )
    }

    @Test
    fun sem_lembrete_se_desligado_ou_tempo_invalido() {
        assertNull(Lembrete.quando(Preferencias(lembreteFechar = false), arquivo, agora))
        assertNull(Lembrete.quando(Preferencias(lembreteTempo = "xx"), arquivo, agora))
    }
}
