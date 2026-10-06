"""Testa a lógica de plataforma.py com classes Java falsas no lugar do Chaquopy."""

import sys
import types

import pytest

from ponto import plataforma


class FakeIntent:
    ACTION_VIEW = "android.intent.action.VIEW"
    FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000
    FLAG_ACTIVITY_NEW_TASK = 0x10000000
    FLAG_ACTIVITY_CLEAR_TASK = 0x00008000

    def __init__(self, action=None, extras=None, flags=0, data=None):
        self.action = action
        self.data = data
        self.extras = dict(extras or {})
        self.flags = flags
        self.classe = None

    def getFlags(self):
        return self.flags

    def getDataString(self):
        return self.data

    def getStringExtra(self, nome):
        return self.extras.get(nome)

    def removeExtra(self, nome):
        self.extras.pop(nome, None)

    def putExtra(self, nome, valor):
        self.extras[nome] = valor

    def setClassName(self, contexto, classe):
        self.classe = classe

    def addFlags(self, flags):
        self.flags |= flags


class FakeBuilder:
    def __init__(self, contexto, ident):
        self.info = {"id": ident}

    def setShortLabel(self, rotulo):
        self.info["rotulo"] = rotulo
        return self

    def setIcon(self, icone):
        self.info["icone"] = icone
        return self

    def setIntent(self, intent):
        self.info["intent"] = intent
        return self

    def build(self):
        return self.info


class FakeArrayList(list):
    def add(self, item):
        self.append(item)


class FakeNotificacao:
    class Builder:
        def __init__(self, contexto, canal=None):
            self.info = {"canal": canal}

        def __getattr__(self, nome):
            if not nome.startswith("set"):
                raise AttributeError(nome)

            def setter(valor):
                self.info[nome[3:]] = valor
                return self
            return setter

        def addAction(self, acao):
            self.info.setdefault("acoes", []).append(acao)
            return self

        def build(self):
            return self.info

    class Action:
        class Builder:
            def __init__(self, icone, rotulo, intent):
                self.acao = (icone, rotulo, intent)

            def build(self):
                return self.acao

    class BigTextStyle:
        def bigText(self, texto):
            return ("big", texto)


class FakeGerenciador:
    def __init__(self):
        self.canais, self.notificacoes = [], {}

    def createNotificationChannel(self, canal):
        self.canais.append(canal)

    def notify(self, ident, notificacao):
        self.notificacoes[ident] = notificacao

    def cancel(self, ident):
        self.notificacoes.pop(ident, None)


class FakeAlarmes:
    def __init__(self):
        self.agendados = {}

    def setAndAllowWhileIdle(self, tipo, quando_ms, pendente):
        _, codigo, intent = pendente
        self.agendados[(intent.classe, codigo)] = (tipo, quando_ms, intent.extras)

    def cancel(self, pendente):
        _, codigo, intent = pendente
        self.agendados.pop((intent.classe, codigo), None)


class FakeAtividade:
    def __init__(self, intent=None):
        self.intent = intent
        self.atalhos = None

    def getIntent(self):
        return self.intent

    def getApplicationInfo(self):
        return types.SimpleNamespace(icon=42)

    def getClass(self):
        return types.SimpleNamespace(getName=lambda: "org.beeware.android.MainActivity")

    def getSystemService(self, nome):
        if nome == "notification":
            return self.gerenciador
        if nome == "alarm":
            return self.alarmes
        assert nome == "shortcut"
        return types.SimpleNamespace(setDynamicShortcuts=lambda lista: setattr(self, "atalhos", lista))

    gerenciador = None
    alarmes = None
    permissao = -1

    def getPackageName(self):
        return "io.github.fabiolimath.ponto"

    def getResources(self):
        return types.SimpleNamespace(getIdentifier=lambda nome, tipo, pacote: 7)

    def getPackageManager(self):
        return types.SimpleNamespace(getLaunchIntentForPackage=lambda pacote: f"abrir:{pacote}")

    def checkSelfPermission(self, permissao):
        return self.permissao


