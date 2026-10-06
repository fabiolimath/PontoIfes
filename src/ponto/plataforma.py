"""Integrações com o Android (via Chaquopy). Fora do Android, não fazem nada."""

from ponto import executor

# Extra do intent com a ação a executar ao abrir o app, ex.: acao=abrir_ponto.
EXTRA_ACAO = "acao"


def _atividade(app):
    """MainActivity do app, ou None fora do Android."""
    # O Chaquopy só importa classes Java com "from pacote import Classe";
    # "import android" sozinho falha mesmo no Android.
    try:
        from android.content import Context  # noqa: F401
    except ImportError:
        return None
    return app._impl.native


def copiar(app, texto):
    """Copia o texto para a área de transferência; devolve False fora do Android."""
    atividade = _atividade(app)
    if atividade is None:
        return False
    from android.content import ClipData, Context

    gerenciador = atividade.getSystemService(Context.CLIPBOARD_SERVICE)
    gerenciador.setPrimaryClip(ClipData.newPlainText("Log do Ponto", texto))
    return True


def acao_do_intent(app):
    """Ação pedida pelo intent que abriu o app (atalho, Tasker...), ou None.

    A ação vem do extra "acao" ou, se ele faltar, do dado (URI) do intent.
    Abrir o app pela tela de recentes reentrega o intent original; nesse
    caso a ação é ignorada para não executar de novo.
    """
    atividade = _atividade(app)
    if atividade is None:
        return None
    from android.content import Intent

    intent = atividade.getIntent()
    if intent is None:
        return None
    if intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY:
        return None
    acao = intent.getStringExtra(EXTRA_ACAO)
    intent.removeExtra(EXTRA_ACAO)
    if not acao and intent.getDataString():
        # Campo "Dado" do "Executar app" do Tasker: "abrir_ponto" ou "ponto://abrir_ponto".
        acao = intent.getDataString().removeprefix("ponto:").strip("/ ")
    return acao if acao in executor.ACOES else None



def abrir_url(app, url):
    """Abre o endereço no navegador (no Android, por um intent ACTION_VIEW)."""
    atividade = _atividade(app)
    if atividade is None:
        import webbrowser

        webbrowser.open(url)
        return
    from android.content import Intent
    from android.net import Uri

    atividade.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))


def campo_de_data(entrada):
    """Teclado numérico de datas (com "/") no campo de texto; só no Android."""
    try:
        from android.text import InputType
    except ImportError:
        return False
    entrada._impl.native.setInputType(
        InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE
    )
    return True


def campo_numerico(entrada):
    """Teclado só com dígitos no campo de texto (ex.: usuário do SIGRH); só no Android.

    Um TextInput comum, e não um NumberInput, para não perder zeros à esquerda.
    """
    try:
        from android.text import InputType
    except ImportError:
        return False
    entrada._impl.native.setInputType(InputType.TYPE_CLASS_NUMBER)
    return True


# -----------------------------------
# NOTIFICAÇÕES DO SISTEMA
# -----------------------------------
CANAL_NOTIFICACOES = "resultados"
PERMISSAO_NOTIFICACOES = "android.permission.POST_NOTIFICATIONS"


def pedir_permissao_notificacoes(app):
    """Pede a permissão de notificar (exigida a partir do Android 13).

    Devolve False fora do Android. Se o usuário já respondeu "não" duas
    vezes, o Android não pergunta mais; aí só pelas configurações do sistema.
    """
    atividade = _atividade(app)
    if atividade is None:
        return False
    from android.content.pm import PackageManager
    from android.os import Build

    if Build.VERSION.SDK_INT < 33:
        return True
    if atividade.checkSelfPermission(PERMISSAO_NOTIFICACOES) != PackageManager.PERMISSION_GRANTED:
        # Pelo Toga, que repassa a resposta (aqui ignorada) ao callback certo.
        app._impl.request_permissions([PERMISSAO_NOTIFICACOES], lambda *resposta: None)
    return True


def notificar(app, titulo, texto, ident=1):
    """Mostra uma notificação do sistema; tocar nela abre o app.

    `ident` igual substitui a notificação anterior. Devolve False fora do Android.
    """
    atividade = _atividade(app)
    if atividade is None:
        return False
    from android.app import Notification, NotificationManager, PendingIntent
    from android.content import Context
    from android.os import Build

    gerenciador = atividade.getSystemService(Context.NOTIFICATION_SERVICE)
    if Build.VERSION.SDK_INT >= 26:
        from android.app import NotificationChannel

        gerenciador.createNotificationChannel(NotificationChannel(
            CANAL_NOTIFICACOES, "Resultado das execuções", NotificationManager.IMPORTANCE_DEFAULT
        ))
        construtor = Notification.Builder(atividade, CANAL_NOTIFICACOES)
    else:
        construtor = Notification.Builder(atividade)

    # Ícone monocromático próprio (android/res/drawable); o ícone do app,
    # adaptativo, pode derrubar a barra de status em alguns Androids.
    pacote = atividade.getPackageName()
    icone = atividade.getResources().getIdentifier("ic_notificacao", "drawable", pacote)
    abrir = atividade.getPackageManager().getLaunchIntentForPackage(pacote)
    toque = PendingIntent.getActivity(atividade, 0, abrir, PendingIntent.FLAG_IMMUTABLE)

    construtor.setSmallIcon(icone or atividade.getApplicationInfo().icon)
    construtor.setContentTitle(titulo)
    construtor.setContentText(texto)
    construtor.setStyle(Notification.BigTextStyle().bigText(texto))
    construtor.setContentIntent(toque)
    construtor.setAutoCancel(True)
    gerenciador.notify(ident, construtor.build())
    return True


def cursor_no_fim(entrada):
    """Põe o cursor no fim do texto; no Android, trocar o texto o leva ao início."""
    try:
        from android.text import InputType  # noqa: F401
    except ImportError:
        return False
    nativo = entrada._impl.native
    nativo.setSelection(nativo.getText().length())
    return True


def confirmacao(titulo, mensagem, sim="OK", nao="Cancelar"):
    """toga.ConfirmDialog com os botões em português.

    O Toga no Android escreve "OK"/"Cancel" fixos; aqui o diálogo nativo é
    trocado por um com os rótulos pedidos. Fora do Android, fica o padrão.
    """
    import toga

    dialogo = toga.ConfirmDialog(titulo, mensagem)
    try:
        from toga_android.dialogs import TextDialog
    except ImportError:
        return dialogo
    dialogo._impl = TextDialog(titulo, mensagem, positive_text=sim, negative_text=nao)
    return dialogo
