# Entregas do PI
### **DECLARAÇÃO DE USO DE INTELIGÊNCIA ARTIFICIAL**

## Entrega 1
<br><br>

### Firmware / IoT (ESP32 e MPU-6050)

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | IoT |
| **Finalidade de uso** | Apoio à implementação, depuração e compreensão do firmware que coleta dados do MPU-6050, detecta movimentos da raquete e identifica golpes no ESP32. |
| ***Prompts* principais utilizados** | "Preciso criar um sistema que integre um esp32 e um mpu6050, esse sistema deve ler a movimentação da raquete e descobrir o golpe executado, como eu posso ligar o esp32 ao mpu? Detalhe as conexões.", "Agora preciso que você crie um código que seja capaz de identificar os golpes, no momento deve verificar somente forehand, backhand e saque", "Como eu posso fazer a calibração e quantos golpes preciso treinar cada classe?", "Quero enviar os golpes reconhecidos via Bluetooth para o meu celular, vou estar usando o app Bluetooth Serial no momento, adapte o código para essa mudança" |
| **Trechos ou entregas afetadas** | Firmware Arduino/ESP32 (`detector_golpes.ino` e revisões); configuração do MPU-6050; detecção por aceleração e velocidade angular; treinamento e persistência na NVS/Preferences. |


### Aplicativo Android

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | SmartTennisApp |
| **Finalidade de uso** | Apoio à implementação e correção de classes Java, autenticação local e estrutura inicial de persistência do aplicativo. |
| ***Prompts* principais utilizados** | "Comecei a desenvolver o meu aplicativo Android para o meu projeto, preciso que você melhore o visual das telas de login, cadastro e a homepage. Todos os dados do projeto devem estar armazenados localmente, use o room + SQLite para armazenar os dados de login", "Conecte a tela de treino livre que criei com o esp32, o usuário só deve ser capaz de iniciar o treinamento quando a raquete estiver conectada, melhore o design da tela também.", "Crie uma tela de treinos guiados com uma seleção pré-estabelecida de treinos, esses treinos devem estar de acordo com os golpes enviados pelo esp.", "Preciso que você crie um framework de criação de desafios, cada usuário pode criar desafios personalizados seguindo o modelo dos desafios que já estão disponíveis no aplicativo.", "Conecte o aplicativo e o banco de dados room ao backend que foi feito, lembrando que o aplicativo ainda deve continuar funcionando mesmo estando offline." |
| **Trechos ou entregas afetadas** | Estrutura inicial do aplicativo Android em Java; classes `MainActivity.java`, `LoginActivity.java`, `Cadastro.java`, `HomePage.java` e `UsuarioDAO.java`, além dos recursos e layouts associados; fluxo de autenticação local, identificação do usuário, navegação e preparação da persistência com banco Room/SQLite. |


## Entrega 2
<br><br>

### Firmware / IoT (ESP32 e MPU-6050)

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | IoT |
| **Finalidade de uso** | Apoio à revisão e evolução do firmware, análise de erros e compatibilização do protocolo de comunicação com o aplicativo. |
| ***Prompts* principais utilizados** | "Para refletir um treino real, aumente a quantidade de classes reconhecidas, inclua forehand (drive, slice e voleio), backhand (drive, slice e voleio), saque (drive, smash), amortida e lob. Também melhore o sistema de detecção utilizando todos os eixos de giroscópio e aceleração do mpu6050.", "Proponha e implemente uma comunicação segura entre o esp e o aplicativo.", "Diminua a quantidade de classes reconhecidas no esp32, o modelo confunde muito entre as classes mais próximas e não consegue diferenciar voleio e amortida." |
| **Trechos ou entregas afetadas** | detector_golpes.ino; aquisição do MPU-6050 por I²C; detecção automática por aceleração/giro; filtro de Mahony; classificação k-NN com pesos de Fisher e limite de rejeição. |


