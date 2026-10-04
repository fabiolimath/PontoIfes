"""Máscara dd/mm/aaaa para o campo de data do PIT."""

import re

_COMPLETA = re.compile(r"\d{1,2}/\d{1,2}/\d{4}")


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
