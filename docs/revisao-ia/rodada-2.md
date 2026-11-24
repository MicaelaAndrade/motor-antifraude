# Revisão por IA — Rodada 2

- **Data:** 2026-11-24
- **Commit revisado:** `30fcc24` — fix: não perder eventos (idempotência liberada em falha, DLQ e envio de alerta confirmado)
- **Objetivo:** verificar se A1, A2 e A7 da [rodada 1](rodada-1.md) foram resolvidos e se as correções criaram problemas novos
- **Agentes:** `caca-bugs`, `arquiteto` e `codigo-morto`, em paralelo
- **Observação:** os agentes não alteraram arquivos. Os pontos de maior gravidade (N1, N2, A3) foram conferidos no código depois das revisões.

## Resumo consolidado

Os testes passam: 19 testes, 0 falhas, `BUILD SUCCESS` (`./mvnw -o test`).

A correspondência entre A1/A2/A7 e as correções foi confirmada pela [rodada 1](rodada-1.md).

### A1, A2 e A7 foram resolvidos?

**Parcialmente, nos três casos.** As correções fecham os casos em que o processo continua rodando, mas deixam aberto o caso de queda do processo.

| Item | Situação | O que ainda falta |
|---|---|---|
| **A1** – idempotência gravada antes de processar perdia o evento | ⚠️ Parcial | Cobre só uma falha que gera exceção. Se o pod cair (kill, OOM, deploy) depois do `registrar` e antes de publicar, a chave fica 24h no Redis. A nova tentativa é tratada como duplicada e o evento se perde. Um `Error` também não é capturado, porque o `catch` só pega `RuntimeException`. |
| **A2** – sem tratamento de erro/DLQ no consumidor | ✅ Resolvido, com ressalva | Retry e DLQ funcionam. O erro de JSON é corretamente tratado como não retentável: o Jackson 3 lança `JacksonException`, e o handler examina a causa da exceção. A ressalva é que o tópico `transactions-dlt` não é criado em lugar nenhum (veja N2). |
| **A7** – envio do alerta sem confirmação do broker | ✅ Resolvido, com efeito colateral | Uma falha no envio agora gera erro e o evento é reprocessado. Mas o `get(5s)` tem um timeout ambíguo e bloqueia a thread que lê as mensagens (veja N3 e N4). |

Também foram resolvidos de tabela o **A6** (eventId nulo agora vai para a DLQ) e a parte de organização do **M3** (`RegraController` movido para o pacote `api`, sem referências sobrando).

### Problemas novos causados pelas correções

**Alta gravidade**
- **N1 – Alertas duplicados.** Se uma transação quebra duas regras e só o envio do segundo alerta falha, a nova tentativa envia o primeiro de novo. Com até 4 tentativas, o cliente pode receber até 4 push ou e-mails iguais. O `alertaId` é sempre o mesmo para a mesma transação e regra, mas o `NotificacaoListener` não usa ele para descartar repetidos. Além disso, `geradoEm = Instant.now()` deixa cada envio com conteúdo diferente (`domain/Alerta.java:18`).
- **N2 – A DLQ não é criada.** Não há nenhum `NewTopic` declarado, e o docker-compose também não cria tópicos. Hoje funciona porque o broker cria tópicos automaticamente. Num broker com essa criação desligada, a publicação na DLQ falha e a mensagem volta para a fila sem parar. A partição trava e os alertas são reenviados a cada ciclo.
- **P2 – Falha do Redis dentro do `catch` perde o evento.** Se o `liberar()` falha (`TransacaoListener.java:54`), o erro original se perde e a chave continua gravada. Na nova tentativa o evento é tratado como duplicado, e a leitura é dada como concluída sem gerar o alerta nem ir para a DLQ.

**Média gravidade**
- **N3** – O `get(5s)` não cancela o envio, e o produtor continua tentando entregar por até 120s. Se o envio original chega depois do timeout, a nova tentativa gera um alerta duplicado.
- **N4** – O envio síncrono bloqueia a thread que lê as mensagens. Com o broker fora do ar, o `send()` pode esperar até 60s antes do `get`. O pior caso passa de 20s por registro. Isso trava a partição, é incompatível com a meta de 500 ms e pode estourar o `max.poll.interval.ms`, causando rebalance e abrindo outra forma de perder o evento.
- **Redis fora do ar por mais de cerca de 3s** manda todas as transações válidas para a DLQ, porque não há plano alternativo. Nada se perde, mas depois é preciso reprocessar tudo à mão.

**Baixa gravidade**
- `IllegalArgumentException` não ser retentável é amplo demais. Uma exceção própria, como `EventoInvalidoException`, seria mais segura.
- `acks=all` e `enable.idempotence=true` já são o padrão. Não causam problema.
- A DLQ guarda o conteúdo completo da transação (atenção à LGPD).

### Problemas antigos que continuam abertos
- **A3 – Cache de regras vazio no início** (`RegraCache.java:17`): eventos que chegam antes da primeira carga, ou com o Postgres fora do ar, são marcados como processados sem passar por nenhuma regra. Essa perda é silenciosa.
- Nada consome a `transactions-dlt`, e o diagrama só mostra a DLQ da notificação.
- Faltam testes para eventId inválido, `liberar()`, `AlertaPublisher` e um teste de integração da DLQ.
- Há código sem uso: o `spotbugs-exclude.xml` ficou órfão e há dependências de validação e de teste no `pom.xml` que nada usa.
- Os outros itens da rodada 1 (A4, A5, A8, M1–M6, B1–B4) e os requisitos de vazão e latência não foram tratados neste commit.

