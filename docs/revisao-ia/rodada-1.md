# Revisão por IA — Rodada 1

- **Data:** 2026-11-22
- **Commit revisado:** `93b474c` (antes de `ea14da8` e das correções)
- **Agentes:** `caca-bugs` (pedido sozinho), depois `arquiteto` e `codigo-morto` em paralelo
- **Observação:** nenhum agente alterou arquivos nem rodou o build. Os achados não foram conferidos no código nesta rodada.
- **Próxima rodada:** [rodada-2.md](rodada-2.md)

## Resumo — caca-bugs

O agente **caca-bugs** encontrou **8 problemas de gravidade alta, 6 média e 4 baixa** no `motor-deteccao`. Ele leu todos os arquivos de `src/main` e o `pom.xml`, e não alterou nada.

O efeito comum dos problemas graves é que **eventos se perdem em silêncio**. O projeto não configura um tratador de erros do Kafka, então vale o padrão do Spring: depois de 10 tentativas o evento é descartado e o offset é confirmado.

#### Alta
- **A1 – Idempotência gravada antes de processar** (`TransacaoListener.java:36-47`, `IdempotenciaService.java:17-21`): se uma regra, o Redis ou a publicação falhar, a nova tentativa vê a chave como "duplicado" e descarta o evento. Uma fraude passa sem alerta.
- **A2 – Sem tratador de erros nem tópico de erros (DLT) em `transactions`**: um JSON inválido, ou o Redis fora do ar por 2 minutos, faz o backlog ser descartado inteiro, sem avaliação.
- **A3 – Sem regras carregadas, tudo é aprovado** (`RegraCache.java:17,28-39`): o cache começa vazio. Se o Postgres estiver fora no startup, todas as transações passam sem avaliação e ainda ficam marcadas como processadas.
- **A4 – Uma regra mal configurada trava todas as atualizações** (`RegraCache.java:31-48`): um tipo nulo ou uma `janelaSegundos` nula quebra o reload inteiro, e o log culpa o Postgres.
- **A5 – NPE em `ValorAltoRegra`** quando o `limite` ou o `valor` é nulo (`ValorAltoRegra.java:10-21`): somado ao A1, todas as transações se perdem.
- **A6 – `eventId` nulo vira a chave `"evento:null"`**: todo evento sem id depois do primeiro é descartado por 24h.
- **A7 – Envio do alerta sem verificação** (`AlertaPublisher.java:21`): o retorno do `send` é ignorado, então uma falha do broker perde o alerta com o offset já confirmado.
- **A8 – Duas regras FREQUENCIA usam a mesma chave `freq:<accountId>`** (`FrequenciaRegra.java:26-32`): a regra de janela curta apaga o histórico de que a regra de janela longa precisa.

#### Média
- **M1**: `id()` das regras é fixo no código, então duas regras do mesmo tipo geram o mesmo `alertaId`.
- **M2**: `FrequenciaRegra` não é atômica (4 comandos separados no Redis). Uma `dataHora` no futuro apaga a janela inteira da conta.
- **M3**: a API `/regras` não tem autenticação nem `@Valid`. Além disso, `RegraController` está na pasta `regras/`, mas declara o pacote `api`.
- **M4**: o endpoint `/simulacao/notificacao` permite desligar as notificações em produção.
- **M5**: a notificação não tem idempotência, o tratador da DLT só registra log e o log de retentativas está desligado (`OFF`).
- **M6**: dados pessoais nos logs (LGPD): `accountId` com o rótulo "SUSPEITA" e o payload completo do alerta.

#### Baixa
- **B1**: as regras comparam o limite de forma diferente: frequência usa `>=` e valor usa `>`.
- **B2**: `intValue()` trunca o limite sem avisar.
- **B3**: o TTL de idempotência de 24h pode ser menor que a janela de replay.
- **B4**: senha do banco em texto puro no repositório e `ddl-auto=update`.

**Ordem sugerida pelo agente:**
1. A1, A2 e A7 juntos: idempotência em duas fases, tratador de erros com DLT e envio do alerta confirmado.
2. A3, A4 e A5, para que o serviço deixe de aprovar transações quando faltam regras ou a configuração está errada.
3. A8 e M1, nas chaves e nos ids das regras.

O B1 depende de uma decisão de negócio: se o limite vale como "acima de N" ou "a partir de N".


## Resumo — arquiteto e codigo-morto

Os agentes **arquiteto** e **codigo-morto** terminaram. Nenhum dos dois alterou arquivos ou rodou o build. Os achados não foram conferidos no código.

#### Arquiteto

O fluxo `transactions → regras → alerts → notificação` funciona e o pacote `domain` está limpo. Mas o projeto **ainda não atende aos requisitos de vazão e de resiliência**, e o diagrama não foi seguido em vários pontos. Os requisitos que ele usou estão só em `.claude/agents/arquiteto.md` (8.000 TPS, picos de 25.000, alerta em 500 ms), porque o `README.md` tem apenas o título.

**Requisitos do case não atendidos:**
- **8k e 25k TPS:** não estão demonstrados.
  - Não há `spring.kafka.listener.concurrency`, então cada instância usa 1 thread, mesmo com 24 partições.
  - Cada transação faz 1 chamada ao Redis para idempotência e mais 4, sem atomicidade, por regra de frequência.
  - A estimativa dele, que não é medição, é de cerca de 200 mensagens por segundo por thread.
- **Alerta em 500 ms:** não existe métrica do tempo entre o evento e o alerta, nem teste de carga.
- **Resiliência:** parcial, pelos mesmos motivos que o caca-bugs apontou.
  - A idempotência é gravada antes do processamento.
  - O `send` do alerta não é confirmado.
  - O tópico `transactions` não tem DLT.
  - Uma regra inválida congela a atualização de todas.
  - O cache de regras pode começar vazio.
- **Diagrama:** seguido em parte.
  - Falta o circuit breaker na notificação.
  - A notificação roda dentro do motor, e não como serviço separado.
  - Falta a "Base de clientes".
  - Falta o bloco de segurança: TLS/mTLS e mascaramento.
  - O Prometheus sobe sem `prometheus.yml` e não coleta métricas do motor.
- **Regras sem redeploy:** só os parâmetros mudam em tempo de execução. Um tipo novo de regra exige mexer no `switch` do `RegraCache` e fazer deploy.

**Organização do código:**
- `RegraController` está na pasta `regras/`, mas declara o pacote `api`.
- O pacote `regras/` mistura o contrato das regras, o acesso JPA, o cache e o Redis.
- `FrequenciaRegra` depende diretamente do `StringRedisTemplate`. Falta uma porta entre a regra e o Redis.
- `TransacaoListener` concentra o caso de uso inteiro. Valeria extrair um `AvaliarTransacaoService`.

