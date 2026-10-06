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

1. Tela principal com três botões, um por função, e campo de data para o PIT.
2. Credenciais pedidas na 1ª execução (usuário/senha do SIGRH; o usuário abre o teclado numérico),
   guardadas na área privada do app e editáveis no botão **Credenciais**.
3. **Notificação do sistema** com o resultado de cada execução (título com o status, texto com a
   última linha da saída), ativável nas Configurações; no Android 13+ pede a permissão ao abrir o app
   e ao salvar as Configurações.
4. **Log** das execuções (saída de cada script + data/hora + código de saída), com botão **Copiar**.
5. **Atalhos de launcher** (segurar o ícone → Abrir ponto, Fechar ponto, Registrar PIT), fixáveis.
6. Execução automática ao abrir via atalho/intent: extra `acao=abrir_ponto` (ou `fechar_ponto`,
   `registrar_pit`), ou o mesmo valor no dado (URI) do intent. Usado por Rotinas da Samsung e Tasker.
   - Tasker: ação **Executar app**, campo **Dado** = `abrir_ponto`, com **Sempre Iniciar Nova Cópia**
     marcado (sem isso, com o app aberto, ele só vem para a frente e não executa).
   - Para rodar com a tela desligada: bateria do app em **Sem restrições**.

## Estrutura

- `src/ponto/app.py` — interface Toga (botões, configurações, log, notificações).
- `src/ponto/executor.py` — roda os scripts como módulos (`runpy`, pacote `ponto.scripts`), definindo
  `os.environ` com as credenciais e capturando stdout/stderr para o log; um script por vez.
- `src/ponto/credenciais.py` — leitura/gravação das credenciais (JSON na área privada).
- `src/ponto/plataforma.py` — código específico do Android (intent, área de transferência,
  notificações, teclados); no PC devolve valores neutros.
- `src/ponto/scripts/` — os três scripts (leem `SIGRH_USER` e `SIGRH_PASS` do ambiente).
- `android/res/` — atalhos de launcher (`xml/shortcuts.xml`, `values/atalhos.xml`) e ícone das
  notificações (`drawable/ic_notificacao.xml`).
- `android/release.sh` — empacota, alinha e assina o APK release → `dist/ponto-ifes.apk`.
- `.github/workflows/release.yml` — build e publicação automáticos.
- `docs/` — site de instalação para usuários leigos (GitHub Pages, branch master, pasta /docs).
- `tests/` — pytest: `PYTHONPATH=src python -m pytest -q tests`.

## Telegram (removido)

- A notificação pelo Telegram (bot @MeuPontoIFESBot) foi substituída pelas notificações do sistema
  na 1.0.10. O segredo `TELEGRAM_TOKEN` do GitHub não é mais usado e pode ser apagado.

## Release

- Feito no GitHub Actions: botão **Run workflow** (aba Actions → Release) ou push de tag `vX.Y.Z`.
  O workflow lê a versão do `pyproject.toml`, falha se a tag já existir, gera o APK assinado e cria
  o release com o arquivo `ponto-ifes.apk`.
- Por isso, **todo PR que muda o app deve aumentar `version` no `pyproject.toml`** (é também o
  versionCode do Android). Tags v1.0.0 a v1.0.6 já existem.
- Segredos do repositório: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`.
- Chave de assinatura: `~/.config/ponto/ponto-release.jks` (alias `ponto`) no PC do autor, com
  cópia de segurança. Nunca entra no repositório (`*.jks` no `.gitignore`); sem a mesma chave, quem
  já instalou não consegue atualizar.
- O nome fixo do arquivo mantém válido o link do site:
  `https://github.com/fabiolimath/PontoIfes/releases/latest/download/ponto-ifes.apk`.
- O SDK Android não baixa no ambiente de nuvem do Claude; não tentar compilar o APK lá.

## Fluxo de trabalho

- Só o Claude edita o código, por PRs (rascunho); o autor faz o merge no site e clica em Run workflow.
- A cópia local do autor (fora da pasta do Mega) serve só para testar builds debug no celular via adb
  (`briefcase run android`).

## Pendências

- Testar os gatilhos automáticos (Bluetooth, horário, Wi-Fi) num dia útil.
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