### Aplicativo Android

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | SmartTennisApp |
| **Finalidade de uso** | Apoio à implementação, integração e depuração do aplicativo Android; revisão da comunicação Bluetooth, sincronização com o backend, interface e recursos de interação. |
| ***Prompts* principais utilizados** | "Crie a tela de ranking global, ela deve mostrar um ranking de todos os usuários, com os três primeiros em destaque. Também inclua uma tela de ranking para cada desafio.", "Conecte o banco de dados do aplicativo, com o backend na Azure. Crie também um sistema de sincronização, quando o backend não estiver disponível, o aplicativo guarda os dados em uma fila e envia quando estiver conectado novamente.", "Melhore e revise a interface do aplicativo, garanta que todo o aplicativo siga a mesma linguagem visual e corrija inconsistências.", "Permita que o usuário possa escolher entre compartilhar ou não, o desafio que ele criou." |
| **Trechos ou entregas afetadas** | Pacote Android em Java e recursos XML. Entre os arquivos/classes afetados ou revisados: `RaqueteBluetooth`, `ParserRaquete`, `ClasseGolpeRaquete`, `SwingEvento`, `AppDatabase`, `TreinoDAO`, `DesafioDAO`, `UsuarioDAO`, `SyncManager`, `BackendApi` e `SessaoRemota`; também autenticação, desafios, rankings e recursos sociais. |

### Visão computacional

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | Detecção e visão computacional |
| **Finalidade de uso** | Apoio à implementação do subsistema Python de visão computacional, incluindo depuração de integração, segurança, comunicação local e tratamento do fluxo da webcam. |
| ***Prompts* principais utilizados** | "Preciso criar um sistema de visão computacional para conectar ao esp32 do projeto, esse sistema deve rodar em um computador da mesma rede, receber a stream de vídeo via uma câmera conectada ao computador. Esse sistema vai ser responsável por identificar a bola, estimar a sua velocidade e local de impacto. Após ser iniciado no computador do usuário, ele deve ser controlado inteiramente pelo aplicativo do smart ace. A interface deve ser inteiramente em Streamlit, sendo possível rodar o sistema via terminal também.
", "Busque exemplos de modelos abertos que fazem algo similar e como eles podem ser usados no nosso projeto.", "Adapte o código para ele rodar somente se aceleração gráfica estiver disponível, o modelo rodando em software é muito lento.", "Verifique o código e busque melhorar a performance do modelo de detecção, ele está muito pesado", "Faça com que o sistema se conecte automaticamente ao aplicativo, descobrindo ele na rede local. Sincronize o treinamento do aplicativo com o modelo. O modelo só deve enviar os dados da velocidade e local de impacto durante o treinamento." |
| **Trechos ou entregas afetadas** | Subsistema Python de visão computacional, incluindo `vision/app.py`, `smarttennis_vision.py` e `smarttennis_worker.py`; fluxo de webcam, processamento de imagem, rastreamento/eventos da bola. |

### Backend / API

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | Backend |
| **Finalidade de uso** | Apoio à implementação e revisão da API de autenticação/sincronização |
| ***Prompts* principais utilizados** | "Criei um backend simples com login e cadastro. Preciso fortalecer a segurança dele usando algum tipo de criptografia. Ele deve rodar localmente no momento, em anexo está o que eu já fiz e o db.sql", "Crie as rotas e tabelas para armazenar os dados de treinamento do usuário, visualizar ranking global, adicionar e visualizar amigos.", "Adicione novas rotas e faça alterações ao banco de dados para acomodar as mudanças no aplicativo e a adição do sistema de câmeras", "Preciso enviar o meu backend e iniciar automaticamente ele na Azure, me ajude com o processo e indique qualquer modificação necessária" |
| **Trechos ou entregas afetadas** | Backend Node.js/Express e MySQL, incluindo `server.js`, `db.sql`/schema, rotas de autenticação e sincronização (`/api/auth/register`, `/api/auth/login` e `/api/sync`), recursos de ranking/social. |

### Dashboard

| **Aluno(s) Responsável(veis)** |Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira |
| :--- | :--- |
| **Ferramenta utilizada** | ChatGPT (OpenAI) |
| **Versão/modelo** | GPT5 |
| **Disciplina / Trabalho** | Dashboard |
| **Finalidade de uso** | Criação da dashboard e auxilio no design visual|
| ***Prompts* principais utilizados** | "Crie uma dashboard em streamlit para sintetizar todas as informações dos usuários disponíveis no banco de dados. Ela deve funcionar conectando diretamente às APIs na Azure. Também deve ser possível editar, adicionar e remover treinos através da dashboard.", "Adicione mais gráficos e melhore o design da dashboard seguindo a identidade visual do resto do projeto" |
| **Trechos ou entregas afetadas** | Dashboard em python, no arquivo `app.py` |

***Declaro que o uso acima descrito está em conformidade com o GUIA PARA O USO RESPONSÁVEL DE
INTELIGÊNCIA ARTIFICIAL NA FECAP e que o conteúdo final é de minha plena responsabilidade
autoral.***