**Testes:** só há testes unitários com mocks. Faltam testes de integração (Testcontainers), testes de carga e uma meta de cobertura no JaCoCo. O PMD está com `failOnViolation=false`.

#### Código morto

Há pouco código morto de fato:
1. **Dependências sem uso:**
   - `spring-boot-starter-validation`, porque nenhum endpoint usa `@Valid`. Removê-la ou passar a usá-la resolve também a falta de validação apontada antes.
   - Os starters de teste `*-test` para jpa, redis, kafka, webmvc e validation, porque nenhum teste de slice os usa.
2. **`spotbugs-exclude.xml` órfão:** o plugin foi removido no commit 93b474c, mas o arquivo ficou.
3. **`Transacao.pais` e `estabelecimento`** nunca são lidos. Vale manter se houver regras planejadas que usem esses campos.
4. **Literais duplicados:** `"VALOR_ALTO"` e `"FREQUENCIA"` aparecem nas regras e no `RegraCache`, e o tópico `"alerts"` aparece no publisher e no listener. Ficariam melhor como constantes ou propriedades.
5. **Testes:** as fixtures de `Transacao` e `Alerta` se repetem em 5 classes, e o campo `redis` do `FrequenciaRegraTest` poderia ser variável local.
6. **`HELP.md`** é o arquivo gerado pelo Spring Initializr e pode ser removido.

#### Por onde começar

Os três agentes concordam nestes pontos. Na minha opinião esta é a ordem:
1. **Não perder eventos:**
   - Gravar a idempotência só depois do sucesso.
   - Configurar error handler com DLT no tópico `transactions`.
   - Confirmar o `send` do alerta, com `acks=all`.
2. **Regras confiáveis:**
   - Validar a entrada na API.
   - Isolar falhas por regra no `RegraCache`.
   - Carregar as regras antes de os listeners começarem a consumir.
   - Usar o id da configuração como id da regra e na chave do Redis.
3. **Vazão:**
   - Configurar a concorrência dos listeners.
   - Trocar os comandos da `FrequenciaRegra` por um script Lua atômico.
   - Criar uma métrica de latência e um teste de carga.
4. **Limpeza rápida:** ajustar o `pom`, remover o `spotbugs-exclude.xml` e o `HELP.md`, e mover o `RegraController` para `api/`.

A limpeza do item 4 é a de menor risco, e as correções do item 1 são as que mais importam.

---

## Relatório completo do agente caca-bugs

### Revisão do motor-deteccao (src/main)

Li todos os 17 arquivos de `/workspaces/motor-antifraude/motor-deteccao/src/main`, além do `pom.xml`. É o único módulo com código de produção. Não alterei nenhum arquivo.

Os níveis de gravidade correspondem assim: **alta** = CRÍTICO, **média** = IMPORTANTE, **baixa** = MELHORIA.

Uma premissa vale para vários achados. O projeto não declara nenhum `CommonErrorHandler`. Por isso o container do Spring Kafka usa o `DefaultErrorHandler` padrão: 10 tentativas sem intervalo entre elas, depois registra o erro, pula o registro e faz o commit do offset. Uma exceção no listener não para o consumidor. Ela faz o evento ser descartado em silêncio.

---

#### ALTA

##### A1. Idempotência gravada antes do processamento: qualquer falha depois disso perde o evento
**Arquivos:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java:36-47` e `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java:17-21`

**Problema:** `registrar(eventId)` grava `evento:<id>` (SETNX com TTL de 24h) antes de avaliar as regras e publicar. Se algo falhar depois disso, a nova tentativa encontra a chave, registra "duplicado, ignorado" e retorna normalmente. O offset é commitado e o evento nunca chega a ser avaliado. Exemplos de falha: NPE em uma regra, Redis instável dentro da `FrequenciaRegra`, erro ao serializar o alerta.

**Cenário em produção:**
- A `FrequenciaRegra` lança `RedisConnectionFailureException` durante um pico.
- A 2ª tentativa do `DefaultErrorHandler` cai no `return` da linha 38.
- Uma transação fraudulenta passa sem alerta, e o único rastro é um log INFO.
- O mesmo acontece em um rebalance ou queda da instância entre a linha 36 e o fim do método. A outra instância recebe o registro, vê a chave e descarta.

**Correção:**
- Gravar o estado em duas fases: `SET evento:id PROCESSANDO NX EX <curto>` e, ao final, `SET evento:id CONCLUIDO EX 24h`.
- Em caso de exceção, apagar a chave (`DEL`) antes de relançar.
- Alternativa: marcar como processado só depois do `send` confirmado.
- A publicação do alerta precisa ser idempotente, o que já é verdade porque `alertaId` é determinístico. Assim, reprocessar fica seguro.

##### A2. Falta de error handler configurado: poison pill e indisponibilidade do Redis descartam eventos em massa
**Arquivos:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java:34` e `/workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties` (não existe configuração de error handler nem de DLT para o tópico `transactions`)

**Problema:**
- `objectMapper.readValue` (Jackson 3) lança `JacksonException`, que é unchecked. Um JSON inválido é tentado 10 vezes sem backoff e depois descartado.
- Se o Redis cair, `registrar` lança exceção em todos os eventos. Cada evento é tentado 10 vezes em milissegundos e descartado com commit.

**Cenário em produção:** o Redis fica 2 minutos fora do ar. O consumidor percorre o backlog de `transactions` descartando todas as transações do período sem avaliar nenhuma. Não existe DLT para reprocessar depois.

**Correção:**
- Declarar um `DefaultErrorHandler` com `DeadLetterPublishingRecoverer` (tópico `transactions.DLT`) e `ExponentialBackOff`. Para falhas de infraestrutura, considerar backoff longo ou parar o container em vez de pular o evento.
- Marcar `JacksonException` como não-retentável (`addNotRetryableExceptions`), para ir direto à DLT.

##### A3. Fail-open: sem regras carregadas, todas as transações passam e ficam marcadas como processadas
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraCache.java:17, 28-39`, usado em `TransacaoListener.java:41`

**Problema:** o cache começa como `List.of()`. O listener Kafka pode começar a consumir antes da primeira execução do `@Scheduled`. Se o Postgres estiver fora no startup, o cache continua vazio indefinidamente e a única reação é um log de erro. O laço da linha 41 não roda nenhuma regra, e mesmo assim o evento já foi gravado como processado (A1).

**Cenário em produção:** um deploy acontece enquanto o Postgres está em manutenção. Todas as transações dos minutos seguintes são aprovadas sem nenhuma avaliação e não podem ser reprocessadas por 24h.

**Correção:**
- Carregar as regras de forma síncrona no startup (`@PostConstruct` ou `ApplicationRunner`) e falhar o boot ou o readiness se não houver regras.
- Iniciar o container Kafka com `autoStartup=false` e ligá-lo só depois da carga.
- No listener, se `atuais()` estiver vazio, lançar exceção ou pausar, em vez de "aprovar".

##### A4. Uma configuração inválida impede todas as atualizações de regras, e o log aponta a causa errada
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraCache.java:31-37, 42-48`

