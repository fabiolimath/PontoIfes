"""Registra a saída (fecha o ponto) no SIGRH.

Uso: fechar_ponto.py

Lê SIGRH_USER e SIGRH_PASS do ambiente (e TELEGRAM_TOKEN e TELEGRAM_CHAT_ID,
opcionais, para a notificação).

Passos, como o navegador faz: login, tela do ponto eletrônico (direto após o
login ou pelo link do portal) e o botão "Registrar Saída". O sucesso é a
mensagem "Operação realizada com sucesso!" (<ul class="info">); sem entrada
aberta, a tela só oferece "Registrar Entrada" e não há o que fechar. Fora da
rede do campus, o SIGRH nega a tela do ponto pelo IP; o script avisa e para.
"""

import os
import sys
import time
from urllib.parse import urlencode

import requests
from bs4 import BeautifulSoup

BASE = "https://sigrh.ifes.edu.br"
LOGIN_URL = BASE + "/sigrh/login.jsf"
PONTO_URL = BASE + "/sigrh/frequencia/ponto_eletronico/cadastro_ponto_eletronico.jsf"
PORTAL_URL = BASE + "/sigrh/servidor/portal/servidor.jsf"

FORM_PONTO = "idFormDadosEntradaSaida"
BTN_SAIDA = FORM_PONTO + ":idBtnRegistrarSaida"
BTN_ENTRADA = FORM_PONTO + ":idBtnRegistrarEntrada"
# Link "Ponto Eletrônico" do portal; usados se não forem achados na página.
FORM_PAINEL = "painelAcessoDadosServidor"
LINK_PONTO = FORM_PAINEL + ":linkPontoEletronicoAntigo"

# Trecho da mensagem do SIGRH quando o acesso não vem da rede do campus.
BLOQUEIO_IP = "não tem autorização para registrar o Ponto"

TIMEOUT = 30
TENTATIVAS = 3
ESPERA = 10


class Recusado(Exception):
    """O SIGRH recusou (senha errada, sem entrada aberta...): não adianta repetir."""


class EnvioIncerto(Exception):
    """Falhou depois de clicar em Registrar Saída: repetir poderia confundir o registro."""


# -----------------------------------
# LEITURA DAS PÁGINAS
# -----------------------------------
def sopa(resp):
    return BeautifulSoup(resp.content, "html.parser")


def viewstate(pagina, form=None):
    raiz = pagina.find("form", id=form) if form else pagina
    campo = (raiz or pagina).find("input", {"name": "javax.faces.ViewState"})
    if not campo:
        raise Exception("ViewState não encontrado na página")
    return campo["value"]


def mensagens(pagina, classe):
    """Textos das listas de aviso do SIGRH (<ul class="info|erros|warning">)."""
    return [li.get_text(" ", strip=True) for ul in pagina.find_all("ul", class_=classe)
            for li in ul.find_all("li")]


def botao(pagina, nome):
    return pagina.find("input", {"name": nome}) is not None


def hora_de_entrada(pagina):
    """Hora de Entrada mostrada na tela do ponto ("" se não houver)."""
    for th in pagina.find_all("th"):
        if th.get_text(strip=True).startswith("Hora de Entrada"):
            td = th.find_next_sibling("td")
            return td.get_text(strip=True) if td else ""
    return ""


def link_do_ponto(portal):
    """(form, link) do "Ponto Eletrônico" no portal do servidor, ou None."""
    for a in portal.find_all("a", id=True):
        if "linkPontoEletronico" in a["id"]:
            form = a.find_parent("form")
            if form and form.get("id"):
                return form["id"], a["id"]
    return None


def recusa(pagina):
    """Levanta Recusado se o SIGRH negou a tela do ponto (ex.: IP fora do campus)."""
    erros = [" ".join(e.split()) for e in mensagens(pagina, "erros")]
    if not erros and pagina.find("form", id=FORM_PONTO) is None:
        texto = " ".join(pagina.get_text(" ").split())
        if BLOQUEIO_IP in texto:
            erros = [frase for frase in texto.split(".") if BLOQUEIO_IP in frase][:1]
    if not erros:
        return
    mensagem = "; ".join(e.strip() for e in erros)
    if any(BLOQUEIO_IP in e for e in erros):
        mensagem += " (o celular está fora da rede do campus?)"
    raise Recusado("O SIGRH negou o acesso ao ponto: " + mensagem)


