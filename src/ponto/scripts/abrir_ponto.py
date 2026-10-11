"""Abre o ponto no SIGRH (registra a entrada no ponto eletrônico).

Uso: abrir_ponto.py

Lê SIGRH_USER e SIGRH_PASS do ambiente.

Depois do login, o SIGRH mostra a tela do ponto eletrônico com o botão
"Registrar Entrada". O script envia esse formulário e só considera a entrada
registrada se a resposta trouxer "Operação realizada com sucesso" (ou já
mostrar o botão "Registrar Saída"). Fora da rede do campus o SIGRH já mostra,
na tela do ponto, "O Endereço IP de seu computador não tem autorização para
registrar o Ponto Eletrônico": o script não envia o formulário, tenta de novo
(a Wi-Fi pode estar conectando; esperas crescentes, cerca de 2 min ao todo) e,
se persistir, avisa e sai com código 1.
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

DICA_WIFI = "Confira se está conectado à Wi-Fi do campus."
FORA_DA_REDE = DICA_WIFI
# Mensagens do SIGRH quando o IP não está liberado: "O Endereço IP de seu computador
# não tem autorização..." ou "...não foi encontrada a configuração que permita o acesso
# ao registro do ponto eletrônico para o seu endereço IP...".
ERRO_DE_IP = re.compile(r"endere.o ip", re.I)
# Palavras que, numa mensagem de erro do SIGRH, indicam restrição de rede.
SINAIS_DE_REDE = ("rede", " ip", "endereço", "local", "computador", "máquina", "permitid", "autorizad")

TIMEOUT = 30
# Espera antes de cada nova tentativa: cresce para aguentar a rede sumir por até
# uns 2 minutos (troca de Wi-Fi ao chegar no campus, DNS ainda sem resposta).
ESPERAS = (10, 20, 30, 60)
TENTATIVAS = len(ESPERAS) + 1


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
    """O SIGRH recusou o pedido (senha errada, fora da rede...): não adianta repetir."""


class EnvioIncerto(Exception):
    """Falhou depois de enviar o formulário: não se sabe se a entrada ficou registrada."""


class ForaDaRede(Exception):
    """O IP não é do campus. Repete-se: logo depois de um gatilho a Wi-Fi pode estar conectando."""


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
    """Junta as mensagens de erro; se falarem de rede, fica só a dica da Wi-Fi."""
    texto = "; ".join(textos)
    if any(sinal in f" {texto.lower()}" for sinal in SINAIS_DE_REDE):
        return DICA_WIFI
    return texto


def formulario_ponto(pagina):
    return pagina.find("form", id=FORM)


def tem_botao(pagina, nome):
    form = formulario_ponto(pagina)
    return bool(form and form.find("input", {"name": nome}))


def hora_entrada(pagina):
    """Hora de entrada mostrada na tela do ponto, "" se faltar."""
    form = formulario_ponto(pagina)
    if form:
        for th in form.find_all("th"):
            if th.get_text(strip=True).lower().startswith("hora de entrada"):
                td = th.find_next_sibling("td")
                return td.get_text(strip=True) if td else ""
    return ""


def descrever(pagina):
    entrada = hora_entrada(pagina)
    return f"às {entrada}" if entrada else ""


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
    if any(ERRO_DE_IP.search(texto) for texto in erros(pagina)):
        raise ForaDaRede(FORA_DA_REDE)
    if not tem_botao(pagina, BTN_ENTRADA):
        problemas = erros(pagina)
        raise Recusado(com_dica(problemas) if problemas
                       else "O botão Registrar Entrada não está disponível. " + DICA_WIFI)

    # Última chance de cancelar: depois do envio final, o registro segue até o fim.
    conferir_cancelamento()
    try:
        resp = sessao.post(PONTO_URL, data={
            FORM: FORM,
            FORM + ":observacoes": "",
            BTN_ENTRADA: "Registrar Entrada",
            "javax.faces.ViewState": viewstate(pagina),
        }, timeout=TIMEOUT)
        resp.raise_for_status()
    except requests.RequestException as exc:
        raise EnvioIncerto(f"falha ao enviar o registro ({resumir(exc)}); confira no SIGRH") from exc

    pagina = sopa(resp)
    problemas = erros(pagina)
    if any(ERRO_DE_IP.search(texto) for texto in problemas):
        raise Recusado(FORA_DA_REDE)
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
# EXECUÇÃO
# -----------------------------------
def main(argv=None):
    usuario, senha = os.getenv("SIGRH_USER"), os.getenv("SIGRH_PASS")
    if not usuario or not senha:
        print("SIGRH_USER e SIGRH_PASS não definidos.")
        return 1

    for tentativa in range(1, TENTATIVAS + 1):
        conferir_cancelamento()
        try:
            pagina = abrir(usuario, senha)
        except JaAberto as exc:
            print(re.sub(r"\s+", " ", f"🔓ℹ️ SIGRH: o ponto já estava aberto {exc}").strip())
            return 0
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
            print(re.sub(r"\s+", " ", f"✅🔓🕑 SIGRH: entrada registrada {descrever(pagina)}").strip())
            return 0

    print(f"🔓❌ SIGRH: entrada não registrada: {resumir(erro)}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
