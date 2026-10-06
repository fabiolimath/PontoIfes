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
    }


def test_campos_antigos_do_telegram_sao_ignorados(tmp_path):
    path = tmp_path / "c.json"
    path.write_text('{"SIGRH_USER": "a", "SIGRH_PASS": "b", "TELEGRAM_CHAT_ID": "42", "TELEGRAM_TOKEN": "x"}')
    assert credenciais.carregar(path) == {"SIGRH_USER": "a", "SIGRH_PASS": "b"}


def test_faltando():
    assert credenciais.faltando(None) == ["Usuário do SIGRH", "Senha do SIGRH"]
    assert credenciais.faltando({"SIGRH_USER": "a", "SIGRH_PASS": "b"}) == []
