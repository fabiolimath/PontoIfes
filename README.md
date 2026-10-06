# Ponto IFES

> **Quer só instalar e usar o app?** Vá para a página do Ponto IFES:
> **<https://fabiolimath.github.io/PontoIfes/>**. Lá estão o download, a instalação e o uso
> no dia a dia. Este README é para quem quer entender como o projeto funciona por dentro.

App Android (Briefcase + Toga) que empacota os scripts do ponto do SIGRH:
**Abrir ponto**, **Fechar ponto** e **Registrar PIT**. A página acima fica em `docs/`.

- Na primeira execução o app pede as credenciais do SIGRH (o usuário abre o teclado numérico) e as
  guarda na área privada do app (`credenciais.json`, permissão 600). O botão **Configurações**
  permite editá-las depois.
- Cada botão roda o script correspondente numa thread, mostrando a saída ao vivo.
- O campo "Registrar o PIT de outro dia" (dd/mm/aaaa, barras automáticas) é opcional: se
  preenchido, o botão Registrar PIT usa essa data em vez da data de hoje. O PIT do dia pede
  para fechar o ponto antes.
- O PIT só é registrado de segunda a sexta. O campo Observação do formulário recebe o texto
  padrão "Conforme PIT docente.", que pode ser trocado em **Configurações > Observação do PIT**.
- Ao terminar cada execução, o app mostra uma notificação do sistema com o resultado
  (pode ser desligada em **Configurações**; no Android 13+ o app pede a permissão).
  Na falha, ela traz o botão **Tentar de novo**; ao fechar o ponto, o botão **Registrar PIT**.
- O PIT do dia pode ser registrado sozinho ao fechar o ponto (**Configurações > Registrar o PIT
  do dia automaticamente**): no 1º fechamento do dia, ou no 2º, para quem fecha no almoço.
- Lembrete de fechar o ponto: se o ponto foi aberto e não foi fechado depois de um tempo
  (padrão 01:40, em **Configurações**), o app avisa com uma notificação com o botão
  **Fechar ponto**, mesmo fechado (alarme do Android; perde-se se o celular reiniciar).
- Ao abrir, o app verifica se há versão nova no GitHub (desligável em **Configurações**).
  O menu de três pontos tem **Sobre o Ponto IFES**, com a versão e o link do site.
- Cada execução é gravada em `ponto.log` (data/hora, saída e código de saída; últimas
  1000 linhas), visível no botão **Log**.

Os scripts ficam em `src/ponto/scripts/`. Eles continuam lendo as credenciais de
`SIGRH_USER` e `SIGRH_PASS`; o app define essas
variáveis antes de rodá-los com `runpy`, então eles também seguem funcionando no Termux.

## Atalhos e automação

- Segurando o ícone do app aparecem os atalhos **Abrir ponto**, **Fechar ponto** e
  **Registrar PIT**. Arraste um deles para fixar na tela inicial. Tocar no atalho abre o
  app e executa a ação na hora. Os atalhos são estáticos (`android/res/xml/shortcuts.xml`),
  por isso também aparecem para o Tasker e para as Rotinas da Samsung. Cada um tem um ícone
  verde próprio (play, stop, relógio, em `android/res/drawable*`); o emblema pequeno no canto
  é posto pelo launcher.
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

## Como o app é desenvolvido

1. Cada mudança vem num PR (rascunho) que aumenta o `version` do `pyproject.toml`.
2. O workflow do PR roda os testes e publica um APK de teste assinado, sempre em
   <https://github.com/fabiolimath/PontoIfes/releases/download/teste/ponto-ifes.apk>,
   que instala por cima do app para testar no celular.
3. O merge no `master` publica sozinho o release `vX.Y.Z` com `ponto-ifes.apk`, e o botão do
   site passa a baixar a versão nova.

Os detalhes dos workflows estão no fim deste arquivo. Para testar ou compilar no PC:

### Uso no PC

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install briefcase

briefcase dev            # roda no desktop (GTK) para testar a interface
briefcase dev --test     # roda os testes
```

### Android no PC

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

### Release assinado no PC

O caminho normal é o release automático (abaixo). Para gerar o APK assinado à mão, com a
chave do app:

```sh
briefcase create android   # só depois de mudar a versão ou o pyproject.toml
android/release.sh
```

Na primeira vez o script cria a chave em `~/.config/ponto/ponto-release.jks` e pede uma
senha. **Guarde uma cópia da chave e a senha.** O Android só aceita uma atualização
assinada com a mesma chave. Sem ela, quem já tem o app instalado precisa desinstalar
e digitar as credenciais de novo. O APK assinado sai em `dist/ponto-ifes.apk`.

Quem tem instalado um APK debug precisa desinstalá-lo antes de instalar o release,
porque as chaves são diferentes.

## Workflows do GitHub

### Testes e APK de teste em cada PR

O workflow `.github/workflows/pr.yml` roda os testes em cada PR e, se passarem, gera o APK
assinado com a chave do app e o publica no pré-release `teste`, sempre no mesmo endereço:
<https://github.com/fabiolimath/PontoIfes/releases/download/teste/ponto-ifes.apk>.
Como a chave é a mesma, ele instala por cima do app. O pré-release não conta como "latest",
então o site e o aviso de atualização continuam no último release oficial.

### Release automático (GitHub Actions)

Com os segredos configurados, o workflow `.github/workflows/release.yml` compila, assina
e publica o release, sem precisar do Briefcase no PC. Ele roda a cada push na branch
`master` (o merge de um PR): se o `version` do `pyproject.toml` ainda não tiver a tag
`vX.Y.Z`, o release sai sozinho; se já tiver, o workflow termina sem publicar nada.

Também dá para disparar à mão:

- pelo navegador: aba **Actions** > **Release** > **Run workflow**; ou
- pelo terminal: `git tag v1.0.5 && git push origin v1.0.5`.

Nesses dois casos, se a versão já tiver sido publicada ou se a tag não bater com o
`version`, o workflow para com erro antes de compilar. O workflow dos PRs falha se o app
mudar sem aumentar o `version`.

Segredos do repositório
(*Settings > Secrets and variables > Actions*), configuráveis uma vez com o `gh`:

```sh
base64 -w0 ~/.config/ponto/ponto-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD   # pede a senha da chave
```
