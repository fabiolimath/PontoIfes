package io.github.fabiolimath.ponto

import android.content.Context
import io.github.fabiolimath.ponto.nucleo.Registro
import org.json.JSONObject
import java.io.File

/** Os arquivos do app em `filesDir/data/`, nos mesmos formatos da versão Python. */
object Dados {
    fun pasta(contexto: Context): File = File(contexto.filesDir, "data").also { it.mkdirs() }

    fun arquivo(contexto: Context, nome: String) = File(pasta(contexto), nome)

    fun registro(contexto: Context) = Registro(arquivo(contexto, "ponto.log"))
}

/** Usuário e senha do SIGRH (`credenciais.json`, só o dono do arquivo lê). */
data class Credenciais(val usuario: String, val senha: String) {
    val completas get() = usuario.isNotEmpty() && senha.isNotEmpty()

    companion object {
        private const val USUARIO = "SIGRH_USER"
        private const val SENHA = "SIGRH_PASS"

        /** O que está salvo, ou null se ainda não houver credenciais. */
        fun carregar(contexto: Context): Credenciais? {
            val arquivo = Dados.arquivo(contexto, "credenciais.json")
            if (!arquivo.exists()) return null
            return try {
                val json = JSONObject(arquivo.readText())
                Credenciais(json.optString(USUARIO), json.optString(SENHA))
            } catch (e: org.json.JSONException) {
                null
            }
        }

        fun salvar(contexto: Context, credenciais: Credenciais) {
            val arquivo = Dados.arquivo(contexto, "credenciais.json")
            val json = JSONObject()
                .put(USUARIO, credenciais.usuario.trim())
                .put(SENHA, credenciais.senha.trim())
            // Permissão 600: só o dono lê e escreve.
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

/** As preferências de `configuracoes.json`; por ora só a Observação do PIT (o resto, na etapa 4). */
object Configuracoes {
    const val OBSERVACAO_PADRAO = "Conforme PIT docente."

    fun observacaoPit(contexto: Context): String {
        val arquivo = Dados.arquivo(contexto, "configuracoes.json")
        if (!arquivo.exists()) return OBSERVACAO_PADRAO
        return try {
            JSONObject(arquivo.readText()).optString("observacao_pit", OBSERVACAO_PADRAO)
        } catch (e: org.json.JSONException) {
            OBSERVACAO_PADRAO
        }
    }
}
