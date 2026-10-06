"""
Ponto: abre e fecha o ponto e registra o PIT no SIGRH.
"""

import asyncio
from datetime import date, datetime

import toga
from toga.style.pack import COLUMN, ROW, Pack

from ponto import atualizacao, configuracoes, credenciais, executor, mascara, plataforma

class Ponto(toga.App):
    def startup(self):
        dados = self.paths.data
        dados.mkdir(parents=True, exist_ok=True)
        self.cred_path = dados / "credenciais.json"
        self.log_path = dados / "ponto.log"
        self.config_path = dados / "configuracoes.json"
        self.fechamento_path = dados / "ultimo_fechamento.txt"
        self.rodando = False

        self.main_window = toga.MainWindow(title=self.formal_name)
        # O Toga escreve "About ..." em inglês no menu de três pontos.
        if toga.Command.ABOUT in self.commands:
            self.commands[toga.Command.ABOUT].text = f"Sobre o {self.formal_name}"
        self._montar_principal()
        if credenciais.carregar(self.cred_path) is None:
            self.mostrar_configuracoes()
        else:
            self.mostrar_principal()
        self.main_window.show()

        # Aberto por um atalho ou pelo Tasker com o extra acao=...: executa já.
        acao = plataforma.acao_do_intent(self)
        if acao:
            self.loop.create_task(self.rodar(acao))
            return
        preferencias = configuracoes.carregar(self.config_path)
        # Na 1ª execução, a permissão é pedida ao salvar as configurações.
        if preferencias["notificacoes"] and credenciais.carregar(self.cred_path) is not None:
            self._pedir_permissao_notificacoes()
        if preferencias["verificar_atualizacoes"]:
            self.loop.create_task(self.verificar_atualizacao(avisar_sem_novidade=False))

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

        self._data_anterior = ""
        self.data_pit = toga.TextInput(placeholder="dd/mm/aaaa", on_change=self._mascara_data,
                                       style=Pack(margin_bottom=4))
        plataforma.campo_de_data(self.data_pit)
        plataforma.preenchimento(self.data_pit)

        self.status = toga.Label("", style=Pack(margin=(8, 0)))
        self.saida = toga.MultilineTextInput(readonly=True, style=Pack(flex=1))

        rodape = toga.Box(
            children=[
                toga.Button("Configurações", on_press=lambda w, **kw: self.mostrar_configuracoes(),
                            style=Pack(flex=1, margin_right=4)),
                toga.Button("Log", on_press=lambda w, **kw: self.mostrar_log(),
                            style=Pack(flex=1, margin_left=4)),
            ],
            style=Pack(direction=ROW, margin_top=8),
        )
        self.tela_principal = toga.Box(
            children=[
                *self.botoes,
                toga.Label("Registrar o PIT de outro dia", style=Pack(margin_top=4)),
                self.data_pit,
                self.status,
                self.saida,
                rodape,
            ],
            style=Pack(direction=COLUMN, margin=12),
        )

    def mostrar_principal(self):
        self.main_window.content = self.tela_principal

    def _mascara_data(self, widget, **kwargs):
        novo = widget.value
        apagando = len(novo) < len(self._data_anterior)
        formatado = mascara.formatar_data(novo, apagando)
        self._data_anterior = formatado
        if formatado != novo:
            # Dispara on_change de novo, mas formatar o já formatado não muda nada.
            widget.value = formatado
            plataforma.cursor_no_fim(widget)

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
            self.mostrar_configuracoes()
            return

        args = ()
        data = self.data_pit.value.strip()
        if acao == "registrar_pit" and data:
            try:
                datetime.strptime(data, "%d/%m/%Y")
            except ValueError:
                await self.main_window.dialog(toga.ErrorDialog(
                    "Data inválida", f"{data!r} não é uma data no formato dd/mm/aaaa."
                ))
                return
            args = (data,)

        # PIT do dia (sem data ou com a data de hoje) só depois de fechar o ponto.
        hoje = not args or datetime.strptime(args[0], "%d/%m/%Y").date() == date.today()
        rotulo = executor.ACOES[acao] + (f" ({args[0]})" if args else "")
        if acao == "registrar_pit" and hoje and not configuracoes.fechou_no_dia(self.fechamento_path):
            mesmo_assim = await self.main_window.dialog(plataforma.confirmacao(
                "Ponto não fechado",
                "O app não registrou o fechamento do ponto hoje.\n"
                "Feche o ponto antes de registrar o PIT do dia, ou informe a data "
                "para registrar o PIT de outro dia.",
                sim="Registrar mesmo assim",
            ))
            if not mesmo_assim:
                return
        if acao == "registrar_pit":
            args += ("--obs", configuracoes.carregar(self.config_path)["observacao_pit"])

        self.rodando = True
        for botao in self.botoes:
            botao.enabled = False
        self.saida.value = ""
        self.status.text = f"Executando: {rotulo}…"

        loop = asyncio.get_running_loop()

        def ao_escrever(texto):
            loop.call_soon_threadsafe(self._anexar, texto)

        try:
            codigo, saida = await loop.run_in_executor(
                None, lambda: executor.executar(
                    acao, credenciais.ambiente(cred), self.log_path, ao_escrever, args=args
                )
            )
            resultado = "concluído" if codigo == 0 else f"falhou (código {codigo})"
            self.status.text = f"{rotulo}: {resultado}."
            self._notificar(acao, self.status.text, saida)
            if acao == "fechar_ponto" and codigo == 0:
                configuracoes.registrar_fechamento(self.fechamento_path)
            if acao == "registrar_pit" and data and codigo == 0:
                self.data_pit.value = ""
        finally:
            self.rodando = False
            for botao in self.botoes:
                botao.enabled = True

    def _notificar(self, acao, titulo, saida):
        """Notificação do sistema com o resultado (se ativada nas configurações)."""
        if not configuracoes.carregar(self.config_path)["notificacoes"]:
            return
        try:
            ident = list(executor.ACOES).index(acao) + 1  # uma por ação
            plataforma.notificar(self, titulo, executor.mensagem_final(saida) or titulo, ident)
        except Exception as exc:
            print("Erro ao mostrar a notificação:", exc)

    def _pedir_permissao_notificacoes(self):
        """No Android 13+, pergunta uma vez se o app pode notificar."""
        try:
            plataforma.pedir_permissao_notificacoes(self)
        except Exception as exc:
            print("Erro ao pedir permissão de notificação:", exc)

    # -----------------------------------
    # CONFIGURAÇÕES
    # -----------------------------------
    def mostrar_configuracoes(self):
        atuais = credenciais.carregar(self.cred_path)
        self.campos = {}
        filhos = [toga.Label("Configurações",
                             style=Pack(font_size=20, font_weight="bold", margin_bottom=12))]
        if atuais is None:
            filhos.append(toga.Label(
                "Informe as credenciais.\nElas ficam guardadas só neste aparelho.",
                style=Pack(margin_bottom=8),
            ))
        for campo, rotulo in credenciais.CAMPOS.items():
            classe = toga.PasswordInput if campo == "SIGRH_PASS" else toga.TextInput
            entrada = classe(value=(atuais or {}).get(campo, ""), style=Pack(margin_bottom=8))
            self.campos[campo] = entrada
            if campo == "SIGRH_USER":
                plataforma.campo_numerico(entrada)
            plataforma.preenchimento(entrada, "username" if campo == "SIGRH_USER" else "password")
            filhos += [toga.Label(rotulo), entrada]

        self.observacao_pit = toga.TextInput(
            value=configuracoes.carregar(self.config_path)["observacao_pit"],
            style=Pack(margin_bottom=8),
        )
        plataforma.preenchimento(self.observacao_pit)
        filhos += [toga.Label("Observação do PIT"), self.observacao_pit]

        self.notificacoes = toga.Switch(
            "Notificar o resultado de cada execução",
            value=configuracoes.carregar(self.config_path)["notificacoes"],
            style=Pack(margin=(8, 0)),
        )
        filhos.append(self.notificacoes)

        self.verificar_atualizacoes = toga.Switch(
            "Verificar atualizações ao abrir o app",
            value=configuracoes.carregar(self.config_path)["verificar_atualizacoes"],
            style=Pack(margin=(8, 0)),
        )
        filhos += [
            self.verificar_atualizacoes,
            toga.Button(f"Verificar agora (versão instalada: {self.version or '?'})",
                        on_press=self._verificar_agora, style=Pack(margin_bottom=8)),
        ]

        botoes = [toga.Button("Salvar", on_press=self.salvar_configuracoes, style=Pack(flex=1))]
        if atuais is not None:
            botoes.insert(0, toga.Button("Cancelar", on_press=lambda w, **kw: self.mostrar_principal(),
                                         style=Pack(flex=1, margin_right=8)))
        filhos.append(toga.Box(children=botoes, style=Pack(direction=ROW, margin_top=8)))

        self.main_window.content = toga.ScrollContainer(
            horizontal=False,
            content=toga.Box(children=filhos, style=Pack(direction=COLUMN, margin=12)),
        )

    async def salvar_configuracoes(self, widget, **kwargs):
        novas = {campo: entrada.value for campo, entrada in self.campos.items()}
        falta = credenciais.faltando(novas)
        if falta:
            await self.main_window.dialog(toga.ErrorDialog(
                "Credenciais incompletas", "Preencha: " + ", ".join(falta) + "."
            ))
            return
        credenciais.salvar(self.cred_path, novas)
        configuracoes.salvar(self.config_path, {
            "notificacoes": self.notificacoes.value,
            "verificar_atualizacoes": self.verificar_atualizacoes.value,
            "observacao_pit": self.observacao_pit.value.strip() or configuracoes.PADRAO["observacao_pit"],
        })
        if self.notificacoes.value:
            self._pedir_permissao_notificacoes()
        self.mostrar_principal()

    async def _verificar_agora(self, widget, **kwargs):
        await self.verificar_atualizacao(avisar_sem_novidade=True)

    # -----------------------------------
    # ATUALIZAÇÕES
    # -----------------------------------
    async def verificar_atualizacao(self, avisar_sem_novidade):
        """Consulta o último release no GitHub e oferece baixar se for mais novo.

        Na checagem automática (ao abrir o app), falhas e "sem novidade" ficam em silêncio.
        """
        if not self.version:
            return
        loop = asyncio.get_running_loop()
        try:
            publicada = await loop.run_in_executor(None, atualizacao.ultima_versao)
        except Exception as exc:
            if avisar_sem_novidade:
                await self.main_window.dialog(toga.ErrorDialog(
                    "Atualizações", f"Não foi possível verificar: {exc}"
                ))
            return
        if atualizacao.mais_nova(publicada, self.version):
            baixar = await self.main_window.dialog(plataforma.confirmacao(
                "Nova versão disponível",
                f"A versão {publicada} está disponível (instalada: {self.version}).",
                sim="Baixar", nao="Agora não",
            ))
            if baixar:
                plataforma.abrir_url(self, atualizacao.URL_DOWNLOAD)
        elif avisar_sem_novidade:
            await self.main_window.dialog(toga.InfoDialog(
                "Atualizações", f"Você já tem a versão mais recente ({self.version})."
            ))

    # -----------------------------------
    # SOBRE
    # -----------------------------------
    def about(self):
        """Tela "Sobre" em português, com o botão para abrir o repositório."""
        self.loop.create_task(self._sobre())

    async def _sobre(self):
        partes = [f"{self.formal_name} {self.version or ''}".strip()]
        if self.author:
            partes.append(f"Autor: {self.author}")
        if self.description:
            partes.append(f"\n{self.description}")
        partes.append(f"\nRepositório: {atualizacao.URL_REPOSITORIO}")
        abrir = await self.main_window.dialog(plataforma.confirmacao(
            f"Sobre o {self.formal_name}", "\n".join(partes),
            sim="Abrir repositório", nao="Fechar",
        ))
        if abrir:
            plataforma.abrir_url(self, atualizacao.URL_REPOSITORIO)

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
                toga.Button("Copiar", on_press=self.copiar_log,
                            style=Pack(flex=1, margin=(0, 4))),
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

    async def copiar_log(self, widget, **kwargs):
        if plataforma.copiar(self, self._ler_log()):
            widget.text = "Copiado"
        else:
            await self.main_window.dialog(toga.InfoDialog(
                "Copiar log", "Copiar só está disponível no Android. Selecione o texto e copie."
            ))

    async def limpar_log(self, widget, **kwargs):
        confirmar = await self.main_window.dialog(plataforma.confirmacao(
            "Limpar log", "Apagar todo o histórico de execuções?", sim="Apagar"
        ))
        if confirmar:
            self.log_path.unlink(missing_ok=True)
            self.texto_log.value = self._ler_log()


def main():
    return Ponto()
