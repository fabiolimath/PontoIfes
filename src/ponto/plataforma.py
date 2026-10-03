"""Integrações com o Android (via Chaquopy). Fora do Android, não fazem nada."""

from ponto import executor

# Extra do intent com a ação a executar ao abrir o app, ex.: acao=abrir_ponto.
EXTRA_ACAO = "acao"


def _atividade(app):
    """MainActivity do app, ou None fora do Android."""
    try:
        import android  # noqa: F401  (pacote do Chaquopy)
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
    return acao if acao in executor.ACOES else None


def criar_atalhos(app):
    """Atalhos do ícone (segurar o ícone do app), um por ação; podem ser fixados."""
    atividade = _atividade(app)
    if atividade is None:
        return
    from android.content import Context, Intent
    from android.content.pm import ShortcutInfo
    from android.graphics.drawable import Icon
    from java.util import ArrayList

    icone = Icon.createWithResource(atividade, atividade.getApplicationInfo().icon)
    atalhos = ArrayList()
    for acao, rotulo in executor.ACOES.items():
        intent = Intent(Intent.ACTION_VIEW)
        intent.setClassName(atividade, atividade.getClass().getName())
        intent.putExtra(EXTRA_ACAO, acao)
        atalhos.add(
            ShortcutInfo.Builder(atividade, acao)
            .setShortLabel(rotulo)
            .setIcon(icone)
            .setIntent(intent)
            .build()
        )
    gerenciador = atividade.getSystemService(Context.SHORTCUT_SERVICE)
    gerenciador.setDynamicShortcuts(atalhos)
