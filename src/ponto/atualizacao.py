"""Verifica se há uma versão mais nova do app publicada no GitHub."""

import re

import requests

REPO = "fabiolimath/PontoIfes"
API_ULTIMA = f"https://api.github.com/repos/{REPO}/releases/latest"
URL_DOWNLOAD = f"https://github.com/{REPO}/releases/latest/download/ponto-ifes.apk"


def _numeros(versao):
    """"v1.0.10" -> (1, 0, 10); partes não numéricas são ignoradas."""
    return tuple(int(n) for n in re.findall(r"\d+", versao or ""))


def mais_nova(publicada, instalada):
    return _numeros(publicada) > _numeros(instalada)


def ultima_versao(timeout=10):
    """Versão do último release publicado (ex.: "1.0.6"). Levanta exceção se falhar."""
    resp = requests.get(API_ULTIMA, timeout=timeout,
                        headers={"Accept": "application/vnd.github+json"})
    resp.raise_for_status()
    return resp.json()["tag_name"].lstrip("vV")
