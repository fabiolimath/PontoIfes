"""Abre o ponto no SIGRH (registra a entrada no ponto eletrônico).

Uso: abrir_ponto.py

Lê SIGRH_USER e SIGRH_PASS do ambiente (e TELEGRAM_TOKEN e TELEGRAM_CHAT_ID,
opcionais, para a notificação).

Depois do login, o SIGRH mostra a tela do ponto eletrônico com o botão
"Registrar Entrada". O script envia esse formulário e só considera a entrada
registrada se a resposta trouxer "Operação realizada com sucesso" (ou já
mostrar o botão "Registrar Saída"). Fora da rede do campus a tela do ponto
pode não aparecer ou vir com erro: o script avisa e sai com código 1.
"""

import os
import re
import sys
import time

import requests
from bs4 import BeautifulSoup

BASE = "https://sigrh.ifes.edu.br"
LOGIN_URL = BASE + "/sigrh/login.jsf"
PONTO_URL = BASE + "/sigrh/frequencia/ponto_eletronico/cadastro_ponto_eletronico.jsf"

FORM = "idFormDadosEntradaSaida"
BTN_ENTRADA = FORM + ":idBtnRegistrarEntrada"
BTN_SAIDA = FORM + ":idBtnRegistrarSaida"

DICA_WIFI = "Confira se o celular está conectado à Wi-Fi do campus."
# Palavras que, numa mensagem de erro do SIGRH, indicam restrição de rede.
SINAIS_DE_REDE = ("rede", " ip", "endereço", "local", "computador", "máquina", "permitid", "autorizad")

TIMEOUT = 30
TENTATIVAS = 3
ESPERA = 10


class Recusado(Exception):
    """O SIGRH recusou o pedido (senha errada, fora da rede...): não adianta repetir."""


class EnvioIncerto(Exception):
    """Falhou depois de enviar o formulário: não se sabe se a entrada ficou registrada."""


class JaAberto(Exception):
    """O ponto já estava aberto: nada a fazer."""


# -----------------------------------
# LEITURA DAS PÁGINAS
# -----------------------------------
def sopa(resp):
    return BeautifulSoup(resp.content, "html.parser")


def viewstate(pagina):
    campo = pagina.find("input", {"name": "javax.faces.ViewState"})
    if not campo:
        raise Exception("ViewState não encontrado na página")
    return campo["value"]


def mensagens(pagina, classe):
    """Textos das listas de aviso do SIGRH (<ul class="info|erros|warning">)."""
    return [li.get_text(" ", strip=True) for ul in pagina.find_all("ul", class_=classe)
            for li in ul.find_all("li")]


def erros(pagina):
    return mensagens(pagina, "erros") + mensagens(pagina, "warning")


def com_dica(textos):
    """Junta as mensagens de erro e acrescenta a dica da Wi-Fi se falarem de rede."""
    texto = "; ".join(textos)
    if any(sinal in f" {texto.lower()}" for sinal in SINAIS_DE_REDE):
        texto += ". " + DICA_WIFI
    return texto


def formulario_ponto(pagina):
    return pagina.find("form", id=FORM)


def tem_botao(pagina, nome):
    form = formulario_ponto(pagina)
    return bool(form and form.find("input", {"name": nome}))


def horarios(pagina):
    """(hora de entrada, saída prevista) mostradas na tela do ponto, "" se faltarem."""
    entrada = prevista = ""
    form = formulario_ponto(pagina)
    if form:
        for th in form.find_all("th"):
            if th.get_text(strip=True).lower().startswith("hora de entrada"):
                td = th.find_next_sibling("td")
                entrada = td.get_text(strip=True) if td else ""
        span = form.find(id=FORM + ":horaSaidaPrevista")
        prevista = span.get_text(strip=True) if span else ""
    return entrada, prevista


def descrever(pagina):
    entrada, prevista = horarios(pagina)
    texto = f"às {entrada}" if entrada else ""
    if prevista:
        texto += f" (saída prevista: {prevista})"
    return texto.strip()


