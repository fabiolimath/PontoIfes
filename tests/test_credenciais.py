import stat

from ponto import credenciais


def test_sem_arquivo_devolve_none(tmp_path):
    assert credenciais.carregar(tmp_path / "c.json") is None


def test_salvar_e_carregar_com_permissao_600(tmp_path):
    path = tmp_path / "sub" / "c.json"
    credenciais.salvar(path, {"SIGRH_USER": " fulano ", "SIGRH_PASS": "segredo"})
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert credenciais.carregar(path) == {
        "SIGRH_USER": "fulano",
        "SIGRH_PASS": "segredo",
        "TELEGRAM_CHAT_ID": "",
    }


def test_token_antigo_salvo_no_aparelho_e_ignorado(tmp_path):
    path = tmp_path / "c.json"
    path.write_text('{"SIGRH_USER": "a", "SIGRH_PASS": "b", "TELEGRAM_TOKEN": "velho"}')
    assert "TELEGRAM_TOKEN" not in credenciais.carregar(path)


def test_ambiente_usa_token_embutido(monkeypatch):
    import sys
    import types

    modulo = types.ModuleType("ponto.segredo_telegram")
    modulo.TELEGRAM_TOKEN = " 123:abc \n"
    monkeypatch.setitem(sys.modules, "ponto.segredo_telegram", modulo)
    env = credenciais.ambiente({"SIGRH_USER": "a", "TELEGRAM_CHAT_ID": "42"})
    assert env == {"SIGRH_USER": "a", "TELEGRAM_CHAT_ID": "42", "TELEGRAM_TOKEN": "123:abc"}


def test_sem_token_embutido(monkeypatch):
    import sys

    monkeypatch.setitem(sys.modules, "ponto.segredo_telegram", None)
    assert credenciais.token_telegram() == ""


def test_faltando():
    assert credenciais.faltando(None) == ["Usuário do SIGRH", "Senha do SIGRH"]
    assert credenciais.faltando({"SIGRH_USER": "a", "SIGRH_PASS": "b"}) == []
