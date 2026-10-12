package io.github.fabiolimath.ponto

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.fabiolimath.ponto.nucleo.AberturaAutomatica
import io.github.fabiolimath.ponto.nucleo.Decisao
import io.github.fabiolimath.ponto.nucleo.EstadoDoPonto
import io.github.fabiolimath.ponto.nucleo.Fechamentos
import io.github.fabiolimath.ponto.nucleo.GatilhoWifi
import io.github.fabiolimath.ponto.nucleo.Notificacoes
import java.time.LocalDateTime

/**
 * Abrir o ponto ao conectar na Wi-Fi do campus, com o app fechado: o gatilho guardado pelo sistema
 * (como o alarme do lembrete), a leitura do nome da rede e a bateria.
 */
object Wifi {
    private const val TAG = "Ponto"
    private const val TRABALHO = "abrir_automatico"
    const val EXTRA_REDE_CONFIRMADA = "rede_confirmada"
    private const val CANAL = "automatico"

    /** Mutável: o sistema põe no intent a rede que conectou. */
    private val MUTAVEL = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

    private fun pedido(contexto: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(contexto, 0, Intent(contexto, RedeReceiver::class.java), flags or MUTAVEL)

    private fun conectividade(contexto: Context) =
        contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Pede ao sistema que chame o [RedeReceiver] a cada Wi-Fi que fica disponível, mesmo com o app
     * fechado; ele chama na hora se já houver uma conectada. Com a opção desligada, desfaz o pedido.
     *
     * [forcar]: refaz o pedido mesmo que já exista (ao ligar a opção, ao reiniciar o celular e ao
     * atualizar o app). Sem [forcar] (ao abrir o app), só o refaz se ele sumiu (Forçar parada), para
     * abrir o app não disparar uma tentativa a cada vez.
     */
    fun registrar(contexto: Context, forcar: Boolean) {
        val app = contexto.applicationContext
        if (!Dados.preferencias(app).wifiAbrir) {
            desfazer(app)
            return
        }
        if (!forcar && pedido(app, PendingIntent.FLAG_NO_CREATE) != null) return
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

    fun desfazer(contexto: Context) {
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

    /** No Android 10+, o app fechado só lê o nome da rede com a localização "o tempo todo". */
    fun temLocalizacaoEmSegundoPlano(contexto: Context) =
        Build.VERSION.SDK_INT < 29 || concedida(contexto, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

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
        Build.VERSION.SDK_INT >= 28 &&
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

    /**
     * A notificação exigida no Android 11 ou anterior, onde o WorkManager roda o trabalho como serviço
     * em primeiro plano: canal próprio, silencioso e recolhido. No Android 12+ não aparece.
     */
    fun emAndamento(contexto: Context): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            (contexto.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CANAL, "Abertura automática em andamento", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notificacao = NotificationCompat.Builder(contexto, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("Abrindo o ponto…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notificacoes.IDENT_EM_ANDAMENTO, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notificacoes.IDENT_EM_ANDAMENTO, notificacao)
        }
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

    override suspend fun getForegroundInfo(): ForegroundInfo = Wifi.emAndamento(applicationContext)
}