# -----------------------------------
# PASSOS NO SIGRH
# -----------------------------------
def entrar(sessao, usuario, senha):
    """Faz o login e devolve a página seguinte (tela do ponto ou portal)."""
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
        erros = mensagens(pagina, "erros") or ["usuário ou senha inválidos"]
        raise Recusado("Login recusado: " + "; ".join(erros))
    return pagina


def abrir_ponto_eletronico(sessao, pagina):
    """Tela do ponto: a própria página do login ou o link do portal."""
    if pagina.find("form", id=FORM_PONTO):
        return pagina
    if not link_do_ponto(pagina):
        pagina = sopa(sessao.get(PORTAL_URL, timeout=TIMEOUT))
    form, link = link_do_ponto(pagina) or (FORM_PAINEL, LINK_PONTO)
    resp = sessao.post(PORTAL_URL, data={
        form: form,
        "javax.faces.ViewState": viewstate(pagina, form),
        link: link,
    }, timeout=TIMEOUT)
    pagina = sopa(resp)
    recusa(pagina)
    if not pagina.find("form", id=FORM_PONTO):
        pagina = sopa(sessao.get(PONTO_URL, timeout=TIMEOUT))
        recusa(pagina)
    if not pagina.find("form", id=FORM_PONTO):
        raise Exception("A tela do ponto eletrônico não abriu")
    return pagina


def registrar_saida(sessao, pagina):
    """Clica em Registrar Saída e confere a resposta."""
    if not botao(pagina, BTN_SAIDA):
        if botao(pagina, BTN_ENTRADA):
            raise Recusado("não há entrada aberta no SIGRH (o ponto já foi fechado "
                           "ou não foi aberto hoje): nada a fechar")
        raise Exception("O botão Registrar Saída não apareceu na tela do ponto")

    entrada = hora_de_entrada(pagina)
    if entrada:
        print(f"Hora de entrada: {entrada}")

    # O formulário declara accept-charset ISO-8859-1 ("Registrar Sa%EDda" na captura).
    corpo = urlencode({
        FORM_PONTO: FORM_PONTO,
        FORM_PONTO + ":observacoes": "",
        BTN_SAIDA: "Registrar Saída",
        "javax.faces.ViewState": viewstate(pagina, FORM_PONTO),
    }, encoding="latin-1")
    try:
        resp = sessao.post(PONTO_URL, data=corpo, timeout=60, headers={
            "Content-Type": "application/x-www-form-urlencoded"})
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise EnvioIncerto(f"falha ao registrar a saída ({exc}); confira no SIGRH") from exc

    pagina = sopa(resp)
    erros = mensagens(pagina, "erros") + mensagens(pagina, "warning")
    if erros:
        raise Recusado("; ".join(erros))
    info = mensagens(pagina, "info")
    if not any("sucesso" in texto.lower() for texto in info):
        raise EnvioIncerto("o SIGRH não confirmou a saída; confira no SIGRH")
    return info


def fechar(usuario, senha):
    sessao = requests.Session()
    sessao.headers["User-Agent"] = "Mozilla/5.0"
    pagina = entrar(sessao, usuario, senha)
    pagina = abrir_ponto_eletronico(sessao, pagina)
    return registrar_saida(sessao, pagina)


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


def main():
    usuario, senha = os.getenv("SIGRH_USER"), os.getenv("SIGRH_PASS")
    if not usuario or not senha:
        print("SIGRH_USER e SIGRH_PASS não definidos.")
        return 1

    for tentativa in range(1, TENTATIVAS + 1):
        try:
            for texto in fechar(usuario, senha):
                print(texto)
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
            mensagem = f"✅🔐📌 SIGRH: saída registrada às {time.strftime('%H:%M')}"
            print(mensagem)
            enviar_telegram(mensagem)
            return 0

    mensagem = f"🔐❌ SIGRH: saída não registrada: {erro}"
    print(mensagem)
    enviar_telegram(mensagem)
    return 1


if __name__ == "__main__":
    sys.exit(main())