**Problema:** um único registro ativo com defeito faz o `stream().map(this::criar)` inteiro lançar exceção. O `catch` mantém as regras antigas e registra "Postgres indisponível". Registros com defeito incluem:
- `tipo` desconhecido ou nulo: o `switch` em String nula lança NPE.
- `limite` nulo em uma regra FREQUENCIA (linha 46).
- `janelaSegundos` nulo, com unboxing de `Integer` na linha 47.

**Cenário em produção:** alguém cadastra pelo `PUT /regras/X` uma regra FREQUENCIA sem `janelaSegundos`. A partir daí nenhuma mudança de regra é aplicada, nem mesmo a desativação de uma regra que gera falsos positivos ou o aumento de um limite. A equipe investiga o Postgres, que está saudável.

**Correção:**
- Isolar a falha por regra: try/catch dentro do `map`, descartar e registrar só a regra inválida (pelo id), e expor uma métrica.
- Validar no `RegraController` (ver M3).
- Separar no log "erro de acesso ao banco" de "configuração inválida".

##### A5. `ValorAltoRegra` com `limite` nulo, ou transação com `valor` nulo: NPE em todas as mensagens
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/ValorAltoRegra.java:10-12, 21`

**Problema:**
- O construtor aceita `limite == null` (o `criar` da linha 43 não valida). Nesse caso, `compareTo(null)` lança NPE em toda transação.
- Uma transação sem `valor` também lança NPE.

Combinado com A1, a 1ª tentativa grava a idempotência, a regra quebra, e o evento é descartado como "duplicado" na tentativa seguinte. Regras posteriores na lista também não são avaliadas.

**Cenário em produção:** uma regra VALOR_ALTO é salva sem `limite`. Todas as transações passam a ser perdidas sem avaliação, inclusive pelas outras regras.

**Correção:**
- Usar `Objects.requireNonNull(limite)` no construtor (com A4 corrigido, a regra inválida é descartada no reload).
- Validar o payload de `Transacao` na entrada (campos obrigatórios) e mandar mensagens inválidas para a DLT.
- No laço, isolar exceções por regra ou falhar o evento inteiro sem marcá-lo como processado.

##### A6. `eventId` nulo: todos os eventos sem id viram "duplicados" por 24h
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java:19`, chamado em `TransacaoListener.java:36`

**Problema:** com `eventId == null`, a chave vira a string literal `"evento:null"`. O primeiro evento sem id é processado. Todos os seguintes, por 24h, são descartados como duplicados.

**Cenário em produção:** uma versão do produtor deixa de preencher `eventId`. Todas as transações a partir da segunda são ignoradas, com apenas um log INFO.

**Correção:** validar `eventId` (não nulo, não em branco) antes de `registrar`. Se faltar, rejeitar para a DLT ou derivar um id determinístico, como `topic-partition-offset` ou `transactionId`.

##### A7. Envio do alerta assíncrono e sem verificação: alerta perdido com offset já commitado
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/AlertaPublisher.java:21`, chamado em `TransacaoListener.java:45`

**Problema:** `kafka.send(...)` retorna um `CompletableFuture` que é ignorado. Uma falha do broker ou timeout de entrega acontece depois que o listener já retornou e o offset de `transactions` foi commitado.

**Cenário em produção:** o líder da partição `alerts` fica indisponível e o `delivery.timeout.ms` expira. A fraude foi detectada, mas o alerta nunca é publicado e nada tenta de novo.

**Correção:**
- Bloquear com timeout, por exemplo `kafka.send(...).get(10, SECONDS)`, e relançar em caso de falha. Isso exige corrigir A1 para o retry funcionar.
- Outra opção é usar transações Kafka (consume-transform-produce com `transactionIdPrefix`) ou um outbox.

##### A8. Duas regras do mesmo tipo FREQUENCIA corrompem a janela uma da outra
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java:26, 31-32`

**Problema:** a chave do Redis é `freq:<accountId>`, sem o id da regra. Se houver duas regras FREQUENCIA ativas (ex.: 3 em 60s e 20 em 3600s), elas compartilham o mesmo ZSET:
- A regra de janela curta executa `removeRangeByScore(…, agora - 60s)` e `expire(60s)`.
- Com isso apaga os eventos de que a regra de 1h precisa.

**Cenário em produção:** a regra "20 transações por hora" nunca dispara, porque o set só guarda os últimos 60s. Cartão sendo testado em ritmo moderado ("card testing") passa despercebido.

**Correção:** incluir o id da configuração na chave (`freq:<regraConfigId>:<accountId>`). Alternativa: um único ZSET com a janela máxima, contando cada regra com `zCount(chave, agora - janela, agora)` em vez de remover.

---

#### MÉDIA

##### M1. `id()` fixo no código em vez do id da configuração: alertas colidem
**Arquivos:**
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/ValorAltoRegra.java:15-17`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java:20-22`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/domain/Alerta.java:16`

**Problema:** `id()` retorna "VALOR_ALTO" ou "FREQUENCIA" em vez de `config.getId()`. Duas regras do mesmo tipo (ex.: VALOR_ALTO_PF e VALOR_ALTO_PJ) geram o mesmo `alertaId` `tx:VALOR_ALTO`.

**Cenário em produção:** qualquer deduplicação por `alertaId` no consumidor descarta o segundo alerta. A auditoria não consegue saber qual configuração disparou.

**Correção:** passar `config.getId()` no construtor das regras e retorná-lo em `id()`.

##### M2. FrequenciaRegra não é atômica e usa a hora do evento sem limites
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java:27-35`

**Problemas:**
1. `add`, `removeRangeByScore`, `expire` e `zCard` são 4 comandos separados. Se `transactions` não for particionado por `accountId` (a chave da mensagem não é controlada neste serviço), duas instâncias processam a mesma conta ao mesmo tempo. As operações se intercalam e o `zCard` mistura os resultados, gerando alertas duplicados ou contagem inconsistente.
2. `zCard` conta também elementos com score maior que `agora`. Isso acontece com eventos fora de ordem ou com `dataHora` no futuro.
3. A remoção usa a hora do evento. Uma transação com `dataHora` muito no futuro (relógio errado no produtor) apaga todo o histórico da conta, porque `agora - janela` fica maior que todos os scores.
4. `dataHora` nulo lança NPE na linha 27, com o efeito descrito em A1.
5. O TTL do `expire` usa o relógio do servidor, mas os scores usam a hora do evento. Isso fica incoerente em replays.

