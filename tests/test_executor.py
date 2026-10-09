import importlib
import os
import sys
import uuid

import pytest

from ponto import executor


@pytest.fixture
def pacote(tmp_path, monkeypatch):
    """Pacote temporário de scripts, no lugar de ponto.scripts."""
    nome = f"scripts_{uuid.uuid4().hex}"
    (tmp_path / nome).mkdir()
    (tmp_path / nome / "__init__.py").write_text("")
    monkeypatch.syspath_prepend(str(tmp_path))
    return nome


def _script(tmp_path, pacote, nome, corpo):
    (tmp_path / pacote / f"{nome}.py").write_text(corpo, encoding="utf-8")
    importlib.invalidate_caches()


def test_sucesso_passa_credenciais_e_grava_log(tmp_path, pacote):
    _script(tmp_path, pacote, "abrir_ponto", "import os\nprint('usuario', os.environ['SIGRH_USER'])\n")
    log = tmp_path / "log" / "ponto.log"
    codigo, saida = executor.executar(
        "abrir_ponto", {"SIGRH_USER": "fulano", "SIGRH_PASS": "x"}, log, pacote=pacote
    )
    assert codigo == 0
    assert saida == "usuario fulano\n"
    texto = log.read_text(encoding="utf-8")
    assert "· abrir_ponto ===" in texto
    assert texto.endswith("usuario fulano\nsaída: 0\n")


def test_ambiente_e_argv_restaurados(tmp_path, pacote):
    _script(tmp_path, pacote, "abrir_ponto", "import sys\nprint(sys.argv)\n")
    os.environ.pop("SIGRH_USER", None)
    argv = sys.argv
    _, saida = executor.executar("abrir_ponto", {"SIGRH_USER": "fulano"}, tmp_path / "l", pacote=pacote)
    assert "SIGRH_USER" not in os.environ
    assert sys.argv is argv
    assert "['abrir_ponto.py']" in saida


def test_sys_exit_vira_codigo(tmp_path, pacote):
    _script(tmp_path, pacote, "fechar_ponto", "import sys\nprint('falhou')\nsys.exit(1)\n")
    codigo, _ = executor.executar("fechar_ponto", {}, tmp_path / "l", pacote=pacote)
    assert codigo == 1
    assert (tmp_path / "l").read_text(encoding="utf-8").endswith("falhou\nsaída: 1\n")


def test_excecao_vira_codigo_1_com_traceback(tmp_path, pacote):
    _script(tmp_path, pacote, "registrar_pit", "raise RuntimeError('quebrou')\n")
    codigo, saida = executor.executar("registrar_pit", {}, tmp_path / "l", pacote=pacote)
    assert codigo == 1
    assert "RuntimeError: quebrou" in saida


def test_callback_recebe_saida_ao_vivo(tmp_path, pacote):
    _script(tmp_path, pacote, "abrir_ponto", "print('a')\nprint('b')\n")
    pedacos = []
    executor.executar("abrir_ponto", {}, tmp_path / "l", ao_escrever=pedacos.append, pacote=pacote)
    assert "".join(pedacos) == "a\nb\n"


def test_log_mantem_ultimas_linhas(tmp_path, pacote, monkeypatch):
    monkeypatch.setattr(executor, "LOG_MAX_LINHAS", 6)
    _script(tmp_path, pacote, "abrir_ponto", "print('x')\n")
    for _ in range(5):
        executor.executar("abrir_ponto", {}, tmp_path / "l", pacote=pacote)
    linhas = (tmp_path / "l").read_text(encoding="utf-8").splitlines()
    assert len(linhas) == 6
    assert linhas[-1] == "saída: 0"


def test_scripts_empacotados_existem():
    for acao in executor.ACOES:
        assert importlib.util.find_spec(f"{executor.SCRIPTS_PACOTE}.{acao}") is not None


def test_roda_script_sem_arquivo_fonte(tmp_path, pacote):
    """Como no Android: só o bytecode existe, não há .py no disco."""
    import compileall

    _script(tmp_path, pacote, "abrir_ponto", "import sys\nprint('ok', sys.argv[1:])\n")
    compileall.compile_dir(tmp_path / pacote, legacy=True, quiet=1)
    (tmp_path / pacote / "abrir_ponto.py").unlink()
    importlib.invalidate_caches()

    codigo, saida = executor.executar("abrir_ponto", {}, tmp_path / "l", args=("x",), pacote=pacote)
    assert codigo == 0
    assert saida == "ok ['x']\n"


def test_escritas_de_outras_threads_ficam_fora_do_log(tmp_path, pacote, capsys):
    """Ex.: avisos de layout que o Toga imprime na thread da interface."""
    _script(tmp_path, pacote, "abrir_ponto", (
        "import sys, threading\n"
        "t = threading.Thread(target=lambda: print('aviso do Toga', file=sys.stderr))\n"
        "t.start(); t.join()\n"
        "print('do script')\n"
    ))
    _, saida = executor.executar("abrir_ponto", {}, tmp_path / "l", pacote=pacote)
    assert saida == "do script\n"
    assert "aviso do Toga" not in (tmp_path / "l").read_text(encoding="utf-8")
    assert "aviso do Toga" in capsys.readouterr().err


def test_mensagem_final():
    assert executor.mensagem_final("Tentativa 1\n✅ entrada registrada\n\n") == "✅ entrada registrada"
    assert executor.mensagem_final("") == ""


def test_cancelar_para_o_script_e_limpa_o_pedido(tmp_path, pacote):
    import threading

    _script(tmp_path, pacote, "abrir_ponto",
            "import os, time\nprint('esperando')\n"
            "while not os.environ.get('PONTO_CANCELAR'):\n    time.sleep(0.05)\n"
            "raise KeyboardInterrupt\n")
    resultado = {}
    comecou = threading.Event()
    thread = threading.Thread(target=lambda: resultado.update(r=executor.executar(
        "abrir_ponto", {}, tmp_path / "ponto.log", lambda texto: comecou.set(), pacote=pacote)))
    thread.start()
    assert comecou.wait(5)
    executor.cancelar()
    thread.join(5)

    assert resultado["r"] == (executor.CANCELADO, "esperando\n⏹️ Cancelado.\n")
    assert executor.VAR_CANCELAR not in os.environ
    assert "saída: 130" in (tmp_path / "ponto.log").read_text(encoding="utf-8")


def test_cabecalho_do_log_nao_repete_a_observacao(tmp_path, pacote):
    _script(tmp_path, pacote, "registrar_pit", "print('obs no script')\n")
    log = tmp_path / "l"
    executor.executar("registrar_pit", {}, log, args=("09/10/2026", "--obs", "Conforme PIT docente."),
                      pacote=pacote)
    texto = log.read_text(encoding="utf-8")
    assert "· registrar_pit 09/10/2026 ===" in texto
    assert "Conforme PIT docente." not in texto
