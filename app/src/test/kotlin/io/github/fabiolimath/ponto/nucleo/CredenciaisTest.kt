package io.github.fabiolimath.ponto.nucleo

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredenciaisTest {
    private fun pasta(): File = Files.createTempDirectory("ponto").toFile()

    @Test
    fun sem_arquivo_devolve_null() {
        assertNull(Credenciais.carregar(File(pasta(), "c.json")))
    }

    @Test
    fun salva_e_carrega_com_permissao_600() {
        val arquivo = File(pasta(), "sub/c.json")
        Credenciais.salvar(arquivo, Credenciais(" fulano ", " segredo "))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(arquivo.toPath()),
        )
        assertEquals(Credenciais("fulano", "segredo"), Credenciais.carregar(arquivo))
    }

    @Test
    fun regrava_com_permissao_600_mesmo_se_o_arquivo_estava_aberto_demais() {
        val arquivo = File(pasta(), "c.json")
        arquivo.writeText("{}")
        Files.setPosixFilePermissions(arquivo.toPath(), PosixFilePermission.values().toSet())
        Credenciais.salvar(arquivo, Credenciais("a", "b"))
        assertEquals(2, Files.getPosixFilePermissions(arquivo.toPath()).size)
    }

    @Test
    fun campos_antigos_do_telegram_sao_ignorados() {
        val arquivo = File(pasta(), "c.json")
        arquivo.writeText("""{"SIGRH_USER": "a", "SIGRH_PASS": "b", "TELEGRAM_CHAT_ID": "42", "TELEGRAM_TOKEN": "x"}""")
        assertEquals(Credenciais("a", "b"), Credenciais.carregar(arquivo))
    }

    @Test
    fun usuario_com_zeros_a_esquerda_continua_texto() {
        val arquivo = File(pasta(), "c.json")
        Credenciais.salvar(arquivo, Credenciais("0012345", "x"))
        assertEquals("0012345", Credenciais.carregar(arquivo)?.usuario)
    }

    @Test
    fun arquivo_ilegivel_conta_como_sem_credenciais() {
        val arquivo = File(pasta(), "c.json")
        arquivo.writeText("isto não é json")
        assertNull(Credenciais.carregar(arquivo))
    }

    @Test
    fun faltando() {
        assertEquals(listOf("Usuário do SIGRH", "Senha do SIGRH"), Credenciais("", " ").faltando())
        assertTrue(Credenciais("a", "b").faltando().isEmpty())
        assertTrue(Credenciais("a", "b").completas)
    }
}
