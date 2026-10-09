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

# Os scripts são executados como módulos, não como arquivos: no Android
# (Chaquopy) o código fica dentro do APK e não há um .py no disco.
SCRIPTS_PACOTE = "ponto.scripts"

ACOES = {
    "abrir_ponto": "Abrir ponto",
    "fechar_ponto": "Fechar ponto",
    "registrar_pit": "Registrar PIT",
}

LOG_MAX_LINHAS = 1000

# Os scripts leem os.environ e escrevem em sys.stdout, que são globais do
# processo; por isso só um script roda por vez.
_lock = threading.Lock()

# Código de saída de um script cancelado pelo usuário (como o Ctrl+C no terminal).
CANCELADO = 130
# Variável de ambiente que pede aos scripts para parar (veja esperar() nos scripts):
# eles a conferem antes de cada tentativa e a cada meio segundo das esperas.
VAR_CANCELAR = "PONTO_CANCELAR"


def cancelar():
    """Pede ao script em execução que pare na próxima conferência."""
    os.environ[VAR_CANCELAR] = "1"


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


class _Desvio(io.TextIOBase):
    """Captura só o que a thread do script escreve.

    O resto (por exemplo, avisos de layout que o Toga imprime na thread da
    interface enquanto o script roda) segue para o destino original.
    """

    def __init__(self, saida, original):
        self.saida = saida
        self.original = original
        self.thread = threading.get_ident()

    def writable(self):
        return True

    def write(self, texto):
        if threading.get_ident() == self.thread:
            return self.saida.write(texto)
        if self.original is not None:
            return self.original.write(texto)
        return len(texto)

    def flush(self):
        if self.original is not None:
            self.original.flush()


def _codigo_de_saida(exc):
    if exc.code is None:
        return 0
    if isinstance(exc.code, int):
        return exc.code
    print(exc.code)
    return 1


def _sem_obs(args):
    """Argumentos sem `--obs TEXTO`: o script do PIT já mostra a observação na saída."""
    args = list(args)
    if "--obs" in args:
        i = args.index("--obs")
        del args[i:i + 2]
    return args


def executar(acao, credenciais, log_path, ao_escrever=None, args=(), pacote=SCRIPTS_PACOTE):
    """Roda o script da ação e devolve (código de saída, saída capturada).

    `credenciais` vira variáveis de ambiente durante a execução.
    `args` são os argumentos de linha de comando passados ao script.
    `ao_escrever(texto)` é chamado (na thread do script) a cada escrita.
    """
    if acao not in ACOES:
        raise ValueError(f"Ação desconhecida: {acao!r}")
    modulo = f"{pacote}.{acao}"
    inicio = datetime.now()
    saida = _Saida(ao_escrever)

    with _lock:
        env_antigo = dict(os.environ)
        argv_antigo = sys.argv
        os.environ.update({k: v for k, v in credenciais.items() if v})
        os.environ.pop(VAR_CANCELAR, None)
        sys.argv = [f"{acao}.py", *args]
        try:
            with contextlib.redirect_stdout(_Desvio(saida, sys.stdout)), \
                    contextlib.redirect_stderr(_Desvio(saida, sys.stderr)):
                try:
                    runpy.run_module(modulo, run_name="__main__")
                    codigo = 0
                except SystemExit as exc:
                    codigo = _codigo_de_saida(exc)
                except KeyboardInterrupt:
                    print("⏹️ Cancelado.")
                    codigo = CANCELADO
                except BaseException:
                    traceback.print_exc()
                    codigo = 1
        finally:
            sys.argv = argv_antigo
            os.environ.clear()
            os.environ.update(env_antigo)

    registrar_log(log_path, " ".join([acao, *_sem_obs(args)]), inicio, saida.valor(), codigo)
    return codigo, saida.valor()


def mensagem_final(saida):
    """Última linha não vazia da saída: o resultado que o script anuncia."""
    for linha in reversed(saida.splitlines()):
        if linha.strip():
            return linha.strip()
    return ""


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
