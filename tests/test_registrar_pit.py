"""Testa o script do PIT contra um SIGRH falso (páginas mínimas no formato do real)."""

import pytest
import requests

from ponto.scripts import registrar_pit as pit

LOGIN = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
LOGIN_ERRO = """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + LOGIN
PONTO = """<form id="idFormDadosEntradaSaida">
<input id="idFormDadosEntradaSaida:idBtnContinuar" type="submit"/>
<input name="javax.faces.ViewState" value="j_id9"/></form>"""
PORTAL = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>"""
FORM = """<script>atualizaPeriodoAusencia=function(){A4J.AJAX.Submit('cadastroAusencia',null,
{'status':'s','parameters':{'cadastroAusencia:p431':'cadastroAusencia:p431'} ,'containerId':'c0'} )};</script>
<form id="cadastroAusencia">
<select name="cadastroAusencia:ausencia" onchange="A4J.AJAX.Submit('cadastroAusencia',event,
{'parameters':{'cadastroAusencia:p491':'cadastroAusencia:p491'} ,'containerId':'cadastroAusencia:c487'} )">
<option value="0">-- SELECIONE --</option><option value="600337">REGISTRO DO PIT - DOCENTE</option></select>
<input name="cadastroAusencia:DataInicio" onchange="atualizaPeriodoAusencia();A4J.AJAX.Submit('cadastroAusencia',event,
{'oncomplete':function(request,event,data){x();},'parameters':{'cadastroAusencia:p530':'cadastroAusencia:p530'} ,'containerId':'cadastroAusencia:c528'} )"/>
<textarea name="cadastroAusencia:observacao" onchange="A4J.AJAX.Submit('cadastroAusencia',event,
{'parameters':{'cadastroAusencia:p584':'cadastroAusencia:p584'} ,'containerId':'cadastroAusencia:c582'} )"></textarea>
<input name="javax.faces.ViewState" value="j_id3"/></form>"""
HORAS = """<input id="cadastroAusencia:horasAusente" name="cadastroAusencia:horasAusente" value="03:47"/>"""
ZERO = HORAS.replace("03:47", "00:00")
SUCESSO = """<ul class="info"><li>Solicitação de ausência enviada com sucesso.</li></ul>""" + FORM
JA_EXISTE = """<ul class="erros"><li>Já existe ausência cadastrada no período.</li></ul>""" + FORM


class Resposta:
    def __init__(self, html):
        self.text = html
        self.content = html.encode("utf-8")

    def raise_for_status(self):
        pass


class SigrhFalso:
    """Responde como o SIGRH; `paginas` permite trocar uma resposta por chave."""

    def __init__(self, **paginas):
        # Como na captura real: atualizaPeriodoAusencia (c0) traz as horas; a outra, 00:00.
        self.paginas = {"apos_login": PORTAL, "login": LOGIN, "horas": HORAS, "horas_data": ZERO,
                        "cadastro": SUCESSO,
                        **paginas}
        self.chamadas = []
        self.headers = {}

    def __call__(self):
        return self

    def get(self, url, timeout=None):
        self.chamadas.append(("GET", url, None))
        return Resposta(self.paginas["login"] if url == pit.LOGIN_URL else PORTAL)

    def post(self, url, data=None, files=None, timeout=None):
        self.chamadas.append(("ENVIO" if files else "POST", url, data or files))
        if url == pit.LOGIN_URL:
            return Resposta(self.paginas["apos_login"])
        if url == pit.PONTO_URL:
            return Resposta(PORTAL)
        if url == pit.PORTAL_URL:
            return Resposta(FORM)
        if files:
            return Resposta(self.paginas["cadastro"])
        respostas = {"c0": "horas", "cadastroAusencia:c528": "horas_data"}
        chave = respostas.get(data["AJAXREQUEST"])
        return Resposta(self.paginas[chave] if chave else "")


@pytest.fixture
def sigrh(monkeypatch):
    monkeypatch.setenv("SIGRH_USER", "usuario")
    monkeypatch.setenv("SIGRH_PASS", "senha")
    monkeypatch.setattr(pit.time, "sleep", lambda s: None)

    def instalar(**paginas):
        falso = SigrhFalso(**paginas)
        monkeypatch.setattr(pit.requests, "Session", falso)
        return falso
    return instalar


