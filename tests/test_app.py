import pytest

toga = pytest.importorskip("toga")


@pytest.fixture
def app(tmp_path, monkeypatch):
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")
    from ponto.app import Ponto

    monkeypatch.setattr(toga.paths.Paths, "data", property(lambda self: tmp_path))
    return Ponto(formal_name="Ponto", app_id="io.github.fabiolimath.ponto")


def test_primeira_execucao_pede_credenciais(app):
    assert set(app.campos) == {"SIGRH_USER", "SIGRH_PASS", "TELEGRAM_TOKEN", "TELEGRAM_CHAT_ID"}
    assert app.main_window.content is not app.tela_principal


def test_tela_principal_tem_tres_botoes(app):
    assert [b.text for b in app.botoes] == ["Abrir ponto", "Fechar ponto", "Registrar PIT"]
    app.mostrar_principal()
    assert app.main_window.content is app.tela_principal


def test_tela_de_log(app):
    app.mostrar_log()
    assert app.texto_log.value == "Nenhuma execução registrada ainda."


def test_rodar_mostra_saida_e_status(app, tmp_path, monkeypatch):
    from ponto import credenciais, executor

    scripts = tmp_path / "scripts"
    scripts.mkdir()
    (scripts / "abrir_ponto.py").write_text("import sys\nprint('tentando')\nsys.exit(1)\n")
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, scripts_dir=scripts, **k))
    credenciais.salvar(app.cred_path, {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    app.mostrar_principal()

    app.loop.run_until_complete(app.rodar("abrir_ponto"))

    assert app.saida.value == "tentando\n"
    assert app.status.text == "Abrir ponto: falhou (código 1)."
    assert all(b.enabled for b in app.botoes)
    assert "saída: 1" in app.log_path.read_text(encoding="utf-8")


def _preparar_script(app, tmp_path, monkeypatch, corpo):
    from ponto import credenciais, executor

    scripts = tmp_path / "scripts"
    scripts.mkdir(exist_ok=True)
    (scripts / "registrar_pit.py").write_text(corpo)
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, scripts_dir=scripts, **k))
    credenciais.salvar(app.cred_path, {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    app.mostrar_principal()


def test_pit_com_data_passa_argumento(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "import sys\nprint(sys.argv[1:])\n")
    app.data_pit.value = " 02/10/2026 "

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['02/10/2026']\n"
    assert app.status.text == "Registrar PIT (02/10/2026): concluído."
    assert app.data_pit.value == ""
    assert "· registrar_pit 02/10/2026 ===" in app.log_path.read_text(encoding="utf-8")


def test_pit_sem_data_usa_o_dia(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "import sys\nprint(sys.argv[1:])\n")

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "[]\n"


def test_pit_com_data_invalida_nao_roda(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "print('rodou')\n")
    app.data_pit.value = "31/02/2026"
    app.main_window._impl.dialog_responses["ErrorDialog"] = [None]

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == ""
    assert not app.log_path.exists()
    assert app.main_window._impl.dialog_responses["ErrorDialog"] == []
