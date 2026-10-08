"""Registra o PIT no SIGRH (Solicitações > Ausências > Informar Ausência).

Uso: registrar_pit.py [dd/mm/aaaa] [--obs TEXTO]

Sem data, registra o dia de hoje. Lê SIGRH_USER e SIGRH_PASS do ambiente.

O SIGRH é JSF + RichFaces: o estado do formulário fica no servidor, então o
script repete as mesmas requisições AJAX que o navegador faz ao escolher o
tipo e a data, e só então envia o formulário.
"""

import argparse
import os
import re
import sys
import time
from datetime import date, datetime

import requests
from bs4 import BeautifulSoup

BASE = "https://sigrh.ifes.edu.br"
LOGIN_URL = BASE + "/sigrh/login.jsf"
PONTO_URL = BASE + "/sigrh/frequencia/ponto_eletronico/cadastro_ponto_eletronico.jsf"
PORTAL_URL = BASE + "/sigrh/servidor/portal/servidor.jsf"
AUSENCIA_URL = BASE + "/sigrh/dap/ausencia/form.jsf"

OBSERVACAO_PADRAO = "Conforme PIT docente."
# Valor da opção "REGISTRO DO PIT - DOCENTE"; usado se a opção não for achada pelo texto.
TIPO_PIT_PADRAO = "600337"
# O navegador manda o combo "Tipo de documento" (desabilitado) com este valor nas chamadas AJAX.
TIPO_DOCUMENTO = "104"
ZERO = ("00:00", "0:00")

TIMEOUT = 30
# Espera antes de cada nova tentativa: cresce para aguentar a rede sumir por até
# uns 2 minutos (troca de Wi-Fi ao chegar no campus, DNS ainda sem resposta).
ESPERAS = (10, 20, 30, 60)
TENTATIVAS = len(ESPERAS) + 1
DIAS = ["segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo"]


def conferir_cancelamento():
    """O botão Cancelar do app define PONTO_CANCELAR; aqui o script para."""
    if os.environ.get("PONTO_CANCELAR"):
        raise KeyboardInterrupt


def esperar(segundos):
    """time.sleep em passos de meio segundo, conferindo se o app pediu para cancelar."""
    for _ in range(round(segundos * 2)):
        conferir_cancelamento()
        time.sleep(0.5)
    conferir_cancelamento()


def resumir(exc):
    """Texto curto do erro: sem rede, não mostra a exceção inteira do requests."""
    if isinstance(exc, requests.Timeout):
        return "o SIGRH não respondeu a tempo"
    if isinstance(exc, requests.ConnectionError):
        return "sem conexão. Confira se está conectado à Wi-Fi do campus."
    return str(exc)


class Recusado(Exception):
    """O SIGRH recusou o pedido (senha errada, PIT já registrado...): não adianta repetir."""


class EnvioIncerto(Exception):
    """Falhou depois de enviar o formulário: repetir poderia registrar duas vezes."""


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


def a4j(js):
    """(AJAXREQUEST, parâmetro) de uma chamada A4J.AJAX.Submit(...) do RichFaces."""
    m = re.search(r"'parameters':\{'([^']+)':'[^']*'\}\s*,\s*'containerId':'([^']+)'", js or "")
    if not m:
        raise Exception("Chamada AJAX do formulário não encontrada")
    return m.group(2), m.group(1)


def tipo_pit(form):
    """Valor da opção de Registro de PIT no combo Tipo."""
    combo = form.find("select", {"name": "cadastroAusencia:ausencia"})
    for opcao in combo.find_all("option") if combo else []:
        if "PIT" in opcao.get_text().upper():
            return opcao["value"]
    return TIPO_PIT_PADRAO


def horas(resposta):
    """Quantidade de Horas devolvida numa resposta AJAX ("" se não vier)."""
    campo = BeautifulSoup(resposta, "html.parser").find(
        "input", {"name": "cadastroAusencia:horasAusente"})
    return (campo.get("value") or "").strip() if campo else ""