### Prioridade sugerida
1. **Corrigir A1 e P2:** trocar o par `registrar`/`liberar` por dois estados. "Em processamento" com prazo curto (2 a 5 min) e "concluído" gravado só depois de todos os alertas serem enviados.
2. **Corrigir N1:** descartar alertas repetidos pelo `alertaId` no consumidor de `alerts` e usar um `geradoEm` que não mude entre tentativas.
3. **Corrigir N2:** declarar `transactions` e `transactions-dlt` como `NewTopic`.
4. **Corrigir N3 e N4:** alinhar `delivery.timeout.ms` e `max.block.ms` ao tempo de espera.


---

## Relatório completo do agente caca-bugs

### Revisão do commit 30fcc24 (motor-deteccao): correções contra perda de eventos

Li todo o código em src/main, os testes e o application.properties. Não alterei nenhum arquivo. Também não executei build nem testes, porque não tenho shell nesta sessão. O que digo sobre comportamento do Spring Kafka e do cliente Kafka vem do conhecimento das bibliotecas na versão do pom (Boot 4.2, spring-kafka 4.x, Jackson 3), sem execução.

**Resumo:** o commit fecha os caminhos de perda "falha de publicação com o processo vivo" e "mensagem inválida que trava a partição". Ainda há perda em três situações: crash no meio do processamento, falha do Redis no `liberar` e consumo com o cache de regras vazio. O commit também traz dois problemas novos de severidade alta: alertas duplicados no retry, e um loop sem fim (com partição travada) se o tópico DLT não existir.

---

#### Por mudança

**1) `liberar` + try/catch + validação de eventId:** resolve em parte. Cobre só exceções do tipo RuntimeException lançadas dentro do laço com o processo vivo. Não cobre crash da JVM, `Error`, nem falha do próprio `liberar`. Faz o 1º alerta ser publicado de novo no retry (problema novo).

**2) KafkaConfig com DefaultErrorHandler, DLT e backoff:** resolve em parte.
- Funciona: o Boot aplica o único `CommonErrorHandler` à factory padrão. Partição -1 funciona: o DeadLetterPublishingRecoverer converte partição < 0 em `null`, e aí o particionador escolhe pela chave. O `KafkaTemplate<String,String>` é compatível com o consumidor de String.
- A classificação está certa: no Jackson 3, `tools.jackson.core.JacksonException` é a base unchecked de `StreamReadException`/`DatabindException`. O classificador do spring-kafka percorre as causas (`traverseCauses=true`), então chega à causa real dentro da `ListenerExecutionFailedException`.
- O que falta: o tópico `transactions-dlt` não é criado em lugar nenhum (problema novo, ver N2).

**3) AlertaPublisher com `.get(5s)` + acks=all/idempotence:** resolve em parte.
- Resolveu: o "fire-and-forget" que perdia alertas em silêncio.
- Criou: duplicação por timeout ambíguo e bloqueio da thread do listener.
- `acks=all` e `enable.idempotence=true` já são o padrão do kafka-clients 3.x. Explicitar não faz mal, mas não deduplica reenvios feitos pela aplicação.

**4) RegraController movido para `api`:** neutro. Fica dentro do component scan de `com.mica.motor`. Nenhum teste referencia o pacote antigo, e `target/classes` não tem classe velha em `regras/`. Não tem relação com perda de eventos.

---

#### PROBLEMAS RESOLVIDOS

- **Falha ao publicar o alerta perdia o evento.** Antes a chave ficava gravada e a reentrega era descartada. Agora a falha é confirmada pelo `.get` (AlertaPublisher.java:24), vira IllegalStateException, a chave é liberada (TransacaoListener.java:53-55) e o evento volta para retry e, se esgotar, para a DLT.
- **Mensagem malformada travava a partição.** `readValue` (TransacaoListener.java:34) lança JacksonException, que vai direto para a DLT (KafkaConfig.java:24).
- **Evento sem eventId ia para Redis como "evento:null".** Agora é barrado antes do `registrar` e cai na DLT (TransacaoListener.java:36-38).
- **Falha do Redis no `registrar`** (TransacaoListener.java:40, fora do try). Lança `RedisConnectionFailureException`, que é retentável: 3 tentativas e depois DLT. Nenhuma chave fica gravada, então o reprocessamento manual funciona. Ressalva: uma queda do Redis maior que cerca de 3s manda **tudo** para a DLT. Não perde, mas obriga a reprocessar em massa.

---

#### RESOLVIDOS PARCIALMENTE

