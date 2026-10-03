"""Executa os scripts do ponto e registra a saída no log."""

import contextlib
import io
import os
import runpy
import sys
import threading
import traceback
from datetime import datetime
from pathlib import Path

SCRIPTS_DIR = Path(__file__).parent / "scripts"

ACOES = {
    "abrir_ponto": "Abrir ponto",
    "fechar_ponto": "Fechar ponto",
    "registrar_pit": "Registrar PIT",
}

LOG_MAX_LINHAS = 1000

# Os scripts leem os.environ e escrevem em sys.stdout, que são globais do
# processo; por isso só um script roda por vez.
_lock = threading.Lock()


class _Saida(io.TextIOBase):
    """Acumula o texto escrito e repassa cada pedaço a um callback."""

    def __init__(self, ao_escrever=None):
        self.partes = []
        self.ao_escrever = ao_escrever

    def writable(self):
        return True

    def write(self, texto):
        self.partes.append(texto)
        if self.ao_escrever:
            self.ao_escrever(texto)
        return len(texto)

    def valor(self):
        return "".join(self.partes)


def _codigo_de_saida(exc):
    if exc.code is None:
        return 0
    if isinstance(exc.code, int):
        return exc.code
    print(exc.code)
    return 1


def executar(acao, credenciais, log_path, ao_escrever=None, scripts_dir=SCRIPTS_DIR):
    """Roda o script da ação e devolve (código de saída, saída capturada).

    `credenciais` vira variáveis de ambiente durante a execução.
    `ao_escrever(texto)` é chamado (na thread do script) a cada escrita.
    """
    if acao not in ACOES:
        raise ValueError(f"Ação desconhecida: {acao!r}")
    script = Path(scripts_dir) / f"{acao}.py"
    inicio = datetime.now()
    saida = _Saida(ao_escrever)

    with _lock:
        env_antigo = dict(os.environ)
        argv_antigo = sys.argv
        os.environ.update({k: v for k, v in credenciais.items() if v})
        sys.argv = [str(script)]
        try:
            with contextlib.redirect_stdout(saida), contextlib.redirect_stderr(saida):
                try:
                    runpy.run_path(str(script), run_name="__main__")
                    codigo = 0
                except SystemExit as exc:
                    codigo = _codigo_de_saida(exc)
                except BaseException:
                    traceback.print_exc()
                    codigo = 1
        finally:
            sys.argv = argv_antigo
            os.environ.clear()
            os.environ.update(env_antigo)

    registrar_log(log_path, acao, inicio, saida.valor(), codigo)
    return codigo, saida.valor()


def registrar_log(log_path, acao, inicio, saida, codigo):
    """Acrescenta uma execução ao log, mantendo só as últimas LOG_MAX_LINHAS."""
    log_path = Path(log_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    bloco = f"=== {inicio:%Y-%m-%d %H:%M:%S} · {acao} ===\n{saida}"
    if not bloco.endswith("\n"):
        bloco += "\n"
    bloco += f"saída: {codigo}\n"

    anterior = log_path.read_text(encoding="utf-8") if log_path.exists() else ""
    linhas = (anterior + bloco).splitlines(keepends=True)
    log_path.write_text("".join(linhas[-LOG_MAX_LINHAS:]), encoding="utf-8")
