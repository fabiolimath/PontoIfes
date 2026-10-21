package io.github.fabiolimath.ponto

import android.Manifest
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.fabiolimath.ponto.nucleo.AberturaAutomatica
import io.github.fabiolimath.ponto.nucleo.Decisao
import io.github.fabiolimath.ponto.nucleo.EstadoDoPonto
import io.github.fabiolimath.ponto.nucleo.Fechamentos
import io.github.fabiolimath.ponto.nucleo.GatilhoWifi
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Abrir o ponto ao conectar na Wi-Fi do campus, com o app fechado: a verificação periódica da
 * Wi-Fi (WorkManager), a leitura do nome da rede e a bateria.
 */
object Wifi {
    private const val TAG = "Ponto"
    private const val TRABALHO = "abrir_automatico"
    private const val VERIFICACAO = "verificar_wifi"
    private const val VERIFICACAO_AGORA = "verificar_wifi_agora"
    const val EXTRA_REDE_CONFIRMADA = "rede_confirmada"

    /** Mutável: o sistema põe no intent a rede que conectou. */
    private fun pedido(contexto: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(contexto, 0, Intent(contexto, RedeReceiver::class.java), flags or PendingIntent.FLAG_MUTABLE)

    private fun conectividade(contexto: Context) =
        contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Liga a verificação da Wi-Fi (com a opção desligada, a desliga): um trabalho periódico do
     * WorkManager, a cada 15 min com Wi-Fi, que vale com o app fechado e depois de reiniciar. Até a
     * 2.6.1 era um pedido de rede ao sistema (registerNetworkCallback), que sumia sempre que o
     * sistema fechava o app (visto no log do APK de teste da 2.6.2).
     *
     * [verificarAgora]: também verifica já (ao abrir o app, ao ligar a opção ou trocar a rede, ao
     * reiniciar o celular e ao atualizar o app).
     */
    fun registrar(contexto: Context, verificarAgora: Boolean) {
        val app = contexto.applicationContext
        desfazerPedidoAntigo(app)
        val trabalhos = WorkManager.getInstance(app)
        if (!Dados.preferencias(app).wifiAbrir) {
            trabalhos.cancelUniqueWork(VERIFICACAO)
            return
        }
        val periodico = PeriodicWorkRequestBuilder<VerificarWifiWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        trabalhos.enqueueUniquePeriodicWork(VERIFICACAO, ExistingPeriodicWorkPolicy.KEEP, periodico)
        if (verificarAgora) {
            trabalhos.enqueueUniqueWork(
                VERIFICACAO_AGORA, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<VerificarWifiWorker>().build(),
            )
        }
    }

    fun desfazer(contexto: Context) {
        val app = contexto.applicationContext
        desfazerPedidoAntigo(app)
        WorkManager.getInstance(app).cancelUniqueWork(VERIFICACAO)
    }

    /** O pedido de rede da 2.6.2 ou anterior, se ainda está no sistema. */
    private fun desfazerPedidoAntigo(contexto: Context) {
        val intencao = pedido(contexto, PendingIntent.FLAG_NO_CREATE) ?: return
        try {
            conectividade(contexto).unregisterNetworkCallback(intencao)
        } catch (e: IllegalArgumentException) {
            // Já não havia pedido.
        }
        intencao.cancel()
    }

    /** Se a rede em uso agora é uma Wi-Fi. */
    fun conectadoNaWifi(contexto: Context): Boolean {
        val conectividade = conectividade(contexto)
        val rede = conectividade.activeNetwork ?: return false
        return conectividade.getNetworkCapabilities(rede)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /**
     * Decide, sem rede, se vale abrir o ponto pela Wi-Fi conectada e põe o trabalho na fila.
     * [anotarIgnorados]: os ignorados relevantes vão ao log (não na verificação periódica, que os
     * repetiria a cada 15 min).
     */
    fun verificar(contexto: Context, anotarIgnorados: Boolean) {
        val app = contexto.applicationContext
        val preferencias = Dados.preferencias(app)
        val agora = LocalDateTime.now()
        val decisao = GatilhoWifi.deveExecutar(
            agora, nomeDaRede(app), estado(app), preferencias.wifiAbrir, preferencias.wifiRede,
            preferencias.duracaoDaEsperaWifi,
        )
        when (decisao) {
            is Decisao.Ignorar -> if (anotarIgnorados && decisao.anotar) anotar(app, "Ignorado: ${decisao.motivo}.", agora)
            is Decisao.Executar -> {
                GatilhoWifi.registrarTentativa(Dados.arquivo(app, "abertura_automatica.txt"), agora)
                agendar(app, decisao.redeConfirmada)
            }
        }
    }

    // ---------------- nome da rede ----------------

    /** Por que o nome da rede pode não ser lido. */
    enum class Leitura { RECONHECIDO, SEM_PERMISSAO, LOCALIZACAO_DESLIGADA }

    private fun concedida(contexto: Context, permissao: String) =
        ContextCompat.checkSelfPermission(contexto, permissao) == PackageManager.PERMISSION_GRANTED

    fun temLocalizacaoPrecisa(contexto: Context) = concedida(contexto, Manifest.permission.ACCESS_FINE_LOCATION)

    /** O app fechado só lê o nome da rede com a localização "o tempo todo". */
    fun temLocalizacaoEmSegundoPlano(contexto: Context) =
        concedida(contexto, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun localizacaoLigada(contexto: Context) =
        LocationManagerCompat.isLocationEnabled(contexto.getSystemService(Context.LOCATION_SERVICE) as LocationManager)

    fun leitura(contexto: Context): Leitura = when {
        !temLocalizacaoPrecisa(contexto) || !temLocalizacaoEmSegundoPlano(contexto) -> Leitura.SEM_PERMISSAO
        !localizacaoLigada(contexto) -> Leitura.LOCALIZACAO_DESLIGADA
        else -> Leitura.RECONHECIDO
    }

    /** O nome da Wi-Fi conectada, ou null se o Android não o mostra (sem localização). */
    fun nomeDaRede(contexto: Context): String? {
        val wifi = contexto.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        return GatilhoWifi.limparNome(wifi.connectionInfo?.ssid)
    }

    // ---------------- bateria ----------------

    private fun otimizada(contexto: Context): Boolean {
        val energia = contexto.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !energia.isIgnoringBatteryOptimizations(contexto.packageName)
    }

    private fun restrita(contexto: Context): Boolean =
        (contexto.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted

    /** Se a bateria do app está em Sem restrições (o que o app consegue saber). */
    fun bateriaLiberada(contexto: Context) = !otimizada(contexto) && !restrita(contexto)

    /**
     * Abre o pedido do sistema para tirar o app da otimização de bateria (um toque) ou, se o que falta
     * é a restrição de segundo plano, a tela do app nas configurações.
     */
    fun liberarBateria(contexto: Context) {
        val pacote = Uri.parse("package:${contexto.packageName}")
        @Suppress("BatteryLife")
        val intencoes = buildList {
            if (otimizada(contexto)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pacote))
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote))
        }
        for (intencao in intencoes) {
            try {
                contexto.startActivity(intencao)
                return
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "Sem a tela ${intencao.action}", e)
            }
        }
    }

