import os
import sys

from ponto import executor


def _script(tmp_path, nome, corpo):
    (tmp_path / f"{nome}.py").write_text(corpo, encoding="utf-8")


def test_sucesso_passa_credenciais_e_grava_log(tmp_path):
    _script(tmp_path, "abrir_ponto", "import os\nprint('usuario', os.environ['SIGRH_USER'])\n")
    log = tmp_path / "log" / "ponto.log"
    codigo, saida = executor.executar(
        "abrir_ponto", {"SIGRH_USER": "fulano", "SIGRH_PASS": "x"}, log, scripts_dir=tmp_path
    )
    assert codigo == 0
    assert saida == "usuario fulano\n"
    texto = log.read_text(encoding="utf-8")
    assert "· abrir_ponto ===" in texto
    assert texto.endswith("usuario fulano\nsaída: 0\n")


def test_ambiente_e_argv_restaurados(tmp_path):
    _script(tmp_path, "abrir_ponto", "import sys\nprint(sys.argv)\n")
    os.environ.pop("SIGRH_USER", None)
    argv = sys.argv
    _, saida = executor.executar("abrir_ponto", {"SIGRH_USER": "fulano"}, tmp_path / "l", scripts_dir=tmp_path)
    assert "SIGRH_USER" not in os.environ
    assert sys.argv is argv
    assert "abrir_ponto.py" in saida


def test_sys_exit_vira_codigo(tmp_path):
    _script(tmp_path, "fechar_ponto", "import sys\nprint('falhou')\nsys.exit(1)\n")
    codigo, _ = executor.executar("fechar_ponto", {}, tmp_path / "l", scripts_dir=tmp_path)
    assert codigo == 1
    assert (tmp_path / "l").read_text(encoding="utf-8").endswith("falhou\nsaída: 1\n")


def test_excecao_vira_codigo_1_com_traceback(tmp_path):
    _script(tmp_path, "registrar_pit", "raise RuntimeError('quebrou')\n")
    codigo, saida = executor.executar("registrar_pit", {}, tmp_path / "l", scripts_dir=tmp_path)
    assert codigo == 1
    assert "RuntimeError: quebrou" in saida


def test_callback_recebe_saida_ao_vivo(tmp_path):
    _script(tmp_path, "abrir_ponto", "print('a')\nprint('b')\n")
    pedacos = []
    executor.executar("abrir_ponto", {}, tmp_path / "l", ao_escrever=pedacos.append, scripts_dir=tmp_path)
    assert "".join(pedacos) == "a\nb\n"


def test_log_mantem_ultimas_linhas(tmp_path, monkeypatch):
    monkeypatch.setattr(executor, "LOG_MAX_LINHAS", 6)
    _script(tmp_path, "abrir_ponto", "print('x')\n")
    for _ in range(5):
        executor.executar("abrir_ponto", {}, tmp_path / "l", scripts_dir=tmp_path)
    linhas = (tmp_path / "l").read_text(encoding="utf-8").splitlines()
    assert len(linhas) == 6
    assert linhas[-1] == "saída: 0"


def test_scripts_empacotados_existem():
    for acao in executor.ACOES:
        assert (executor.SCRIPTS_DIR / f"{acao}.py").is_file()
