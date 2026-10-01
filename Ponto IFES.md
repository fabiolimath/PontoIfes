# Controle de Ponto - IFES
Quero automatizar o controle de ponto que preciso fazer no trabalho. São três tarefas executadas numa página web. Parte delas só pode ser feita enquanto estou no campus. Não tenho uma máquina no campus para hospedar o serviço. Por isso, é preciso que seja executado no celular, Android. 

Nunca programei aplicativos móveis. Então quero que você me sugira uma abordagem para implementar isso. Vamos primeiro analisar os procedimentos na página?

Os três procedimentos são:
1.

## Abrir o ponto

1. Faz: a
1.Após o login;
2. A, abre uma página onde devo clicar noem um botão "Registrar Entrada";
3. Sou direcionado para a página de RH. 

Está aberto o ponto. Deve ser executado sempre que chego ao campus, e só pode ser executado se estou no campus. 

**Estratégia de automação**: executar após o telefone se conectar à rede do campus.

## Fechar o ponto: após

1. Faz o login;
2. Com o ponto aberto, vai direto para a página de RH;
3. Nela, devo clicar no botão "Ponto Eletrônico";
4. Vai para uma nova página. Nela, eu clico no botão "Registrar Saída";
5. Caio na página de abrir o ponto. 

Está fechado o ponto. Deve ser executado sempre que o ponto for aberto, e também só pode ser executado se eu estiver no campus. Como estratégia de automação, esse procedimento pode ser sempre executado em dias e horários específicos. Mas esses dias e horários mudam todo semestre. Não são todos os dias da semana e nem sempre no mesmo horário. E pode ocorrer mais de uma vez por dia. Não é problema se for executada sem que o ponto esteja aberto ou sem que eu esteja no campus, seguindo o agendamento. Nesses casos, o procedimento seria desnecessário, mas ele apenas retornaria erro, sem consequência.
. Registro de PIT: supondo que o ponto esteja fechado o loginlico em um botão diferente da abertura do ponto. Dou OK em um diálogo pop-up (não é uma página separada). Então sou direcionado para a página de RH. Nela, devo entrar em uma opção do menu. Vai abrir um formulário. Devo escolher uma opção em um combo box que modifica o formulário. Depois, entro com a data do dia em um campo. Então, tenho de aguardar o retorno da página, que vai fazer uma consulta ao sistema interno e depois a página vai preencher um campo de horas. Por fim, preencho mais um campo com um texto padrão e clico em um botão para enviar o formulário. Então a página retorna ao formulário original. O procedimento está feito. Esse procedimento não depende de eu estar no campus. Tem que ser feito de segunda a sexta. Tendo batido ponto ou não. Mas, nos dias em que bato ponto, tem que ser feito após fechar o ponto. Como estratégia de automação, basta executá-lo de segunda a sexta em um horário em que o ponto sempre já estará fechado.



<!--stackedit_data:
eyJoaXN0b3J5IjpbLTE3NTIwOTg0OF19
-->