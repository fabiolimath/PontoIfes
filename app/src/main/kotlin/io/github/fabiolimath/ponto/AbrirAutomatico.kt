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
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
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
 * Abrir o ponto ao conectar na Wi-Fi do campus, com o app fechado: o gatilho guardado pelo sistema
 * (como o alarme do lembrete), a leitura do nome da rede e a bateria.
 */
object Wifi {
    private const val TAG = "Ponto"
    private const val TRABALHO = "abrir_automatico"
    private const val VERIFICACAO = "verificar_wifi"
    const val EXTRA_REDE_CONFIRMADA = "rede_confirmada"

    /** Mutável: o sistema põe no intent a rede que conectou. */
    private fun pedido(contexto: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(contexto, 0, Intent(contexto, RedeReceiver::class.java), flags or PendingIntent.FLAG_MUTABLE)

    private fun conectividade(contexto: Context) =
        contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Pede ao sistema que chame o [RedeReceiver] a cada Wi-Fi que fica disponível, mesmo com o app
     * fechado; ele chama na hora se já houver uma conectada. Com a opção desligada, desfaz o pedido.
     *
     * [forcar]: refaz o pedido mesmo que já exista (ao ligar a opção, ao reiniciar o celular e ao
     * atualizar o app). Sem [forcar] (ao abrir o app), só o refaz se ele sumiu (Forçar parada), para
     * abrir o app não disparar uma tentativa a cada vez.
     *
     * O pedido vale uma vez só: o sistema o desfaz 5 s depois de chamar o [RedeReceiver]
     * (ConnectivityService.onSendFinished, "releasePendingNetworkRequestWithDelay"; visto no
     * `dumpsys connectivity` do celular do Nick, REGISTER e RELEASE com 5 s de diferença). Por isso
     * o [VerificarWifiWorker] o refaz a cada 15 min, e o app a cada vez que sobe.
     *
     * [soSemWifi]: só refaz fora da Wi-Fi, para o pedido novo esperar a próxima conexão em vez de
     * disparar na hora pela Wi-Fi atual (que já foi tratada) a cada 15 min.
     */
    fun registrar(contexto: Context, forcar: Boolean, soSemWifi: Boolean = false) {
        val app = contexto.applicationContext
        if (!Dados.preferencias(app).wifiAbrir) {
            desfazer(app)
            return
        }
        val periodico = PeriodicWorkRequestBuilder<VerificarWifiWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(VERIFICACAO, ExistingPeriodicWorkPolicy.KEEP, periodico)
        if (!forcar && pedido(app, PendingIntent.FLAG_NO_CREATE) != null) return
        if (soSemWifi && conectadoNaWifi(app)) return
        val intencao = pedido(app, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val rede = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            try {
                conectividade(app).unregisterNetworkCallback(intencao)
            } catch (e: IllegalArgumentException) {
                // Ainda não havia pedido.
            }
            conectividade(app).registerNetworkCallback(rede, intencao)
        } catch (e: RuntimeException) {
            // Sem o pedido no sistema, o PendingIntent também sai, para a próxima abertura tentar de novo.
            intencao.cancel()
            throw e
        }
    }

    /**
     * Chamado pelo [RedeReceiver]: o sistema desfaz o pedido em 5 s, mas o PendingIntent pode
     * continuar existindo, e [registrar] acharia que o pedido ainda vale. Cancelado, a próxima
     * conferência o refaz.
     */
    fun marcarUsado(contexto: Context) {
        pedido(contexto.applicationContext, PendingIntent.FLAG_NO_CREATE)?.cancel()
    }

    /** Se a rede em uso agora é uma Wi-Fi. */
    private fun conectadoNaWifi(contexto: Context): Boolean {
        val conectividade = conectividade(contexto)
        val rede = conectividade.activeNetwork ?: return false
        return conectividade.getNetworkCapabilities(rede)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun desfazer(contexto: Context) {
        WorkManager.getInstance(contexto.applicationContext).cancelUniqueWork(VERIFICACAO)
        val intencao = pedido(contexto.applicationContext, PendingIntent.FLAG_NO_CREATE) ?: return
        try {
            conectividade(contexto).unregisterNetworkCallback(intencao)
        } catch (e: IllegalArgumentException) {
            // Já não havia pedido.
        }
        intencao.cancel()
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
 * Chamado pelo sistema a cada Wi-Fi que fica disponível (pedido feito por [Wifi.registrar]).
 * Decide em milissegundos, sem rede, se vale abrir o ponto, e põe o trabalho na fila.
 */
class RedeReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        val app = contexto.applicationContext
        try {
            val preferencias = Dados.preferencias(app)
            if (!preferencias.wifiAbrir) {
                Wifi.desfazer(app)
                return
            }
            Wifi.marcarUsado(app)
            val agora = LocalDateTime.now()
            val decisao = GatilhoWifi.deveExecutar(
                agora, Wifi.nomeDaRede(app), Wifi.estado(app), preferencias.wifiAbrir, preferencias.wifiRede,
                preferencias.duracaoDaEsperaWifi,
            )
            when (decisao) {
                is Decisao.Ignorar -> if (decisao.anotar) Wifi.anotar(app, "Ignorado: ${decisao.motivo}.", agora)
                is Decisao.Executar -> {
                    GatilhoWifi.registrarTentativa(Dados.arquivo(app, "abertura_automatica.txt"), agora)
                    Wifi.agendar(app, decisao.redeConfirmada)
                }
            }
        } catch (e: Exception) {
            Log.w("Ponto", "Erro no gatilho da Wi-Fi", e)
        }
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

/** A cada 15 min, refaz o pedido da Wi-Fi ([Wifi.registrar]) se ele foi usado e o celular está fora da Wi-Fi. */
class VerificarWifiWorker(contexto: Context, parametros: WorkerParameters) : Worker(contexto, parametros) {
    override fun doWork(): Result {
        try {
            Wifi.registrar(applicationContext, forcar = false, soSemWifi = true)
        } catch (e: Exception) {
            Log.w("Ponto", "Erro ao conferir o gatilho da Wi-Fi", e)
        }
        return Result.success()
    }
}
