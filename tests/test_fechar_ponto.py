"""Testa o script de fechar ponto contra um SIGRH falso (páginas mínimas no formato do real)."""

from urllib.parse import parse_qs

import pytest
import requests

from ponto.scripts import fechar_ponto as fp

LOGIN = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
LOGIN_ERRO = """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + LOGIN
PORTAL = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>
<form id="painelAcessoDadosServidor">
<a id="painelAcessoDadosServidor:linkPontoEletronicoAntigo" href="#">Ponto Eletrônico</a>
<input name="javax.faces.ViewState" value="j_id2"/></form>"""
ABERTO = """<form id="idFormDadosEntradaSaida" accept-charset="ISO-8859-1">
<table><tbody><tr><th class="rotulo">Hora de Entrada:</th><td>17:52:55</td></tr></tbody></table>
<textarea name="idFormDadosEntradaSaida:observacoes"></textarea>
<input name="idFormDadosEntradaSaida:idBtnRegistrarSaida" type="submit" value="Registrar Saída"/>
<input name="javax.faces.ViewState" value="j_id3"/></form>"""
FECHADO = """<form id="idFormDadosEntradaSaida">
<input name="idFormDadosEntradaSaida:idBtnRegistrarEntrada" type="submit" value="Registrar Entrada"/>
<input name="javax.faces.ViewState" value="j_id4"/></form>"""
SUCESSO = """<ul class="info"><li>Operação realizada com sucesso!</li></ul>""" + FECHADO


class Resposta:
    def __init__(self, html):
        self.text = html
        self.content = html.encode("utf-8")

    def raise_for_status(self):
        pass


class SigrhFalso:
    """Responde como o SIGRH; `paginas` permite trocar uma resposta por chave."""

    def __init__(self, **paginas):
        self.paginas = {"login": LOGIN, "apos_login": PORTAL, "ponto": ABERTO,
                        "saida": SUCESSO, **paginas}
        self.chamadas = []
        self.headers = {}

    def __call__(self):
        return self

    def get(self, url, timeout=None):
        self.chamadas.append(("GET", url, None))
        if url == fp.LOGIN_URL:
            return Resposta(self.paginas["login"])
        return Resposta(self.paginas["ponto"] if url == fp.PONTO_URL else PORTAL)

    def post(self, url, data=None, timeout=None, headers=None):
        self.chamadas.append(("POST", url, data))
        if url == fp.LOGIN_URL:
            return Resposta(self.paginas["apos_login"])
        if url == fp.PORTAL_URL:
            return Resposta(self.paginas["ponto"])
        if isinstance(self.paginas["saida"], Exception):
            raise self.paginas["saida"]
        return Resposta(self.paginas["saida"])


@pytest.fixture
def sigrh(monkeypatch):
    monkeypatch.setenv("SIGRH_USER", "usuario")
    monkeypatch.setenv("SIGRH_PASS", "senha")
    monkeypatch.setattr(fp.time, "sleep", lambda s: None)

    def instalar(**paginas):
        falso = SigrhFalso(**paginas)
        monkeypatch.setattr(fp.requests, "Session", falso)
        return falso
    return instalar


def test_fecha_pelo_link_do_portal_como_na_captura(sigrh, capsys):
    falso = sigrh()

    assert fp.main() == 0

    menu = falso.chamadas[2]
    assert menu[:2] == ("POST", fp.PORTAL_URL)
    assert menu[2] == {"painelAcessoDadosServidor": "painelAcessoDadosServidor",
                       "javax.faces.ViewState": "j_id2",
                       "painelAcessoDadosServidor:linkPontoEletronicoAntigo":
                           "painelAcessoDadosServidor:linkPontoEletronicoAntigo"}
    metodo, url, corpo = falso.chamadas[-1]
    assert (metodo, url) == ("POST", fp.PONTO_URL)
    assert "Registrar+Sa%EDda" in corpo  # ISO-8859-1, como o navegador
    assert parse_qs(corpo, keep_blank_values=True, encoding="latin-1") == {
        "idFormDadosEntradaSaida": ["idFormDadosEntradaSaida"],
        "idFormDadosEntradaSaida:observacoes": [""],
        "idFormDadosEntradaSaida:idBtnRegistrarSaida": ["Registrar Saída"],
        "javax.faces.ViewState": ["j_id3"],
    }
    saida = capsys.readouterr().out
    assert "Hora de entrada: 17:52:55" in saida
    assert "Operação realizada com sucesso!" in saida
    assert "saída registrada" in saida