# -----------------------------------
# PASSOS NO SIGRH
# -----------------------------------
def entrar(sessao, usuario, senha):
    """Faz o login e devolve a página do portal do servidor."""
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

    # Com o ponto pendente, o SIGRH mostra antes a tela do ponto; o botão
    # "Continuar Acessando o Sistema" (e o OK do diálogo) leva ao portal.
    if "idBtnContinuar" in str(pagina):
        resp = sessao.post(PONTO_URL, data={
            "idFormDadosEntradaSaida": "idFormDadosEntradaSaida",
            "idFormDadosEntradaSaida:observacoes": "",
            "idFormDadosEntradaSaida:idBtnContinuar": "Continuar Acessando o Sistema >>",
            "javax.faces.ViewState": viewstate(pagina),
        }, timeout=TIMEOUT)
        pagina = sopa(resp)

    if not pagina.find("form", id="menu:FormMenuServidor"):
        pagina = sopa(sessao.get(PORTAL_URL, timeout=TIMEOUT))
    return pagina


def abrir_formulario(sessao, portal):
    """Menu Solicitações > Ausências > Informar Ausência."""
    resp = sessao.post(PORTAL_URL, data={
        "menu:FormMenuServidor": "menu:FormMenuServidor",
        "javax.faces.ViewState": viewstate(portal),
        "menu:InformarAusencia": "menu:InformarAusencia",
    }, timeout=TIMEOUT)
    pagina = sopa(resp)
    if not pagina.find("form", id="cadastroAusencia"):
        raise Exception("O formulário de ausência não abriu")
    return pagina


def campos(estado, tipo, dia="", horas_ausente=None, observacao=""):
    dados = {
        "cadastroAusencia": "cadastroAusencia",
        "cadastroAusencia:idAusencia": "0",
        "confirmButton": "Cadastrar",
        "cadastroAusencia:ausencia": tipo,
        "cadastroAusencia:DataInicio": dia,
    }
    if horas_ausente is not None:
        dados["cadastroAusencia:horasAusente"] = horas_ausente
    dados["cadastroAusencia:observacao"] = observacao
    dados["javax.faces.ViewState"] = estado
    return dados


def ajax(sessao, chamada, dados):
    container, parametro = chamada
    dados = {"AJAXREQUEST": container, **dados, "cadastroAusencia:tipoDocumento": TIPO_DOCUMENTO,
             "cadastroAusencia:arquivo": "", parametro: parametro}
    resp = sessao.post(AUSENCIA_URL, data=dados, timeout=TIMEOUT)
    resp.raise_for_status()
    return resp.text


def preencher(sessao, pagina, dia, observacao):
    """Escolhe o tipo e a data como o navegador faz; devolve os campos finais."""
    form = pagina.find("form", id="cadastroAusencia")
    estado = viewstate(pagina)
    tipo = tipo_pit(form)

    combo = form.find("select", {"name": "cadastroAusencia:ausencia"})
    ajax(sessao, a4j(combo.get("onchange")),
         {**campos(estado, tipo), "cadastroAusencia:DataTermino": ""})

    # Ao mudar a data, a página dispara duas chamadas: atualizaPeriodoAusencia()
    # e outra ligada ao campo. Na captura, a primeira devolveu as horas (03:47)
    # e a segunda 00:00; o formulário ficou com o valor calculado.
    periodo = re.search(r"atualizaPeriodoAusencia=function\(\)\{(.*?)\};", str(pagina), re.S)
    data_inicio = form.find("input", {"name": "cadastroAusencia:DataInicio"})
    respostas = []
    if periodo:
        respostas.append(ajax(sessao, a4j(periodo.group(1)), campos(estado, tipo, dia, "")))
    respostas.append(ajax(sessao, a4j(data_inicio.get("onchange")), campos(estado, tipo, dia, "")))
    valores = [h for h in map(horas, respostas) if h]
    if not valores:
        raise Exception("O SIGRH não devolveu a Quantidade de Horas")
    horas_ausente = next((h for h in valores if h not in ZERO), valores[0])
    print(f"Quantidade de horas calculada pelo SIGRH: {horas_ausente}")
    if horas_ausente in ZERO:
        raise Recusado(f"O SIGRH calculou 00:00 horas para {dia}: nada a registrar")

    textarea = form.find("textarea", {"name": "cadastroAusencia:observacao"})
    if textarea and textarea.get("onchange"):
        ajax(sessao, a4j(textarea["onchange"]), campos(estado, tipo, dia, horas_ausente, observacao))
    return campos(estado, tipo, dia, horas_ausente, observacao)


