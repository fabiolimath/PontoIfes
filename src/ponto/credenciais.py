"""Credenciais guardadas na área privada do app."""

import json
import os
from pathlib import Path

CAMPOS = {
    "SIGRH_USER": "Usuário do SIGRH",
    "SIGRH_PASS": "Senha do SIGRH",
}

OBRIGATORIOS = ("SIGRH_USER", "SIGRH_PASS")


def carregar(path):
    """Devolve o dicionário salvo, ou None se ainda não houver credenciais."""
    path = Path(path)
    if not path.exists():
        return None
    dados = json.loads(path.read_text(encoding="utf-8"))
    return {campo: dados.get(campo, "") for campo in CAMPOS}


def ambiente(credenciais):
    """Variáveis de ambiente que os scripts leem."""
    return dict(credenciais)


def faltando(credenciais):
    """Rótulos dos campos obrigatórios em branco."""
    return [CAMPOS[c] for c in OBRIGATORIOS if not (credenciais or {}).get(c)]


def salvar(path, credenciais):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    dados = {campo: credenciais.get(campo, "").strip() for campo in CAMPOS}
    # Cria já com permissão 600 para a senha nunca ficar legível por outros.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as arq:
        json.dump(dados, arq)
    os.chmod(path, 0o600)