**P1 – ALTA – Crash no meio do processamento ainda perde o evento** (TransacaoListener.java:40 e 45-56; IdempotenciaService.java:17-20)
- Cenário: o pod recebe SIGKILL/OOM, ou cai num deploy, depois do `setIfAbsent` e antes de publicar. O `catch` nunca roda, a chave fica no Redis por 24h e o offset não é commitado. Outra instância recebe o evento de novo, vê "duplicado" (linha 41) e commita. O alerta nunca sai.
- A mesma coisa acontece com `Error` (StackOverflowError, OOM), que não é capturado pelo `catch (RuntimeException)`.
- Correção: separar o estado "em processamento" (TTL curto, por exemplo 2 a 5 min) de "concluído" (gravado com SET só depois do laço, TTL de 24h). Em vez de `liberar`, gravar "concluído" ao final. Ou usar `finally` com uma flag de sucesso, capturando Throwable.

**P2 – ALTA – Falha do Redis dentro do `catch` engole a exceção original e perde o evento** (TransacaoListener.java:54)
- Se `redis.delete` lança (`RedisConnectionFailureException` num soluço do Redis), a exceção original `e` se perde: nem vira "suppressed".
- O handler recebe a exceção do Redis (retentável) e faz retry. Mas a chave **continua gravada**, então o retry cai em "duplicado" (linha 40-42), retorna normalmente e o offset é commitado. O evento não chega nem à DLT.
- Correção mínima:
  `try { idempotencia.liberar(id); } catch (RuntimeException ex) { e.addSuppressed(ex); } throw e;`
  Isso ainda deixa a chave presa: o TTL curto de "em processamento" (P1) é o que resolve de fato.

**P3 – ALTA (já existia, segue aberto) – Cache de regras vazio marca eventos como processados sem avaliar** (RegraCache.java:17, 24-26 e 28-39)
- O cache começa com `List.of()`. Se o listener consome antes do primeiro `recarregar`, ou se o Postgres está fora no boot, o laço não roda nenhuma regra. A chave fica gravada por 24h e o offset é commitado.
- Isso é perda silenciosa de detecção, e o retry e a DLT não ajudam.
- Correção: carregar as regras de forma síncrona no startup (ApplicationRunner, com fail-fast). No listener, lançar exceção retentável ou pausar o container se `atuais()` estiver vazio.

**P4 – MÉDIA – Reprocessamento manual da DLT**
- Para falhas dentro do try com `liberar` bem-sucedido: a chave foi removida, então o replay para `transactions` funciona.
- O replay **não** funciona se `liberar` falhou (P2): a chave fica 24h e o replay é descartado como duplicado.
- No replay, o 1º alerta é publicado de novo (ver N1).
- Na FrequenciaRegra (FrequenciaRegra.java:30-35), um replay com `dataHora` antiga faz `removeRangeByScore(0, antigo - janela)`, que não remove nada recente. O `zCard` conta eventos mais novos, fora da janela do evento antigo, e gera falso positivo. Também reinicia o TTL da chave na linha 32.
- Não existe consumidor nem ferramenta para `transactions-dlt`.
- Correção: um job de replay que reinjete com header de origem e use dedup por alertaId. Na regra de frequência, contar com `zCount(chave, agora - janela, agora)` em vez de `zCard`.

**P5 – MÉDIA – Exceções de dado retentadas à toa**
- Com `valor` ou `dataHora` nulos, as regras lançam NPE (ValorAltoRegra.java:21, FrequenciaRegra.java:27). Payload JSON `null` faz `transacao` ser null e dá NPE na linha 36.
- NPE é retentável: são 4 execuções, e cada uma pode publicar alertas antes de falhar (N1).
- Correção: validar os campos obrigatórios junto com o eventId (lançando IllegalArgumentException), ou incluir NullPointerException entre as não-retentáveis.

---

#### PROBLEMAS NOVOS

**N1 – ALTA – Alertas duplicados no retry, e não há dedup no consumidor** (TransacaoListener.java:46-51; Alerta.java:14-18; NotificacaoListener.java:23-29)
- O alertaId é determinístico (`transactionId + ":" + regraId`), mas `geradoEm = Instant.now()` muda a cada vez, então o payload nunca é idêntico.
- Cenário: a transação quebra VALOR_ALTO e FREQUENCIA. O alerta 1 é confirmado; o 2º `publicar` dá timeout. A chave é liberada e o retry republica o alerta 1. Com 1 + 3 tentativas, a conta pode receber até 4 push/e-mails do mesmo alerta, e mais um a cada replay da DLT.
- `NotificacaoListener` não deduplica por alertaId, e o `@RetryableTopic(attempts="4")` dele também reenvia.
- O `enable.idempotence` do produtor só deduplica retries internos de uma mesma sessão, não reenvios da aplicação.
- Correção:
  1. Dedup no consumidor de `alerts`: `SETNX notif:<alertaId>` com TTL, gravado depois de `gateway.enviar`, ou usar uma chave de idempotência no provedor.
  2. Registrar por regra quais alertas já foram confirmados (`alerta:<alertaId>` no Redis) e pular esses no retry.
  3. Não usar `Instant.now()`: usar `t.dataHora()` ou um valor estável.

