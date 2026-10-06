from datetime import date, datetime, timedelta

from ponto import atualizacao, configuracoes, mascara


def test_preferencias_padrao_e_salvas(tmp_path):
    path = tmp_path / "c.json"
    padrao = {"notificacoes": True, "lembrete_fechar": True, "lembrete_tempo": "01:40",
              "verificar_atualizacoes": True, "observacao_pit": "Conforme PIT docente."}
    assert configuracoes.carregar(path) == padrao
    configuracoes.salvar(path, {"verificar_atualizacoes": False})
    assert configuracoes.carregar(path) == {**padrao, "verificar_atualizacoes": False}


def test_tempo_do_lembrete():
    assert configuracoes.tempo_lembrete("01:40") == timedelta(hours=1, minutes=40)
    assert configuracoes.tempo_lembrete(" 8:00 ") == timedelta(hours=8)
    for invalido in ["", "0:00", "1:60", "24:00", "1h40", "01:40:00", "-1:30"]:
        assert configuracoes.tempo_lembrete(invalido) is None, invalido


def test_lembrete_pendente(tmp_path):
    path = tmp_path / "l.txt"
    agora = datetime(2026, 10, 6, 8, 0)
    assert configuracoes.lembrete_pendente(path, agora) is None
    configuracoes.registrar_lembrete(path, datetime(2026, 10, 6, 9, 40))
    assert configuracoes.lembrete_pendente(path, agora) == datetime(2026, 10, 6, 9, 40)
    assert configuracoes.lembrete_pendente(path, datetime(2026, 10, 6, 9, 41)) is None
    configuracoes.apagar_lembrete(path)
    assert configuracoes.lembrete_pendente(path, agora) is None


def test_fechamento_vale_so_no_dia(tmp_path):
    path = tmp_path / "f.txt"
    assert not configuracoes.fechou_no_dia(path)
    configuracoes.registrar_fechamento(path, date(2026, 10, 3))
    assert not configuracoes.fechou_no_dia(path, date(2026, 10, 4))
    assert configuracoes.fechou_no_dia(path, date(2026, 10, 3))


def test_comparacao_de_versoes():
    assert atualizacao.mais_nova("v1.0.10", "1.0.9")
    assert not atualizacao.mais_nova("1.0.6", "1.0.6")
    assert not atualizacao.mais_nova("1.0.5", "1.0.6")


def test_mascara():
    casos = {"0": "0", "04": "04/", "04/1": "04/1", "0410": "04/10/", "2/": "02/",
             "04102026": "04/10/2026", "04/10/20261": "04/10/2026", "2/10/2026": "2/10/2026"}
    for texto, esperado in casos.items():
        assert mascara.formatar_data(texto) == esperado
    assert mascara.formatar_data("04/", apagando=True) == "04/"