    // ---------------- execução ----------------

    /** Põe na fila a abertura automática; com uma já na fila ou rodando, esta é descartada. */
    fun agendar(contexto: Context, redeConfirmada: Boolean) {
        val trabalho = OneTimeWorkRequestBuilder<AbrirAutomaticoWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(EXTRA_REDE_CONFIRMADA to redeConfirmada))
            .build()
        WorkManager.getInstance(contexto).enqueueUniqueWork(TRABALHO, ExistingWorkPolicy.KEEP, trabalho)
    }

    /** O que o app sabe do ponto: últimas abertura, fechamento e tentativa automática. */
    fun estado(contexto: Context) = EstadoDoPonto(
        ultimaAbertura = GatilhoWifi.ultimaAbertura(Dados.arquivo(contexto, "ultima_abertura.txt")),
        ultimoFechamento = Fechamentos.ultimo(Dados.arquivo(contexto, "ultimo_fechamento.txt")),
        ultimaTentativa = GatilhoWifi.ultimaTentativa(Dados.arquivo(contexto, "abertura_automatica.txt")),
    )

    fun anotar(contexto: Context, texto: String, quando: LocalDateTime = LocalDateTime.now()) =
        Dados.registro(contexto).anotar(AberturaAutomatica.NOME_NO_LOG, quando, texto)
}

/**
 * Chamado pelo sistema a cada Wi-Fi que fica disponível, pelo pedido de rede da 2.6.2 ou anterior
 * que ainda estiver no sistema: verifica essa conexão e troca o pedido pela verificação periódica.
 */
class RedeReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        try {
            if (Dados.preferencias(contexto).wifiAbrir) Wifi.verificar(contexto, anotarIgnorados = true)
            // O pedido antigo some; daqui em diante vale a verificação periódica.
            Wifi.registrar(contexto, verificarAgora = false)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro no gatilho da Wi-Fi", e)
        }
    }
}

/**
 * A verificação da Wi-Fi (trabalho do WorkManager, periódico ou na hora): com a opção ligada e
 * uma Wi-Fi em uso, decide como o gatilho antigo e põe a abertura na fila. Ignorados ficam fora do log.
 */
class VerificarWifiWorker(contexto: Context, parametros: WorkerParameters) : Worker(contexto, parametros) {
    override fun doWork(): Result {
        try {
            val app = applicationContext
            if (Dados.preferencias(app).wifiAbrir && Wifi.conectadoNaWifi(app)) Wifi.verificar(app, anotarIgnorados = false)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro na verificação da Wi-Fi", e)
        }
        return Result.success()
    }
}

/**
 * A abertura automática (trabalho expedito do WorkManager): roda o mesmo cliente do botão, com as
 * mesmas tentativas e o timeout menor. Se o sistema o parar, o WorkManager o refaz depois.
 */
class AbrirAutomaticoWorker(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        // Pode ser uma repetição depois de o sistema parar o trabalho: confere de novo o essencial.
        if (!Dados.preferencias(app).wifiAbrir) return Result.success()
        if (GatilhoWifi.pontoAberto(LocalDateTime.now(), Wifi.estado(app))) return Result.success()
        val credenciais = Dados.credenciais(app)
        if (credenciais == null || !credenciais.completas) {
            Wifi.anotar(app, "Ignorado: usuário e senha do SIGRH não definidos.")
            return Result.success()
        }
        val redeConfirmada = inputData.getBoolean(Wifi.EXTRA_REDE_CONFIRMADA, false)
        if (!Execucao.abrirAutomatico(app, credenciais, redeConfirmada)) {
            Wifi.anotar(app, "Ignorado: outra ação em andamento.")
        }
        return Result.success()
    }
}
