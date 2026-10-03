"""Testa a lógica de plataforma.py com classes Java falsas no lugar do Chaquopy."""

import sys
import types

import pytest

from ponto import plataforma


class FakeIntent:
    ACTION_VIEW = "android.intent.action.VIEW"
    FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY = 0x00100000

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
        assert nome == "shortcut"
        return types.SimpleNamespace(setDynamicShortcuts=lambda lista: setattr(self, "atalhos", lista))


@pytest.fixture
def android(monkeypatch):
    def modulo(nome, **attrs):
        m = types.ModuleType(nome)
        m.__dict__.update(attrs)
        monkeypatch.setitem(sys.modules, nome, m)

    # Como no Chaquopy: "import android" falha, só "from android.x import Classe" funciona.
    monkeypatch.setitem(sys.modules, "android", None)
    modulo("android.content", Intent=FakeIntent,
           Context=types.SimpleNamespace(SHORTCUT_SERVICE="shortcut"))
    modulo("android.content.pm", ShortcutInfo=types.SimpleNamespace(Builder=FakeBuilder))
    modulo("android.graphics.drawable",
           Icon=types.SimpleNamespace(createWithResource=lambda ctx, res: f"icone:{res}"))
    modulo("java.util", ArrayList=FakeArrayList)

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