def test_usa_a_tela_do_ponto_que_aparece_logo_apos_o_login(sigrh):
    falso = sigrh(apos_login=ABERTO)

    assert fp.main() == 0
    assert [c[1] for c in falso.chamadas] == [fp.LOGIN_URL, fp.LOGIN_URL, fp.PONTO_URL]


def test_sem_entrada_aberta_nao_clica_nem_repete(sigrh, capsys):
    falso = sigrh(ponto=FECHADO)

    assert fp.main() == 1
    assert not any(c[1] == fp.PONTO_URL and c[0] == "POST" for c in falso.chamadas)
    assert len([c for c in falso.chamadas if c[1] == fp.LOGIN_URL]) == 2  # uma tentativa só
    assert "não há entrada aberta" in capsys.readouterr().out


def test_login_recusado_nao_repete(sigrh, capsys):
    falso = sigrh(apos_login=LOGIN_ERRO)

    assert fp.main() == 1
    assert len(falso.chamadas) == 2
    assert "Usuário e/ou senha inválidos" in capsys.readouterr().out


BLOQUEADO = """<ul class="erros"><li>O Endereço IP de seu computador não tem autorização para registrar
o Ponto Eletrônico. Em caso de dúvidas entrar em contato com a Administração do Sistema.</li></ul>""" + PORTAL


@pytest.mark.parametrize("pagina", [BLOQUEADO, BLOQUEADO.replace('class="erros"', 'class="x"')])
def test_fora_do_campus_avisa_e_nao_repete(sigrh, capsys, pagina):
    falso = sigrh(ponto=pagina)

    assert fp.main() == 1
    assert len([c for c in falso.chamadas if c[1] == fp.LOGIN_URL]) == 2  # uma tentativa só
    assert not any(c[1] == fp.PONTO_URL and c[0] == "POST" for c in falso.chamadas)
    saida = capsys.readouterr().out
    assert "não tem autorização para registrar" in saida
    assert "fora da rede do campus" in saida


def test_erro_do_sigrh_na_saida_e_mostrado(sigrh, capsys):
    sigrh(saida="""<ul class="erros"><li>Horário fora do permitido.</li></ul>""" + ABERTO)

    assert fp.main() == 1
    assert "Horário fora do permitido." in capsys.readouterr().out


def test_sem_confirmacao_nao_repete(sigrh, capsys):
    falso = sigrh(saida=ABERTO)

    assert fp.main() == 1
    assert len([c for c in falso.chamadas if c[1] == fp.PONTO_URL]) == 1
    assert "confira no SIGRH" in capsys.readouterr().out


def test_queda_de_rede_ao_enviar_nao_repete(sigrh, capsys):
    falso = sigrh(saida=requests.ConnectionError("caiu"))

    assert fp.main() == 1
    assert len([c for c in falso.chamadas if c[1] == fp.PONTO_URL]) == 1
    assert "confira no SIGRH" in capsys.readouterr().out


def test_falha_antes_de_enviar_repete(sigrh, capsys):
    sigrh(login="<html>manutenção</html>")

    assert fp.main() == 1
    assert capsys.readouterr().out.count("falhou") == fp.TENTATIVAS


def test_sem_credenciais(monkeypatch, capsys):
    monkeypatch.delenv("SIGRH_USER", raising=False)
    monkeypatch.delenv("SIGRH_PASS", raising=False)

    assert fp.main() == 1
    assert "não definidos" in capsys.readouterr().out
