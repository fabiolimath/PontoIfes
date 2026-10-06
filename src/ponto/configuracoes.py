"""Preferências do app, o registro do último fechamento e o lembrete agendado."""

import json
from datetime import date, datetime, timedelta
from pathlib import Path

PADRAO = {
    "notificacoes": True,
    "lembrete_fechar": True,
    "lembrete_tempo": "01:40",
    "verificar_atualizacoes": True,
    "observacao_pit": "Conforme PIT docente.",
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


def tempo_lembrete(texto):
    """"hh:mm" (ex.: "01:40", "8:00") como timedelta; None se inválido ou zero."""
    try:
        horas, minutos = (int(parte) for parte in texto.strip().split(":"))
    except (AttributeError, ValueError):
        return None
    if horas < 0 or not 0 <= minutos < 60 or (horas == 0 and minutos == 0) or horas > 23:
        return None
    return timedelta(hours=horas, minutes=minutos)


def registrar_lembrete(path, quando):
    """Anota o horário do lembrete agendado."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(quando.isoformat(timespec="seconds"), encoding="utf-8")


def lembrete_pendente(path, agora=None):
    """Horário do lembrete agendado, se ainda não passou; senão None."""
    path = Path(path)
    if not path.exists():
        return None
    try:
        quando = datetime.fromisoformat(path.read_text(encoding="utf-8").strip())
    except ValueError:
        return None
    return quando if quando > (agora or datetime.now()) else None


def apagar_lembrete(path):
    Path(path).unlink(missing_ok=True)
