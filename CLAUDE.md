# Projeto: App Android "Ponto" (empacotar scripts Python)

## Objetivo

Criar **um único app Android** que empacote três scripts Python existentes e permita executá-los sem Termux:

- **Abrir ponto** → `abrir_ponto.py`
- **Fechar ponto** → `fechar_ponto.py`
- **Registrar PIT** → `registrar_pit.py`

Requisitos:

1. Tela principal com três botões, um por função.
2. **Credenciais pedidas na 1ª execução**, armazenadas na área privada do app, com opção de editar depois.
3. **Visualizar o log** das execuções (saída de cada script + data/hora + código de saída).
4. **Atalhos de launcher** (app shortcuts: segurar o ícone → "Abrir ponto", "Fechar ponto", "PIT"), fixáveis na tela inicial.
5. **Executar automaticamente ao abrir via atalho/intent** (ex.: extra `acao=abrir_ponto`), para que Rotinas da Samsung ou o Tasker possam disparar a ação só abrindo o app, sem Termux.

## Stack escolhida

- **BeeWare: Briefcase + Toga** (Python → APK; widgets nativos; usa Chaquopy).
- Dependências dos scripts: `requests`, `beautifulsoup4` (Python puro, sem problemas no Android).
- Build no PC do usuário: Kubuntu 26.04 (Briefcase baixa JDK/Android SDK sozinho).
- Instalação no celular por APK (sideload) ou `briefcase run android` via adb.
- Alternativas descartadas: Flet (empacotamento menos maduro), Kivy/Buildozer (mais trabalhoso, visual não nativo).

## Situação atual (como funciona hoje, via Termux)

- Celular Samsung com Termux + Termux:Widget + Termux:Tasker + Tasker.
- Scripts sincronizados pelo Mega em `~/storage/shared/Mega/mobile/ponto/scripts/`.
- Os scripts leem as credenciais de **variáveis de ambiente**:
  - `SIGRH_USER`, `SIGRH_PASS` — login no sistema SIGRH (página web onde o ponto é registrado)
  - `TELEGRAM_TOKEN`, `TELEGRAM_CHAT_ID` — notificação do resultado via bot do Telegram
- As credenciais ficam em `~/.config/ponto/credenciais.env` (chmod 600), carregadas pelos wrappers.
- `setup.sh` (na pasta do Mega) recria o ambiente: instala python/requests/bs4/openssh e gera wrappers em
  `~/.termux/tasker/` (com `sleep 5` para esperar a rede) e `~/.termux/widget/dynamic_shortcuts/`.
- Cada wrapper: `termux-wake-lock` → carrega credenciais → `cd` na pasta dos scripts → `python <script>.py`
  → grava log em `Mega/mobile/ponto/logs/<script>.log` (últimas 300 linhas) → `termux-wake-unlock`.
- Gatilhos: um script roda ao conectar um aparelho Bluetooth, outro em horário fixo (ambos funcionavam
  pelas Rotinas da Samsung), e um ao conectar numa rede Wi-Fi (falhava nas Rotinas; migrado para Tasker,
  em teste).

## Notas de implementação

- Adaptação mínima dos scripts: o app define `os.environ[...]` com as credenciais e executa o script
  (ex.: `runpy.run_path`), capturando stdout/stderr para o log. Revisar os `.py` antes, para ver como
  leem as credenciais e o que imprimem.
- Manter a notificação via Telegram como está.
- Nunca commitar credenciais; o repositório não deve conter valores reais.

## Sobre o usuário

- Professor; comunica-se em português (Brasil).
- Usuário Linux experiente (20+ anos), formação em computação científica; programa ocasionalmente.
- Prefere a solução mais simples e robusta, terminal ou GUI indiferente.
- Quer sugestões proativas de ferramentas/fluxos mais modernos quando estiver usando algo menos eficiente.
- Acessa o celular pelo PC via SSH (Termux, porta 8022) e SFTP (Dolphin); sincroniza arquivos pelo Mega.
