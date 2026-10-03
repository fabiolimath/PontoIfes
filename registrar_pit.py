import os
import re
import sys
import time
import requests
from datetime import date, datetime
from bs4 import BeautifulSoup

USUARIO = os.getenv("SIGRH_USER")
SENHA = os.getenv("SIGRH_PASS")
TELEGRAM_TOKEN = os.getenv("TELEGRAM_TOKEN")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID")

BASE = "https://sigrh.ifes.edu.br"
LOGIN_URL = BASE + "/sigrh/login.jsf"
PONTO_URL = BASE + "/sigrh/frequencia/ponto_eletronico/cadastro_ponto_eletronico.jsf"
MENU_URL = BASE + "/sigrh/servidor/portal/servidor.jsf"
AUSENCIA_URL = BASE + "/sigrh/dap/ausencia/form.jsf"

AUSENCIA_PIT_ID = "600337"

MAX_TENTATIVAS = 3
ESPERA_ENTRE_TENTATIVAS = 10


def enviar_telegram(mensagem):
    url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"
    try:
        requests.post(url, data={"chat_id": TELEGRAM_CHAT_ID, "text": mensagem}, timeout=30)
    except Exception as e:
        print("Erro Telegram:", e)


def get_viewstate(html):
    soup = BeautifulSoup(html, "html.parser")
    campo = soup.find("input", {"name": "javax.faces.ViewState"})
    if not campo:
        raise Exception("ViewState não encontrado")
    return campo["value"]


def extrair_horas(xml_text):
    m = re.search(
        r'name=["\']cadastroAusencia:horasAusente["\'][^>]*value=["\']([^"\'>]+)["\']',
        xml_text
    )
    if not m:
        raise Exception(
            "Não foi possível extrair horasAusente da resposta AJAX. "
            f"Resposta (primeiros 500 chars): {xml_text[:500]}"
        )
    return m.group(1).strip()