**Cenário em produção:** um produtor com relógio adiantado 1 dia apaga a janela de todas as contas que ele processa, e a regra de frequência deixa de disparar.

**Correção:**
- Executar tudo em um script Lua ou `MULTI/EXEC` e contar com `zCount(chave, agora - janela, agora)`.
- Rejeitar ou limitar `dataHora` fora de uma tolerância em relação ao relógio do servidor.
- Garantir no produtor que a chave da mensagem é `accountId`.

##### M3. API de regras sem autenticação nem validação
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraController.java:23-27`. O arquivo está na pasta `regras/` mas declara `package com.mica.motor.api`.

**Problema:**
- Não há `spring-boot-starter-security` no `pom.xml`. Qualquer pessoa na rede pode desativar regras (`ativa=false`) ou subir limites com `PUT /regras/{id}`.
- O `@RequestBody` não tem `@Valid` e a entidade não tem constraints. Isso é a porta de entrada de A4 e A5.
- A entidade JPA é exposta diretamente.
- Não há auditoria de quem alterou cada regra.

**Cenário em produção:** um insider ou atacante na rede interna desliga VALOR_ALTO antes de uma fraude. O sistema não registra quem fez.

**Correção:**
- Adicionar autenticação e autorização (perfil antifraude-admin).
- Usar um DTO com Bean Validation (`@NotNull`, `@Positive`, `tipo` como enum, `janelaSegundos` obrigatório quando o tipo for FREQUENCIA).
- Registrar auditoria das alterações.
- Mover o arquivo para a pasta `api/`.

##### M4. Endpoint de simulação permite desligar as notificações em produção
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/api/SimulacaoController.java:16-20`

**Problema:** `PUT /simulacao/notificacao/false`, sem autenticação, faz o `NotificacaoGateway` lançar exceção em todo envio. Todos os alertas vão para a DLT.

**Correção:** anotar com `@Profile("dev")` (ou equivalente) e proteger. O gateway simulado também não deve estar ativo em produção.

##### M5. Notificação sem idempotência e DLT que só registra log
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoListener.java:23-34`

**Problemas:**
- **Envio duplicado:** se ocorrer rebalance ou queda depois de `gateway.enviar` e antes do commit, o cliente recebe push e e-mail em duplicidade. Não há deduplicação por `alertaId`.
- **Alerta perdido sem aviso:** o `@DltHandler` só registra um log. O cliente não é avisado da possível fraude e nenhum processo reprocessa a mensagem.
- **Retentativas inúteis:** `JacksonException` não está na lista de exceções não-retentáveis do `@RetryableTopic`. Um JSON inválido passa pelas 4 tentativas, com o backoff padrão, antes de ir para a DLT.
- **Retentativas invisíveis:** `logging.level.org.springframework.kafka.retrytopic=OFF` (application.properties:15) esconde as retentativas.

**Correção:**
- Deduplicar com `SETNX notif:<alertaId>`, gravado após envio bem-sucedido.
- Configurar backoff exponencial e `exclude = JacksonException.class`.
- Na DLT, persistir a mensagem, emitir métrica e alarme, e ter um processo de reenvio.
- Voltar o nível de log de retrytopic para pelo menos WARN.

##### M6. Dados pessoais em log (LGPD)
**Locais:**
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java:43-44`: `accountId` junto com o rótulo "SUSPEITA", em WARN.
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoGateway.java:23-24`: `accountId` em INFO.
- `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoListener.java:33`: o payload inteiro do alerta (`accountId`, `valor`, `transactionId`) em ERROR.

**Risco:** os logs vão para ferramentas centralizadas com acesso amplo e retenção longa. Eles passam a associar uma conta identificável a uma suspeita de fraude e a valores, sem necessidade.

**Correção:**
- Registrar só `alertaId` e `transactionId`, ou o `accountId` mascarado ou com hash.
- Nunca registrar o payload bruto. Na DLT, registrar tópico, partição, offset e `alertaId`.

---

#### BAIXA

##### B1. Limite de frequência inclusivo e diferente da regra de valor
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java:35`, comparado com `ValorAltoRegra.java:21`

**Problema:** a regra de frequência usa `quantidade >= maxTransacoes`: com `limite=5`, a 5ª transação já dispara. A regra de valor usa `>` estrito: um valor igual ao limite não dispara. O nome "max" sugere que 5 é permitido.

**Correção:** é preciso confirmar com o negócio qual semântica vale ("acima de N" ou "a partir de N"), padronizar as duas regras, cobrir os limites com testes e documentar no `RegraConfig`.

##### B2. `intValue()` trunca o limite em silêncio
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraCache.java:46`

**Problema:** `BigDecimal` 5.9 vira 5, e valores acima de `Integer.MAX_VALUE` estouram.

**Correção:** usar `intValueExact()` e validar que o limite é um inteiro positivo.

##### B3. TTL de idempotência de 24h
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java:19`

**Problema:** um reset de offset ou replay de mais de 24h reprocessa os eventos. Com isso a `FrequenciaRegra` recebe dados reinseridos e alertas são publicados de novo.

**Correção:** alinhar o TTL à retenção do tópico ou à janela máxima de replay, e documentar.

##### B4. Configuração
**Arquivo:** `/workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties:5-6`

**Problema:**
- A senha do banco está em texto puro no repositório.
- `ddl-auto=update` altera o schema em produção sem controle.

**Correção:** usar variáveis de ambiente ou um cofre de segredos, `ddl-auto=validate` e migrações com Flyway ou Liquibase.

---

#### Prioridade sugerida

1. Corrigir primeiro A1, A2 e A7 juntos: idempotência em duas fases, error handler com DLT e envio confirmado. Hoje são a principal fonte de perda silenciosa de eventos.
2. Depois corrigir A3, A4 e A5, que tornam o sistema fail-open quando a configuração tem problema.
3. Em seguida, A8 e M1, nas chaves e ids das regras.

Há um ponto que depende de fora deste serviço. Se o produtor de `transactions` não usar `accountId` como chave da mensagem, os problemas de concorrência da `FrequenciaRegra` (M2) passam a ocorrer em produção com várias instâncias.

---

## Relatório completo do agente arquiteto

### Revisão de arquitetura do motor antifraude (`motor-deteccao`)

O fluxo `transactions -> regras -> alerts -> notificação` está implementado e o domínio está limpo. Mas o código ainda não atende aos requisitos de vazão nem de resiliência, e diverge do diagrama em quatro pontos: não há circuit breaker, a notificação não é um serviço separado, a "Base de clientes" não existe e nada do bloco de segurança foi implementado.

