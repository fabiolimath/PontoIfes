# Ponto IFES

App Android (Briefcase + Toga) que empacota os scripts do ponto do SIGRH:
**Abrir ponto**, **Fechar ponto** e **Registrar PIT**.

- Na primeira execução o app pede as credenciais (SIGRH e, opcionalmente, o Chat ID do
  Telegram) e as guarda na área privada do app (`credenciais.json`, permissão 600). O
  botão **Credenciais** permite editá-las depois.
- Cada botão roda o script correspondente numa thread, mostrando a saída ao vivo.
- O campo "Registrar o PIT de outro dia" (dd/mm/aaaa) é opcional: se preenchido, o
  botão Registrar PIT usa essa data em vez da data de hoje.
- Cada execução é gravada em `ponto.log` (data/hora, saída e código de saída; últimas
  1000 linhas), visível no botão **Log**.

Os scripts ficam em `src/ponto/scripts/`. Eles continuam lendo as credenciais de
`SIGRH_USER`, `SIGRH_PASS`, `TELEGRAM_TOKEN` e `TELEGRAM_CHAT_ID`; o app define essas
variáveis antes de rodá-los com `runpy`, então eles também seguem funcionando no Termux.

## Atalhos e automação

- Segurando o ícone do app aparecem os atalhos **Abrir ponto**, **Fechar ponto** e
  **Registrar PIT**. Arraste um deles para fixar na tela inicial. Tocar no atalho abre o
  app e executa a ação na hora. Os atalhos são estáticos (`android/res/xml/shortcuts.xml`),
  por isso também aparecem para o Tasker e para as Rotinas da Samsung.
- No Tasker, o mais simples é a ação *Executar app* com o app Ponto IFES e, no campo
  **Dado**, o nome da ação: `abrir_ponto`, `fechar_ponto` ou `registrar_pit`. Marque
  **Sempre Iniciar Nova Cópia**: sem isso, se o app já estiver aberto em segundo plano,
  o Android só o traz para frente e a ação não roda.
- Qualquer automação que abra o app com o extra `acao` (ou com o dado `ponto://<ação>`)
  também dispara a ação. Alternativa no Tasker, *Sistema > Enviar Intent*:
  - Ação: `android.intent.action.VIEW`
  - Extra: `acao:abrir_ponto` (ou `fechar_ponto`, `registrar_pit`)
  - Pacote: `io.github.fabiolimath.ponto`
  - Classe: `org.beeware.android.MainActivity`
  - Alvo: Atividade

  O Tasker precisa da permissão "Sobrepor a outros apps" para abrir atividades em
  segundo plano. Para testar pelo PC:
  `adb shell am start -n io.github.fabiolimath.ponto/org.beeware.android.MainActivity --es acao abrir_ponto`
- Reabrir o app pela tela de recentes não repete a ação.

## Uso no PC

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install briefcase

briefcase dev            # roda no desktop (GTK) para testar a interface
briefcase dev --test     # roda os testes
```

## Token do bot do Telegram

O token do bot vai embutido no APK, mas nunca no Git. Antes de compilar:

```sh
cp src/ponto/segredo_telegram.py.exemplo src/ponto/segredo_telegram.py
# edite e coloque o token
```

Sem esse arquivo o app funciona normalmente, só sem notificações. Quem tiver o APK
consegue extrair o token, então não o distribua fora de quem pode usar o bot. Se o
token vazar, revogue no @BotFather (`/revoke`), atualize o arquivo e gere o APK de novo.

## Android

Com o celular ligado por USB e a depuração USB ativa:

```sh
briefcase create android
briefcase build android
briefcase run android    # instala e abre no aparelho via adb
```

Na primeira vez o Briefcase baixa sozinho o JDK e o Android SDK. Para gerar um APK e
instalar por sideload: `briefcase package android -p debug-apk` (o arquivo fica em `dist/`).

Depois de mudar só o código Python, `-u` basta (`briefcase package android -u -p debug-apk`).
Se mudar o `pyproject.toml`, o ícone ou algo em `android/`, recrie o projeto Android antes
com `briefcase create android` (confirme a sobrescrita).