def registrar_pit():
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0"})

    # --- 1. LOGIN ---
    # GET modo=classico redireciona para login.jsf e gera o ViewState correto.
    # Não fazer GET separado de login.jsf — quebraria a continuidade da sessão JSF.
    resp = session.get(BASE + "/sigrh?modo=classico", timeout=30)
    viewstate = get_viewstate(resp.text)

    # POST do login
    resp = session.post(LOGIN_URL, data={
        "formLogin": "formLogin",
        "width": "1920",
        "height": "1080",
        "urlRedirect": "",
        "acessibilidade": "",
        "login": USUARIO,
        "senha": SENHA,
        "logar": "Entrar",
        "javax.faces.ViewState": viewstate,
    }, timeout=30, headers={"Referer": LOGIN_URL, "Origin": BASE,
                            "Content-Type": "application/x-www-form-urlencoded"})

    soup = BeautifulSoup(resp.text, "html.parser")
    erro = soup.find(string=lambda t: t and (
        "senha" in t.lower() or "inválid" in t.lower() or "incorret" in t.lower()
    ))
    if erro:
        raise Exception(f"Credenciais rejeitadas: {erro.strip()}")

    # --- 2. TELA DO PONTO: "Continuar Acessando o Sistema" ---
    # O ViewState do ponto vem da resposta do POST do login (não de um GET separado).
    # Se não encontrar na resposta atual, faz GET do ponto como fallback.
    try:
        viewstate = get_viewstate(resp.text)
        ponto_html = resp.text
    except Exception:
        resp = session.get(PONTO_URL, timeout=30)
        viewstate = get_viewstate(resp.text)
        ponto_html = resp.text

    # Verifica se estamos de fato na tela do ponto (contém o botão Continuar)
    if "idBtnContinuar" not in ponto_html:
        # Já caiu direto no portal — vai para o menu
        resp = session.get(MENU_URL, timeout=30)
    else:
        resp = session.post(PONTO_URL, data={
            "idFormDadosEntradaSaida": "idFormDadosEntradaSaida",
            "idFormDadosEntradaSaida:observacoes": "",
            "idFormDadosEntradaSaida:idBtnContinuar": "Continuar Acessando o Sistema >>",
            "javax.faces.ViewState": viewstate,
        }, timeout=30, headers={"Referer": PONTO_URL, "Origin": BASE,
                                "Content-Type": "application/x-www-form-urlencoded"})

        # Segue redirects 302 → entrarPortalServidor → servidor.jsf
        if "servidor.jsf" not in resp.url:
            resp = session.get(MENU_URL, timeout=30)

    # --- 3. MENU: Solicitações > Ausências > Informar Ausência ---
    viewstate = get_viewstate(resp.text)

    resp = session.post(MENU_URL, data={
        "menu:FormMenuServidor": "menu:FormMenuServidor",
        "menu:InformarAusencia": "menu:InformarAusencia",
        "javax.faces.ViewState": viewstate,
    }, timeout=30, headers={"Referer": MENU_URL, "Origin": BASE,
                            "Content-Type": "application/x-www-form-urlencoded"})

    # --- 4. FORMULÁRIO DE AUSÊNCIA ---
    resp = session.get(AUSENCIA_URL, timeout=30)
    viewstate = get_viewstate(resp.text)

    if len(sys.argv) > 1:
        try:
            hoje = datetime.strptime(sys.argv[1], "%d/%m/%Y").strftime("%d/%m/%Y")
            print(f"ℹ️  Usando data do argumento: {hoje}")
        except ValueError:
            raise Exception(f"Data inválida: {sys.argv[1]!r}. Use o formato DD/MM/AAAA.")
    else:
        hoje = date.today().strftime("%d/%m/%Y")

    # 4a. Selecionar tipo de ausência
    session.post(AUSENCIA_URL, data={
        "AJAXREQUEST": "cadastroAusencia:j_id_jsp_837310543_487",
        "cadastroAusencia": "cadastroAusencia",
        "cadastroAusencia:idAusencia": "0",
        "confirmButton": "Cadastrar",
        "cadastroAusencia:ausencia": AUSENCIA_PIT_ID,
        "cadastroAusencia:DataInicio": "",
        "cadastroAusencia:DataTermino": "",
        "cadastroAusencia:observacao": "",
        "cadastroAusencia:tipoDocumento": "104",
        "cadastroAusencia:arquivo": "",
        "javax.faces.ViewState": viewstate,
        "cadastroAusencia:j_id_jsp_837310543_491": "cadastroAusencia:j_id_jsp_837310543_491",
    }, timeout=30, headers={"Referer": AUSENCIA_URL, "Origin": BASE,
                            "Content-Type": "application/x-www-form-urlencoded",
                            "X-Requested-With": "XMLHttpRequest"})

    # 4b. Preencher data de início — primeira POST (servidor retorna 00:00)
    session.post(AUSENCIA_URL, data={
        "AJAXREQUEST": "j_id_jsp_837310543_0",
        "cadastroAusencia": "cadastroAusencia",
        "cadastroAusencia:idAusencia": "0",
        "confirmButton": "Cadastrar",
        "cadastroAusencia:ausencia": AUSENCIA_PIT_ID,
        "cadastroAusencia:DataInicio": hoje,
        "cadastroAusencia:horasAusente": "",
        "cadastroAusencia:observacao": "",
        "cadastroAusencia:tipoDocumento": "104",
        "cadastroAusencia:arquivo": "",
        "javax.faces.ViewState": viewstate,
        "cadastroAusencia:j_id_jsp_837310543_431": "cadastroAusencia:j_id_jsp_837310543_431",
    }, timeout=30, headers={"Referer": AUSENCIA_URL, "Origin": BASE,
                            "Content-Type": "application/x-www-form-urlencoded",
                            "X-Requested-With": "XMLHttpRequest"})

    # 4c. Segunda POST — servidor recalcula e retorna o horasAusente correto
    resp_ajax = session.post(AUSENCIA_URL, data={
        "AJAXREQUEST": "cadastroAusencia:j_id_jsp_837310543_528",
        "cadastroAusencia": "cadastroAusencia",
        "cadastroAusencia:idAusencia": "0",
        "confirmButton": "Cadastrar",
        "cadastroAusencia:ausencia": AUSENCIA_PIT_ID,
        "cadastroAusencia:DataInicio": hoje,
        "cadastroAusencia:horasAusente": "",
        "cadastroAusencia:observacao": "",
        "cadastroAusencia:tipoDocumento": "104",
        "cadastroAusencia:arquivo": "",
        "javax.faces.ViewState": viewstate,
        "cadastroAusencia:j_id_jsp_837310543_530": "cadastroAusencia:j_id_jsp_837310543_530",
    }, timeout=30, headers={"Referer": AUSENCIA_URL, "Origin": BASE,
                            "Content-Type": "application/x-www-form-urlencoded",
                            "X-Requested-With": "XMLHttpRequest"})

    horas_ausente = extrair_horas(resp_ajax.text)
    print(f"ℹ️  horasAusente calculado pelo servidor: {horas_ausente}")

    # 4d. AJAX final com horas e observação preenchidos
    session.post(AUSENCIA_URL, data={
        "AJAXREQUEST": "cadastroAusencia:j_id_jsp_837310543_582",
        "cadastroAusencia": "cadastroAusencia",
        "cadastroAusencia:idAusencia": "0",
        "confirmButton": "Cadastrar",
        "cadastroAusencia:ausencia": AUSENCIA_PIT_ID,
        "cadastroAusencia:DataInicio": hoje,
        "cadastroAusencia:horasAusente": horas_ausente,
        "cadastroAusencia:observacao": ".",
        "cadastroAusencia:tipoDocumento": "104",
        "cadastroAusencia:arquivo": "",
        "javax.faces.ViewState": viewstate,
        "cadastroAusencia:j_id_jsp_837310543_584": "cadastroAusencia:j_id_jsp_837310543_584",
    }, timeout=30, headers={"Referer": AUSENCIA_URL, "Origin": BASE,
                            "Content-Type": "application/x-www-form-urlencoded",
                            "X-Requested-With": "XMLHttpRequest"})

    # --- 5. CADASTRAR (POST multipart final) ---
    resp = session.post(AUSENCIA_URL, files={
        "cadastroAusencia": (None, "cadastroAusencia"),
        "cadastroAusencia:idAusencia": (None, "0"),
        "confirmButton": (None, "Cadastrar"),
        "cadastroAusencia:ausencia": (None, AUSENCIA_PIT_ID),
        "cadastroAusencia:DataInicio": (None, hoje),
        "cadastroAusencia:horasAusente": (None, horas_ausente),
        "cadastroAusencia:observacao": (None, "."),
        "cadastroAusencia:arquivo": ("", b"", "application/octet-stream"),
        "cadastroAusencia:cadastrarAusencia": (None, "Cadastrar"),
        "javax.faces.ViewState": (None, viewstate),
    }, timeout=60, headers={"Referer": AUSENCIA_URL, "Origin": BASE})

    if resp.status_code != 200:
        raise Exception(f"Erro HTTP {resp.status_code} no cadastro final")

    soup = BeautifulSoup(resp.text, "html.parser")
    texto = soup.get_text(separator=" ", strip=True).lower()

    if "sucesso" in texto or "submetida" in texto or "homologa" in texto:
        return True

    erro = soup.find(string=lambda t: t and (
        "erro" in t.lower() or "falha" in t.lower() or "inválid" in t.lower()
    ))
    if erro:
        raise Exception(f"Erro no cadastro: {erro.strip()}")

    return True


# -----------------------------------
# LOOP DE RETRY
# -----------------------------------
for tentativa in range(1, MAX_TENTATIVAS + 1):
    try:
        registrar_pit()
        mensagem = "📋✅ SIGRH: PIT registrado com sucesso"
        print(mensagem)
        enviar_telegram(mensagem)
        break

    except Exception as e:
        print(f"⚠️ Tentativa {tentativa} falhou: {e}")

        if tentativa < MAX_TENTATIVAS:
            print(f"⏳ Tentando novamente em {ESPERA_ENTRE_TENTATIVAS}s...")
            time.sleep(ESPERA_ENTRE_TENTATIVAS)
        else:
            mensagem = "📋❌ SIGRH: todas as tentativas de registro do PIT falharam"
            print(mensagem)
            enviar_telegram(mensagem)