**Fonte dos requisitos:** o `README.md` contém só o título. Os únicos requisitos escritos estão em `/workspaces/motor-antifraude/.claude/agents/arquiteto.md`: 8.000 TPS, picos de 25.000, alerta em 500 ms e resiliência. A arquitetura proposta é o diagrama `/workspaces/motor-antifraude/docs/arquitetura-motor-antifraude.drawio`.

Li todos os fontes de main e de test, o `pom.xml`, o `application.properties`, o `docker-compose.yml` e o diagrama. Não alterei nenhum arquivo e não executei build nem testes.

Caminho base: `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/`

---

#### 1. O que está bom

- **`domain/` é puro.** `Transacao.java` e `Alerta.java` são records sem nenhuma dependência de framework.
- **Os IDs de alerta são determinísticos** (`transactionId:regraId`, em `Alerta.java:16`). Isso permite deduplicar no consumidor.
- **Os alertas são publicados com chave `accountId`** (`AlertaPublisher.java:21`), o que mantém a ordem por conta.
- **A idempotência usa `SETNX` com TTL no Redis** (`IdempotenciaService.java:18-20`). É atômica e simples.
- **`ValorAltoRegra` segue o contrato `Regra`**, é pura e o teste cobre o limite exato.
- **Os parâmetros das regras mudam sem redeploy.** O fluxo é API -> Postgres -> `RegraCache` com polling de 10 s.
- **A lista de regras troca atomicamente** (`AtomicReference`), e se o Postgres cair as últimas regras continuam valendo (`RegraCache.java:36-38`, com teste).
- **A notificação tem retry por tópico e DLT** (`@RetryableTopic` e `@DltHandler`), como o diagrama pede ("falhou de vez" -> DLQ).
- **Actuator e Prometheus estão expostos**, e o compose sobe a infraestrutura do diagrama (Kafka com 24 partições, Redis, Postgres, Prometheus, Grafana).

---

#### 2. O que está fora do lugar

##### Pacotes e camadas

| # | Arquivo:linha | Problema | Princípio / requisito | Gravidade | Sugestão |
|---|---|---|---|---|---|
| 1 | `regras/RegraController.java:1` | O arquivo está na pasta `regras/`, mas declara `package com.mica.motor.api`. | Organização de pacotes | Média | Mover para `api/`. Expor um DTO no lugar da entidade JPA. |
| 2 | `regras/` (vários arquivos) | O pacote mistura o contrato (`Regra`), as implementações, a persistência JPA (`RegraConfig`, `RegraConfigRepository`), a fábrica e o cache (`RegraCache`) e o acesso a Redis (`FrequenciaRegra`). | Responsabilidade única | Média | Separar em `regras` (contrato e lógica), `regras.config` ou `infra.persistencia` (JPA) e `infra.redis`. |
| 3 | `regras/Regra.java:5` | O contrato de regra fica fora do domínio. | Regra de negócio no domínio | Baixa | Mover `Regra` para `domain` (ou `domain.regras`). |
| 4 | `regras/FrequenciaRegra.java:5,9,29-34` | A regra depende diretamente de `StringRedisTemplate`. A lógica de negócio fica acoplada à infraestrutura e o teste precisa mockar o Redis. | Domínio sem framework | Média | Criar uma porta (por exemplo `ContadorJanela.contar(conta, ts, janela)`) com um adaptador Redis. |
| 5 | `kafka/TransacaoListener.java:33-48` | O adaptador Kafka contém o caso de uso inteiro (idempotência, avaliação das regras, publicação). | Separação adaptador / aplicação | Média | Extrair um serviço tipo `AvaliarTransacaoService`. O listener ficaria só com desserialização e ack. |
| 6 | `notificacao/` (pacote inteiro) | O diagrama (`drawio:34`) mostra "Serviço de notificação" como serviço próprio. No código ele roda dentro do motor. Retries e falhas do provedor disputam recursos com a detecção, e não dá para escalar um sem o outro. | Aderência ao diagrama, isolamento de falhas | Média | Extrair para um módulo ou deployable próprio que consome `alerts`. |
| 7 | `api/SimulacaoController.java:16-20` | Um endpoint de teste que derruba o provedor de notificação está ativo em qualquer ambiente e sem autenticação. | Segurança | Média | Restringir com `@Profile("dev")` ou mover para os testes. |
| 8 | `idempotencia/IdempotenciaService.java:7-10` | Usa o Redis concreto, sem porta. Funciona, mas o `TransacaoListener` fica acoplado a ele. | Inversão de dependência | Baixa | Criar a interface `Idempotencia` com uma implementação Redis. |

##### Regras: contrato e extensibilidade

| # | Arquivo:linha | Problema | Gravidade | Sugestão |
|---|---|---|---|---|
| 9 | `regras/RegraCache.java:31-34,48` | Uma única config inválida congela todas as atualizações de regras. Um `tipo` desconhecido lança exceção dentro do stream, o `catch` (linha 36) descarta o lote inteiro e a mensagem de log diz "Postgres indisponível", o que engana. O mesmo acontece com `FREQUENCIA` sem `janelaSegundos` (NPE na linha 47) e com `limite` nulo (linha 46). O teste `RegraCacheTest.tipoDesconhecidoNaoDerrubaOMotor` valida esse comportamento sem revelar o efeito colateral. | Alta | Isolar cada regra (try/catch por config, ignorar a inválida, emitir métrica) e validar na entrada. |
| 10 | `regras/RegraController.java:24` | O PUT aceita qualquer corpo: sem `@Valid`, sem enum para `tipo`, sem autenticação. Qualquer pessoa pode desativar regras ou cadastrar regras quebradas, o que leva ao item 9. O starter de validation está no pom, mas não é usado. | Alta | Usar DTO com Bean Validation, enum `TipoRegra`, autenticação e auditoria. |
| 11 | `regras/RegraCache.java:42-49` | Um tipo novo de regra exige mudar o `switch`, ou seja, redeploy. Só os parâmetros mudam em runtime. Os campos `pais` e `estabelecimento` de `Transacao` não são usados por nenhuma regra. | Média | Criar tipos genéricos declarativos (campo / operador / valor, listas de país, expressão SpEL ou CEL) ou registrar `RegraFactory` como bean por tipo. |
| 12 | `regras/ValorAltoRegra.java:16`, `regras/FrequenciaRegra.java:21` | O `id()` é fixo por tipo e ignora `RegraConfig.id`. Duas configs do mesmo tipo (limites diferentes) geram o mesmo `regraId` e o mesmo `alertaId`. | Média | Passar o id da config para o construtor. |
| 13 | `regras/FrequenciaRegra.java:26` | A chave `freq:{conta}` é compartilhada entre todas as configs `FREQUENCIA`. A de janela menor apaga, via `removeRangeByScore` (linha 31), os membros de que a de janela maior precisa. | Média | Incluir o id da regra na chave ou guardar uma janela máxima única. |
| 14 | `regras/FrequenciaRegra.java:29-34` | São quatro round-trips não atômicos ao Redis por transação e por regra. Isso pesa na vazão (seção 3). | Alta (para TPS) | Usar um script Lua ou pipeline: 1 round-trip, atômico. |
| 15 | `regras/RegraConfig.java:13` | O campo `limite` tem duas semânticas (reais ou quantidade), e por isso é convertido com `intValue()`. | Baixa | Separar os campos ou usar parâmetros tipados por tipo de regra. |
| 16 | `regras/RegraCache.java:17,28` | O cache começa vazio e só é carregado pelo `@Scheduled`. O listener Kafka pode consumir antes da primeira carga e avaliar transações com zero regras, deixando fraude passar. Não há seed de regras. | Média | Carregar de forma síncrona antes de iniciar os listeners (`SmartLifecycle` ou `ApplicationReadyEvent` com `autoStartup=false`) e adicionar uma migração com regras padrão. |