**N2 – ALTA – DLT inexistente gera loop sem fim e partição travada** (KafkaConfig.java:17-18)
- Não há bean `NewTopic` para `transactions-dlt`. Em produção, `auto.create.topics.enable` costuma estar desligado.
- Cenário: depois de 4 tentativas, o recoverer tenta publicar. O produtor espera metadados por até `max.block.ms` (60s) e falha. O DefaultErrorHandler trata a falha de recuperação reposicionando o registro, que volta a ser processado.
- Resultado: loop infinito, a partição para e cada ciclo publica de novo os alertas já confirmados (N1).
- Correção: declarar `TopicBuilder.name("transactions-dlt").partitions(<mesmo nº de transactions>).build()`, com retenção definida. Alertar sobre falha do recoverer.

**N3 – MÉDIA – Timeout de 5s é ambíguo e gera mais duplicação** (AlertaPublisher.java:24 e 28-29)
- `get(5, SECONDS)` não cancela o envio. O registro continua no buffer do produtor e pode ser entregue até `delivery.timeout.ms` (120s padrão).
- Cenário: broker lento com latência de 6s. O `get` estoura, o listener faz retry e publica de novo; o envio original também chega. Resultado: dois alertas.
- Correção: alinhar `delivery.timeout.ms` e `request.timeout.ms` com o tempo de espera, ou esperar o resultado do `CompletableFuture` sem timeout menor que o do produtor. Deduplicar no consumidor (N1).

**N4 – MÉDIA – Envio síncrono na thread do listener ameaça o `max.poll.interval.ms`** (AlertaPublisher.java:24)
- Com o broker indisponível, `send()` pode bloquear até `max.block.ms` (60s) **antes** de devolver o future, então o limite de 5s não vale nesse caso.
- Pior caso por tentativa: nº de alertas × (60s + 5s). Com 2 regras, cerca de 130s por tentativa.
- No caminho feliz, mas degradado: `max.poll.records` = 500 (padrão), 150 transações suspeitas, cerca de 3s por ack. Isso dá uns 450s, acima dos 300s do `max.poll.interval.ms`. O consumidor é expulso, há rebalance e o lote é refeito em outra instância.
- Esse rebalance também abre a janela de corrida que você pediu para verificar:
  - Tudo começa com a instância A ainda processando o evento X, com a chave já gravada, quando o rebalance acontece.
  - A instância B recebe a partição. Ela vê X como "duplicado" e commita.
  - Se A falhar logo depois, ela libera a chave, mas não consegue mais fazer seek nem commit.
  - Resultado: X perdido.
- Fora de rebalance não há processamento paralelo do mesmo evento: o retry do DefaultErrorHandler acontece por seek no mesmo consumidor e na mesma partição.
- Correção: `max.block.ms` baixo (por exemplo 5000), `max.poll.records` menor (por exemplo 50), publicar o lote de alertas de forma assíncrona e esperar todos juntos, e o TTL curto de "em processamento" (P1).

**N5 – BAIXA – Não-retentável por causa encadeada**
- Como o classificador percorre a cadeia de causas, uma IllegalArgumentException em qualquer causa de uma IllegalStateException do publisher manda o evento direto para a DLT, sem retry.
- O risco é baixo hoje, mas é um efeito de pôr uma exceção tão genérica entre as não-retentáveis.
- Sugestão: criar uma exceção própria, `EventoInvalidoException`, e usar só ela.

**N6 – BAIXA – LGPD e retenção na DLT** (KafkaConfig.java:17)
- A DLT guarda o payload completo (accountId, valor, estabelecimento) e os headers `kafka_dlt-exception-*`, com stacktrace e mensagem que inclui o alertaId.
- Precisa de retenção e ACL definidas.
- Já existia: NotificacaoListener.java:33 loga a mensagem inteira em nível ERROR, e NotificacaoGateway.java:23-24 loga accountId.
- `logging.level.org.springframework.kafka.retrytopic=OFF` (application.properties:15) esconde erros do retry topic.

---

#### Testes

- **Nenhum teste existente quebra por código.** `TransacaoListenerTest` usa eventId "ev-1"; `liberar` é void no mock; nada referencia o pacote antigo do controller.
- `MotorDeteccaoApplicationTests` (`@SpringBootTest`) depende de Postgres, Redis e Kafka reais. Isso já era assim e não foi causado pelo commit. Não executei, então não confirmo se passa no CI.
- **Coberto:** só `liberaIdempotenciaQuandoPublicacaoFalha` (TransacaoListenerTest.java:80-90).
- **Sem cobertura:**
  - eventId nulo ou em branco.
  - `liberar` lançando exceção (P2).
  - `registrar` lançando exceção.
  - Falha no 2º alerta após o 1º confirmado (N1).
  - `IdempotenciaService.liberar` (IdempotenciaServiceTest não testa).
  - `AlertaPublisher` (nenhum teste: timeout, interrupção, falha do future).
  - `KafkaConfig`: nenhum teste de integração com `@EmbeddedKafka` que verifique os 4 tentativas, o envio para `transactions-dlt` e o desvio direto de JacksonException e IllegalArgumentException.

#### Arquivos relevantes
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/KafkaConfig.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/AlertaPublisher.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/domain/Alerta.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoListener.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraCache.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties
- /workspaces/motor-antifraude/motor-deteccao/src/test/java/com/mica/motor/kafka/TransacaoListenerTest.java

**Prioridade sugerida:** N1 e N2 primeiro (o commit introduziu), depois P1/P2 com o modelo de estados "em processamento" e "concluído", depois P3.

