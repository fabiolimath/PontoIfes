# Ponto

App Android (Briefcase + Toga) que empacota os scripts do ponto do SIGRH:
**Abrir ponto**, **Fechar ponto** e **Registrar PIT**.

- Na primeira execução o app pede as credenciais (SIGRH e, opcionalmente, Telegram)
  e as guarda na área privada do app (`credenciais.json`, permissão 600). O botão
  **Credenciais** permite editá-las depois.
- Cada botão roda o script correspondente numa thread, mostrando a saída ao vivo.
- Cada execução é gravada em `ponto.log` (data/hora, saída e código de saída; últimas
  1000 linhas), visível no botão **Log**.

Os scripts ficam em `src/ponto/scripts/`. Eles continuam lendo as credenciais de
`SIGRH_USER`, `SIGRH_PASS`, `TELEGRAM_TOKEN` e `TELEGRAM_CHAT_ID`; o app define essas
variáveis antes de rodá-los com `runpy`, então eles também seguem funcionando no Termux.

## Uso no PC

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install briefcase

briefcase dev            # roda no desktop (GTK) para testar a interface
briefcase dev --test     # roda os testes
```

## Android

Com o celular ligado por USB e a depuração USB ativa:

```sh
briefcase create android
briefcase build android
briefcase run android    # instala e abre no aparelho via adb
```

Na primeira vez o Briefcase baixa sozinho o JDK e o Android SDK. Para gerar um APK e
instalar por sideload: `briefcase package android -p debug-apk` (o arquivo fica em `dist/`).

Depois de mudar o código, use `briefcase run android -u` para atualizar o app.
