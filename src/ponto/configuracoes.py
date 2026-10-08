"""Preferências do app, o registro do último fechamento e o lembrete agendado."""

import json
from datetime import date, datetime, timedelta
from pathlib import Path

PADRAO = {
    "notificacoes": True,
    "lembrete_fechar": True,
    "lembrete_tempo": "01:00",
    "verificar_atualizacoes": True,
    "observacao_pit": "Conforme PIT docente.",
    # Registrar o PIT do dia sozinho ao fechar o ponto: 0 = não, 1 = no 1º
    # fechamento do dia (quem não faz almoço), 2 = no 2º (quem fecha no almoço).
    "pit_automatico": 0,
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
    """Anota que o ponto foi fechado com sucesso no dia (padrão: hoje).

    Devolve quantas vezes o ponto foi fechado nesse dia, contando esta.
    """
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    dia = dia or date.today()
    vezes = fechamentos_no_dia(path, dia) + 1
    path.write_text(f"{dia.isoformat()} {vezes}", encoding="utf-8")
    return vezes


def fechamentos_no_dia(path, dia=None):
    """Quantas vezes o ponto foi fechado com sucesso no dia (padrão: hoje)."""
    path = Path(path)
    if not path.exists():
        return 0
    # "2026-10-06 2"; as versões antigas gravavam só a data (uma vez).
    partes = path.read_text(encoding="utf-8").split()
    if not partes or partes[0] != (dia or date.today()).isoformat():
        return 0
    try:
        return int(partes[1]) if len(partes) > 1 else 1
    except ValueError:
        return 1


def fechou_no_dia(path, dia=None):
    return fechamentos_no_dia(path, dia) > 0


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
