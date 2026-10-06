from datetime import date

from ponto import atualizacao, configuracoes, mascara


def test_preferencias_padrao_e_salvas(tmp_path):
    path = tmp_path / "c.json"
    assert configuracoes.carregar(path) == {"notificacoes": True, "verificar_atualizacoes": True,
                                            "observacao_pit": "Conforme PIT docente."}
    configuracoes.salvar(path, {"verificar_atualizacoes": False})
    assert configuracoes.carregar(path) == {"notificacoes": True, "verificar_atualizacoes": False,
                                            "observacao_pit": "Conforme PIT docente."}


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
