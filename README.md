# Ponto IFES

> **Quer só instalar e usar o app?** Vá para a página do Ponto IFES:
> **<https://fabiolimath.github.io/PontoIfes/>**. Lá estão o download, a instalação e o uso
> no dia a dia. Este README é para quem quer entender como o projeto funciona por dentro.

App Android nativo, em Kotlin, que abre e fecha o ponto e registra o PIT no SIGRH:
**Abrir ponto**, **Fechar ponto** e **Registrar PIT**. A página acima fica em `docs/`.

Até a 1.0.15 o app era feito em Python (Briefcase + Toga). A 2.0.0 é a mesma coisa reescrita em
Kotlin, com o mesmo comportamento, e instala por cima da 1.0.x sem perder credenciais,
configurações, log, ícone, atalhos fixados nem o lembrete pendente.

- Na primeira execução o app pede as credenciais do SIGRH (o usuário abre o teclado numérico) e as
  guarda na área privada do app (`credenciais.json`, permissão 600). O botão **Configurações**
  permite editá-las depois.
- Cada botão roda a ação em segundo plano, uma de cada vez, mostrando a saída ao vivo, o tempo
  decorrido e o botão **Cancelar**. O Cancelar vale antes de cada tentativa e nas esperas, nunca
  depois do envio final ao SIGRH.
- O campo "Registrar o PIT de outro dia" (dd/mm/aaaa, barras automáticas) é opcional: se
  preenchido, o botão Registrar PIT usa essa data em vez da data de hoje. O PIT do dia pede
  para fechar o ponto antes.
- O PIT só é registrado de segunda a sexta. O campo Observação do formulário recebe o texto
  padrão "Conforme PIT docente.", que pode ser trocado em **Configurações > Observação do PIT**.
- Ao terminar cada execução, o app mostra uma notificação do sistema com o resultado
  (pode ser desligada em **Configurações**; no Android 13+ o app pede a permissão).
  Na falha, ela traz o botão **Tentar de novo**; ao fechar o ponto, o botão **Registrar PIT**.
- O PIT do dia pode ser registrado sozinho (**Configurações > Registrar o PIT do dia
  automaticamente**): todo dia, de segunda a sexta, no horário escolhido (padrão 18:30), também
  nos dias sem ponto. O horário deve vir depois do último fechamento do dia.
- Lembrete de fechar o ponto: se o ponto foi aberto e não foi fechado depois de um tempo
  (padrão 01:00, no máximo 06:00, em **Configurações**), o app avisa com uma notificação com o
  botão **Fechar ponto**, mesmo fechado (alarme do Android). Se o celular reiniciar, o lembrete
  é reagendado; se a hora dele passou com o celular desligado, ele é esquecido.
- Ao abrir, o app verifica se há versão nova no GitHub (desligável em **Configurações**).
  O menu de três pontos tem **Sobre o Ponto IFES**, com a versão e o link do site.
- Cada execução é gravada em `ponto.log` (data/hora, saída e código de saída; últimas
  1000 linhas), visível no botão **Log**.

## Atalhos e automação

- Segurando o ícone do app aparecem os atalhos **Abrir ponto**, **Fechar ponto** e
  **Registrar PIT**. Arraste um deles para fixar na tela inicial. Tocar no atalho abre o
  app e executa a ação na hora. Os atalhos são estáticos (`app/src/main/res/xml/shortcuts.xml`),
  por isso também aparecem para o Tasker e para as Rotinas da Samsung. Cada um tem um ícone
  verde próprio (play, stop, relógio, em `app/src/main/res/drawable*`); o emblema pequeno no
  canto é posto pelo launcher.
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

  A classe tem o nome da versão Python: no app em Kotlin ela é um `activity-alias` da
  `MainActivity`, mantido para que ícone, atalhos fixados e automações antigas continuem
  valendo. O Tasker precisa da permissão "Sobrepor a outros apps" para abrir atividades em
  segundo plano. Para testar pelo PC:
  `adb shell am start -n io.github.fabiolimath.ponto/org.beeware.android.MainActivity --es acao abrir_ponto`
- Reabrir o app pela tela de recentes não repete a ação.

## Estrutura do código

Projeto Gradle (Kotlin DSL) com dois módulos:

- `sigrh/` — o cliente do SIGRH em Kotlin puro (JVM, sem Android): login, abrir e fechar o
  ponto e registrar o PIT, com OkHttp e Jsoup. A classe `Sigrh` devolve um `Resultado`
  (sucesso, fora da rede do campus, falha de conexão, recusado, envio incerto ou cancelado) e
  manda a saída linha a linha para a tela e o log. Os testes rodam contra um SIGRH falso
  (MockWebServer).
