# Entregas do PI
### **DECLARAÇÃO DE USO DE INTELIGÊNCIA ARTIFICIAL**

## Entrega 1
<br><br>

### Firmware / IoT (ESP32 e MPU-6050)

| **Aluno(s) Responsável(veis)** |[Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira] |
| :--- | :--- |
| **Ferramenta utilizada** | [ChatGPT (OpenAI)] |
| **Versão/modelo** | [GPT5] |
| **Disciplina / Trabalho** | [IoT] |
| **Finalidade de uso** | [Apoio à implementação, depuração e compreensão do firmware que coleta dados do MPU-6050, detecta movimentos da raquete e identifica golpes no ESP32.] |
| ***Prompts* principais utilizados** | [Auxiliar na leitura do acelerômetro e giroscópio MPU-6050 pelo ESP32; ajustar a aquisição I²C e os limiares de detecção; capturar janelas de amostras; implementar e revisar o treinamento/classificação; preparar a comunicação Bluetooth Classic com o aplicativo Android.] |
| **Trechos ou entregas afetadas** | [Firmware Arduino/ESP32 (`detector_golpes.ino` e revisões); configuração do MPU-6050; detecção por aceleração e velocidade angular; treinamento e persistência na NVS/Preferences.] |


### Aplicativo Android

| **Aluno(s) Responsável(veis)** |[Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira] |
| :--- | :--- |
| **Ferramenta utilizada** | [ChatGPT (OpenAI)] |
| **Versão/modelo** | [GPT5] |
| **Disciplina / Trabalho** | [SmartTennisApp] |
| **Finalidade de uso** | [Apoio à implementação e correção de classes Java, autenticação local e estrutura inicial de persistência do aplicativo.] |
| ***Prompts* principais utilizados** | [Revisar e corrigir as classes Java do aplicativo; conectar a identificação do usuário ao banco local; preparar a integração do aplicativo com a raquete ESP32.] |
| **Trechos ou entregas afetadas** | [Estrutura inicial do aplicativo Android em Java; classes `MainActivity.java`, `LoginActivity.java`, `Cadastro.java`, `HomePage.java` e `UsuarioDAO.java`, além dos recursos e layouts associados; fluxo de autenticação local, identificação do usuário, navegação e preparação da persistência com banco Room/SQLite.] |

## Entrega 2
<br><br>

### Firmware / IoT (ESP32 e MPU-6050)

| **Aluno(s) Responsável(veis)** |[Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira] |
| :--- | :--- |
| **Ferramenta utilizada** | [ChatGPT (OpenAI)] |
| **Versão/modelo** | [GPT5] |
| **Disciplina / Trabalho** | [IoT] |
| **Finalidade de uso** | [Apoio à revisão e evolução do firmware, análise de erros e compatibilização do protocolo de comunicação com o aplicativo.] |
| ***Prompts* principais utilizados** | [Revisar e depurar o firmware atualizado do ESP32; ajustar leitura do MPU-6050; melhorar filtragem e extração de características; revisar o classificador k-NN e a rejeição de movimentos fora do modelo; verificar os comandos e as mensagens transmitidas por Bluetooth.] |
| **Trechos ou entregas afetadas** | [detector_golpes.ino; aquisição do MPU-6050 por I²C; detecção automática por aceleração/giro; filtro de Mahony; classificação k-NN com pesos de Fisher e limite de rejeição; registro em LittleFS e protocolo Bluetooth Classic SPP. Também houve apoio à análise das revisões posteriores do catálogo de golpes para manter a compatibilidade com o aplicativo.] |


### Aplicativo Android

| **Aluno(s) Responsável(veis)** |[Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira] |
| :--- | :--- |
| **Ferramenta utilizada** | [ChatGPT (OpenAI)] |
| **Versão/modelo** | [GPT5] |
| **Disciplina / Trabalho** | [SmartTennisApp] |
| **Finalidade de uso** | [Apoio à implementação, integração e depuração do aplicativo Android; revisão da comunicação Bluetooth, sincronização com o backend, interface e recursos de interação.] |
| ***Prompts* principais utilizados** | [Adaptar o parser Android às classes e mensagens emitidas pelo firmware; corrigir conexão e leitura Bluetooth; revisar regras de correspondência entre golpes e metas; manter funcionamento offline com Room; melhorar telas, navegação e seleção de avatares; integrar compartilhamento de treinos e recursos sociais; corrigir erros de compilação e de execução.] |
| **Trechos ou entregas afetadas** | [Pacote Android em Java e recursos XML. Entre os arquivos/classes afetados ou revisados: `RaqueteBluetooth`, `ParserRaquete`, `ClasseGolpeRaquete`, `SwingEvento`, `AppDatabase`, `TreinoDAO`, `DesafioDAO`, `UsuarioDAO`, `SyncManager`, `BackendApi` e `SessaoRemota`; também autenticação, desafios, rankings e recursos sociais.] |

### Backend / API e visão computacional

| **Aluno(s) Responsável(veis)** |[Guilherme Barioni, Iury Xavier, Lilian Conde, Marcus Duque e Murilo Vieira] |
| :--- | :--- |
| **Ferramenta utilizada** | [ChatGPT (OpenAI)] |
| **Versão/modelo** | [GPT5] |
| **Disciplina / Trabalho** | [Backend] |
| **Finalidade de uso** | [Apoio à implementação e revisão da API de autenticação/sincronização e do subsistema Python de visão computacional, incluindo depuração de integração, segurança, comunicação local e tratamento do fluxo da webcam.] |
| ***Prompts* principais utilizados** | [Revisar e corrigir a API Node.js/Express e a conexão MySQL; revisar validação de entradas e proteção das rotas; corrigir recursos de ranking, desafios e compartilhamento de treinos; integrar o aplicativo à estação de visão;] |
| **Trechos ou entregas afetadas** | [Backend Node.js/Express e MySQL, incluindo `server.js`, `db.sql`/schema, rotas de autenticação e sincronização (`/api/auth/register`, `/api/auth/login` e `/api/sync`), recursos de ranking/social. Subsistema Python de visão computacional, incluindo `vision/app.py`, `smarttennis_vision.py` e `smarttennis_worker.py`; fluxo de webcam, processamento de imagem, rastreamento/eventos da bola.] |

***Declaro que o uso acima descrito está em conformidade com o GUIA PARA O USO RESPONSÁVEL DE
INTELIGÊNCIA ARTIFICIAL NA FECAP e que o conteúdo final é de minha plena responsabilidade
autoral.***