##### Fluxo Kafka e resiliência

| # | Arquivo:linha | Problema | Gravidade | Sugestão |
|---|---|---|---|---|
| 17 | `kafka/TransacaoListener.java:36` | O evento é marcado como processado antes de avaliar e publicar. Se uma regra ou a publicação falhar, a redelivery é descartada como "duplicado" e o alerta se perde. | Alta | Gravar a marca depois do sucesso, ou usar estado "processando" com TTL curto e confirmar no fim. Como o `alertaId` já é determinístico, dá para deduplicar no consumidor. |
| 18 | `kafka/AlertaPublisher.java:21` | `kafka.send` é assíncrono e o resultado é ignorado. Uma falha de envio some em silêncio e o offset da transação é commitado. Não há `acks=all` nem `enable.idempotence` configurados. | Alta | Tratar o future (ou usar `send().get()` com timeout) e configurar o producer, eventualmente com transação Kafka. |
| 19 | `kafka/TransacaoListener.java:32-34` | Não há error handler nem DLT no tópico `transactions`. Um JSON inválido (poison pill) passa pelos retries padrão e depois é descartado só com log. Uma queda do Redis faz o mesmo com todas as mensagens, inclusive para `ValorAlto`, que nem usa Redis. | Alta | Configurar `DefaultErrorHandler` com `DeadLetterPublishingRecoverer` e `ErrorHandlingDeserializer`, e definir uma política explícita para Redis fora do ar (timeout e fail-open ou fail-closed por regra). |
| 20 | `application.properties:11-12` | Não há `spring.kafka.listener.concurrency`. Com o padrão de 1 thread por instância, as 24 partições do compose ficam praticamente ociosas. | Alta (para TPS) | Configurar concorrência igual ao número de partições por instância, ou escalar horizontalmente. |
| 21 | `notificacao/NotificacaoListener.java:23` | O diagrama (`drawio:34`) pede "retry e circuit breaker". Só existe retry (4 tentativas, backoff padrão). Não há circuit breaker (Resilience4j não está no pom) nem backoff exponencial. | Alta (diagrama) | Envolver `NotificacaoGateway.enviar` em `@CircuitBreaker` e configurar `backoff` exponencial no `@RetryableTopic`. |
| 22 | `notificacao/NotificacaoGateway.java:19-25` | Push e e-mail são um único envio. Se um canal falhar, o retry reenvia os dois. O consumidor não tem idempotência, então redeliveries duplicam notificações. A "Base de clientes" do diagrama (`drawio:43`, aresta "dados do cliente") não existe. | Média | Separar um canal por adaptador (ou um tópico por canal), deduplicar por `alertaId` e adicionar uma porta para buscar contatos do cliente. |
| 23 | `notificacao/NotificacaoListener.java:31-34` | O handler da DLT só registra a mensagem completa no log. Não há métrica, alarme nem reprocessamento. | Média | Emitir métrica e alerta e documentar o reprocessamento. |
| 24 | `application.properties:15` | `logging.level.org.springframework.kafka.retrytopic=OFF` esconde os logs do mecanismo de retry. | Baixa | Usar `WARN`, não `OFF`. |
| 25 | `docker-compose.yml:7-14,16-19` | Um único broker com replicação 1 e um único Redis. Os tópicos não são declarados (dependem de auto-criação). | Média (local é aceitável, mas não demonstra resiliência) | Declarar beans `NewTopic` (partições e RF) e documentar a topologia de produção (RF 3, `min.insync.replicas=2`, Redis Sentinel ou Cluster). |

##### Postgres, segurança e observabilidade

| # | Arquivo:linha | Problema | Gravidade | Sugestão |
|---|---|---|---|---|
| 26 | `application.properties:6` | `ddl-auto=update`, sem migrações. | Média | Usar Flyway ou Liquibase, com seed das regras. |
| 27 | `application.properties:3-5` | Credenciais em texto no arquivo. | Baixa | Usar variáveis de ambiente ou um secret manager. |
| 28 | `drawio:49` comparado com `kafka/TransacaoListener.java:43`, `notificacao/NotificacaoGateway.java:23`, `notificacao/NotificacaoListener.java:33` | O diagrama promete TLS/mTLS, criptografia e mascaramento de CPF e cartão. Nada disso está implementado: os logs imprimem `accountId` e a mensagem completa vai para o log na DLT. | Média | Mascarar nos logs e configurar SSL no Kafka, Redis e Postgres (ao menos por profile). |
| 29 | `docker-compose.yml:30-38` | O Prometheus sobe sem `prometheus.yml`, então não faz scrape do motor. O Grafana sobe sem provisioning. O motor não está no compose (não há Dockerfile). O código não tem métricas próprias: latência de ponta a ponta, alertas por regra, tempo de avaliação. | Média | Criar um `Timer` medindo do `dataHora` até a publicação, contadores por regra, o scrape configurado e um dashboard. |

##### Testes

