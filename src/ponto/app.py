"""
Ponto: abre e fecha o ponto e registra o PIT no SIGRH.
"""

import asyncio

import toga
from toga.style.pack import COLUMN, ROW, Pack

from ponto import credenciais, executor


class Ponto(toga.App):
    def startup(self):
        dados = self.paths.data
        dados.mkdir(parents=True, exist_ok=True)
        self.cred_path = dados / "credenciais.json"
        self.log_path = dados / "ponto.log"
        self.rodando = False

        self.main_window = toga.MainWindow(title=self.formal_name)
        self._montar_principal()
        if credenciais.carregar(self.cred_path) is None:
            self.mostrar_credenciais()
        else:
            self.mostrar_principal()
        self.main_window.show()

    # -----------------------------------
    # TELA PRINCIPAL
    # -----------------------------------
    def _montar_principal(self):
        self.botoes = []
        for acao, rotulo in executor.ACOES.items():
            botao = toga.Button(
                rotulo,
                on_press=self._ao_tocar(acao),
                style=Pack(margin=(4, 0), height=64, font_size=16),
            )
            self.botoes.append(botao)

        self.status = toga.Label("Pronto.", style=Pack(margin=(8, 0)))
        self.saida = toga.MultilineTextInput(readonly=True, style=Pack(flex=1))

        rodape = toga.Box(
            children=[
                toga.Button("Credenciais", on_press=lambda w, **kw: self.mostrar_credenciais(),
                            style=Pack(flex=1, margin_right=4)),
                toga.Button("Log", on_press=lambda w, **kw: self.mostrar_log(),
                            style=Pack(flex=1, margin_left=4)),
            ],
            style=Pack(direction=ROW, margin_top=8),
        )
        self.tela_principal = toga.Box(
            children=[*self.botoes, self.status, self.saida, rodape],
            style=Pack(direction=COLUMN, margin=12),
        )

    def mostrar_principal(self):
        self.main_window.content = self.tela_principal

    def _ao_tocar(self, acao):
        async def handler(widget, **kwargs):
            await self.rodar(acao)
        return handler

    def _anexar(self, texto):
        self.saida.value += texto
        self.saida.scroll_to_bottom()

    async def rodar(self, acao):
        if self.rodando:
            return
        cred = credenciais.carregar(self.cred_path)
        if credenciais.faltando(cred):
            self.mostrar_credenciais()
            return

        self.rodando = True
        for botao in self.botoes:
            botao.enabled = False
        self.saida.value = ""
        self.status.text = f"Executando: {executor.ACOES[acao]}…"

        loop = asyncio.get_running_loop()

        def ao_escrever(texto):
            loop.call_soon_threadsafe(self._anexar, texto)

        try:
            codigo, _ = await loop.run_in_executor(
                None, executor.executar, acao, cred, self.log_path, ao_escrever
            )
            resultado = "concluído" if codigo == 0 else f"falhou (código {codigo})"
            self.status.text = f"{executor.ACOES[acao]}: {resultado}."
        finally:
            self.rodando = False
            for botao in self.botoes:
                botao.enabled = True

    # -----------------------------------
    # CREDENCIAIS
    # -----------------------------------
    def mostrar_credenciais(self):
        atuais = credenciais.carregar(self.cred_path)
        self.campos = {}
        filhos = []
        if atuais is None:
            filhos.append(toga.Label(
                "Informe as credenciais. Elas ficam guardadas só neste aparelho.",
                style=Pack(margin_bottom=8),
            ))
        for campo, rotulo in credenciais.CAMPOS.items():
            classe = toga.PasswordInput if campo in ("SIGRH_PASS", "TELEGRAM_TOKEN") else toga.TextInput
            entrada = classe(value=(atuais or {}).get(campo, ""), style=Pack(margin_bottom=8))
            self.campos[campo] = entrada
            filhos += [toga.Label(rotulo), entrada]

        botoes = [toga.Button("Salvar", on_press=self.salvar_credenciais, style=Pack(flex=1))]
        if atuais is not None:
            botoes.insert(0, toga.Button("Cancelar", on_press=lambda w, **kw: self.mostrar_principal(),
                                         style=Pack(flex=1, margin_right=8)))
        filhos.append(toga.Box(children=botoes, style=Pack(direction=ROW, margin_top=8)))

        self.main_window.content = toga.ScrollContainer(
            horizontal=False,
            content=toga.Box(children=filhos, style=Pack(direction=COLUMN, margin=12)),
        )

    async def salvar_credenciais(self, widget, **kwargs):
        novas = {campo: entrada.value for campo, entrada in self.campos.items()}
        falta = credenciais.faltando(novas)
        if falta:
            await self.main_window.dialog(toga.ErrorDialog(
                "Credenciais incompletas", "Preencha: " + ", ".join(falta) + "."
            ))
            return
        credenciais.salvar(self.cred_path, novas)
        self.mostrar_principal()

    # -----------------------------------
    # LOG
    # -----------------------------------
    def mostrar_log(self):
        self.texto_log = toga.MultilineTextInput(
            readonly=True, value=self._ler_log(), style=Pack(flex=1)
        )
        botoes = toga.Box(
            children=[
                toga.Button("Voltar", on_press=lambda w, **kw: self.mostrar_principal(),
                            style=Pack(flex=1, margin_right=4)),
                toga.Button("Limpar", on_press=self.limpar_log,
                            style=Pack(flex=1, margin_left=4)),
            ],
            style=Pack(direction=ROW, margin_top=8),
        )
        self.main_window.content = toga.Box(
            children=[self.texto_log, botoes],
            style=Pack(direction=COLUMN, margin=12),
        )
        self.texto_log.scroll_to_bottom()

    def _ler_log(self):
        if self.log_path.exists():
            return self.log_path.read_text(encoding="utf-8")
        return "Nenhuma execução registrada ainda."

    async def limpar_log(self, widget, **kwargs):
        confirmar = await self.main_window.dialog(toga.ConfirmDialog(
            "Limpar log", "Apagar todo o histórico de execuções?"
        ))
        if confirmar:
            self.log_path.unlink(missing_ok=True)
            self.texto_log.value = self._ler_log()


def main():
    return Ponto()