- `app/` — o app Android (Views e Material Components).
  - `app/src/main/kotlin/.../ponto/nucleo/` — regras sem Android, testadas na JVM: preferências,
    credenciais, máscaras, log, contagem de fechamentos, lembrete, notificações e atualização.
  - `app/src/main/kotlin/.../ponto/` — telas (`MainActivity`, `ConfiguracoesActivity`,
    `LogActivity`), a execução em andamento (`Execucao`), notificações (`Notificador`), alarme
    do lembrete (`Lembretes.kt`, com o `LembreteReceiver` e o `InicioReceiver`), verificação de
    atualização (`Atualizador`) e limpeza dos restos da versão Python (`Legado`).
  - `app/src/main/res/` — layouts, ícone do app (`mipmap-*`), atalhos e ícone das notificações.
- `icons/` — os ícones do app em PNG, nos vários tamanhos (originais dos `mipmap-*`).

Os dados ficam em `filesDir/data/`, nos mesmos arquivos da versão Python: `credenciais.json`,
`configuracoes.json`, `ultimo_fechamento.txt`, `lembrete.txt` e `ponto.log`.

## Como o app é desenvolvido

1. Cada mudança vem num PR (rascunho) que aumenta `val versao` em `app/build.gradle.kts`.
   O versionCode sai da mesma versão (X.Y.Z → X·1000000 + Y·10000 + Z·100).
2. O workflow do PR roda os testes e publica um APK de teste assinado, sempre em
   <https://github.com/fabiolimath/PontoIfes/releases/download/teste/ponto-ifes.apk>,
   que instala por cima do app para testar no celular.
3. O merge no `master` publica sozinho o release `vX.Y.Z` com `ponto-ifes.apk`, e o botão do
   site passa a baixar a versão nova.

Os detalhes dos workflows estão no fim deste arquivo.

### Compilar no PC

Precisa do JDK 17 e do Android SDK (o do Android Studio serve). Indique o SDK pela variável
`ANDROID_HOME` ou por `sdk.dir=` num `local.properties` na raiz (fora do Git). O Gradle Wrapper
baixa o resto.

```sh
./gradlew test               # testes dos dois módulos
./gradlew :sigrh:test        # só o cliente do SIGRH
./gradlew installDebug       # instala no celular ligado por USB (depuração USB ativa)
```

O build debug usa o pacote `io.github.fabiolimath.ponto.debug`, com outra chave: ele instala
**ao lado** do app oficial, sem mexer nele (as credenciais são digitadas de novo). Para ver o
que o app registra: `adb logcat --pid=$(adb shell pidof io.github.fabiolimath.ponto.debug)`.

### Release assinado no PC

O caminho normal é o release automático (abaixo). Para gerar o APK assinado à mão, com a
chave do app em `~/.config/ponto/ponto-release.jks` (alias `ponto`):

```sh
PONTO_KEYSTORE_PASS='senha da chave' ./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Outro caminho para a chave pode ser dado em `PONTO_KEYSTORE`. Sem a senha, o APK sai sem
assinatura. **Guarde uma cópia da chave e a senha.** O Android só aceita uma atualização
assinada com a mesma chave. Sem ela, quem já tem o app instalado precisa desinstalar e digitar
as credenciais de novo.

## Workflows do GitHub

### Testes e APK de teste em cada PR

O workflow `.github/workflows/pr.yml` roda `./gradlew test` em cada PR e, se os testes passarem,
gera o APK assinado com a chave do app (`assembleRelease`) e o publica no pré-release `teste`,
sempre no mesmo endereço:
<https://github.com/fabiolimath/PontoIfes/releases/download/teste/ponto-ifes.apk>.
Como a chave e o pacote são os mesmos, ele instala por cima do app. O pré-release não conta
como "latest", então o site e o aviso de atualização continuam no último release oficial.
O workflow falha se o app mudar sem aumentar `val versao`.

### Release automático (GitHub Actions)

Com os segredos configurados, o workflow `.github/workflows/release.yml` testa, compila, assina
e publica o release. Ele roda a cada push na branch `master` (o merge de um PR): se a versão de
`app/build.gradle.kts` ainda não tiver a tag `vX.Y.Z`, o release sai sozinho; se já tiver, o
workflow termina sem publicar nada.

Também dá para disparar à mão:

- pelo navegador: aba **Actions** > **Release** > **Run workflow**; ou
- pelo terminal: `git tag v2.0.1 && git push origin v2.0.1`.

Nesses dois casos, se a versão já tiver sido publicada ou se a tag não bater com a versão, o
workflow para com erro antes de compilar.

Segredos do repositório
(*Settings > Secrets and variables > Actions*), configuráveis uma vez com o `gh`:

```sh
base64 -w0 ~/.config/ponto/ponto-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD   # pede a senha da chave
```