| # | Arquivo:linha | Problema | Gravidade | Sugestão |
|---|---|---|---|---|
| 30 | `src/test/...` (todos) | Só há testes unitários com mocks. Não há teste de integração com Kafka ou Redis reais, embora `spring-boot-starter-kafka-test` e `-data-redis-test` estejam no pom e o Testcontainers não esteja. | Média | Criar um teste de ponta a ponta com Testcontainers ou EmbeddedKafka: `transactions` -> `alerts` -> DLT. |
| 31 | `test/.../FrequenciaRegraTest.java:38-47` | Mocka o `zCard`. A lógica da janela (remoção por score, TTL) e o conflito entre regras (item 13) nunca são exercitados. | Média | Testar contra um Redis real (Testcontainers). |
| 32 | `test/.../MotorDeteccaoApplicationTests.java:6-11` | `@SpringBootTest` depende de Postgres, Redis e Kafka rodando. Ele não aparece em `target/surefire-reports/`, o que sugere que não rodou na última build. | Baixa | Rodar com Testcontainers, ou com um profile de teste. |
| 33 | (ausentes) | Faltam testes para: `AlertaPublisher`, `RegraController` (validação), falha depois da idempotência (item 17), carga e latência (k6, Gatling ou kafka-producer-perf-test). O pom não tem `jacoco:check` com meta de cobertura, e o PMD está com `failOnViolation=false` (`pom.xml:132`). | Média | Adicionar esses testes, o teste de carga e os gates de qualidade. |

---

#### 3. Requisitos do case não atendidos

1. **8.000 TPS sustentados e picos de 25.000: não atendido nem demonstrado.**
   - O consumo é de 1 thread por instância (item 20) e processa uma mensagem por vez.
   - Cada mensagem faz 1 round-trip ao Redis para idempotência, mais 4 por regra de frequência (item 14), todos síncronos.
   - Com cerca de 1 ms por round-trip, uma thread processa algo como 200 msg/s. Isso é uma estimativa minha, não uma medição.
   - Não há teste de carga, plano de escalonamento (instâncias × partições) nem estratégia de backpressure para os picos.
2. **Alerta em 500 ms: não medido.** Não existe métrica do evento até o alerta.
   - No pico, o lag do consumidor cresce sem limite com a configuração atual.
   - Se o requisito valer até a notificação ao cliente, os retries com backoff de cerca de 1 s já estouram o prazo na primeira falha.
3. **Resiliência: parcial.**
   - Há perda de alerta em dois pontos: a idempotência marcada antes do processamento (item 17) e o send sem confirmação (item 18).
   - O tópico de entrada não tem DLT (item 19).
   - Não há circuit breaker (item 21).
   - Uma queda do Redis para toda a detecção.
   - Uma config inválida congela as regras (item 9).
   - O cache pode começar vazio (item 16).
   - A infraestrutura tem ponto único de falha (item 25).
4. **Aderência ao diagrama: parcial.**
   - A notificação não é um serviço separado (item 6).
   - Não existe a "Base de clientes".
   - Push e e-mail não são independentes (item 22).
   - O bloco de segurança (TLS/mTLS, criptografia, mascaramento) não foi implementado (item 28).
   - O Prometheus e o Grafana não estão conectados ao motor (item 29).
5. **Regras extensíveis sem redeploy: só os parâmetros.** Um tipo novo de regra exige código e deploy (item 11).
6. **Documentação: ausente.** O `README.md` tem só o título. Não há instruções de execução, decisões de arquitetura nem os requisitos.

---

#### 4. As 3 mudanças de maior impacto

1. **Vazão e latência.**
   - Configurar `spring.kafka.listener.concurrency` (até 24 por instância, contando as instâncias) e escalar horizontalmente.
   - Trocar os 4 comandos da `FrequenciaRegra` por um único script Lua atômico, e considerar listener em lote com pipelining no Redis.
   - Criar um `Timer` de `dataHora` até a publicação e um teste de carga com 8k e 25k TPS para provar o SLA de 500 ms.
   - Arquivos: `application.properties`, `regras/FrequenciaRegra.java:29-34`, `kafka/TransacaoListener.java:41-47`.

2. **Garantia de entrega e resiliência do fluxo Kafka.**
   - Marcar a idempotência só depois do sucesso, e deduplicar a jusante pelo `alertaId`.
   - Fazer o `send` com confirmação, `acks=all` e `enable.idempotence=true`.
   - Configurar `DefaultErrorHandler` com DLT e `ErrorHandlingDeserializer` em `transactions`, timeouts no Redis e uma política fail-open ou fail-closed explícita.
   - Adicionar circuit breaker e backoff exponencial na notificação, e extrair a notificação para um serviço próprio, como no diagrama.
   - Arquivos: `kafka/TransacaoListener.java:36`, `kafka/AlertaPublisher.java:21`, `notificacao/NotificacaoListener.java:23`.

3. **Regras robustas e realmente extensíveis.**
   - Validar na API: DTO, `@Valid`, enum de tipo e autenticação.
   - Isolar falhas por regra no `RegraCache` e carregar as regras antes de iniciar os listeners.
   - Usar o id da config como `regraId` e uma chave Redis por regra.
   - Mover o contrato `Regra` para o domínio, com uma porta no lugar do Redis.
   - Introduzir tipos de regra declarativos (ou fábricas por bean) para criar regras novas sem redeploy.
   - Arquivos: `regras/RegraCache.java:31-49`, `regras/RegraController.java:24`, `regras/ValorAltoRegra.java:16`, `regras/FrequenciaRegra.java:5,21,26`.

Arquivos de referência:
- `/workspaces/motor-antifraude/docs/arquitetura-motor-antifraude.drawio`
- `/workspaces/motor-antifraude/docker-compose.yml`
- `/workspaces/motor-antifraude/motor-deteccao/pom.xml`
- `/workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties`
- `/workspaces/motor-antifraude/.claude/agents/arquiteto.md`

---

## Relatório completo do agente codigo-morto

#### Revisão de código sem uso: motor-deteccao (src/main, src/test, pom.xml, application.properties)

Encontrei pouco código morto de verdade. Li os 17 fontes de main e os 8 de test e confirmei cada ponto com Grep (ignorando `target/` e `.git/`). Nenhum arquivo foi alterado.

