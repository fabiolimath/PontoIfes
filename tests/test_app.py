import asyncio
import importlib
import uuid
from datetime import date

import pytest

from ponto import configuracoes

toga = pytest.importorskip("toga")


@pytest.fixture
def app(tmp_path, monkeypatch):
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")
    from ponto.app import Ponto

    monkeypatch.setattr(toga.paths.Paths, "data", property(lambda self: tmp_path))
    return Ponto(formal_name="Ponto", app_id="io.github.fabiolimath.ponto")


def test_primeira_execucao_pede_credenciais(app):
    assert set(app.campos) == {"SIGRH_USER", "SIGRH_PASS", "TELEGRAM_CHAT_ID"}
    assert app.main_window.content is not app.tela_principal


def test_tela_principal_tem_tres_botoes(app):
    assert [b.text for b in app.botoes] == ["Abrir ponto", "Fechar ponto", "Registrar PIT"]
    app.mostrar_principal()
    assert app.main_window.content is app.tela_principal


def test_tela_de_log(app):
    app.mostrar_log()
    assert app.texto_log.value == "Nenhuma execução registrada ainda."


def _preparar_script(app, tmp_path, monkeypatch, acao, corpo):
    from ponto import credenciais, executor

    pacote = f"scripts_{uuid.uuid4().hex}"
    (tmp_path / pacote).mkdir()
    (tmp_path / pacote / "__init__.py").write_text("")
    (tmp_path / pacote / f"{acao}.py").write_text(corpo)
    monkeypatch.syspath_prepend(str(tmp_path))
    importlib.invalidate_caches()
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, pacote=pacote, **k))
    credenciais.salvar(app.cred_path, {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    app.mostrar_principal()


def test_rodar_mostra_saida_e_status(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "abrir_ponto", "import sys\nprint('tentando')\nsys.exit(1)\n")

    app.loop.run_until_complete(app.rodar("abrir_ponto"))

    assert app.saida.value == "tentando\n"
    assert app.status.text == "Abrir ponto: falhou (código 1)."
    assert all(b.enabled for b in app.botoes)
    assert "saída: 1" in app.log_path.read_text(encoding="utf-8")


def test_pit_com_data_passa_argumento(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nprint(sys.argv[1:])\n")
    app.data_pit.value = " 02/10/2026 "

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['02/10/2026', '--obs', 'Conforme PIT docente.']\n"
    assert app.status.text == "Registrar PIT (02/10/2026): concluído."
    assert app.data_pit.value == ""
    assert "· registrar_pit 02/10/2026 --obs Conforme PIT docente. ===" in app.log_path.read_text(encoding="utf-8")


def test_pit_sem_data_usa_o_dia(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nprint(sys.argv[1:])\n")
    configuracoes.registrar_fechamento(app.fechamento_path)

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['--obs', 'Conforme PIT docente.']\n"


def test_pit_usa_observacao_das_configuracoes(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nprint(sys.argv[1:])\n")
    configuracoes.salvar(app.config_path, {"observacao_pit": "PIT segundo portaria"})
    app.data_pit.value = "02/10/2026"

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['02/10/2026', '--obs', 'PIT segundo portaria']\n"


def test_pit_com_data_invalida_nao_roda(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.data_pit.value = "31/02/2026"
    app.main_window._impl.dialog_responses["ErrorDialog"] = [None]

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == ""
    assert not app.log_path.exists()
    assert app.main_window._impl.dialog_responses["ErrorDialog"] == []


def test_copiar_log_fora_do_android_avisa(app):
    app.mostrar_log()
    app.main_window._impl.dialog_responses["InfoDialog"] = [None]
    botao = app.main_window.content.children[1].children[1]
    assert botao.text == "Copiar"

    app.loop.run_until_complete(app.copiar_log(botao))

    assert app.main_window._impl.dialog_responses["InfoDialog"] == []


def test_aberto_por_intent_executa_a_acao(tmp_path, monkeypatch):
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")
    monkeypatch.setattr(toga.paths.Paths, "data", property(lambda self: tmp_path))
    from ponto import credenciais, executor, plataforma
    from ponto.app import Ponto

    pacote = f"scripts_{uuid.uuid4().hex}"
    (tmp_path / pacote).mkdir()
    (tmp_path / pacote / "__init__.py").write_text("")
    (tmp_path / pacote / "fechar_ponto.py").write_text("print('fechou')\n")
    monkeypatch.syspath_prepend(str(tmp_path))
    importlib.invalidate_caches()
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, pacote=pacote, **k))
    credenciais.salvar(tmp_path / "credenciais.json", {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    monkeypatch.setattr(plataforma, "acao_do_intent", lambda app: "fechar_ponto")

    app = Ponto(formal_name="Ponto", app_id="io.github.fabiolimath.ponto")
    app.loop.run_until_complete(asyncio.sleep(0.5))

    assert app.saida.value == "fechou\n"
    assert app.status.text == "Fechar ponto: concluído."


def test_pit_do_dia_sem_fechar_o_ponto_pergunta(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [False, False]

    app.loop.run_until_complete(app.rodar("registrar_pit"))
    app.data_pit.value = date.today().strftime("%d/%m/%Y")
    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == ""
    assert app.main_window._impl.dialog_responses["ConfirmDialog"] == []


def test_pit_do_dia_registrar_mesmo_assim(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [True]

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "rodou\n"


def test_pit_de_outro_dia_nao_exige_fechamento(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.data_pit.value = "02/10/2020"

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "rodou\n"


def test_fechar_ponto_libera_o_pit_do_dia(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "fechar_ponto", "print('fechou')\n")

    app.loop.run_until_complete(app.rodar("fechar_ponto"))

    assert configuracoes.fechou_no_dia(app.fechamento_path)


def test_mascara_no_campo_de_data(app):
    app.mostrar_principal()
    for texto in ["0", "04", "04/1", "04/10", "04/10/2026"]:
        app.data_pit.value = texto
    assert app.data_pit.value == "04/10/2026"
    app.data_pit.value = "04/"
    app.data_pit.value = "04"  # apagou a barra: não volta
    assert app.data_pit.value == "04"


def test_tela_de_configuracoes(app):
    titulo = app.main_window.content.content.children[0]
    assert titulo.text == "Configurações"
    assert app.verificar_atualizacoes.value is True
    app.campos["SIGRH_USER"].value = "a"
    app.campos["SIGRH_PASS"].value = "b"
    app.verificar_atualizacoes.value = False
    assert app.observacao_pit.value == "Conforme PIT docente."
    app.observacao_pit.value = " PIT segundo portaria "

    app.loop.run_until_complete(app.salvar_configuracoes(None))

    assert app.main_window.content is app.tela_principal
    assert configuracoes.carregar(app.config_path) == {
        "verificar_atualizacoes": False, "observacao_pit": "PIT segundo portaria"}
    assert not any(isinstance(w, toga.Label) and w.text == "Pronto." for w in app.tela_principal.children)


def test_atualizacao_oferece_baixar(app, monkeypatch):
    from ponto import atualizacao, plataforma

    abertos = []
    monkeypatch.setattr(type(app), "version", property(lambda self: "1.0.6"))
    monkeypatch.setattr(atualizacao, "ultima_versao", lambda: "1.0.10")
    monkeypatch.setattr(plataforma, "abrir_url", lambda app, url: abertos.append(url))
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [True, False]

    # A checagem agendada ao abrir o app roda junto, na 1ª volta do loop.
    app.loop.run_until_complete(app.verificar_atualizacao(avisar_sem_novidade=False))

    assert abertos == [atualizacao.URL_DOWNLOAD]
    assert app.main_window._impl.dialog_responses["ConfirmDialog"] == []


def test_atualizacao_em_dia_so_avisa_se_pedido(app, monkeypatch):
    from ponto import atualizacao

    monkeypatch.setattr(type(app), "version", property(lambda self: "1.0.6"))
    monkeypatch.setattr(atualizacao, "ultima_versao", lambda: "1.0.6")

    app.loop.run_until_complete(app.verificar_atualizacao(avisar_sem_novidade=False))
    app.main_window._impl.dialog_responses["InfoDialog"] = [None]
    app.loop.run_until_complete(app.verificar_atualizacao(avisar_sem_novidade=True))

    assert app.main_window._impl.dialog_responses["InfoDialog"] == []
