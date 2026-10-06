"""
Ponto: abre e fecha o ponto e registra o PIT no SIGRH.
"""

import asyncio
from datetime import date, datetime, timedelta

import toga
from toga.style.pack import COLUMN, ROW, Pack

from ponto import atualizacao, configuracoes, credenciais, executor, mascara, plataforma

# Opções do PIT automático (configuracoes.PADRAO["pit_automatico"]).
PIT_AUTOMATICO = {
    0: "Não",
    1: "No 1º fechamento do dia",
    2: "No 2º fechamento do dia",
}
EXPLICACAO_PIT_AUTOMATICO = {
    0: "Registre o PIT pelo botão do app\nou pelo da notificação de ponto fechado.",
    1: "Para quem fecha o ponto uma vez por dia:\no PIT é registrado logo após o fechamento.",
    2: "Para quem fecha o ponto no almoço:\no PIT é registrado só após o 2º fechamento do dia.",
}


class Ponto(toga.App):
    def startup(self):
        dados = self.paths.data
        dados.mkdir(parents=True, exist_ok=True)
        self.cred_path = dados / "credenciais.json"
        self.log_path = dados / "ponto.log"
        self.config_path = dados / "configuracoes.json"
        self.fechamento_path = dados / "ultimo_fechamento.txt"
        self.lembrete_path = dados / "lembrete.txt"
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
        data = plataforma.data_do_intent(self)
        if acao:
            if acao == "registrar_pit" and data:
                self.data_pit.value = data
            self.loop.create_task(self.rodar(acao))
            return
        preferencias = configuracoes.carregar(self.config_path)
        # Na 1ª execução, a permissão é pedida ao salvar as configurações.
        notifica = preferencias["notificacoes"] or preferencias["lembrete_fechar"]
        if notifica and credenciais.carregar(self.cred_path) is not None:
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

        self.data_pit = toga.TextInput(placeholder="dd/mm/aaaa",
                                       on_change=self._mascara(mascara.formatar_data),
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

    @staticmethod
    def _mascara(formatar):
        """on_change que aplica a máscara `formatar` (data ou hora) enquanto se digita."""
        anterior = [""]

        def aplicar(widget, **kwargs):
            novo = widget.value
            apagando = len(novo) < len(anterior[0])
            formatado = formatar(novo, apagando)
            anterior[0] = formatado
            if formatado != novo:
                # Dispara on_change de novo, mas formatar o já formatado não muda nada.
                widget.value = formatado
                plataforma.cursor_no_fim(widget)
        return aplicar

    def _ao_tocar(self, acao):
        async def handler(widget, **kwargs):
            await self.rodar(acao)
        return handler

    def _anexar(self, texto):
        self.saida.value += texto
        self.saida.scroll_to_bottom()

    async def rodar(self, acao, encadeado=False):
        """Executa a ação. `encadeado`: PIT automático logo após fechar o ponto,
        sempre do dia (ignora o campo de data) e sem apagar a saída do fechamento."""
        if self.rodando:
            return
        cred = credenciais.carregar(self.cred_path)
        if credenciais.faltando(cred):
            self.mostrar_configuracoes()
            return

        args = ()
        data = "" if encadeado else self.data_pit.value.strip()
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
        # Some a notificação anterior desta ação: a do resultado novo aparece de novo,
        # em vez de só substituir em silêncio a que ainda estava na tela.
        # O PIT pelo botão também apaga a do fechamento, que tem o botão "Registrar PIT";
        # o automático a mantém, com o resultado do fechamento.
        apagar = [acao] + (["fechar_ponto"] if acao == "registrar_pit" and not encadeado else [])
        try:
            for outra in apagar:
                plataforma.cancelar_notificacao(self, self._ident_notificacao(outra))
        except Exception as exc:
            print("Erro ao apagar a notificação:", exc)
        for botao in self.botoes:
            botao.enabled = False
        self.saida.value = self.saida.value + "\n" if encadeado else ""
        self.status.text = f"Executando: {rotulo}…"

        loop = asyncio.get_running_loop()
        pit_em_seguida = False

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
            self._notificar(acao, self.status.text, saida, codigo, data)
            if acao == "abrir_ponto" and codigo == 0:
                self._agendar_lembrete()
            if acao == "fechar_ponto" and codigo == 0:
                vezes = configuracoes.registrar_fechamento(self.fechamento_path)
                self._cancelar_lembrete()
                pit_em_seguida = self._pit_automatico_agora(vezes)
            if acao == "registrar_pit" and data and codigo == 0:
                self.data_pit.value = ""
        finally:
            self.rodando = False
            for botao in self.botoes:
                botao.enabled = True
        if pit_em_seguida:
            await self.rodar("registrar_pit", encadeado=True)

    def _pit_automatico_agora(self, vezes):
        """Se o fechamento nº `vezes` do dia é o que registra o PIT sozinho (seg a sex)."""
        quando = configuracoes.carregar(self.config_path)["pit_automatico"]
        return quando in (1, 2) and vezes == quando and date.today().weekday() < 5

    def _notificar(self, acao, titulo, saida, codigo=0, data=""):
        """Notificação do sistema com o resultado (se ativada nas configurações).

        Na falha, a notificação ganha o botão "Tentar de novo" (com a mesma data do PIT).
        Ao fechar o ponto com sucesso num dia útil, ganha o botão "Registrar PIT" do dia.
        """
        if not configuracoes.carregar(self.config_path)["notificacoes"]:
            return
        botao = None
        if codigo != 0:
            repetir = {plataforma.EXTRA_ACAO: acao}
            if acao == "registrar_pit" and data:
                repetir[plataforma.EXTRA_DATA] = data
            botao = ("Tentar de novo", repetir)
        elif (acao == "fechar_ponto" and date.today().weekday() < 5
              and not configuracoes.carregar(self.config_path)["pit_automatico"]):
            # Com o PIT automático ligado, o app registra sozinho: sem botão.
            botao = (executor.ACOES["registrar_pit"], {plataforma.EXTRA_ACAO: "registrar_pit"})
        try:
            plataforma.notificar(self, titulo, executor.mensagem_final(saida) or titulo,
                                 self._ident_notificacao(acao), botao=botao)
        except Exception as exc:
            print("Erro ao mostrar a notificação:", exc)

    def _agendar_lembrete(self, agora=None):
        """Agenda o lembrete de fechar o ponto, contado a partir da abertura.

        Se já há um lembrete pendente (o ponto foi aberto de novo, ou "já estava
        aberto"), ele fica como está, em vez de ser empurrado para mais tarde.
        """
        preferencias = configuracoes.carregar(self.config_path)
        tempo = configuracoes.tempo_lembrete(preferencias["lembrete_tempo"])
        if not preferencias["lembrete_fechar"] or tempo is None:
            return
        agora = agora or datetime.now()
        if configuracoes.lembrete_pendente(self.lembrete_path, agora):
            return
        quando = agora + tempo
        try:
            plataforma.agendar_lembrete(
                self, quando, "Ponto ainda aberto",
                f"O ponto foi aberto às {agora:%H:%M} e ainda não foi fechado.",
            )
        except Exception as exc:
            print("Erro ao agendar o lembrete:", exc)
            return
        configuracoes.registrar_lembrete(self.lembrete_path, quando)

    def _cancelar_lembrete(self):
        configuracoes.apagar_lembrete(self.lembrete_path)
        try:
            plataforma.cancelar_lembrete(self)
        except Exception as exc:
            print("Erro ao cancelar o lembrete:", exc)

    @staticmethod
    def _ident_notificacao(acao):
        """Uma notificação por ação: a nova substitui a anterior da mesma ação."""
        return list(executor.ACOES).index(acao) + 1

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

        preferencias = configuracoes.carregar(self.config_path)
        self.observacao_pit = toga.TextInput(
            value=preferencias["observacao_pit"],
            style=Pack(margin_bottom=8),
        )
        plataforma.preenchimento(self.observacao_pit)
        filhos += [toga.Label("Observação do PIT"), self.observacao_pit]

        self.pit_automatico = toga.Selection(
            items=list(PIT_AUTOMATICO.values()),
            value=PIT_AUTOMATICO.get(preferencias["pit_automatico"], PIT_AUTOMATICO[0]),
            on_change=self._explicar_pit_automatico,
            style=Pack(margin_bottom=4),
        )
        self.explicacao_pit = toga.Label("", style=Pack(margin_bottom=8, font_size=12))
        self._explicar_pit_automatico(self.pit_automatico)
        filhos += [toga.Label("Registrar o PIT do dia automaticamente"),
                   self.pit_automatico, self.explicacao_pit]

        self.notificacoes = toga.Switch(
            "Notificar o resultado de cada execução",
            value=preferencias["notificacoes"],
            style=Pack(margin=(8, 0)),
        )
        filhos.append(self.notificacoes)

        self.lembrete_fechar = toga.Switch(
            "Lembrar de fechar o ponto",
            value=preferencias["lembrete_fechar"],
            style=Pack(margin=(8, 0)),
        )
        self.lembrete_tempo = toga.TextInput(
            value=preferencias["lembrete_tempo"], placeholder="hh:mm",
            on_change=self._mascara(mascara.formatar_hora), style=Pack(width=88),
        )
        plataforma.campo_de_hora(self.lembrete_tempo)
        plataforma.preenchimento(self.lembrete_tempo)
        filhos += [
            self.lembrete_fechar,
            toga.Box(
                children=[
                    toga.Label("Avisar quanto tempo depois de abrir",
                               style=Pack(flex=1, margin_right=8)),
                    self.lembrete_tempo,
                ],
                style=Pack(direction=ROW, align_items="center", margin_bottom=8),
            ),
        ]

        self.verificar_atualizacoes = toga.Switch(
            "Verificar atualizações ao abrir o app",
            value=preferencias["verificar_atualizacoes"],
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
        tempo = self.lembrete_tempo.value.strip()
        if self.lembrete_fechar.value and configuracoes.tempo_lembrete(tempo) is None:
            await self.main_window.dialog(toga.ErrorDialog(
                "Tempo inválido",
                f"{tempo!r} não é um tempo no formato hh:mm (ex.: 01:40), entre 00:01 e 23:59.",
            ))
            return
        credenciais.salvar(self.cred_path, novas)
        configuracoes.salvar(self.config_path, {
            "notificacoes": self.notificacoes.value,
            "lembrete_fechar": self.lembrete_fechar.value,
            "lembrete_tempo": tempo if configuracoes.tempo_lembrete(tempo)
                              else configuracoes.PADRAO["lembrete_tempo"],
            "verificar_atualizacoes": self.verificar_atualizacoes.value,
            "observacao_pit": self.observacao_pit.value.strip() or configuracoes.PADRAO["observacao_pit"],
            "pit_automatico": self._opcao_pit_automatico(),
        })
        if not self.lembrete_fechar.value:
            self._cancelar_lembrete()
        if self.notificacoes.value or self.lembrete_fechar.value:
            self._pedir_permissao_notificacoes()
        self.mostrar_principal()

    def _opcao_pit_automatico(self):
        return next(n for n, texto in PIT_AUTOMATICO.items() if texto == self.pit_automatico.value)

    def _explicar_pit_automatico(self, widget, **kwargs):
        """Texto abaixo da seleção, conforme a opção escolhida."""
        self.explicacao_pit.text = EXPLICACAO_PIT_AUTOMATICO[self._opcao_pit_automatico()]

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
        """Tela "Sobre" em português, com o botão para abrir o site do app."""
        self.loop.create_task(self._sobre())

    async def _sobre(self):
        partes = [f"{self.formal_name} {self.version or ''}".strip()]
        if self.author:
            partes.append(f"Autor: {self.author}")
        if self.description:
            partes.append(f"\n{self.description}")
        partes.append(f"\nSite: {atualizacao.URL_SITE}")
        abrir = await self.main_window.dialog(plataforma.confirmacao(
            f"Sobre o {self.formal_name}", "\n".join(partes),
            sim="Abrir o site", nao="Fechar",
        ))
        if abrir:
            plataforma.abrir_url(self, atualizacao.URL_SITE)

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