@pytest.fixture
def android(monkeypatch):
    def modulo(nome, **attrs):
        m = types.ModuleType(nome)
        m.__dict__.update(attrs)
        monkeypatch.setitem(sys.modules, nome, m)

    # Como no Chaquopy: "import android" falha, só "from android.x import Classe" funciona.
    monkeypatch.setitem(sys.modules, "android", None)
    modulo("android.content", Intent=FakeIntent,
           Context=types.SimpleNamespace(SHORTCUT_SERVICE="shortcut",
                                         NOTIFICATION_SERVICE="notification",
                                         ALARM_SERVICE="alarm"))
    modulo("android.content.pm", ShortcutInfo=types.SimpleNamespace(Builder=FakeBuilder),
           PackageManager=types.SimpleNamespace(PERMISSION_GRANTED=0))
    modulo("android.view", View=types.SimpleNamespace(IMPORTANT_FOR_AUTOFILL_YES=1,
                                                      IMPORTANT_FOR_AUTOFILL_NO=2))
    modulo("android.os", Build=types.SimpleNamespace(VERSION=types.SimpleNamespace(SDK_INT=34)))
    modulo("android.app", Notification=FakeNotificacao,
           NotificationChannel=lambda ident, nome, importancia: (ident, nome),
           NotificationManager=types.SimpleNamespace(IMPORTANCE_DEFAULT=3),
           PendingIntent=types.SimpleNamespace(
               FLAG_IMMUTABLE=0x04000000, FLAG_UPDATE_CURRENT=0x08000000,
               getActivity=lambda ctx, codigo, intent, flags: ("pendente", intent),
               getBroadcast=lambda ctx, codigo, intent, flags: ("difusao", codigo, intent)),
           AlarmManager=types.SimpleNamespace(RTC_WAKEUP=0))
    modulo("android.graphics.drawable",
           Icon=types.SimpleNamespace(createWithResource=lambda ctx, res: f"icone:{res}"))
    modulo("java.util", ArrayList=FakeArrayList)
    modulo("android.text", InputType=types.SimpleNamespace(
        TYPE_CLASS_NUMBER=2, TYPE_CLASS_DATETIME=4, TYPE_DATETIME_VARIATION_TIME=32))

    def app_com(atividade):
        return types.SimpleNamespace(_impl=types.SimpleNamespace(native=atividade))

    return app_com


def test_fora_do_android_nao_faz_nada():
    app = types.SimpleNamespace(_impl=None)
    assert plataforma.acao_do_intent(app) is None
    assert plataforma.copiar(app, "x") is False


def test_acao_do_intent(android):
    intent = FakeIntent(extras={"acao": "fechar_ponto"})
    assert plataforma.acao_do_intent(android(FakeAtividade(intent))) == "fechar_ponto"
    assert "acao" not in intent.extras


def test_acao_desconhecida_e_ignorada(android):
    intent = FakeIntent(extras={"acao": "rm -rf"})
    assert plataforma.acao_do_intent(android(FakeAtividade(intent))) is None


