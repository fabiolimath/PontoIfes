"""Testa o script de abrir ponto contra um SIGRH falso (páginas mínimas no formato do real)."""

import pytest
import requests

from ponto.scripts import abrir_ponto as ap

LOGIN = """<form id="formLogin"><input name="javax.faces.ViewState" value="j_id1"/></form>"""
LOGIN_ERRO = """<ul class="erros"><li>Usuário e/ou senha inválidos</li></ul>""" + LOGIN
# Tela do ponto logo após o login, com o ponto fechado (como no navegador).
PONTO_FECHADO = """<form id="idFormDadosEntradaSaida">
<textarea name="idFormDadosEntradaSaida:observacoes"></textarea>
<input name="idFormDadosEntradaSaida:idBtnRegistrarEntrada" type="submit" value="Registrar Entrada"/>
<input name="idFormDadosEntradaSaida:idBtnContinuar" type="submit"/>
<input name="javax.faces.ViewState" value="j_id1"/></form>"""
# Resposta da captura: mensagem de sucesso e a tela já com "Registrar Saída".
PONTO_ABERTO = """<form id="idFormDadosEntradaSaida"><table>
<tbody id="idFormDadosEntradaSaida:registroPontoSaida">
<tr><th class="rotulo">Hora de Entrada:</th><td>07:46:40</td></tr>
<tr><th class="rotulo">Hora de Saída Prevista:</th><td>
<span id="idFormDadosEntradaSaida:horaSaidaPrevista">14:53:40</span></td></tr></tbody></table>
<input name="idFormDadosEntradaSaida:idBtnRegistrarSaida" type="submit" value="Registrar Saída"/>
<input name="javax.faces.ViewState" value="j_id2"/></form>"""
SUCESSO = """<ul class="info"><li>Operação realizada com sucesso!</li></ul>""" + PONTO_ABERTO
PORTAL = """<form id="menu:FormMenuServidor"><input name="javax.faces.ViewState" value="j_id2"/></form>"""
FORA_DA_REDE = """<ul class="erros"><li>Registro de ponto não permitido a partir desta rede.</li></ul>""" + PORTAL


class Resposta:
    def __init__(self, html):
        self.text = html
        self.content = html.encode("utf-8")

    def raise_for_status(self):
        pass


class SigrhFalso:
    def __init__(self, **paginas):
        self.paginas = {"login": LOGIN, "apos_login": PONTO_FECHADO, "ponto": PONTO_FECHADO,
                        "registro": SUCESSO, **paginas}
        self.chamadas = []
        self.headers = {}

    def __call__(self):
        return self

    def _pagina(self, chave):
        pagina = self.paginas[chave]
        if isinstance(pagina, Exception):
            raise pagina
        return Resposta(pagina)

    def get(self, url, timeout=None):
        self.chamadas.append(("GET", url, None))
        return self._pagina("login" if url == ap.LOGIN_URL else "ponto")

    def post(self, url, data=None, timeout=None):
        self.chamadas.append(("POST", url, data))
        return self._pagina("apos_login" if url == ap.LOGIN_URL else "registro")

    def registros(self):
        return [d for metodo, url, d in self.chamadas if metodo == "POST" and url == ap.PONTO_URL]


@pytest.fixture
def sigrh(monkeypatch):
    monkeypatch.setenv("SIGRH_USER", "usuario")
    monkeypatch.setenv("SIGRH_PASS", "senha")
    monkeypatch.delenv("TELEGRAM_TOKEN", raising=False)
    monkeypatch.setattr(ap.time, "sleep", lambda s: None)

    def instalar(**paginas):
        falso = SigrhFalso(**paginas)
        monkeypatch.setattr(ap.requests, "Session", falso)
        return falso

    return instalar


def test_registra_a_entrada_como_o_navegador(sigrh, capsys):
    falso = sigrh()
    assert ap.main([]) == 0
    assert falso.registros() == [{
        "idFormDadosEntradaSaida": "idFormDadosEntradaSaida",
        "idFormDadosEntradaSaida:observacoes": "",
        "idFormDadosEntradaSaida:idBtnRegistrarEntrada": "Registrar Entrada",
        "javax.faces.ViewState": "j_id1",
    }]
    saida = capsys.readouterr().out
    assert "entrada registrada às 07:46:40 (saída prevista: 14:53:40)" in saida


