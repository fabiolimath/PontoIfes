import os
import time
import requests
from bs4 import BeautifulSoup

USUARIO = os.getenv("SIGRH_USER")
SENHA = os.getenv("SIGRH_PASS")
TELEGRAM_TOKEN = os.getenv("TELEGRAM_TOKEN")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID")

BASE = "https://sigrh.ifes.edu.br"
CLASSICO_URL = BASE + "/sigrh?modo=classico"
LOGIN_URL = BASE + "/sigrh/login.jsf"
PONTO_URL = BASE + "/sigrh/frequencia/ponto_eletronico/cadastro_ponto_eletronico.jsf"

MAX_TENTATIVAS = 3
ESPERA_ENTRE_TENTATIVAS = 10


# -----------------------------------
# Send notifications to Telegram bot
# -----------------------------------
def enviar_telegram(mensagem):

    url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"

    data = {
        "chat_id": TELEGRAM_CHAT_ID,
        "text": mensagem
    }

    try:
        resp = requests.post(url, data=data, timeout=30)

        # print("Telegram status:", resp.status_code)
        # print(resp.text)

    except Exception as e:
        print("Erro Telegram:", e)


def get_viewstate(html):
    soup = BeautifulSoup(html, "html.parser")
    campo = soup.find("input", {"name": "javax.faces.ViewState"})
    if not campo:
        raise Exception("ViewState não encontrado")
    return campo["value"]


def registrar_entrada():

    session = requests.Session()
    session.headers.update({
    "User-Agent": "Mozilla/5.0",
    "Content-Type": "application/x-www-form-urlencoded",
})

    # -----------------------------------
    # LOGIN
    # -----------------------------------
    resp = session.get(CLASSICO_URL, timeout=30)
    viewstate = get_viewstate(resp.text)


    data = {
        "formLogin": "formLogin",
        "width": "1920",
        "height": "1080",
        "urlRedirect": "",
        "acessibilidade": "",
        "login": USUARIO,
        "senha": SENHA,
        "logar": "Entrar",
        "javax.faces.ViewState": viewstate
    }

    resp = session.post(LOGIN_URL, data=data, timeout=30, headers={
        "Referer": LOGIN_URL,
        "Origin": BASE,
    })

    # Não checar resp.url — ela pode ser login.jsf mesmo com sucesso
    # Checar se o ViewState existe (página do ponto tem ViewState diferente)
    # e se há indicação de login inválido no conteúdo
    soup = BeautifulSoup(resp.text, "html.parser")
    erro = soup.find(string=lambda t: t and ("senha" in t.lower() or "inválid" in t.lower() or "incorret" in t.lower()))
    if erro:
        raise Exception(f"Credenciais rejeitadas: {erro.strip()}")

    # Tenta extrair ViewState — se não tiver, login falhou de outro jeito
    viewstate = get_viewstate(resp.text)

    # -----------------------------------
    # REGISTRAR ENTRADA
    # -----------------------------------
    data = {
        "idFormDadosEntradaSaida": "idFormDadosEntradaSaida",
        "idFormDadosEntradaSaida:observacoes": "",
        "idFormDadosEntradaSaida:idBtnRegistrarEntrada": "Registrar Entrada",
        "javax.faces.ViewState": viewstate
    }

    resp = session.post(PONTO_URL, data=data, timeout=30, headers={
        "Referer": PONTO_URL,
        "Origin": BASE,
    })

    if resp.status_code != 200:
        raise Exception(f"Erro HTTP {resp.status_code}")

    # Verifica se há mensagem de sucesso ou erro na resposta
    if "login.jsf" in resp.url:
        raise Exception("Sessão perdida antes do registro")

    return True


# -----------------------------------
# LOOP DE RETRY
# -----------------------------------

for tentativa in range(1, MAX_TENTATIVAS + 1):
    try:
        registrar_entrada()
        mensagem = "✅🔓🕑 SIGRH: entrada registrada com sucesso"
        print(mensagem)
        enviar_telegram(mensagem)

        break

    except Exception as e:

        print(f"⚠️ Tentativa {tentativa} falhou: {e}")

        if tentativa < MAX_TENTATIVAS:
            print(f"⏳ Tentando novamente em {ESPERA_ENTRE_TENTATIVAS}s...")
            time.sleep(ESPERA_ENTRE_TENTATIVAS)
        else:
            mensagem = "🔓❌ SIGRH: todas as tentativas de entrada falharam"
            print(mensagem)
            enviar_telegram(mensagem)