def test_aberto_pelos_recentes_nao_repete(android):
    intent = FakeIntent(extras={"acao": "abrir_ponto"},
                        flags=FakeIntent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
    assert plataforma.acao_do_intent(android(FakeAtividade(intent))) is None


def test_sem_extra(android):
    assert plataforma.acao_do_intent(android(FakeAtividade(FakeIntent()))) is None


@pytest.mark.parametrize("dado", ["registrar_pit", "ponto://registrar_pit", " registrar_pit/ "])
def test_acao_pelo_dado_do_intent(android, dado):
    intent = FakeIntent(data=dado)
    assert plataforma.acao_do_intent(android(FakeAtividade(intent))) == "registrar_pit"


def test_dado_desconhecido_e_ignorado(android):
    intent = FakeIntent(data="https://exemplo.com")
    assert plataforma.acao_do_intent(android(FakeAtividade(intent))) is None


def test_confirmacao_em_portugues_no_android(monkeypatch):
    pytest.importorskip("toga")
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")

    class FakeTextDialog:
        def __init__(self, titulo, mensagem, positive_text, negative_text):
            self.rotulos = (positive_text, negative_text)

    monkeypatch.setitem(sys.modules, "toga_android", types.ModuleType("toga_android"))
    monkeypatch.setitem(sys.modules, "toga_android.dialogs",
                        types.SimpleNamespace(TextDialog=FakeTextDialog))
    dialogo = plataforma.confirmacao("T", "M", sim="Registrar mesmo assim")
    assert dialogo._impl.rotulos == ("Registrar mesmo assim", "Cancelar")


def test_fora_do_android_sem_notificacao():
    app = types.SimpleNamespace(_impl=None)
    assert plataforma.notificar(app, "t", "x") is False
    assert plataforma.pedir_permissao_notificacoes(app) is False
    assert plataforma.cancelar_notificacao(app, 1) is False


def test_notificar(android):
    atividade = FakeAtividade()
    atividade.gerenciador = FakeGerenciador()
    assert plataforma.notificar(android(atividade), "Abrir ponto: concluído.", "✅ entrada", 1)
    assert atividade.gerenciador.canais == [("resultados", "Resultado das execuções")]
    info = atividade.gerenciador.notificacoes[1]
    assert info["canal"] == "resultados"
    assert info["SmallIcon"] == 7
    assert (info["ContentTitle"], info["ContentText"]) == ("Abrir ponto: concluído.", "✅ entrada")
    assert info["ContentIntent"] == ("pendente", "abrir:io.github.fabiolimath.ponto")
    assert info["AutoCancel"] is True
    assert "acoes" not in info


def test_notificacao_de_falha_tem_tentar_de_novo(android):
    atividade = FakeAtividade()
    atividade.gerenciador = FakeGerenciador()
    plataforma.notificar(android(atividade), "Registrar PIT: falhou.", "❌", 3,
                         tentar_de_novo={"acao": "registrar_pit", "data": "02/10/2026"})
    [(icone, rotulo, (_, intent))] = atividade.gerenciador.notificacoes[3]["acoes"]
    assert rotulo == "Tentar de novo"
    assert intent.classe == "org.beeware.android.MainActivity"
    assert intent.extras == {"acao": "registrar_pit", "data": "02/10/2026"}
    assert intent.flags & FakeIntent.FLAG_ACTIVITY_CLEAR_TASK

    # Ao abrir o app por esse botão, a ação e a data são lidas do intent.
    reaberta = android(FakeAtividade(intent))
    assert plataforma.acao_do_intent(reaberta) == "registrar_pit"
    assert plataforma.data_do_intent(reaberta) == "02/10/2026"
    assert plataforma.data_do_intent(reaberta) is None


def test_pede_permissao_so_se_faltar(android):
    pedidos = []
    atividade = FakeAtividade()
    app = android(atividade)
    app._impl.request_permissions = lambda permissoes, ao_terminar: pedidos.append(permissoes)

    assert plataforma.pedir_permissao_notificacoes(app)
    atividade.permissao = 0  # concedida
    assert plataforma.pedir_permissao_notificacoes(app)

    assert pedidos == [["android.permission.POST_NOTIFICATIONS"]]


def test_campo_numerico(android):
    tipos = []
    entrada = types.SimpleNamespace(_impl=types.SimpleNamespace(
        native=types.SimpleNamespace(setInputType=tipos.append)))
    assert plataforma.campo_numerico(entrada)
    assert tipos == [2]


def test_preenchimento_automatico(android):
    class Campo:
        dicas, importancia = None, None

        def setAutofillHints(self, *dicas):
            self.dicas = dicas

        def setImportantForAutofill(self, valor):
            self.importancia = valor

    usuario, observacao = Campo(), Campo()
    assert plataforma.preenchimento(types.SimpleNamespace(_impl=types.SimpleNamespace(native=usuario)),
                                    "username")
    assert plataforma.preenchimento(types.SimpleNamespace(_impl=types.SimpleNamespace(native=observacao)))
    assert (usuario.dicas, usuario.importancia) == (("username",), 1)
    assert (observacao.dicas, observacao.importancia) == (None, 2)


def test_cancelar_notificacao(android):
    atividade = FakeAtividade()
    atividade.gerenciador = FakeGerenciador()
    app = android(atividade)
    plataforma.notificar(app, "Abrir ponto: falhou.", "❌", 1)
    assert plataforma.cancelar_notificacao(app, 1)
    assert atividade.gerenciador.notificacoes == {}


def test_campo_de_hora(android):
    tipos = []
    entrada = types.SimpleNamespace(_impl=types.SimpleNamespace(
        native=types.SimpleNamespace(setInputType=tipos.append)))
    assert plataforma.campo_de_hora(entrada)
    assert tipos == [4 | 32]


def test_agendar_e_cancelar_lembrete(android):
    from datetime import datetime, timezone

    atividade = FakeAtividade()
    atividade.gerenciador = FakeGerenciador()
    atividade.alarmes = FakeAlarmes()
    app = android(atividade)
    quando = datetime(2026, 10, 6, 12, 0, tzinfo=timezone.utc)

    assert plataforma.agendar_lembrete(app, quando, "Ponto ainda aberto", "Aberto às 10:20.")
    receptor = ("io.github.fabiolimath.ponto.LembreteReceiver", 0)
    assert atividade.alarmes.agendados == {receptor: (
        0, 1791288000000, {"titulo": "Ponto ainda aberto", "texto": "Aberto às 10:20."})}

    atividade.gerenciador.notify(plataforma.IDENT_LEMBRETE, {"ContentTitle": "Ponto ainda aberto"})
    assert plataforma.cancelar_lembrete(app)
    assert atividade.alarmes.agendados == {}
    assert atividade.gerenciador.notificacoes == {}


def test_lembrete_fora_do_android():
    from datetime import datetime

    app = types.SimpleNamespace(_impl=None)
    assert plataforma.agendar_lembrete(app, datetime.now(), "t", "x") is False
    assert plataforma.cancelar_lembrete(app) is False