##### O que está em uso (não remover)
- **Classes e beans:** `TransacaoListener` e `NotificacaoListener` (`@KafkaListener`), `RegraCache.recarregar` (`@Scheduled`, ativado pelo `@EnableScheduling` em `MotorDeteccaoApplication.java:7`), `NotificacaoListener.falhouDeVez` (`@DltHandler`) e os dois controllers.
- **JPA:** `RegraConfigRepository.findByAtivaTrue` é chamado em `RegraCache.java:31`. Os getters e setters de `RegraConfig` (inclusive `getId()` e `isAtiva()`, que não aparecem no Grep fora da própria classe) são usados pelo JPA e pelo Jackson em `RegraController` (GET e PUT). Não são código morto.
- **`Alerta`:** `regraId`, `valor` e `geradoEm` só aparecem na própria classe, mas entram no JSON publicado em `AlertaPublisher.java:20`. São contrato de saída.
- **Imports:** todos os imports de main e test são usados. Os imports curinga `org.springframework.web.bind.annotation.*` (`RegraController.java:6`, `SimulacaoController.java:4`) também, mas é melhor trocá-los por imports explícitos.
- **application.properties:** todas as chaves são propriedades padrão do Spring Boot lidas pela autoconfiguração:
  - `spring.datasource.*` e `spring.jpa.hibernate.ddl-auto`: JPA e Postgres.
  - `spring.data.redis.*`: Redis.
  - `spring.kafka.bootstrap-servers` e `spring.kafka.consumer.group-id`: o group-id é usado pelo `TransacaoListener`, que não define `groupId`. O `NotificacaoListener` sobrescreve com `"notificacao"`.
  - `management.endpoints.web.exposure.include=health,prometheus`: depende do actuator e do micrometer-prometheus.
  - `logging.level.org.springframework.kafka.retrytopic=OFF`: faz sentido porque `@RetryableTopic` é usado.

  Nenhuma propriedade deixou de ser lida.

##### Achados

**1. Dependência sem uso: `spring-boot-starter-validation`**
- Onde: `/workspaces/motor-antifraude/motor-deteccao/pom.xml:49-52`, e o par de teste `spring-boot-starter-validation-test` em `pom.xml:93-97`.
- Evidência: a busca por `jakarta\.validation|@Valid|@NotNull|@NotBlank|@Positive` em `src/` não achou nada. `RegraController.salvar` (`RegraController.java:24`) recebe `@RequestBody RegraConfig` sem `@Valid`.
- Sugestão: remover as duas dependências, ou começar a validar de fato o PUT `/regras/{id}`. Hoje dá para gravar `tipo` nulo ou `limite` nulo, e isso quebra o `RegraCache.criar`.

**2. Starters de teste sem uso**
- Onde: `pom.xml:73-102` (`actuator-test`, `data-jpa-test`, `data-redis-test`, `kafka-test`, `validation-test`, `webmvc-test`).
- Evidência: a busca por `@DataJpaTest|@WebMvcTest|@DataRedisTest|EmbeddedKafka|MockMvc|@AutoConfigure|TestRestTemplate|RestTestClient` em `src/` não achou nada. Os testes usam só JUnit e Mockito, mais um `@SpringBootTest` vazio em `MotorDeteccaoApplicationTests.java:6`.
- Sugestão: manter um só, ou trocar todos por `spring-boot-starter-test`, que traz JUnit e Mockito. Acrescente os outros apenas quando houver testes de slice. Rode `mvn test` depois para confirmar.

**3. Arquivo de configuração órfão: `spotbugs-exclude.xml`**
- Onde: `/workspaces/motor-antifraude/motor-deteccao/spotbugs-exclude.xml`.
- Evidência: a busca por `spotbugs` (sem diferenciar maiúsculas) fora de `target/` não achou nada. O `pom.xml` não tem mais o plugin, que foi retirado no commit 93b474c ("removido spotbugs suporte").
- Sugestão: remover o arquivo.

**4. Campos do record nunca lidos: `Transacao.pais` e `Transacao.estabelecimento`**
- Onde: `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/domain/Transacao.java:11-12`.
- Evidência: a busca por `pais|estabelecimento` no repositório só acha a declaração. Nenhuma regra e nenhum `Alerta` leem esses campos.
- Ressalva: eles fazem parte do JSON consumido do tópico `transactions`. Removê-los não quebra a desserialização, que hoje simplesmente os ignora, mas tira o contrato do código.
- Sugestão: manter se houver regras planejadas que usem esses dados (por exemplo, país incomum). Caso contrário, remover. Prioridade baixa.

**5. Package não bate com a pasta: `RegraController`**
- Onde: `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraController.java:1`.
- Evidência: o arquivo declara `package com.mica.motor.api;` mas está na pasta `regras/`.
- Não é código morto: o javac aceita e o component scan encontra a classe. Mas confunde a navegação e pode disparar avisos de ferramentas.
- Sugestão: mover o arquivo para `src/main/java/com/mica/motor/api/`, junto do `SimulacaoController`.

**6. Literais duplicados (IDs de regra e nomes de tópico)**
- **IDs de regra:** `"VALOR_ALTO"` em `ValorAltoRegra.java:16` e `RegraCache.java:43`; `"FREQUENCIA"` em `FrequenciaRegra.java:21` e `RegraCache.java:44`. Sugestão: uma constante ou enum de tipos de regra, usada pelas regras e pelo `switch`.
- **Tópico `"alerts"`:** aparece em `AlertaPublisher.java:21` e `NotificacaoListener.java:24`. Se mudar só de um lado, o fluxo quebra sem nenhum erro. Sugestão: uma constante compartilhada ou uma propriedade (por exemplo, `${topicos.alertas}`).

**7. Duplicação nos testes (fixtures)**
- **`Transacao` de teste**, com os mesmos `"ev-1", "tx-1", "conta-1", ..., "BR", "Loja", Instant.now()`:
  - `ValorAltoRegraTest.java:15-18`
  - `FrequenciaRegraTest.java:32-35`
  - `TransacaoListenerTest.java:30-31`
- **`Alerta` de teste**, idêntico (`"tx-1:VALOR_ALTO", "tx-1", "conta-1", "VALOR_ALTO", new BigDecimal("20000"), Instant.now()`):
  - `NotificacaoGatewayTest.java:14-15`
  - `NotificacaoListenerTest.java:19-20`
- Sugestão: uma classe de fixtures em `src/test`, por exemplo `com.mica.motor.Fixtures` com `transacao(valor)` e `alerta()`.

**8. Campo que poderia ser variável local**
- Onde: `/workspaces/motor-antifraude/motor-deteccao/src/test/java/com/mica/motor/regras/FrequenciaRegraTest.java:19` (`private StringRedisTemplate redis;`).
- Evidência: o campo só é usado dentro de `setUp()` (linhas 26-29). Nenhum teste o lê ou faz `verify` sobre ele.
- Sugestão: transformar em variável local, como já é feito em `IdempotenciaServiceTest.java:24`. Outra opção é usar o campo para verificar `expire` e `removeRangeByScore`, que hoje não são testados.

##### Observações menores
- `MotorDeteccaoApplicationTests.java:9-11`: `contextLoads()` vazio, que é boilerplate. Ele precisa de Postgres, Redis e Kafka de pé para passar. Se não roda no CI, vale remover ou trocar por um teste de contexto com Testcontainers.
- `/workspaces/motor-antifraude/motor-deteccao/HELP.md` é o arquivo gerado pelo Spring Initializr e não tem conteúdo do projeto. Pode ser removido.