def cadastrar(sessao, dados):
    """Envia o formulário (multipart, como o navegador) e confere a resposta."""
    partes = {nome: (None, valor) for nome, valor in dados.items() if nome != "javax.faces.ViewState"}
    partes["cadastroAusencia:arquivo"] = ("", b"", "application/octet-stream")
    partes["cadastroAusencia:cadastrarAusencia"] = (None, "Cadastrar")
    partes["javax.faces.ViewState"] = (None, dados["javax.faces.ViewState"])
    # Última chance de cancelar: depois do envio final, o registro segue até o fim.
    conferir_cancelamento()
    try:
        resp = sessao.post(AUSENCIA_URL, files=partes, timeout=60)
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise EnvioIncerto(f"falha ao enviar o formulário ({resumir(exc)}); confira no SIGRH") from exc

    pagina = sopa(resp)
    erros = mensagens(pagina, "erros") + mensagens(pagina, "warning")
    if erros:
        raise Recusado("; ".join(erros))
    info = mensagens(pagina, "info")
    if not any("sucesso" in texto.lower() for texto in info):
        raise EnvioIncerto("o SIGRH não confirmou o cadastro; confira no SIGRH")
    return info


def registrar(dia, observacao, usuario, senha):
    sessao = requests.Session()
    sessao.headers["User-Agent"] = "Mozilla/5.0"
    portal = entrar(sessao, usuario, senha)
    pagina = abrir_formulario(sessao, portal)
    dados = preencher(sessao, pagina, dia, observacao)
    return cadastrar(sessao, dados)


# -----------------------------------
# EXECUÇÃO
# -----------------------------------
def ler_argumentos(argv):
    parser = argparse.ArgumentParser(prog="registrar_pit.py", description="Registra o PIT no SIGRH.")
    parser.add_argument("data", nargs="?", help="dia no formato dd/mm/aaaa (padrão: hoje)")
    parser.add_argument("--obs", default=OBSERVACAO_PADRAO, help="texto do campo Observação")
    args = parser.parse_args(argv)
    if args.data:
        try:
            args.data = datetime.strptime(args.data, "%d/%m/%Y").date()
        except ValueError:
            parser.error(f"data inválida: {args.data!r}; use dd/mm/aaaa")
    else:
        args.data = date.today()
    args.obs = args.obs.strip() or OBSERVACAO_PADRAO
    return args


def main(argv=None):
    args = ler_argumentos(sys.argv[1:] if argv is None else argv)
    dia = args.data.strftime("%d/%m/%Y")
    if args.data.weekday() >= 5:
        print(f"{dia} é {DIAS[args.data.weekday()]}: o PIT só é registrado de segunda a sexta. Nada feito.")
        return 0

    usuario, senha = os.getenv("SIGRH_USER"), os.getenv("SIGRH_PASS")
    if not usuario or not senha:
        print("SIGRH_USER e SIGRH_PASS não definidos.")
        return 1

    print(f"Registrando o PIT de {dia} ({DIAS[args.data.weekday()]}), observação: {args.obs!r}")
    for tentativa in range(1, TENTATIVAS + 1):
        conferir_cancelamento()
        try:
            registrar(dia, args.obs, usuario, senha)
        except (Recusado, EnvioIncerto) as exc:
            erro = exc
            break
        except Exception as exc:
            erro = exc
            print(f"Tentativa {tentativa} falhou: {resumir(exc)}")
            if tentativa < TENTATIVAS:
                espera = ESPERAS[tentativa - 1]
                print(f"Tentando de novo em {espera}s...")
                esperar(espera)
        else:
            mensagem = "📋✅ SIGRH: PIT registrado"
            print(mensagem)
            return 0

    mensagem = f"📋❌ SIGRH: PIT de {dia} não registrado: {resumir(erro)}"
    print(mensagem)
    return 1


if __name__ == "__main__":
    sys.exit(main())
