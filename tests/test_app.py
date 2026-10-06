import asyncio
import importlib
import uuid
from datetime import date

import pytest

from ponto import configuracoes

toga = pytest.importorskip("toga")


@pytest.fixture
def app(tmp_path, monkeypatch):
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")
    from ponto.app import Ponto

    monkeypatch.setattr(toga.paths.Paths, "data", property(lambda self: tmp_path))
    return Ponto(formal_name="Ponto", app_id="io.github.fabiolimath.ponto")


def test_primeira_execucao_pede_credenciais(app):
    assert set(app.campos) == {"SIGRH_USER", "SIGRH_PASS"}
    assert app.main_window.content is not app.tela_principal


def test_tela_principal_tem_tres_botoes(app):
    assert [b.text for b in app.botoes] == ["Abrir ponto", "Fechar ponto", "Registrar PIT"]
    app.mostrar_principal()
    assert app.main_window.content is app.tela_principal


def test_tela_de_log(app):
    app.mostrar_log()
    assert app.texto_log.value == "Nenhuma execução registrada ainda."


def _preparar_script(app, tmp_path, monkeypatch, acao, corpo):
    from ponto import credenciais, executor

    pacote = f"scripts_{uuid.uuid4().hex}"
    (tmp_path / pacote).mkdir()
    (tmp_path / pacote / "__init__.py").write_text("")
    (tmp_path / pacote / f"{acao}.py").write_text(corpo)
    monkeypatch.syspath_prepend(str(tmp_path))
    importlib.invalidate_caches()
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, pacote=pacote, **k))
    credenciais.salvar(app.cred_path, {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    app.mostrar_principal()


def test_rodar_mostra_saida_e_status(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "abrir_ponto", "import sys\nprint('tentando')\nsys.exit(1)\n")

    app.loop.run_until_complete(app.rodar("abrir_ponto"))

    assert app.saida.value == "tentando\n"
    assert app.status.text == "Abrir ponto: falhou (código 1)."
    assert all(b.enabled for b in app.botoes)
    assert "saída: 1" in app.log_path.read_text(encoding="utf-8")


def test_pit_com_data_passa_argumento(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nprint(sys.argv[1:])\n")
    app.data_pit.value = " 02/10/2026 "

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['02/10/2026', '--obs', 'Conforme PIT docente.']\n"
    assert app.status.text == "Registrar PIT (02/10/2026): concluído."
    assert app.data_pit.value == ""
    assert "· registrar_pit 02/10/2026 --obs Conforme PIT docente. ===" in app.log_path.read_text(encoding="utf-8")


def test_pit_sem_data_usa_o_dia(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nprint(sys.argv[1:])\n")
    configuracoes.registrar_fechamento(app.fechamento_path)

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['--obs', 'Conforme PIT docente.']\n"


def test_pit_usa_observacao_das_configuracoes(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nprint(sys.argv[1:])\n")
    configuracoes.salvar(app.config_path, {"observacao_pit": "PIT segundo portaria"})
    app.data_pit.value = "02/10/2026"

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "['02/10/2026', '--obs', 'PIT segundo portaria']\n"


def test_pit_com_data_invalida_nao_roda(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.data_pit.value = "31/02/2026"
    app.main_window._impl.dialog_responses["ErrorDialog"] = [None]

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == ""
    assert not app.log_path.exists()
    assert app.main_window._impl.dialog_responses["ErrorDialog"] == []


def test_copiar_log_fora_do_android_avisa(app):
    app.mostrar_log()
    app.main_window._impl.dialog_responses["InfoDialog"] = [None]
    botao = app.main_window.content.children[1].children[1]
    assert botao.text == "Copiar"

    app.loop.run_until_complete(app.copiar_log(botao))

    assert app.main_window._impl.dialog_responses["InfoDialog"] == []


def test_aberto_por_intent_executa_a_acao(tmp_path, monkeypatch):
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")
    monkeypatch.setattr(toga.paths.Paths, "data", property(lambda self: tmp_path))
    from ponto import credenciais, executor, plataforma
    from ponto.app import Ponto

    pacote = f"scripts_{uuid.uuid4().hex}"
    (tmp_path / pacote).mkdir()
    (tmp_path / pacote / "__init__.py").write_text("")
    (tmp_path / pacote / "fechar_ponto.py").write_text("print('fechou')\n")
    monkeypatch.syspath_prepend(str(tmp_path))
    importlib.invalidate_caches()
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, pacote=pacote, **k))
    credenciais.salvar(tmp_path / "credenciais.json", {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    monkeypatch.setattr(plataforma, "acao_do_intent", lambda app: "fechar_ponto")

    app = Ponto(formal_name="Ponto", app_id="io.github.fabiolimath.ponto")
    app.loop.run_until_complete(asyncio.sleep(0.5))

    assert app.saida.value == "fechou\n"
    assert app.status.text == "Fechar ponto: concluído."


def test_tentar_de_novo_do_pit_usa_a_data(tmp_path, monkeypatch):
    monkeypatch.setenv("TOGA_BACKEND", "toga_dummy")
    monkeypatch.setattr(toga.paths.Paths, "data", property(lambda self: tmp_path))
    from ponto import credenciais, executor, plataforma
    from ponto.app import Ponto

    pacote = f"scripts_{uuid.uuid4().hex}"
    (tmp_path / pacote).mkdir()
    (tmp_path / pacote / "__init__.py").write_text("")
    (tmp_path / pacote / "registrar_pit.py").write_text("import sys\nprint(sys.argv[1])\n")
    monkeypatch.syspath_prepend(str(tmp_path))
    importlib.invalidate_caches()
    original = executor.executar
    monkeypatch.setattr(executor, "executar", lambda *a, **k: original(*a, pacote=pacote, **k))
    credenciais.salvar(tmp_path / "credenciais.json", {"SIGRH_USER": "a", "SIGRH_PASS": "b"})
    monkeypatch.setattr(plataforma, "acao_do_intent", lambda app: "registrar_pit")
    monkeypatch.setattr(plataforma, "data_do_intent", lambda app: "02/10/2020")

    app = Ponto(formal_name="Ponto", app_id="io.github.fabiolimath.ponto")
    app.loop.run_until_complete(asyncio.sleep(0.5))

    assert app.saida.value == "02/10/2020\n"


def test_pit_do_dia_sem_fechar_o_ponto_pergunta(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [False, False]

    app.loop.run_until_complete(app.rodar("registrar_pit"))
    app.data_pit.value = date.today().strftime("%d/%m/%Y")
    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == ""
    assert app.main_window._impl.dialog_responses["ConfirmDialog"] == []


def test_pit_do_dia_registrar_mesmo_assim(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [True]

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "rodou\n"


def test_pit_de_outro_dia_nao_exige_fechamento(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "print('rodou')\n")
    app.data_pit.value = "02/10/2020"

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert app.saida.value == "rodou\n"


def test_fechar_ponto_libera_o_pit_do_dia(app, tmp_path, monkeypatch):
    _preparar_script(app, tmp_path, monkeypatch, "fechar_ponto", "print('fechou')\n")

    app.loop.run_until_complete(app.rodar("fechar_ponto"))

    assert configuracoes.fechou_no_dia(app.fechamento_path)


def test_mascara_no_campo_de_data(app):
    app.mostrar_principal()
    for texto in ["0", "04", "04/1", "04/10", "04/10/2026"]:
        app.data_pit.value = texto
    assert app.data_pit.value == "04/10/2026"
    app.data_pit.value = "04/"
    app.data_pit.value = "04"  # apagou a barra: não volta
    assert app.data_pit.value == "04"


def test_tela_de_configuracoes(app):
    titulo = app.main_window.content.content.children[0]
    assert titulo.text == "Configurações"
    assert app.verificar_atualizacoes.value is True
    app.campos["SIGRH_USER"].value = "a"
    app.campos["SIGRH_PASS"].value = "b"
    app.verificar_atualizacoes.value = False
    assert app.notificacoes.value is True
    app.notificacoes.value = False
    assert app.observacao_pit.value == "Conforme PIT docente."
    app.observacao_pit.value = " PIT segundo portaria "

    app.loop.run_until_complete(app.salvar_configuracoes(None))

    assert app.main_window.content is app.tela_principal
    assert configuracoes.carregar(app.config_path) == {
        "notificacoes": False, "lembrete_fechar": True, "lembrete_tempo": "01:40",
        "verificar_atualizacoes": False, "observacao_pit": "PIT segundo portaria"}
    assert not any(isinstance(w, toga.Label) and w.text == "Pronto." for w in app.tela_principal.children)


def test_configuracoes_do_lembrete(app, monkeypatch):
    from ponto import plataforma

    cancelados = []
    monkeypatch.setattr(plataforma, "cancelar_lembrete", lambda app: cancelados.append(1))
    dialogos = []
    monkeypatch.setattr(app.main_window, "dialog", _dialogo_falso(dialogos, None))
    app.campos["SIGRH_USER"].value = "a"
    app.campos["SIGRH_PASS"].value = "b"
    assert app.lembrete_fechar.value is True
    assert app.lembrete_tempo.value == "01:40"
    app.lembrete_tempo.value = ""
    for texto in ["0", "02", "02:3", "02:30"]:
        app.lembrete_tempo.value = texto
    assert app.lembrete_tempo.value == "02:30"

    app.lembrete_tempo.value = "1h40"
    app.loop.run_until_complete(app.salvar_configuracoes(None))
    assert [type(d).__name__ for d in dialogos] == ["ErrorDialog"]
    assert app.main_window.content is not app.tela_principal

    app.lembrete_tempo.value = " 2:15 "
    app.loop.run_until_complete(app.salvar_configuracoes(None))
    assert configuracoes.carregar(app.config_path)["lembrete_tempo"] == "2:15"
    assert cancelados == []

    app.mostrar_configuracoes()
    app.lembrete_fechar.value = False
    app.lembrete_tempo.value = ""
    app.loop.run_until_complete(app.salvar_configuracoes(None))
    preferencias = configuracoes.carregar(app.config_path)
    assert (preferencias["lembrete_fechar"], preferencias["lembrete_tempo"]) == (False, "01:40")
    assert cancelados == [1]


def _dialogo_falso(dialogos, resposta):
    async def dialogo(d):
        dialogos.append(d)
        return resposta
    return dialogo


def test_abrir_ponto_agenda_o_lembrete(app, tmp_path, monkeypatch):
    from ponto import plataforma

    agendados = []
    monkeypatch.setattr(plataforma, "agendar_lembrete", lambda app, *a: agendados.append(a))
    _preparar_script(app, tmp_path, monkeypatch, "abrir_ponto", "print('ok')\n")

    app.loop.run_until_complete(app.rodar("abrir_ponto"))
    # Abrir de novo ("já estava aberto") não empurra o lembrete para mais tarde.
    app.loop.run_until_complete(app.rodar("abrir_ponto"))

    assert len(agendados) == 1
    quando, titulo, texto = agendados[0]
    assert titulo == "Ponto ainda aberto"
    assert configuracoes.lembrete_pendente(app.lembrete_path) == quando.replace(microsecond=0)


def test_lembrete_conta_do_horario_de_abertura(app, monkeypatch):
    from datetime import datetime

    from ponto import plataforma

    agendados = []
    monkeypatch.setattr(plataforma, "agendar_lembrete", lambda app, *a: agendados.append(a))
    configuracoes.salvar(app.config_path, {"lembrete_tempo": "02:00"})

    app._agendar_lembrete(datetime(2026, 10, 6, 7, 55))

    assert agendados == [(datetime(2026, 10, 6, 9, 55), "Ponto ainda aberto",
                          "O ponto foi aberto às 07:55 e ainda não foi fechado.")]


def test_sem_lembrete_se_desligado_ou_se_abrir_falha(app, tmp_path, monkeypatch):
    from ponto import plataforma

    agendados = []
    monkeypatch.setattr(plataforma, "agendar_lembrete", lambda app, *a: agendados.append(a))
    _preparar_script(app, tmp_path, monkeypatch, "abrir_ponto", "import sys\nsys.exit(1)\n")
    app.loop.run_until_complete(app.rodar("abrir_ponto"))
    configuracoes.salvar(app.config_path, {"lembrete_fechar": False})
    app._agendar_lembrete()

    assert agendados == []


def test_fechar_ponto_cancela_o_lembrete(app, tmp_path, monkeypatch):
    from datetime import datetime, timedelta

    from ponto import plataforma

    cancelados = []
    monkeypatch.setattr(plataforma, "cancelar_lembrete", lambda app: cancelados.append(1))
    configuracoes.registrar_lembrete(app.lembrete_path, datetime.now() + timedelta(hours=1))
    _preparar_script(app, tmp_path, monkeypatch, "fechar_ponto", "print('fechou')\n")

    app.loop.run_until_complete(app.rodar("fechar_ponto"))

    assert cancelados == [1]
    assert configuracoes.lembrete_pendente(app.lembrete_path) is None


def test_atualizacao_oferece_baixar(app, monkeypatch):
    from ponto import atualizacao, plataforma

    abertos = []
    monkeypatch.setattr(type(app), "version", property(lambda self: "1.0.6"))
    monkeypatch.setattr(atualizacao, "ultima_versao", lambda: "1.0.10")
    monkeypatch.setattr(plataforma, "abrir_url", lambda app, url: abertos.append(url))
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [True, False]

    # A checagem agendada ao abrir o app roda junto, na 1ª volta do loop.
    app.loop.run_until_complete(app.verificar_atualizacao(avisar_sem_novidade=False))

    assert abertos == [atualizacao.URL_DOWNLOAD]
    assert app.main_window._impl.dialog_responses["ConfirmDialog"] == []


def test_atualizacao_em_dia_so_avisa_se_pedido(app, monkeypatch):
    from ponto import atualizacao

    monkeypatch.setattr(type(app), "version", property(lambda self: "1.0.6"))
    monkeypatch.setattr(atualizacao, "ultima_versao", lambda: "1.0.6")

    app.loop.run_until_complete(app.verificar_atualizacao(avisar_sem_novidade=False))
    app.main_window._impl.dialog_responses["InfoDialog"] = [None]
    app.loop.run_until_complete(app.verificar_atualizacao(avisar_sem_novidade=True))

    assert app.main_window._impl.dialog_responses["InfoDialog"] == []


def test_notifica_o_resultado(app, tmp_path, monkeypatch):
    from ponto import plataforma

    notificacoes = []
    monkeypatch.setattr(plataforma, "notificar", lambda app, *a, **k: notificacoes.append((a, k)))
    _preparar_script(app, tmp_path, monkeypatch, "fechar_ponto",
                     "print('Tentativa 1...')\nprint('SIGRH: saída registrada às 17:00')\n")

    app.loop.run_until_complete(app.rodar("fechar_ponto"))
    configuracoes.salvar(app.config_path, {"notificacoes": False})
    app.loop.run_until_complete(app.rodar("fechar_ponto"))

    assert notificacoes == [(("Fechar ponto: concluído.", "SIGRH: saída registrada às 17:00", 2),
                             {"tentar_de_novo": None})]


def test_falha_notifica_com_tentar_de_novo(app, tmp_path, monkeypatch):
    from ponto import plataforma

    notificacoes = []
    monkeypatch.setattr(plataforma, "notificar", lambda app, *a, **k: notificacoes.append(k))
    _preparar_script(app, tmp_path, monkeypatch, "registrar_pit", "import sys\nsys.exit(1)\n")
    app.data_pit.value = "02/10/2026"

    app.loop.run_until_complete(app.rodar("registrar_pit"))

    assert notificacoes == [{"tentar_de_novo": {"acao": "registrar_pit", "data": "02/10/2026"}}]


def test_nova_execucao_apaga_a_notificacao_anterior(app, tmp_path, monkeypatch):
    from ponto import plataforma

    eventos = []
    monkeypatch.setattr(plataforma, "cancelar_notificacao", lambda app, i: eventos.append(("apaga", i)))
    monkeypatch.setattr(plataforma, "notificar", lambda app, t, x, i, **k: eventos.append(("mostra", i)))
    _preparar_script(app, tmp_path, monkeypatch, "abrir_ponto", "import sys\nsys.exit(1)\n")

    app.loop.run_until_complete(app.rodar("abrir_ponto"))

    assert eventos == [("apaga", 1), ("mostra", 1)]


def test_erro_na_notificacao_nao_atrapalha(app, tmp_path, monkeypatch):
    from ponto import plataforma

    def falha(*a):
        raise RuntimeError("sem permissão")

    monkeypatch.setattr(plataforma, "notificar", falha)
    _preparar_script(app, tmp_path, monkeypatch, "abrir_ponto", "print('ok')\n")

    app.loop.run_until_complete(app.rodar("abrir_ponto"))

    assert app.status.text == "Abrir ponto: concluído."


def test_sobre_em_portugues_abre_o_site(app, monkeypatch):
    from ponto import atualizacao, plataforma

    abertos = []
    monkeypatch.setattr(plataforma, "abrir_url", lambda app, url: abertos.append(url))
    app.main_window._impl.dialog_responses["ConfirmDialog"] = [True]

    app.loop.run_until_complete(app._sobre())

    assert abertos == [atualizacao.URL_SITE]
    if toga.Command.ABOUT in app.commands:
        assert app.commands[toga.Command.ABOUT].text == "Sobre o Ponto"