# -----------------------------------
# PASSOS NO SIGRH
# -----------------------------------
def entrar(sessao, usuario, senha):
    """Faz o login e devolve a página seguinte (normalmente a tela do ponto)."""
    pagina = sopa(sessao.get(LOGIN_URL, timeout=TIMEOUT))
    resp = sessao.post(LOGIN_URL, data={
        "formLogin": "formLogin",
        "width": "1920",
        "height": "1080",
        "urlRedirect": "",
        "acessibilidade": "",
        "login": usuario,
        "senha": senha,
        "logar": "Entrar",
        "javax.faces.ViewState": viewstate(pagina),
    }, timeout=TIMEOUT)
    pagina = sopa(resp)
    if pagina.find("form", id="formLogin"):
        raise Recusado("Login recusado: " + "; ".join(erros(pagina) or ["usuário ou senha inválidos"]))
    return pagina


def tela_do_ponto(sessao, pagina):
    """Garante que estamos na tela do ponto; se o login levou a outro lugar, abre-a."""
    if formulario_ponto(pagina):
        return pagina
    pagina = sopa(sessao.get(PONTO_URL, timeout=TIMEOUT))
    if formulario_ponto(pagina):
        return pagina
    problemas = erros(pagina)
    if problemas:
        raise Recusado(com_dica(problemas))
    # Sem formulário e sem mensagem: pode ser a rede (ou a Wi-Fi ainda conectando),
    # então vale tentar de novo.
    raise Exception("A tela do ponto eletrônico não apareceu. " + DICA_WIFI)


def registrar_entrada(sessao, pagina):
    """Clica em "Registrar Entrada" e confere a resposta; devolve a página final."""
    if tem_botao(pagina, BTN_SAIDA):
        raise JaAberto(descrever(pagina))
    if not tem_botao(pagina, BTN_ENTRADA):
        problemas = erros(pagina)
        raise Recusado(com_dica(problemas) if problemas
                       else "O botão Registrar Entrada não está disponível. " + DICA_WIFI)

    try:
        resp = sessao.post(PONTO_URL, data={
            FORM: FORM,
            FORM + ":observacoes": "",
            BTN_ENTRADA: "Registrar Entrada",
            "javax.faces.ViewState": viewstate(pagina),
        }, timeout=TIMEOUT)
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise EnvioIncerto(f"falha ao enviar o registro ({exc}); confira no SIGRH") from exc

    pagina = sopa(resp)
    problemas = erros(pagina)
    if problemas:
        raise Recusado(com_dica(problemas))
    sucesso = any("sucesso" in texto.lower() for texto in mensagens(pagina, "info"))
    if not sucesso and not tem_botao(pagina, BTN_SAIDA):
        raise EnvioIncerto("o SIGRH não confirmou a entrada; confira no SIGRH")
    return pagina


def abrir(usuario, senha):
    sessao = requests.Session()
    sessao.headers["User-Agent"] = "Mozilla/5.0"
    pagina = tela_do_ponto(sessao, entrar(sessao, usuario, senha))
    return registrar_entrada(sessao, pagina)


# -----------------------------------
# NOTIFICAÇÃO E EXECUÇÃO
# -----------------------------------
def enviar_telegram(mensagem):
    token, chat = os.getenv("TELEGRAM_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
    if not token or not chat:
        return
    try:
        requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                      data={"chat_id": chat, "text": mensagem}, timeout=TIMEOUT)
    except Exception as exc:
        print("Erro ao enviar ao Telegram:", exc)


def avisar(mensagem):
    print(mensagem)
    enviar_telegram(mensagem)


def main(argv=None):
    usuario, senha = os.getenv("SIGRH_USER"), os.getenv("SIGRH_PASS")
    if not usuario or not senha:
        print("SIGRH_USER e SIGRH_PASS não definidos.")
        return 1

    for tentativa in range(1, TENTATIVAS + 1):
        try:
            pagina = abrir(usuario, senha)
        except JaAberto as exc:
            avisar(re.sub(r"\s+", " ", f"🔓ℹ️ SIGRH: o ponto já estava aberto {exc}").strip())
            return 0
        except (Recusado, EnvioIncerto) as exc:
            erro = exc
            break
        except Exception as exc:
            erro = exc
            print(f"Tentativa {tentativa} falhou: {exc}")
            if tentativa < TENTATIVAS:
                print(f"Tentando de novo em {ESPERA}s...")
                time.sleep(ESPERA)
        else:
            avisar(re.sub(r"\s+", " ", f"✅🔓🕑 SIGRH: entrada registrada {descrever(pagina)}").strip())
            return 0

    avisar(f"🔓❌ SIGRH: entrada não registrada: {erro}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
