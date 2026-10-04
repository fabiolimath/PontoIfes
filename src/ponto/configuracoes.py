"""Preferências do app e o registro do último fechamento do ponto."""

import json
from datetime import date
from pathlib import Path

PADRAO = {
    "verificar_atualizacoes": True,
}


def carregar(path):
    """Preferências salvas, completadas com os valores padrão."""
    path = Path(path)
    dados = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    return {chave: dados.get(chave, valor) for chave, valor in PADRAO.items()}


def salvar(path, preferencias):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    dados = {chave: preferencias.get(chave, valor) for chave, valor in PADRAO.items()}
    path.write_text(json.dumps(dados), encoding="utf-8")


def registrar_fechamento(path, dia=None):
    """Anota que o ponto foi fechado com sucesso no dia (padrão: hoje)."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text((dia or date.today()).isoformat(), encoding="utf-8")


def fechou_no_dia(path, dia=None):
    path = Path(path)
    if not path.exists():
        return False
    return path.read_text(encoding="utf-8").strip() == (dia or date.today()).isoformat()