---

## Relatório completo do agente arquiteto

#### Revisão do commit 30fcc24 contra a arquitetura e os requisitos

As quatro mudanças vão na direção certa. Mas a DLQ criada não é a DLQ do diagrama, ela depende de auto-create, e a lógica de liberar a idempotência deixou dois buracos (crash e alerta duplicado). Os requisitos de 8.000/25.000 TPS e de 500 ms continuam sem cobertura. Fiz só leitura, nada foi alterado e nada foi executado.

##### O que o diagrama, o README e o docker-compose dizem

- **Diagrama** (`/workspaces/motor-antifraude/docs/arquitetura-motor-antifraude.drawio`): existe uma caixa "DLQ" (linha 46). Ela só recebe a seta tracejada "falhou de vez" que sai do **Serviço de notificação** (aresta e13, linha 94). Não há DLQ saindo do Motor nem do tópico `transactions`, e nenhum lugar fala em `-dlt`.
- **README** (`/workspaces/motor-antifraude/README.md:1`): só tem o título. Não documenta tópicos, DLQ nem pacotes.
- **docker-compose** (`/workspaces/motor-antifraude/docker-compose.yml:2-14`): não cria nenhum tópico, nem `transactions`, nem `alerts`, nem `*-dlt`. Também não tem init-container nem `kafka-topics.sh`. Tudo depende do `auto.create.topics.enable` do broker, que é `true` por padrão, com `KAFKA_NUM_PARTITIONS: 24` e replicação 1. No código também não há beans `NewTopic`.
  - `alerts-retry-*` e `alerts-dlt` são criados pelo `@RetryableTopic` (`notificacao/NotificacaoListener.java:23`), que tem auto-create ligado por padrão.
  - `transactions-dlt` só passa a existir na primeira publicação, via auto-create do broker.

---

#### Resolvido

1. **RegraController no pacote `api`** (`api/RegraController.java:1`). Agora `api` junta os dois controllers REST (`RegraController` e `SimulacaoController`) e `regras` não tem mais endpoint HTTP. Não sobrou referência ao pacote antigo no código nem nos testes, e nenhum pacote ficou vazio.
2. **Publicação do alerta confirmada pelo broker** (`kafka/AlertaPublisher.java:24`, `application.properties:17-18`). O caminho "alerta perdido sem ninguém saber" foi fechado: uma falha agora vira exceção, que dispara retry e depois DLQ.
3. **Transação com problema de dado vai direto para a DLQ** (`kafka/KafkaConfig.java:24`). JacksonException e eventId vazio (`TransacaoListener.java:36-38`) não ficam em retry infinito. A validação do eventId acontece antes do `registrar`, então não deixa chave órfã no Redis.
4. **O sufixo `-dlt` é o mesmo nos dois fluxos** (`KafkaConfig.java:18` e o padrão do `@RetryableTopic`). Fica `transactions-dlt` e `alerts-dlt`, um padrão previsível.

#### Parcial

1. **KafkaConfig / DLQ**
   - Está no lugar certo: é configuração de infraestrutura Kafka dentro de `kafka`.
   - Não bate com o diagrama: lá a DLQ é só da notificação. O `transactions-dlt` é um fluxo novo e não documentado.
   - Nada consome `transactions-dlt`: não há reprocessador, métrica nem alerta. Uma transação que cai ali nunca é avaliada, e isso é perda silenciosa de detecção.
   - O bean `DefaultErrorHandler` é global. O Spring Boot o aplica à factory padrão, ou seja, a qualquer `@KafkaListener` futuro. O `NotificacaoListener` escapa porque o `@RetryableTopic` usa um error handler próprio. Isso funciona, mas é implícito.
   - `IllegalArgumentException` como não-retentável (`KafkaConfig.java:24`) é amplo demais: o `Assert` do Spring e outros erros de infraestrutura também lançam IAE. O melhor é uma exceção própria, por exemplo `EventoInvalidoException`.
   - `new TopicPartition(..., -1)` deixa o producer escolher a partição. Funciona, mas perde a relação com a partição de origem.
   - `logging.level.org.springframework.kafka.retrytopic=OFF` (`application.properties:15`) esconde justamente os logs de retry e DLT que as mudanças acabaram de criar.
2. **Liberar a idempotência** (`TransacaoListener.java:40-56`, `IdempotenciaService.java:24`). Cobre a falha "limpa" por exceção. Não cobre:
   - **Crash** (kill, OOM, rebalance) entre `registrar` (l.40) e `publicar`. A chave fica 24h no Redis (`IdempotenciaService.java:19`), a reentrega é tratada como duplicada e o evento se perde. É o mesmo problema que o commit queria resolver, só que no caminho de crash.
   - **Falha no meio do loop.** Se a regra 1 publica e a regra 2 falha, a chave é liberada, o retry republica o alerta da regra 1 e o cliente recebe push/e-mail duplicado. O `alertaId` é determinístico (`Alerta.java:16`), mas ninguém deduplica por ele (`NotificacaoListener.java:25-29`).
   - **Redis fora no `liberar()`** (l.54). A exceção do `delete` substitui a exceção original e a chave continua lá.
   - **Testes.** `IdempotenciaServiceTest` não testa `liberar()`, e `KafkaConfig` não tem teste nenhum.
