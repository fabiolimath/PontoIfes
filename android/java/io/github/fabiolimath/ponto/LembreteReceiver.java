package io.github.fabiolimath.ponto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

/**
 * Lembrete de fechar o ponto, disparado pelo AlarmManager (agendado em
 * plataforma.agendar_lembrete). Roda em Java, sem o Python, para funcionar
 * mesmo com o app fechado ou suspenso.
 */
public class LembreteReceiver extends BroadcastReceiver {
    static final String CANAL = "resultados";  // o mesmo de plataforma.CANAL_NOTIFICACOES
    static final int IDENT = 4;                // plataforma.IDENT_LEMBRETE

    @Override
    public void onReceive(Context ctx, Intent intent) {
        NotificationManager gerenciador =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder construtor;
        if (Build.VERSION.SDK_INT >= 26) {
            gerenciador.createNotificationChannel(new NotificationChannel(
                    CANAL, "Resultado das execuções", NotificationManager.IMPORTANCE_DEFAULT));
            construtor = new Notification.Builder(ctx, CANAL);
        } else {
            construtor = new Notification.Builder(ctx);
        }

        String pacote = ctx.getPackageName();
        int icone = ctx.getResources().getIdentifier("ic_notificacao", "drawable", pacote);
        if (icone == 0) {
            icone = ctx.getApplicationInfo().icon;
        }
        Intent abrir = ctx.getPackageManager().getLaunchIntentForPackage(pacote);
        PendingIntent toque = PendingIntent.getActivity(ctx, 0, abrir, PendingIntent.FLAG_IMMUTABLE);

        // Botão "Fechar ponto": abre o app com acao=fechar_ponto, como o "Tentar de novo".
        Intent fechar = new Intent();
        fechar.setClassName(ctx, "org.beeware.android.MainActivity");
        fechar.putExtra("acao", "fechar_ponto");
        fechar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent botao = PendingIntent.getActivity(ctx, 200, fechar,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String titulo = intent.getStringExtra("titulo");
        String texto = intent.getStringExtra("texto");
        construtor.setSmallIcon(icone)
                .setContentTitle(titulo != null ? titulo : "Ponto ainda aberto")
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setContentIntent(toque)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx, icone), "Fechar ponto", botao).build());
        gerenciador.notify(IDENT, construtor.build());
    }
}