def test_sem_mensagem_de_sucesso_nao_diz_que_registrou(sigrh, capsys):
    falso = sigrh(registro=PONTO_FECHADO)
    assert ap.main([]) == 1
    assert len(falso.registros()) == 1  # não repete: poderia registrar duas vezes
    saida = capsys.readouterr().out
    assert "não confirmou" in saida and "registrada às" not in saida


def test_erro_do_sigrh_no_registro(sigrh, capsys):
    erro = """<ul class="erros"><li>Não é permitido registrar o ponto a partir deste computador.</li></ul>"""
    falso = sigrh(registro=erro + PONTO_FECHADO)
    assert ap.main([]) == 1
    assert len(falso.registros()) == 1
    saida = capsys.readouterr().out
    assert "computador" in saida and "Wi-Fi do campus" in saida


def test_login_recusado_nao_repete(sigrh, capsys):
    falso = sigrh(apos_login=LOGIN_ERRO)
    assert ap.main([]) == 1
    assert sum(1 for c in falso.chamadas if c[1] == ap.LOGIN_URL and c[0] == "POST") == 1
    assert "Usuário e/ou senha inválidos" in capsys.readouterr().out


def test_ponto_ja_aberto_nao_registra_de_novo(sigrh, capsys):
    falso = sigrh(apos_login=PONTO_ABERTO)
    assert ap.main([]) == 0
    assert falso.registros() == []
    assert "já estava aberto às 07:46:40" in capsys.readouterr().out


def test_fora_da_rede_com_mensagem(sigrh, capsys):
    falso = sigrh(apos_login=PORTAL, ponto=FORA_DA_REDE)
    assert ap.main([]) == 1
    assert falso.registros() == []
    saida = capsys.readouterr().out
    assert "não permitido a partir desta rede" in saida and "Wi-Fi do campus" in saida


def test_sem_tela_do_ponto_tenta_de_novo_e_avisa_da_wifi(sigrh, capsys):
    falso = sigrh(apos_login=PORTAL, ponto=PORTAL)
    assert ap.main([]) == 1
    assert sum(1 for c in falso.chamadas if c[1] == ap.LOGIN_URL and c[0] == "POST") == ap.TENTATIVAS
    assert falso.registros() == []
    assert "Wi-Fi do campus" in capsys.readouterr().out.splitlines()[-1]


def test_login_cai_no_portal_mas_tela_do_ponto_abre(sigrh):
    falso = sigrh(apos_login=PORTAL)
    assert ap.main([]) == 0
    assert len(falso.registros()) == 1


def test_sem_rede_tenta_de_novo(sigrh, capsys):
    sigrh(login=requests.ConnectionError("sem conexão"))
    assert ap.main([]) == 1
    assert capsys.readouterr().out.count("falhou") == ap.TENTATIVAS


def test_falha_ao_enviar_o_registro_nao_repete(sigrh, capsys):
    falso = sigrh(registro=requests.ConnectionError("caiu"))
    assert ap.main([]) == 1
    assert len(falso.registros()) == 1
    assert "confira no SIGRH" in capsys.readouterr().out


def test_sem_credenciais(monkeypatch, capsys):
    monkeypatch.delenv("SIGRH_USER", raising=False)
    monkeypatch.delenv("SIGRH_PASS", raising=False)
    assert ap.main([]) == 1


# Como na captura sigrh.loginFora: o erro já vem na tela do ponto, logo após o login.
ERRO_IP = ("""<ul class="erros"><li>O Endereço IP de seu computador não tem autorização para registrar """
           """o Ponto Eletrônico. Em caso de dúvidas entrar em contato com a Administração do Sistema."""
           """</li></ul>""")


def test_fora_do_campus_nao_envia_e_tenta_de_novo(sigrh, capsys):
    falso = sigrh(apos_login=ERRO_IP + PONTO_FECHADO)
    assert ap.main([]) == 1
    assert falso.registros() == []
    assert sum(1 for c in falso.chamadas if c[1] == ap.LOGIN_URL and c[0] == "POST") == ap.TENTATIVAS
    ultima = capsys.readouterr().out.splitlines()[-1]
    assert "fora da rede do campus" in ultima and "Wi-Fi do campus" in ultima


def test_fora_do_campus_na_resposta_do_registro(sigrh, capsys):
    falso = sigrh(registro=ERRO_IP + PONTO_FECHADO)
    assert ap.main([]) == 1
    assert len(falso.registros()) == 1
    assert "fora da rede do campus" in capsys.readouterr().out