3. **acks=all + idempotência**
   - No compose, com replicação 1 e sem `min.insync.replicas`, `acks=all` é na prática `acks=1`. Nos clients Kafka 3.x, `enable.idempotence=true` e `acks=all` já são o padrão.
   - O producer idempotente só deduplica retries internos de um mesmo `send()`. O `get(5, SECONDS)` (`AlertaPublisher.java:24`) tem timeout menor que o `delivery.timeout.ms` padrão (120s). O `get` pode estourar e o producer ainda entregar depois; aí o retry do listener faz um novo `send()` e gera alerta duplicado.

#### Problema novo

1. **Bloqueio da partição incompatível com 500 ms.** O pior caso por registro fica em torno de 4 × 5s de `get` + 3 × 1s de backoff, uns 23s. Esse retry é bloqueante dentro do consumer, então nesse tempo a partição inteira para e todas as transações atrás acumulam lag. Com o broker de alertas fora, todas as partições param.
2. **Throughput serializado.** O `send().get()` síncrono roda dentro do loop de regras (`TransacaoListener.java:46-51`), e `spring.kafka.listener.concurrency` não está configurado, então é 1 thread por instância para 24 partições. Cada alerta custa um round-trip completo ao broker.
3. **Redis fora manda transação válida para a DLQ.** Com Redis indisponível, `registrar` lança exceção, vêm 3 retries e a transação vai para `transactions-dlt` sem ter sido avaliada. Não existe fallback, como processar sem dedupe e deduplicar depois.
4. **O listener ficou com papel de orquestrador** (`TransacaoListener.java:40-56`). Ele não conhece o Redis diretamente, porque passa pelo `IdempotenciaService`, então o acoplamento não é com tecnologia. Mas agora conhece o protocolo de compensação (registrar, processar, liberar) e o loop de regras. Isso é caso de uso de aplicação dentro do adaptador Kafka.
   - Proposta: um `DeteccaoService.processar(Transacao)` fora de `kafka`, em que a idempotência tenha semântica de estado (por exemplo "processando", com TTL curto, e depois "concluído"), ou que só marque depois de publicar.
   - O listener ficaria só com deserializar e delegar.

#### Ainda aberto

1. **Notificação dentro do motor.** O diagrama mostra o "Serviço de notificação" como componente separado, com retry **e circuit breaker**. No código ele está dentro do motor (`notificacao/NotificacaoListener.java`, `groupId = "notificacao"`), e não há circuit breaker: o pom não tem resilience4j. O nome do grupo de consumo é o único isolamento.
2. **API de Regras dentro do motor.** No diagrama a "API de Regras" é separada e grava no Postgres. No código ela está no motor e expõe direto a entidade JPA (`api/RegraController.java:19-26`).
   - Não tem DTO, `@Valid` nem autenticação, e o starter de validation está no pom sem uso.
   - Um `tipo` inválido gravado por ela faz `RegraCache.criar` lançar exceção (`regras/RegraCache.java:48`). O `catch` (l.36-38) mantém as regras antigas, então uma única regra inválida congela qualquer atualização de regras.
3. **SimulacaoController em código de produção** (`api/SimulacaoController.java:16-20`). Qualquer um pode desligar a notificação, e ele não está restrito por `@Profile`.
4. **Pacote `regras` com responsabilidades misturadas.** Junta o contrato de domínio (`Regra`), as implementações, a entidade JPA (`RegraConfig`), o repositório Spring Data e o cache com `@Scheduled` + Redis. Além disso, `FrequenciaRegra` depende de `StringRedisTemplate` (`regras/FrequenciaRegra.java:5,29-35`), quando deveria depender de uma porta (por exemplo `ContadorJanela`).
   - Essa regra faz 4 round-trips ao Redis por transação, sem pipeline nem script Lua. A 25k TPS isso dá uns 100k ops/s só nela.
5. **Extensibilidade sem redeploy só vale para parâmetros.** Limite, janela e ativação mudam sem deploy. Um tipo novo de regra exige redeploy, por causa do `switch` fechado em `RegraCache.java:42-49`. O reload a cada 10s (l.28) também significa até 10s de regra desatualizada.
6. **Capacidade (8k/25k TPS) e latência (500 ms) sem garantias**:
   - nenhuma concorrência de consumer configurada;
   - nenhum tuning de `max.poll.records`, `linger.ms` ou batch;
   - nenhuma métrica de latência ponta a ponta (do `dataHora` até a publicação do alerta);
   - Prometheus e Grafana no compose sem nenhum scrape configurado (`docker-compose.yml:30-38`);
   - nenhum teste de carga.
7. **Resiliência de infraestrutura**:
   - Kafka com 1 nó e replicação 1;
   - Redis e Postgres com instância única;
   - `ddl-auto=update` em produção (`application.properties:6`);
   - credenciais em texto puro (`application.properties:4-5`);
   - nada da faixa "Segurança: TLS/mTLS, CPF e cartão mascarados" do diagrama (linha 49) está implementado. Inclusive os logs imprimem `accountId` (`NotificacaoGateway.java:23-24`).
