# Projeto: App Android "Ponto IFES"

App Android nativo em Kotlin que faz três ações no SIGRH:

- **Abrir ponto**
- **Fechar ponto**
- **Registrar PIT** (do dia ou de uma data dd/mm/aaaa; só de segunda a sexta; o texto da
  Observação vem das Configurações, padrão "Conforme PIT docente.")

E, desde a 2.1.0, abre o ponto sozinho ao conectar na Wi-Fi do campus (item 9).

O app está pronto, em uso pelo autor e distribuído a colegas por APK assinado (versão atual em
`val versao`, `app/build.gradle.kts`).

Até a 1.0.15 era um app Python (Briefcase + Toga, com Chaquopy). A 2.0.0 é a migração para
Kotlin com o mesmo comportamento, instalada por cima da 1.0.x (mesmo pacote
`io.github.fabiolimath.ponto`, mesma chave, mesmos arquivos de dados). O código Python saiu do
repositório e fica no histórico do Git (tag `v1.0.15`).

## O que o app faz

1. Tela principal com três botões, um por função, e campo de data para o PIT. Durante a execução,
   barra animada, tempo decorrido ("Executando: Abrir ponto… 0:42") e botão **Cancelar** embaixo.
   Botões disponíveis ficam verdes (#008577, o da barra do topo, texto branco) e os
   indisponíveis, cinza; os das Configurações e do Log também são verdes. Na tela principal:
   os de ação, Configurações e Log verdes fora da execução, o Cancelar verde só durante ela
   (`Cancelamento.cancelar()`, um sinal próprio e não o cancelamento da corrotina: a ação para
   antes de cada tentativa, nas esperas e logo antes do envio final, nunca depois dele; resultado
   `Cancelado`, código 130 no log, sem notificação).
2. Credenciais pedidas na 1ª execução num diálogo sobre a tela principal (`MainActivity.pedirCredenciais`,
   sem Cancelar, só fecha ao salvar os dois; também ao tocar numa ação sem elas), a única configuração
   obrigatória (usuário/senha do SIGRH; o usuário abre o teclado numérico), guardadas na área privada
   do app e editáveis em **Configurações**. Campos de data (PIT) e de
   hora (lembrete) com máscara (`nucleo/Mascara`, `MascaraDeTexto`) e teclado próprio.
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
   (`setAndAllowWhileIdle`, funciona com o app fechado; pode atrasar minutos no Doze) uma
   notificação para o tempo configurado depois (padrão 01:00, de 00:01 a 06:00, editável nas
   Configurações), com botão **Fechar ponto**. Mostrada pelo `LembreteReceiver`; fechar o ponto com
   sucesso cancela. Abrir de novo não adia um lembrete pendente (`lembrete.txt`). O `InicioReceiver`
   reagenda o pendente ao reiniciar o celular e ao atualizar o app (se a hora já passou, esquece).
5. **Configurações** (`configuracoes.json`), em seções (2.1.0), nesta ordem: Notificações;
   Credenciais; Abrir ponto (Wi-Fi); Fechar ponto (lembrete); Registrar PIT (Observação, PIT
   automático); Atualizações (verificar ao abrir + Verificar agora). Cada opção tem uma linha de
   explicação; os detalhes da Wi-Fi e do lembrete só aparecem com o interruptor ligado.
   Menu de três pontos: **Sobre o Ponto IFES** (versão, autor, botão para o site).
6. **Log** das execuções (`ponto.log`: saída de cada ação + data/hora + código de saída 0, 1 ou 130;
   últimas 1000 linhas), com botões **Copiar** e **Limpar**.
7. **Atalhos de launcher** (segurar o ícone → Abrir ponto, Fechar ponto, Registrar PIT), fixáveis; ícone de
   cada um com o símbolo verde (play, stop, relógio). O emblema pequeno do canto é posto pelo
   launcher (o ícone do app) e não dá para mudar.
8. Execução automática ao abrir via atalho/intent: extra `acao=abrir_ponto` (ou `fechar_ponto`,
   `registrar_pit`), ou o mesmo valor no dado (URI) do intent (`abrir_ponto` ou `ponto://abrir_ponto`);
   reabrir pela tela de recentes não repete. Usado por Rotinas da Samsung e Tasker. A entrada do
   launcher é o `activity-alias` `org.beeware.android.MainActivity` (nome da versão Python), alvo
   da `MainActivity`: ícone, atalhos fixados e automações antigas continuam valendo.
   - Tasker: ação **Executar app**, campo **Dado** = `abrir_ponto`, com **Sempre Iniciar Nova Cópia**
     marcado (sem isso, com o app aberto, ele só vem para a frente e não executa).
   - Para rodar com a tela desligada: bateria do app em **Sem restrições**.

9. **Abrir o ponto ao conectar na Wi-Fi** (2.1.0; plano em `planos/abrir-ponto-wifi.md` da pasta do
   projeto). Configurações: liga/desliga (`wifi_abrir`, padrão desligado), **Nome da rede** (`wifi_rede`,
   padrão `ws_wlan`), espera depois de fechar (`wifi_espera`, hh:mm, padrão 01:00, de 00:01 a 06:00),
   linha de estado (nome reconhecido ou não) e `aviso_bateria`.
   - Gatilho: `ConnectivityManager.registerNetworkCallback` com PendingIntent para o `RedeReceiver`
     (guardado pelo sistema, vale com o app fechado; o sistema chama na hora se já há Wi-Fi). Feito
     ao salvar (forçado ao ligar ou trocar a rede), ao abrir o app (só se o PendingIntent sumiu, por
     exemplo com Forçar parada), ao reiniciar e ao atualizar (`InicioReceiver`).
   - Filtro (`nucleo/GatilhoWifi`, sem rede): seg a sex; nome legível e diferente do configurado
     ignora (sem localização "o tempo todo" o nome é ilegível e o app tenta assim mesmo); ponto já
     aberto hoje (`ultima_abertura.txt` depois do último fechamento); a espera configurada após fechar; 5 min entre
     tentativas (`abertura_automatica.txt`). Ignorados relevantes vão ao log como
     "abrir_ponto (automático)".
   - Execução: `AbrirAutomaticoWorker` (WorkManager, expedito, trabalho único KEEP; no Android 11 ou
     anterior, serviço em primeiro plano com notificação mínima) chama `Execucao.abrirAutomatico`:
     aparece na tela como "Abrir ponto (automático)" com Cancelar; timeout de 15 s por requisição.
   - Resultado (`nucleo/AberturaAutomatica`): entrada registrada notifica e agenda o lembrete; já
     aberto fica em silêncio e agenda o lembrete pela hora de entrada; erros de rede em silêncio;
     recusa ou envio incerto notifica só com o nome da rede confirmado, uma vez por dia.
   - Pop-ups nas Configurações: localização ao ligar (Android 11+: segundo aviso e a tela do "tempo
     todo"); bateria ao salvar, se não está em Sem restrições (Liberar agora, Não avisar mais).

## Estrutura

Projeto Gradle (Kotlin DSL, Gradle Wrapper 8.14.3, AGP 8.13.0, Kotlin 2.2.21, JVM 17; minSdk 24,
compileSdk 36, targetSdk 35, porque com 36 o Android 16 força o edge-to-edge).

- `sigrh/` — cliente do SIGRH em Kotlin puro (JVM): `Sigrh` (`abrirPonto()`, `fecharPonto()`,
  `registrarPit(dia, observacao)`, todas `suspend`, devolvem `Resultado`), `AbrirPonto`,
  `FecharPonto`, `RegistrarPit`, `Sessao` (OkHttp, cookies, ISO-8859-1, `retryOnConnectionFailure`
  desligado no envio final), `Cancelamento`. `Resultado`: Sucesso, ForaDaRede, FalhaDeConexao,
  Recusado, EnvioIncerto, Cancelado. Tentativas com esperas de 10/20/30/60 s.
  Testes (JUnit 5) contra o `SigrhFalso` (MockWebServer) em `sigrh/src/test`.
- `app/src/main/kotlin/io/github/fabiolimath/ponto/nucleo/` — regras sem Android, testadas na JVM
  (JUnit 4, `app/src/test`): `Acao`, `Executor` (roda a ação e grava o log), `Registro` (log),
  `Credenciais`, `Preferencias` (`configuracoes.json`), `Mascara`, `Fechamentos`, `Lembrete`,
  `Notificacoes` (idents: sucesso 1 a 3, lembrete 4, abertura automática em andamento 5, falha
  1000+ pelo relógio; textos e botões), `Atualizacao`, `GatilhoWifi`, `AberturaAutomatica`.
- `app/src/main/kotlin/io/github/fabiolimath/ponto/` — Android: `MainActivity`, `LogActivity`,
  `ConfiguracoesActivity` (Views, não PreferenceFragment, porque os valores ficam no JSON),
  `Execucao` (estado da ação em andamento, notificação, lembrete, PIT automático), `Notificador`,
  `Lembretes.kt` (`Lembretes`, `LembreteReceiver`, `InicioReceiver`), `AbrirAutomatico.kt` (`Wifi`,
  `RedeReceiver`, `AbrirAutomaticoWorker`), `Atualizador` (GitHub, Sobre),
  `PermissaoDeNotificacao`, `MascaraDeTexto`, `Dados.kt` (arquivos em `filesDir/data/`) e
  `Legado` (apaga as pastas `chaquopy` da versão Python).
- `app/src/main/res/` — layouts, ícone do app (`mipmap-*`), atalhos (`xml/shortcuts.xml`, ícones
  `drawable*/atalho_*.xml`) e ícone das notificações (`drawable/ic_notificacao.xml`).
  - O launcher não resolve `@string` no `targetPackage` dos atalhos, então o pacote vai por
    extenso em duas cópias de `shortcuts.xml`: `app/src/main/res/xml` e `app/src/debug/res/xml`
    (pacote `.debug`). Mudou uma, mude a outra.
- `icons/` — ícone do app em PNG (logo do IFES no verde #008577), origem dos `mipmap-*`.
  `icons/antigos/` guarda os ícones no verde claro original (#7FC41C) do app, do site e dos
  atalhos, caso se queira voltar a eles.
- `.github/workflows/release.yml` — build e publicação automáticos.
- `.github/workflows/pr.yml` — em cada PR: testes e APK de teste assinado no pré-release `teste`.
- `docs/` — site de instalação para usuários leigos (GitHub Pages, branch master, pasta /docs),
  com capturas de tela em `docs/img/` (o login nelas é fictício).
- Dados do app (`filesDir/data/`, mesmos formatos da versão Python): `credenciais.json`,
  `configuracoes.json`, `ultimo_fechamento.txt` ("aaaa-mm-dd N hh:mm:ss" desde a 2.1.0; lê os
  formatos antigos), `lembrete.txt`, `ponto.log`; desde a 2.1.0, `ultima_abertura.txt` e
  `abertura_automatica.txt`.

## Telegram (removido)

- A notificação pelo Telegram (bot @MeuPontoIFESBot) foi substituída pelas notificações do sistema
  na 1.0.10. O segredo `TELEGRAM_TOKEN` do GitHub não é mais usado e pode ser apagado.

## Release

- Automático no merge: cada push no master roda `release.yml`; se a `val versao` de
  `app/build.gradle.kts` ainda não tem tag, gera o APK assinado e cria o release `vX.Y.Z` com `ponto-ifes.apk`; se já
  tem, termina sem publicar. O botão **Run workflow** e o push de tag `vX.Y.Z` continuam valendo.
- Por isso, **todo PR que muda o app deve aumentar `val versao` em `app/build.gradle.kts`** (o
  versionCode sai dela: X.Y.Z → X·1000000 + Y·10000 + Z·100, a fórmula do Briefcase); o `pr.yml`
  falha se `app/`, `sigrh/`, `gradle/` ou os arquivos Gradle da raiz mudarem sem isso. Várias
  mudanças no mesmo PR aberto ficam na mesma versão.
- Segredos do repositório: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`.
- Chave de assinatura: `~/.config/ponto/ponto-release.jks` (alias `ponto`) no PC do autor, com
  cópia de segurança. O Gradle a lê de `PONTO_KEYSTORE` (padrão, esse caminho) com a senha em
  `PONTO_KEYSTORE_PASS`; sem a senha, o release sai sem assinatura. Nunca entra no repositório (`*.jks` no `.gitignore`); sem a mesma chave, quem
  já instalou não consegue atualizar.
- O nome fixo do arquivo mantém válido o link do site:
  `https://github.com/fabiolimath/PontoIfes/releases/latest/download/ponto-ifes.apk`.
- O SDK Android e o AGP não baixam no ambiente de nuvem do Claude (dl.google.com bloqueado); não
  tentar compilar o módulo `app` lá: os erros dele aparecem no CI.
  - Lá dá para compilar e testar o `sigrh` com um settings temporário que inclui só esse módulo,
    usando repo1.maven.org e, como reserva (o Maven Central dá 429),
    maven-central.storage-download.googleapis.com/maven2 e plugins.gradle.org/m2.
  - As classes de `nucleo/` testam do mesmo jeito, num módulo JVM temporário cujo srcDir aponta
    para `nucleo/`, `sigrh/src/main/kotlin` e `app/src/test`, com `org.json:json`.
  - Nomes de teste só em ASCII (acentos quebram o compilador lá) e `runBlocking<Unit>` (sem o
    `<Unit>`, um teste que termina em `assertIs` é ignorado pelo JUnit).

## Fluxo de trabalho

- Só o Claude edita o código, por PRs (rascunho); o autor faz o merge no site, e o release sai sozinho.
- Para testar um PR, o autor instala no celular o APK de teste (mesma chave, instala por cima):
  `https://github.com/fabiolimath/PontoIfes/releases/download/teste/ponto-ifes.apk`. O pré-release
  `teste` é sempre o do último PR que rodou e não conta como "latest".
- A cópia local do autor (fora da pasta do Mega) ainda serve para builds debug via adb
  (`./gradlew installDebug`, pacote `.debug` que instala ao lado do oficial), mas não é mais
  necessária para testar.

## Pendências

- Testar os gatilhos automáticos (Bluetooth, horário, Wi-Fi) num dia útil.
- Testar num dia útil: botão Registrar PIT na notificação (o lembrete, o Fechar ponto pelo lembrete e
  o PIT automático já foram testados em 07/10). Da 1.0.13: tentativas longas no gatilho do Tasker ao
  chegar no campus (notificação de falha, mensagens curtas e Cancelar já testados em 08/10).
- Trocar a captura `docs/img/configuracoes.jpg` pela tela em seções (2.1.0).
- Testar a abertura pela Wi-Fi (2.1.0) pelo roteiro de `planos/abrir-ponto-wifi.md`.
- O esquema antigo via Termux (wrappers do `setup.sh`, credenciais em `~/.config/ponto/credenciais.env`)
  é legado, foi substituído pelo app e saiu do repositório na 2.0.0.

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
