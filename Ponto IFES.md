# Controle de Ponto - IFES

Quero automatizar o controle de ponto que preciso fazer no trabalho. São três tarefas executadas numa página web. Parte delas só pode ser feita enquanto estou no campus. Não tenho uma máquina no campus para hospedar o serviço. Por isso, é preciso que seja executado no celular, Android. 

Nunca programei aplicativos móveis. Então quero que você me sugira uma abordagem para implementar isso. Vamos primeiro analisar os procedimentos na página?

Os três procedimentos são:

## Abrir o ponto

1. Faz login;
2. Abre uma página onde devo clicar no botão "Registrar Entrada";
4. Sou direcionado para a página de RH. 

Está aberto o ponto. Deve ser executado sempre que chego ao campus, e só pode ser executado se estou no campus. 

**Estratégia de automação**: executa após o telefone se conectar à rede do campus.

## Fechar o ponto

1. Faz login;
2. Supondo o ponto aberto, vai direto para a página de RH;
3. Nela, devo clicar no botão, "Ponto Eletrônico";
4. Cai em uma nova página. Nela, eu clico no botão "Registrar Saída";
5. Caio na página de abrir o ponto. 

Está fechado o ponto. Deve ser executado sempre que o ponto for aberto, e também só consegue ser executado com sucesso se eu estiver no campus. Se eu saio antes do horário vou fazer manualmente, tornando a ação desnecessária.

**Estratégia de automação:** esse procedimento pode ser sempre executado em dias e horários específicos. Mas esses dias e horários mudam todo semestre. Não são todos os dias da semana e nem sempre no mesmo horário. E pode ocorrer mais de uma vez por dia. Não é problema se for executada, seguindo o agendamento, sem que o ponto esteja aberto ou sem que eu esteja no campus. Nesses casos, o procedimento seria desnecessário, mas ele apenas retornaria erro, sem consequência. Simplifica a automação.

## Registro de PIT
 
1. Faz login;
2. Supondo que o ponto esteja fechado, clico  botão "Continuar Acessando o Sistema"; 
3. Abre um diálogo (não é uma página separada);
4. Clico em OK;
5. Então, sou direcionado para a página de RH;
6. Nela, devo entrar em uma opção do menu;
7. Solicitações > Ausências > Informar Ausência; 
8. Vai abrir um formulário;
9. Devo escolher uma opção "Registro de PIT" no combo box "Tipo";
10. Depois, entro com a data do dia no campo "Data de Início";
11. Então, tenho de aguardar o retorno da página, que vai fazer uma consulta ao sistema interno e depois a página vai preencher um campo "Quantidade de Horas";
12. Por fim, preencho o campo "Observação" com texto padrão "Registro de PIT";
13. Clico no botão "Cadastrar";
14. Então a página retorna ao formulário original. 

O procedimento está feito. Esse procedimento não depende de eu estar no campus. Tem que ser feito de segunda a sexta. Tendo batido ponto ou não. Mas, nos dias em que bato ponto, tem que ser feito após fechar o ponto. 

**Estratégia de automação:** basta executá-lo de segunda a sexta em um horário em que o ponto sempre já estará fechado. Pode ser feito tarde da noite.

## Observações sobre a página de login

A resposta da página de login é diferente em diferentes situações.

1. Depende se eu estou na rede do campus ou não.
2. Depende se o ponto está aberto ou não.
3. Depende se estou abrindo ou fechando o ponto no meio do dia;

Durante o dia, em um período que ainda não sei especificar, há um limite de tempo entre fechar o ponto e abrir novamente. De noite não. Isso não afeta a rotina dos procedimentos, mas sei que a página se comporta diferente, então pode afetar a iteração automatizada.