8. **Testes.** Só há o `contextLoads` (`MotorDeteccaoApplicationTests.java`), que precisa da infraestrutura no ar. Não há teste de integração com Kafka/DLT (por exemplo `@EmbeddedKafka` ou Testcontainers) nem testes dos controllers em `api`.

---

#### Prioridades sugeridas

1. **Idempotência e entrega.** Mover a orquestração para um serviço de aplicação e trocar o par registrar/liberar por "marcar como concluído só depois de publicar" (ou estado com TTL curto). Para os alertas, ou usar transação Kafka (consume-process-produce com `read_committed`), ou deduplicar por `alertaId` no lado da notificação. Alinhar `delivery.timeout.ms` ao timeout do `get`.
2. **Tirar o bloqueio do caminho quente.**
   - Retry não bloqueante para `transactions`, via `@RetryableTopic` ou tópico de retry, mantendo o `-dlt` que já existe.
   - `concurrency` próximo do número de partições.
   - Publicação assíncrona com confirmação agregada por lote.
   - Métrica de latência ponta a ponta contra o SLA de 500 ms.
3. **Documentação e infraestrutura declarativas.**
   - Criar `transactions`, `alerts`, `transactions-dlt` e `alerts-dlt` explicitamente, com `NewTopic` ou init no compose.
   - Atualizar o diagrama com a DLQ do motor e um consumidor/alarme para ela.
   - Escrever no README os tópicos, os pacotes e a decisão de manter notificação e API de Regras dentro do motor, ou separá-las como o diagrama mostra.

Arquivos principais:
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/KafkaConfig.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/AlertaPublisher.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/api/RegraController.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraCache.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoListener.java`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties`
- `/workspaces/motor-antifraude/docker-compose.yml`
- `/workspaces/motor-antifraude/docs/arquitetura-motor-antifraude.drawio`

---

## Relatório completo do agente codigo-morto

Revisão de limpeza do motor-deteccao, com foco no commit 30fcc24. Não alterei nenhum arquivo. Não rodei git, então a separação entre o que é novo e o que é pré-existente segue a lista de mudanças que você me passou. Usei Grep para confirmar cada item antes de apontar.

#### 1. Imports depois do commit: nada sobrando, nada faltando

Conferi cada import contra o uso no próprio arquivo:
- **`kafka/TransacaoListener.java`:3-11 (novo)**: todos são usados.
- **`kafka/KafkaConfig.java`:3-10 (novo)**: todos são usados. `TopicPartition`, `JacksonException` e `FixedBackOff` aparecem nas linhas 18, 21 e 24.
- **`kafka/AlertaPublisher.java`:3-7 (novo)**: o `TimeUnit` (linha 4) é usado na linha 24.
- **`idempotencia/IdempotenciaService.java`:3-5**: tudo é usado.
- **`api/RegraController.java`:3-6 (movido)**:
  - Os imports explícitos de `com.mica.motor.regras.RegraConfig` e `RegraConfigRepository` estão presentes, e os dois são `public`.
  - O import curinga `org.springframework.web.bind.annotation.*` cobre as 6 anotações usadas. O mesmo estilo já existia em `api/SimulacaoController.java:4`.
  - Não falta nada.
- **`src/test/java/com/mica/motor/kafka/TransacaoListenerTest.java`:3-21 (novo teste)**: todos os imports estáticos (`assertThrows`, `any`, `doThrow`, `mock`, `never`, `verify`, `when`) e os de classe são usados.
- Os outros testes (`IdempotenciaServiceTest`, `RegraCacheTest`, `FrequenciaRegraTest`, `ValorAltoRegraTest`, `NotificacaoListenerTest`, `NotificacaoGatewayTest`) também não têm import sobrando.

#### 2. Pacotes `regras` e `api` depois da mudança: nada órfão

- **Testes:** o Grep por `RegraController` só encontra `src/main/java/com/mica/motor/api/RegraController.java`:10 e :14. Nenhum teste referencia `com.mica.motor.regras.RegraController`, e também não existe teste do controller.
- **`regras/RegraConfig` e `regras/RegraConfigRepository`:** continuam em uso, por `api/RegraController.java` e por `regras/RegraCache.java`:15/31.
- **Getters sem chamada direta:** `RegraConfig.getId()` (`regras/RegraConfig.java`:17) e `isAtiva()` (:25) não têm chamada direta no código. Não são código morto: o Jackson usa os dois para serializar a resposta de `RegraController.listar()`, e o JPA também usa.
- **Build:** em `target/classes` não sobrou `regras/RegraController.class`. Só existe `api/RegraController.class`.

#### 3. A validação de eventId no listener não duplica nada (novo)

- **Onde está:** `src/main/java/com/mica/motor/kafka/TransacaoListener.java`:36-38.
- **Por que não duplica:**
  - O record `domain/Transacao.java`:6-14 não tem anotações de Bean Validation.
  - O Grep por `jakarta.validation`, `@Valid` e `@NotBlank` não encontra nada no projeto.
  - Nenhum outro lugar valida o eventId. Sem essa checagem, um eventId nulo viraria a chave `"evento:null"` (`IdempotenciaService.java`:29) e todos os eventos sem id seriam tratados como duplicados entre si.
