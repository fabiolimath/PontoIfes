package io.github.fabiolimath.ponto.nucleo

import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Usuário e senha do SIGRH (`credenciais.json`, só o dono do arquivo lê). */
data class Credenciais(val usuario: String, val senha: String) {
    val completas get() = usuario.isNotBlank() && senha.isNotBlank()

    /** Rótulos dos campos obrigatórios em branco. */
    fun faltando(): List<String> = buildList {
        if (usuario.isBlank()) add("Usuário do SIGRH")
        if (senha.isBlank()) add("Senha do SIGRH")
    }

    companion object {
        private const val USUARIO = "SIGRH_USER"
        private const val SENHA = "SIGRH_PASS"

        /** O que está salvo, ou null se ainda não houver credenciais (ou o arquivo estiver ilegível). */
        fun carregar(arquivo: File): Credenciais? {
            if (!arquivo.exists()) return null
            return try {
                val json = JSONObject(arquivo.readText())
                // Campos extras (como os do antigo Telegram) são ignorados.
                Credenciais(json.optString(USUARIO), json.optString(SENHA))
            } catch (e: JSONException) {
                null
            }
        }

        fun salvar(arquivo: File, credenciais: Credenciais) {
            val json = JSONObject()
                .put(USUARIO, credenciais.usuario.trim())
                .put(SENHA, credenciais.senha.trim())
            arquivo.parentFile?.mkdirs()
            // Permissão 600: só o dono lê e escreve. Ajustada antes de gravar a senha.
            arquivo.delete()
            arquivo.createNewFile()
            arquivo.setReadable(false, false)
            arquivo.setWritable(false, false)
            arquivo.setReadable(true, true)
            arquivo.setWritable(true, true)
            arquivo.writeText(json.toString())
        }
    }
}
