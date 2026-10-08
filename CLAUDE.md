# Projeto: App Android "Ponto IFES"

App Android (BeeWare: Briefcase + Toga, com Chaquopy) que empacota três scripts Python e os executa
sem Termux:

- **Abrir ponto** → `abrir_ponto.py`
- **Fechar ponto** → `fechar_ponto.py`
- **Registrar PIT** → `registrar_pit.py [dd/mm/aaaa] [--obs TEXTO]` (sem data, hoje; só de segunda a sexta;
  o texto da Observação vem das Configurações, padrão "Conforme PIT docente.")

O app está pronto, em uso pelo autor e distribuído a colegas por APK assinado (versão atual em
`pyproject.toml`).

## O que o app faz

1. Tela principal com três botões, um por função, e campo de data para o PIT. Durante a execução,
   barra animada, tempo decorrido ("Executando: Abrir ponto… 0:42") e botão **Cancelar** embaixo.
   Botões disponíveis ficam verdes (#388E3C, texto branco) e os indisponíveis, cinza; os das
   Configurações e do Log também são verdes. Na tela principal: os de ação, Configurações e
   Log verdes fora da
   execução, o Cancelar verde só durante ela
   (o executor define `PONTO_CANCELAR`; o script para antes de cada tentativa, nas esperas e logo
   antes do envio final, nunca depois dele; sai com código 130, sem notificação).
2. Credenciais pedidas na 1ª execução (usuário/senha do SIGRH; o usuário abre o teclado numérico),
   guardadas na área privada do app e editáveis em **Configurações**. Campos de data (PIT) e de
   hora (lembrete) com máscara (`mascara.py`) e teclado próprio.
3. **Notificação do sistema** com o resultado de cada execução (título com o status, texto com a
   última linha da saída), ativável nas Configurações; no Android 13+ pede a permissão ao abrir o app
   e ao salvar as Configurações. Na falha, botão **Tentar de novo** (reabre o app com os extras
   `acao` e, no PIT, `data`, com NEW_TASK|CLEAR_TASK). A notificação de falha fica na tela mesmo
   depois de um sucesso da mesma ação (idents próprios). Ao fechar o ponto com sucesso (seg a sex),
   botão **Registrar PIT** do dia; o PIT apaga essa notificação.
   - **PIT automático** (Configurações: Não / No 1º / No 2º fechamento do dia): após o fechamento
     nº N do dia (seg a sex), registra o PIT do dia em seguida; aí a notificação não tem o botão.
     Os fechamentos do dia ficam em `ultimo_fechamento.txt` ("aaaa-mm-dd N").
4. **Lembrete de fechar o ponto**: ao abrir o ponto com sucesso, agenda pelo AlarmManager
   (`setAndAllowWhileIdle`, funciona com o app fechado; pode atrasar minutos no Doze; perde-se ao
   reiniciar o celular) uma notificação para o tempo configurado depois (padrão 01:00, editável nas
   Configurações), com botão **Fechar ponto**. Mostrada pelo `LembreteReceiver` em Java; fechar o
   ponto com sucesso cancela. Abrir de novo não adia um lembrete pendente (`lembrete.txt`).
5. **Configurações** (`configuracoes.json`): credenciais, Observação do PIT, PIT automático,
   notificações, lembrete (ligado/tempo), verificar atualizações ao abrir (+ Verificar agora).
   Menu de três pontos: **Sobre o Ponto IFES** (versão, autor, botão para o site).
6. **Log** das execuções (saída de cada script + data/hora + código de saída), com botão **Copiar**.
7. **Atalhos de launcher** (segurar o ícone → Abrir ponto, Fechar ponto, Registrar PIT), fixáveis; ícone de
   cada um com o símbolo verde (play, stop, relógio). O emblema pequeno do canto é posto pelo
   launcher (o ícone do app) e não dá para mudar.
8. Execução automática ao abrir via atalho/intent: extra `acao=abrir_ponto` (ou `fechar_ponto`,
   `registrar_pit`), ou o mesmo valor no dado (URI) do intent. Usado por Rotinas da Samsung e Tasker.
   - Tasker: ação **Executar app**, campo **Dado** = `abrir_ponto`, com **Sempre Iniciar Nova Cópia**
     marcado (sem isso, com o app aberto, ele só vem para a frente e não executa).
   - Para rodar com a tela desligada: bateria do app em **Sem restrições**.

## Estrutura

- `src/ponto/app.py` — interface Toga (botões, configurações, log, notificações).
- `src/ponto/executor.py` — roda os scripts como módulos (`runpy`, pacote `ponto.scripts`), definindo
  `os.environ` com as credenciais e capturando stdout/stderr para o log; um script por vez.
- `src/ponto/credenciais.py` — leitura/gravação das credenciais (JSON na área privada).
- `src/ponto/configuracoes.py` — preferências, contagem de fechamentos do dia e lembrete agendado.
- `src/ponto/atualizacao.py` — consulta do último release no GitHub e `URL_SITE`.
- `src/ponto/mascara.py` — máscaras dd/mm/aaaa e hh:mm.
- `src/ponto/plataforma.py` — código específico do Android (intent, área de transferência,
  notificações, teclados); no PC devolve valores neutros.
- `src/ponto/scripts/` — os três scripts (leem `SIGRH_USER` e `SIGRH_PASS` do ambiente).
- `android/java/` — `LembreteReceiver.java`, que mostra a notificação do lembrete (incluído no
  Gradle por `build_gradle_extra_content` e no manifesto pelo `pyproject.toml`).
- `android/res/` — atalhos de launcher (`xml/shortcuts.xml`, `values/atalhos.xml`, ícones
  `drawable*/atalho_*.xml`) e ícone das notificações (`drawable/ic_notificacao.xml`).
- `android/release.sh` — empacota, alinha e assina o APK release → `dist/ponto-ifes.apk`.
- `.github/workflows/release.yml` — build e publicação automáticos.
- `.github/workflows/pr.yml` — em cada PR: testes e APK de teste assinado no pré-release `teste`.
- `docs/` — site de instalação para usuários leigos (GitHub Pages, branch master, pasta /docs),
  com capturas de tela em `docs/img/` (o login nelas é fictício).
- `tests/` — pytest: `PYTHONPATH=src python -m pytest -q tests`.

## Telegram (removido)

- A notificação pelo Telegram (bot @MeuPontoIFESBot) foi substituída pelas notificações do sistema
  na 1.0.10. O segredo `TELEGRAM_TOKEN` do GitHub não é mais usado e pode ser apagado.

## Release

- Automático no merge: cada push no master roda `release.yml`; se o `version` do `pyproject.toml`
  ainda não tem tag, gera o APK assinado e cria o release `vX.Y.Z` com `ponto-ifes.apk`; se já
  tem, termina sem publicar. O botão **Run workflow** e o push de tag `vX.Y.Z` continuam valendo.
- Por isso, **todo PR que muda o app deve aumentar `version` no `pyproject.toml`** (é também o
  versionCode do Android); o `pr.yml` falha se `src/`, `android/` ou o `pyproject.toml` mudarem sem isso. Várias mudanças
  no mesmo PR aberto ficam na mesma versão.
- Segredos do repositório: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`.
- Chave de assinatura: `~/.config/ponto/ponto-release.jks` (alias `ponto`) no PC do autor, com
  cópia de segurança. Nunca entra no repositório (`*.jks` no `.gitignore`); sem a mesma chave, quem
  já instalou não consegue atualizar.
- O nome fixo do arquivo mantém válido o link do site:
  `https://github.com/fabiolimath/PontoIfes/releases/latest/download/ponto-ifes.apk`.
- O SDK Android não baixa no ambiente de nuvem do Claude; não tentar compilar o APK lá.

## Fluxo de trabalho

- Só o Claude edita o código, por PRs (rascunho); o autor faz o merge no site, e o release sai sozinho.
- Para testar um PR, o autor instala no celular o APK de teste (mesma chave, instala por cima):
  `https://github.com/fabiolimath/PontoIfes/releases/download/teste/ponto-ifes.apk`. O pré-release
  `teste` é sempre o do último PR que rodou e não conta como "latest".
- A cópia local do autor (fora da pasta do Mega) ainda serve para builds debug via adb
  (`briefcase run android -u -d SERIAL`), mas não é mais necessária para testar.

## Pendências

- Testar os gatilhos automáticos (Bluetooth, horário, Wi-Fi) num dia útil.
- Testar num dia útil: botão Registrar PIT na notificação (o lembrete, o Fechar ponto pelo lembrete e
  o PIT automático já foram testados em 07/10). Da 1.0.13: tentativas longas no gatilho do Tasker ao
  chegar no campus (notificação de falha, mensagens curtas e Cancelar já testados em 08/10).
- O esquema antigo via Termux (wrappers do `setup.sh`, credenciais em `~/.config/ponto/credenciais.env`)
  é legado e foi substituído pelo app.

## Regras

- Nunca commitar credenciais; o repositório não deve conter valores reais.
- O site (`docs/`) é para quem só instala o APK: sem uso no PC, sem build e,
  no Tasker, só a opção Executar app (não Enviar Intent).

## Sobre o usuário

- Professor; comunica-se em português (Brasil).
- Usuário Linux experiente (20+ anos), formação em computação científica; programa ocasionalmente.
- Prefere a solução mais simples e robusta, terminal ou GUI indiferente.
- Quer sugestões proativas de ferramentas/fluxos mais modernos quando estiver usando algo menos eficiente.
- Acessa o celular pelo PC via SSH (Termux, porta 8022) e SFTP (Dolphin); sincroniza arquivos pelo Mega.