- **Coerência com a DLQ:** a validação lança `IllegalArgumentException`, que está em `addNotRetryableExceptions` (`KafkaConfig.java`:24). Então a mensagem vai direto para a DLQ, sem novas tentativas.
- **Lacuna de teste (novo):** `TransacaoListenerTest` não tem caso para eventId nulo ou em branco.
- **Lacuna de teste (novo):** `IdempotenciaServiceTest` não cobre `liberar()`. Ele só aparece mockado em `TransacaoListenerTest.java`:89.

#### 4. Configuração duplicada ou conflitante

- **`application.properties`:15, `logging.level.org.springframework.kafka.retrytopic=OFF` (pré-existente):** ainda faz sentido. O `@RetryableTopic` continua em `notificacao/NotificacaoListener.java`:23, no tópico `alerts`. Essa linha silencia apenas os logs do retry-topic. Ela não afeta os logs do novo `DefaultErrorHandler`, que ficam no pacote `org.springframework.kafka.listener`.
- **Error handler:** não existe outro definido em properties. O Grep por `error-handler`, `retry` e `backoff` nas properties só encontra a linha 15. O que existe são dois mecanismos de retry convivendo, sem conflito:
  - **`KafkaConfig.errorHandler` (novo):** o Spring Boot aplica esse bean à factory padrão de listeners, ou seja, a todos os `@KafkaListener`.
  - **`@RetryableTopic` do `NotificacaoListener` (pré-existente):** o retry-topic instala o próprio error handler nos containers dele, então na prática o `DefaultErrorHandler` atua só no `TransacaoListener`.
  - **Nomes de DLT:** os dois usam o sufixo `-dlt` (`transactions-dlt` e `alerts-dlt`), sem colisão.
  - **Observação:** isso não fica explícito em lugar nenhum. Um comentário em `KafkaConfig` ou um `@KafkaListener(containerFactory=...)` deixaria claro.
- **`application.properties`:17-18, `acks=all` e `enable.idempotence=true` (novo):** são redundantes, porque já são o padrão do cliente Kafka desde a 3.0. Mesmo assim não conflitam e servem como documentação explícita. Eles também valem para o `KafkaTemplate` que publica na DLQ.
- **Ninguém consome `transactions-dlt`, e não há `NewTopic` declarado (novo):** não é código morto, é só um ponto a anotar.

#### 5. Outro código morto ou configuração sem uso (tudo pré-existente)

- **`/workspaces/motor-antifraude/motor-deteccao/spotbugs-exclude.xml`:** está órfão. O `pom.xml` não tem mais o plugin do SpotBugs (removido no commit 93b474c) e nada mais aponta para esse arquivo.
- **`pom.xml`:49-52, `spring-boot-starter-validation`, e :93-97, `spring-boot-starter-validation-test`:** nada usa `jakarta.validation`. Ou remove as duas dependências, ou passa a usar Bean Validation de fato (por exemplo, `@NotBlank` no eventId).
- **Starters de teste sem uso:** `pom.xml`:78-92 e :98-102 trazem `data-jpa-test`, `data-redis-test`, `kafka-test` e `webmvc-test`, e :73-77 traz `actuator-test`. Nenhum teste usa `@DataJpaTest`, `@WebMvcTest` ou `@EmbeddedKafka`. O único teste com Spring é `MotorDeteccaoApplicationTests`, com `@SpringBootTest`.
  - **Cuidado:** JUnit e Mockito chegam de forma transitiva por esses starters. Se remover, deixe pelo menos um `spring-boot-starter-test`.
- **`src/test/java/com/mica/motor/MotorDeteccaoApplicationTests.java`:9-11:** é o `contextLoads()` vazio que vem do Initializr. Ele exige Postgres, Redis e Kafka no ar. Vale avaliar se ele se mantém.

#### 6. Duplicação (toda pré-existente e de baixo impacto, exceto o último item)

- **IDs de regra:** os literais `"VALOR_ALTO"` e `"FREQUENCIA"` estão repetidos entre `regras/RegraCache.java`:43-44 e `ValorAltoRegra.java`:16 / `FrequenciaRegra.java`:21. Um enum ou constante resolveria.
- **Fixture de `Alerta`:** a construção é idêntica em `NotificacaoGatewayTest.java`:14-15 e `NotificacaoListenerTest.java`:19-20.
- **Fixture de `Transacao`:** `new Transacao("ev-1","tx-1","conta-1",...,"BR","Loja",Instant.now())` se repete em `TransacaoListenerTest.java`:32 (novo), `FrequenciaRegraTest.java`:33 e `ValorAltoRegraTest.java`:16.
- **`AlertaPublisher.java`:25-30 (novo):** são dois `catch` que embrulham a exceção em `IllegalStateException`. A separação é justificada, porque o `InterruptedException` precisa restaurar a flag de interrupção. Não é duplicação a remover.

**Resumo:** o commit 30fcc24 não deixou imports sobrando ou faltando, nem órfãos de pacote ou referências ao pacote antigo do controller. As únicas pendências ligadas a ele são os testes que faltam (eventId inválido e `liberar()`) e as duas properties do producer, que são redundantes por já serem o padrão. O código morto encontrado é todo anterior ao commit: `spotbugs-exclude.xml` e as dependências de validação e de teste sem uso no `pom.xml`.
