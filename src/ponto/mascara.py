"""Máscaras dd/mm/aaaa (data do PIT) e hh:mm (tempo do lembrete)."""

import re

_COMPLETA = re.compile(r"\d{1,2}/\d{1,2}/\d{4}")
_HORA_COMPLETA = re.compile(r"\d{1,2}:\d{2}")


def formatar_data(texto, apagando=False):
    """Põe as barras enquanto o usuário digita a data.

    Ao apagar, o texto fica como está, para a barra poder ser apagada.
    Uma data já completa (ex.: colada como 2/10/2026) também fica como está.
    """
    if apagando or _COMPLETA.fullmatch(texto.strip()):
        return texto
    # "2/" vira "02/": quem digita a barra depois de um só dígito quis dizer 0X.
    pedacos = texto.split("/")
    pedacos = [p.zfill(2) if len(p) == 1 else p for p in pedacos[:-1]] + pedacos[-1:]
    digitos = re.sub(r"\D", "", "".join(pedacos))[:8]
    partes = [digitos[:2], digitos[2:4], digitos[4:]]
    resultado = partes[0]
    if len(digitos) >= 2:
        resultado += "/" + partes[1]
    if len(digitos) >= 4:
        resultado += "/" + partes[2]
    return resultado


def formatar_hora(texto, apagando=False):
    """Põe os ":" depois dos dois dígitos da hora enquanto o usuário digita.

    Como em formatar_data: ao apagar, ou com a hora já completa (ex.: 8:00), fica como está.
    """
    if apagando or _HORA_COMPLETA.fullmatch(texto.strip()):
        return texto
    # "8:" vira "08:".
    horas, sep, resto = texto.partition(":")
    if sep and len(horas) == 1:
        texto = "0" + texto
    digitos = re.sub(r"\D", "", texto)[:4]
    return digitos[:2] + (":" + digitos[2:] if len(digitos) >= 2 else "")
