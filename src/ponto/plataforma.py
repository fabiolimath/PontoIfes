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


def links(rotulo, html):
    """Troca o texto do toga.Label por HTML com links clicáveis (só no Android).

    Fora do Android devolve False e o rótulo fica com o texto simples.
    """
    try:
        from android.text import Html
        from android.text.method import LinkMovementMethod
    except ImportError:
        return False
    texto = rotulo._impl.native  # TextView
    texto.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY))
    texto.setMovementMethod(LinkMovementMethod.getInstance())
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