def test_registra_com_a_sequencia_do_navegador(sigrh, capsys):
    falso = sigrh()

    assert pit.main(["05/10/2026", "--obs", "PIT segundo portaria"]) == 0

    ajax = [c[2]["AJAXREQUEST"] for c in falso.chamadas if c[2] and "AJAXREQUEST" in c[2]]
    assert ajax == ["cadastroAusencia:c487", "c0", "cadastroAusencia:c528", "cadastroAusencia:c582"]
    final = falso.chamadas[-1][2]
    assert final["cadastroAusencia:ausencia"] == (None, "600337")
    assert final["cadastroAusencia:DataInicio"] == (None, "05/10/2026")
    assert final["cadastroAusencia:horasAusente"] == (None, "03:47")
    assert "cadastroAusencia:tipoDocumento" not in final
    primeira = next(c[2] for c in falso.chamadas if c[2] and "AJAXREQUEST" in c[2])
    assert primeira["cadastroAusencia:tipoDocumento"] == "104"
    assert primeira["cadastroAusencia:DataTermino"] == ""
    assert final["cadastroAusencia:observacao"] == (None, "PIT segundo portaria")
    assert final["javax.faces.ViewState"] == (None, "j_id3")
    saida = capsys.readouterr().out
    assert "enviada com sucesso" in saida
    assert "PIT de 05/10/2026 registrado" in saida


def test_passa_pela_tela_do_ponto(sigrh):
    falso = sigrh(apos_login=PONTO)
    assert pit.main(["05/10/2026"]) == 0
    ponto = [c for c in falso.chamadas if c[1] == pit.PONTO_URL]
    assert ponto[0][2]["javax.faces.ViewState"] == "j_id9"
    assert falso.chamadas[-1][2]["cadastroAusencia:observacao"] == (None, "Conforme PIT docente.")


def test_login_recusado_nao_repete(sigrh, capsys):
    falso = sigrh(apos_login=LOGIN_ERRO)
    assert pit.main(["05/10/2026"]) == 1
    assert sum(1 for c in falso.chamadas if c[1] == pit.LOGIN_URL and c[0] == "POST") == 1
    assert "Usuário e/ou senha inválidos" in capsys.readouterr().out


def test_erro_do_sigrh_no_cadastro_nao_repete(sigrh, capsys):
    falso = sigrh(cadastro=JA_EXISTE)
    assert pit.main(["05/10/2026"]) == 1
    assert [c[0] for c in falso.chamadas].count("ENVIO") == 1
    assert "Já existe ausência" in capsys.readouterr().out


def test_sem_confirmacao_nao_repete(sigrh, capsys):
    falso = sigrh(cadastro=FORM)
    assert pit.main(["05/10/2026"]) == 1
    assert [c[0] for c in falso.chamadas].count("ENVIO") == 1
    assert "confira no SIGRH" in capsys.readouterr().out


def test_zero_horas_nao_envia(sigrh, capsys):
    falso = sigrh(horas=ZERO)
    assert pit.main(["05/10/2026"]) == 1
    assert "ENVIO" not in [c[0] for c in falso.chamadas]


def test_falha_de_rede_antes_do_envio_repete(sigrh):
    falso = sigrh()
    get = falso.get
    falhas = iter([True])

    def get_instavel(url, timeout=None):
        if next(falhas, False):
            raise requests.ConnectionError("sem rede")
        return get(url, timeout)
    falso.get = get_instavel

    assert pit.main(["05/10/2026"]) == 0


def test_fim_de_semana_nao_faz_nada(sigrh, capsys):
    falso = sigrh()
    assert pit.main(["04/10/2026"]) == 0
    assert falso.chamadas == []
    assert "domingo" in capsys.readouterr().out


def test_data_invalida():
    with pytest.raises(SystemExit) as exc:
        pit.main(["31/02/2026"])
    assert exc.value.code == 2


def test_observacao_em_branco_usa_o_padrao():
    assert pit.ler_argumentos(["--obs", "  "]).obs == "Conforme PIT docente."
