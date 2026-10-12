package io.github.fabiolimath.ponto

import android.content.Context
import io.github.fabiolimath.ponto.nucleo.Credenciais
import io.github.fabiolimath.ponto.nucleo.Preferencias
import io.github.fabiolimath.ponto.nucleo.Registro
import java.io.File

/** Os arquivos do app em `filesDir/data/`, nos mesmos formatos da versão Python. */
object Dados {
    fun pasta(contexto: Context): File = File(contexto.filesDir, "data").also { it.mkdirs() }

    fun arquivo(contexto: Context, nome: String) = File(pasta(contexto), nome)

    fun registro(contexto: Context) = Registro(arquivo(contexto, "ponto.log"))

    /** Usuário e senha salvos, ou null se ainda não houver. */
    fun credenciais(contexto: Context) = Credenciais.carregar(arquivo(contexto, "credenciais.json"))

    fun salvarCredenciais(contexto: Context, credenciais: Credenciais) =
        Credenciais.salvar(arquivo(contexto, "credenciais.json"), credenciais)

    fun preferencias(contexto: Context) = Preferencias.carregar(arquivo(contexto, "configuracoes.json"))

    fun salvarPreferencias(contexto: Context, preferencias: Preferencias) =
        preferencias.salvar(arquivo(contexto, "configuracoes.json"))
}
