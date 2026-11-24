# Revisão por IA — Rodada 3

> ⚠️ **Relatório parcial.** O agente `caca-bugs` ainda não terminou. O resumo consolidado de N1, N2, P1, P2, P5 e N5 será escrito quando ele concluir; por enquanto vale a análise do `arquiteto`, abaixo.

- **Data:** 2026-11-24
- **Commit revisado:** `b83719b`. A mensagem é "docs: README e uso de IA", mas o commit traz as correções de código. O `README.md` revisado é a versão do disco, ainda não commitada.
- **Objetivo:** verificar se N1, N2, P1, P2, P5 e N5 da [rodada 2](rodada-2.md) foram resolvidos, se as correções criaram problemas novos e se o `README.md` descreve corretamente o que o código faz
- **Agentes:** `caca-bugs`, `arquiteto` e `codigo-morto`, em paralelo
- **Observação:** os agentes não alteraram arquivos. Os testes, a cobertura e as afirmações sobre o `pom.xml` foram conferidos depois, rodando o build.

## Verificações feitas fora dos agentes

### Testes
- Comando do README (`./mvnw test -Dtest='*RegraTest,...'`): **26 testes, 0 falhas**, sem precisar de Docker.
- Suíte completa (`./mvnw test`, inclui `contextLoads`, precisa da infraestrutura no ar): **27 testes, 0 falhas**.

### Cobertura (JaCoCo)

| Execução | Instruções | Desvios |
|---|---|---|
| Afirmado no README | 84% | 80% |
| Comando do README (só unitários) | 75,9% | 77,5% |
| Suíte completa (com infraestrutura) | 86,6% | 77,5% |

Os números do README não batem com nenhuma das duas medições. Os 80% de desvios ficam acima do que foi medido nas duas.

### Ferramentas no `pom.xml`
- O plugin do SpotBugs **não está** no `pom.xml`. Ele foi removido no commit `93b474c`. O README diz que o projeto usa SpotBugs.
- O `maven-pmd-plugin` **continua** no `pom.xml` (linha 128), sem `<executions>`. O README diz que ele foi removido.
- O `pom.xml` declara `java.version` 21 e o ambiente roda o JDK 25 (`openjdk 25.0.5`). Quem não lê Java 25 é a ferramenta rodando no JDK do ambiente, e não as classes compiladas, como o README dá a entender.

## Afirmações falsas ou imprecisas no README (até agora)

| Trecho do README | Situação | Evidência |
|---|---|---|
| "Cobertura (JaCoCo): 84% das instruções e 80% dos desvios" | ❌ Falso | Medido: 75,9%/77,5% (unitários) ou 86,6%/77,5% (suíte completa) |
| "Ficaram de fora os controllers e a classe main" | ⚠️ Incompleto | `AlertaPublisher` e `KafkaConfig` também estão com 0% de cobertura |
| "Análise estática: SpotBugs ..." | ❌ Falso | Não há plugin do SpotBugs no `pom.xml`, e o `spotbugs-exclude.xml` está órfão |
| "O PMD ... foi removido" | ❌ Falso | `maven-pmd-plugin` em `pom.xml:128` |
| "O PMD não lê as classes do Java 25" | ⚠️ Impreciso | As classes são compiladas para Java 21. Java 25 é o do ambiente |
| "A notificação não tem idempotência própria" (Limitações) | ❌ Falso desde `b83719b` | Há dedup por `alertaId` em `NotificacaoListener.java:32-37` |
| "`SETNX` no Redis por `eventId`, liberado em caso de falha" / "gravado com `SETNX` e TTL de 24 h" | ⚠️ Impreciso | Agora são dois estados: `PROCESSANDO` (SETNX, 5 min) e `CONCLUIDO` (SET, 24h) |
| Link para `AI_USAGE.md` | ⚠️ Risco | O arquivo existe, mas não é rastreado pelo git. Se não for commitado junto, o link quebra |
| Comando de testes | ⚠️ Incompleto | Correto, mas não avisa que um `./mvnw test` sem filtro roda o `contextLoads` e falha sem a infraestrutura |

---

## Relatório completo do agente codigo-morto

### Rodada 3: código sem uso, duplicação e conferência do README (motor-deteccao)

Não alterei nenhum arquivo. Não tinha shell, então não rodei git nem o build. Separei o que é novo do que é pré-existente usando a lista de mudanças de b83719b que você me passou.

Um ponto antes de tudo: segundo o git status do início da sessão, o README.md está modificado e ainda não foi commitado. O AI_USAGE.md e o docs/revisao-ia/ não são rastreados pelo git. O texto do README que conferi é o que está no disco, não o que está em b83719b.

---

#### TAREFA 1: código sem uso, sobras e duplicação

##### 1.1 O que você citou e continua em uso (não é código morto)

- **`IdempotenciaService.liberar()`** (`src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java:35-37`)
  - Ainda é necessário. É chamado em `kafka/TransacaoListener.java:58` e testado em `IdempotenciaServiceTest.java:60-63` e `TransacaoListenerTest.java:100,109`.
  - Sem ele, a marca `PROCESSANDO` fica no Redis por 5 minutos (`IdempotenciaService.java:23`). Aí cada nova tentativa daria `EventoEmProcessamentoException`, e com as 3 tentativas a 1s de intervalo (`KafkaConfig.java:40`) o evento iria para a DLT.
  - Com o `liberar`, a nova tentativa começa limpa. O `liberar` só deixaria de ser necessário se o backoff passasse a ser maior que o TTL de 5 minutos.
- **Imports de `IllegalArgumentException`/`IllegalStateException`:** não existem. As duas classes são de `java.lang`, então não precisam de import.
  - `IllegalArgumentException` não aparece mais no `KafkaConfig`: `KafkaConfig.java:43` usa só `JacksonException` e `EventoInvalidoException`. O único uso restante é `regras/RegraCache.java:48`, que é pré-existente e é capturado pelo `catch (Exception)` da linha 36.
  - `IllegalStateException` continua em uso em `AlertaPublisher.java:27,29`, `NotificacaoGateway.java:21` e nos testes.
- **`RegraCache` em `TransacaoListenerTest`:** é usado. O import fica em `TransacaoListenerTest.java:18`, e a classe é usada como variável local na linha 42 (`mock(RegraCache.class)`) e no construtor na linha 49.
- **`Instant` em `domain/Alerta.java:4`:** é usado como tipo do campo `geradoEm` (linha 12). A mudança foi só no valor, que agora é `t.dataHora()` na linha 19.
- **`EventoInvalidoException` e `EventoEmProcessamentoException` (novas):** as duas são usadas. A primeira aparece em `TransacaoListener.java:68,71` e `KafkaConfig.java:43`. A segunda aparece em `TransacaoListener.java:43`. As duas são testadas.
- **`jaNotificado` e `marcarNotificado` (novos):** os dois são usados em `NotificacaoListener.java:32,37`.
- **Imports dos arquivos novos ou alterados:** nenhum está sobrando. Conferi `IdempotenciaService`, `TransacaoListener`, `KafkaConfig` (`NewTopic`, `TopicBuilder`, `TopicPartition` etc.), `NotificacaoListener` (`JacksonException` na linha 28), `Alerta` e os três testes alterados. Por exemplo, `assertEquals` e `anyString` em `TransacaoListenerTest.java:3,6` são usados nas linhas 101 e 113-114.

##### 1.2 Duplicação e constantes repetidas

**Novos (b83719b):**
1. **Prefixo `"notif:"` duplicado** em `IdempotenciaService.java:40` e `:44`. Já o `"evento:"` está centralizado em `chave()` na linha 48. Falta o equivalente para a notificação, algo como `chaveNotif()`.
2. **TTL de 24h duplicado** em `IdempotenciaService.java:32` e `:44` (`Duration.ofHours(24)`). Os TTLs de 5 minutos (linha 23) e de 24h poderiam virar constantes.
3. **As constantes `PROCESSANDO`/`CONCLUIDO` (`IdempotenciaService.java:12-13`) são package-private, mas o teste repete os literais.** O teste está no mesmo pacote, mas usa as strings direto em `IdempotenciaServiceTest.java:35,41,42,48,49,56`. O mesmo vale para `"evento:ev-1"` (linhas 35-62).
4. **O nome `CONCLUIDO` aparece duas vezes:** como a constante String da linha 13 e como o valor do enum `Inicio.CONCLUIDO` da linha 10. Funciona, mas a linha 28 (`CONCLUIDO.equals(atual) ? Inicio.CONCLUIDO`) fica ambígua para quem lê.
5. **Nomes de tópico espalhados, sem uma constante comum:**
   - `"transactions"`: `KafkaConfig.java:21` (novo) e `TransacaoListener.java:32`.
   - `"alerts"`: `KafkaConfig.java:31` (novo), `AlertaPublisher.java:24` e `NotificacaoListener.java:29`.
   - `"transactions-dlt"` escrito por extenso em `KafkaConfig.java:26` (novo), enquanto a linha 37 do mesmo arquivo monta o mesmo nome com `registro.topic() + "-dlt"`. Ou seja, o sufixo `-dlt` está definido duas vezes no mesmo arquivo. Além disso, o sufixo de `alerts-dlt` é implícito, vem do padrão do `@RetryableTopic`.
6. **`.replicas(1)` repetido três vezes** em `KafkaConfig.java:21,26,31` (novo).
7. **O número de partições, 24, aparece em dois lugares:** `KafkaConfig.java:17` (`PARTICOES = 24`, novo) e `docker-compose.yml:14` (`KAFKA_NUM_PARTITIONS: 24`, pré-existente).
   - Hoje o valor do compose só vale para tópicos criados automaticamente que não têm `NewTopic`: `alerts-dlt` e os `alerts-retry-*`.
   - Observação: o `@RetryableTopic` cria esses tópicos com a configuração padrão dele, e não com `PARTICOES`, então a contagem de partições pode não bater com a de `alerts`. É o mesmo número em dois lugares, sem garantia de que continuem iguais.
8. **O número de tentativas, 4, aparece duas vezes:** em `NotificacaoListener.java:28` (`attempts = "4"`) e no texto do log da linha 43 ("depois de 4 tentativas").

**Pré-existentes (continuam):**
- **IDs de regra:** `"VALOR_ALTO"`/`"FREQUENCIA"` estão em `RegraCache.java:43-44`, `ValorAltoRegra.java:16` e `FrequenciaRegra.java:21`.
- **Fixture de `Alerta`:** repetida em `NotificacaoGatewayTest.java:14-15` e `NotificacaoListenerTest.java:23-24`.
- **Fixture de `Transacao`:** repetida em `TransacaoListenerTest.java:34-35,119-120`, `ValorAltoRegraTest.java:16-17` e `FrequenciaRegraTest.java:33-34`.

##### 1.3 Validação de campos duplicada?

Não há duplicação. `TransacaoListener.validar` (linhas 66-73, novo) é o único lugar que valida. O Grep por `jakarta.validation`, `@Valid`, `@NotNull` e `@NotBlank` não encontra nada no projeto, e `domain/Transacao.java` não tem anotações. As regras não checam nulos (`ValorAltoRegra.java:21`, `FrequenciaRegra.java:27`), porque contam com o `validar`.

Duas observações sobre o código novo:
- **Falta de teste:** só o eventId nulo é testado (`TransacaoListenerTest.java:118-125`). Os casos de `valor`, `dataHora` e `accountId` nulos e de eventId em branco não têm teste. Isso confere com o JaCoCo, que mostra 5 desvios não cobertos em `TransacaoListener`.
- **Mensagem duplicada:** as duas mensagens de erro de `validar` (linhas 68 e 71) repetem a montagem `"Evento ..."`, também usada em `EventoEmProcessamentoException.java:6`. É pequeno.

##### 1.4 `logging.level.org.springframework.kafka.retrytopic=OFF`

- Continua em `src/main/resources/application.properties:15` (pré-existente).
- Ainda tem efeito, porque o `@RetryableTopic` continua em `NotificacaoListener.java:28`. Mas desliga **todo** o log do retry topic, inclusive erros.
- Com o `exclude = JacksonException.class` (novo), um JSON inválido em `alerts` vai direto para a DLT. O único registro disso é o `log.error` sem payload da linha 43.
- Recomendo `WARN` no lugar de `OFF`.

##### 1.5 Outras propriedades em application.properties

Tudo é lido. `spring.kafka.consumer.group-id` (linha 12) é usado pelo `TransacaoListener`, que não define `groupId` próprio. As linhas 17-18 (`acks=all`, `enable.idempotence=true`) continuam sendo redundantes, porque já são o padrão do cliente Kafka. Isso já foi apontado na rodada 2.

##### 1.6 Itens da rodada 2: continuam lá?

| Item | Situação |
|---|---|
| `spotbugs-exclude.xml` órfão (`/workspaces/motor-antifraude/motor-deteccao/spotbugs-exclude.xml`) | **Continua.** Nada aponta para ele: o Grep fora de `target/` só encontra os relatórios de revisão. |
| `spring-boot-starter-validation` (`pom.xml:49-52`) e `spring-boot-starter-validation-test` (`pom.xml:93-97`) | **Continuam sem uso.** Não há nenhuma ocorrência de `jakarta.validation` nem `@Valid`. |
| Starters de teste sem uso: `actuator-test` (`pom.xml:73-77`), `data-jpa-test` (78-82), `data-redis-test` (83-87), `kafka-test` (88-92), `webmvc-test` (98-102) | **Continuam.** Não há `@EmbeddedKafka`, `@WebMvcTest`, `@DataJpaTest` nem `@DataRedisTest`. O único teste com Spring é `@SpringBootTest` em `MotorDeteccaoApplicationTests.java:6`. Se forem removidos, mantenha um starter que traga JUnit e Mockito. |
| `HELP.md` (`/workspaces/motor-antifraude/motor-deteccao/HELP.md`) | **Continua no disco, mas está em `.gitignore:1`**, então não vai para o repositório. Isso é só limpeza local. |
| `MotorDeteccaoApplicationTests.contextLoads()` vazio (`MotorDeteccaoApplicationTests.java:9-11`) | **Continua.** Precisa da infraestrutura no ar. |
| **Novo, achado nesta rodada:** `maven-pmd-plugin` (`pom.xml:126-134`) | Não tem `<executions>`, então não roda no build. É configuração morta e contradiz o README (veja 2.4). |

---

#### TAREFA 2: conferência do README (TESTES, BUILD, FERRAMENTAS)

##### 2.1 O comando `./mvnw test -Dtest='*RegraTest,IdempotenciaServiceTest,TransacaoListenerTest,RegraCacheTest,Notificacao*Test'` (README.md:64)

- **Todos os padrões casam com classes que existem:**
  - `*RegraTest` casa com `ValorAltoRegraTest` e `FrequenciaRegraTest`. Ele **não** casa com `RegraCacheTest`, que termina em `CacheTest`, e por isso esse teste aparece listado à parte.
  - `IdempotenciaServiceTest`, `TransacaoListenerTest` e `RegraCacheTest` casam com o nome exato.
  - `Notificacao*Test` casa com `NotificacaoGatewayTest` e `NotificacaoListenerTest`.
- **Fica de fora só o `MotorDeteccaoApplicationTests`** (o padrão termina em `Tests`). É o `@SpringBootTest` que precisa de Postgres, Redis e Kafka. Excluir ele é proposital e está correto.
- **Nenhum dos testes incluídos precisa de Docker.** Todos usam só Mockito.
- Os `target/surefire-reports` têm exatamente essas 7 classes e 26 casos: 6 de Idempotencia, 7 de TransacaoListener, 4 de RegraCache, 3 de ValorAlto, 2 de Frequencia, 2 de NotificacaoListener e 2 de NotificacaoGateway.
- **Imprecisões:**
  - O README não avisa que um simples `./mvnw test`, sem o `-Dtest`, roda o `contextLoads` e falha sem a infraestrutura.
  - A rodada 2 diz "19 testes", e hoje são 26. Não é erro do README, mas as contagens nos relatórios de revisão ficaram desatualizadas.
- O comentário `# relatório: target/site/jacoco/index.html` (README.md:65) está correto, porque o `report` está ligado à fase `test` (`pom.xml:117-123`).

##### 2.2 "Unitários: regras (incluindo o limite exato), idempotência, listener (...), cache de regras (...) e notificação" (README.md:106)

| Afirmação | Teste | Situação |
|---|---|---|
| regras, limite exato | `ValorAltoRegraTest.exatamenteNoLimiteNaoEhSuspeita` (:26). A fronteira da frequência está em `FrequenciaRegraTest` (:38, :44) | OK |
| idempotência | `IdempotenciaServiceTest` (6 testes) | OK. Falta testar `marcarNotificado` e `jaNotificado == false` |
| listener: publica só quando suspeita | `publicaAlertaEConcluiQuandoSuspeita` (:53) e `naoPublicaQuandoNaoSuspeitaMasConclui` (:64) | OK |
| listener: ignora duplicado | `ignoraEventoJaConcluido` (:75) | OK. O termo "duplicado" agora significa "já concluído" |
| listener: libera a marca quando a publicação falha | `liberaENãoConcluiQuandoPublicacaoFalha` (:93) | OK. O nome do método tem um caractere não ASCII ("ã"), diferente dos outros testes |
| cache: Postgres fora do ar | `RegraCacheTest.postgresForaDoArMantemUltimasRegras` (:54) | OK |
| cache: tipo inválido | `tipoDesconhecidoNaoDerrubaOMotor` (:66) | OK |
| notificação | `NotificacaoGatewayTest` e `NotificacaoListenerTest` | OK |

**O README está incompleto, e não errado:** não cita testes que existem.
- `eventoPelaMetadeFalhaEmVezDeSumir` (:85)
- `falhaAoLiberarNaoEscondeOErroOriginal` (:105)
- `eventoSemIdEhInvalido` (:118)
- `NotificacaoListenerTest.naoEnviaDeNovoAlertaRepetido` (:46)

**Falsas fora da seção de testes, mas causadas pelo b83719b:**
- **README.md:132**, "A notificação não tem idempotência própria": é **falso** agora. A deduplicação por `alertaId` existe em `NotificacaoListener.java:32-37` e `IdempotenciaService.java:39-45`. A parte "nem circuit breaker" continua verdadeira.
- **README.md:83** ("`SETNX` no Redis por `eventId`, liberado em caso de falha") e **README.md:96** ("O `eventId` é gravado com `SETNX` e TTL de 24 h"): são **imprecisas**. Agora são dois estados: `PROCESSANDO`, com TTL de 5 minutos via `setIfAbsent` (`IdempotenciaService.java:23`), e `CONCLUIDO`, com TTL de 24h gravado por `SET` (linha 32). O TTL de 24h não vem do SETNX.
- **README.md:96**, "permite que quem consome o alerta descarte repetidos": é verdadeira e já está implementada, mas não diz que ela existe.

##### 2.3 "Cobertura (JaCoCo): 84% das instruções e 80% dos desvios" (README.md:107)

- **É falsa em relação ao relatório que está no repositório.** O rodapé de `target/site/jacoco/index.html` mostra "182 of 754" instruções perdidas, ou seja, **75%**, e "9 of 40" desvios perdidos, ou seja, **77%**. A soma das linhas de `target/site/jacoco/jacoco.csv` dá o mesmo resultado: 572 de 754 e 31 de 40.
- **O relatório parece atual, e não velho.** Ele já inclui as classes de b83719b: `IdempotenciaService.Inicio`, `EventoEmProcessamentoException` e `KafkaConfig` com 6 métodos, que são o construtor, os 3 beans `NewTopic`, o `errorHandler` e o lambda. Também bate com os 26 casos do surefire. O mais provável é que **84%/80% sejam de uma medição anterior** a b83719b. O KafkaConfig cresceu com 65 instruções não cobertas, e isso puxou a média para baixo.
- **"Ficaram de fora os controllers e a classe main" está incompleto.** Também ficaram com 0% o `AlertaPublisher` (45 instruções) e o `KafkaConfig` (65), que juntos somam mais instruções perdidas que os controllers e a main (43).
- **Ressalva:** como não tenho git, não sei os horários dos arquivos. A conclusão de que o relatório está atual vem da coerência entre o conteúdo do relatório e o código-fonte.

##### 2.4 "Análise estática: SpotBugs; os avisos EI_EXPOSE_REP ... foram filtrados. O PMD não lê as classes do Java 25 ... e foi removido." (README.md:109)

- **"SpotBugs" é falso.** Não há plugin SpotBugs no `pom.xml`: o Grep por "spotbugs" no pom não encontra nada, e o commit 93b474c se chama "removido spotbugs suporte". O filtro de EI_EXPOSE_REP só existe no `spotbugs-exclude.xml`, que está órfão.
- **"O PMD foi removido" é falso.** O `maven-pmd-plugin` 3.26.0 continua em `pom.xml:126-134`, com `failOnViolation=false` e sem `<executions>`. Ele só roda se alguém chamar `mvn pmd:check` à mão.
- O README descreve o contrário do pom: diz que o SpotBugs ficou e o PMD saiu, mas no pom o PMD ficou e o SpotBugs saiu.
- **"classes do Java 25" é impreciso.** O pom declara `<java.version>21</java.version>` (`pom.xml:30`), igual ao README.md:18 e :26. Se o problema era o JDK 25 do ambiente rodando a ferramenta, o texto deveria dizer isso, porque as classes são compiladas para Java 21. Não consegui confirmar a versão do JDK do ambiente.

##### 2.5 Menção a `AI_USAGE.md` (README.md:110 e :147)

O arquivo existe em `/workspaces/motor-antifraude/AI_USAGE.md`. Mas segundo o git status ele **não é rastreado** (`?? ../AI_USAGE.md`), assim como `docs/revisao-ia/`. Se o README for commitado sem ele, o link fica quebrado no repositório.

---

##### Arquivos relevantes
- /workspaces/motor-antifraude/README.md
- /workspaces/motor-antifraude/motor-deteccao/pom.xml
- /workspaces/motor-antifraude/motor-deteccao/spotbugs-exclude.xml
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/KafkaConfig.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoListener.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties
- /workspaces/motor-antifraude/motor-deteccao/src/test/java/com/mica/motor/idempotencia/IdempotenciaServiceTest.java
- /workspaces/motor-antifraude/motor-deteccao/target/site/jacoco/index.html
- /workspaces/motor-antifraude/motor-deteccao/target/site/jacoco/jacoco.csv
- /workspaces/motor-antifraude/docker-compose.yml

---

## Relatório completo do agente arquiteto

### Revisão de arquitetura, rodada 3 (commit b83719b e README não commitado)

Só li os arquivos. Não alterei nada e não rodei build nem testes. O que digo sobre o comportamento do spring-kafka e do KafkaAdmin vem do conhecimento das bibliotecas, sem execução. Caminhos relativos a `/workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/`, salvo indicação.

**Resumo:** o commit fecha a perda silenciosa de eventos (P1, P2) e a falta da DLT (N2). Mas três coisas continuam abertas:
- Eventos interrompidos acabam na `transactions-dlt`, que ninguém consome.
- A deduplicação de alertas só existe na notificação e fica acoplada ao Redis e a uma classe do motor.
- O README tem várias afirmações desatualizadas ou falsas.

---

#### Tarefa 1 — N1, N2, P1, P2, P5, N5

| Item | Situação | Coerente com a arquitetura? |
|---|---|---|
| **N1** alertas duplicados | **Parcial** | Não totalmente (veja abaixo) |
| **N2** DLT não criada | **Resolvido no ambiente local**, parcial para produção | Parcial |
| **P1** crash no meio perde evento | **Parcial**: não perde mais em silêncio, mas o evento vai para a DLT | Sim, falta a outra metade |
| **P2** falha no `liberar` perde evento | **Resolvido** quanto à perda; o evento termina na DLT | Sim |
| **P5** NPE retentada | **Quase resolvido**; falta validar `transactionId` | Validação no lugar errado |
| **N5** IAE genérica não-retentável | **Resolvido** | Exceção no pacote errado |

##### P1 e P2: não perdem mais, mas tudo acaba na DLT
- O crash deixa a chave `PROCESSANDO` com TTL de 5 min (`idempotencia/IdempotenciaService.java:23`). A reentrega vira `EventoEmProcessamentoException` (`kafka/TransacaoListener.java:43`).
- Essa exceção é retentável, mas o backoff é de 3 tentativas com 1 s entre elas (`kafka/KafkaConfig.java:40`), uns 3 s no total.
- Como 3 s é muito menos que 5 min, **todo evento interrompido por crash vai sempre para `transactions-dlt`**. O mesmo vale para P2, quando o `liberar` falha (`TransacaoListener.java:57-61`) e a chave continua lá.
- **Ninguém consome `transactions-dlt`**. Então "não perde" depende de um replay manual que não existe nem está documentado. Para fechar, falta uma destas:
  - um backoff para essa exceção maior que o TTL;
  - um retry não bloqueante em tópico de retry;
  - um reprocessador da DLT.
- Na DLT, os eventos "em processamento" ficam misturados com as mensagens inválidas. Só o header da exceção diferencia.
- O `addSuppressed` em P2 está correto, e o teste `falhaAoLiberarNaoEscondeOErroOriginal` cobre o caso (`src/test/.../kafka/TransacaoListenerTest.java:104-115`).

##### N1: resolvido só para a notificação, e com acoplamento
- **O lado do produtor não mudou.** No retry, o motor publica de novo o alerta da regra 1 no tópico `alerts` (`TransacaoListener.java:48-53`). O tópico continua recebendo alertas duplicados.
- **O "Antifraude interno" não é resolvido.** Ele não existe no código: não há nenhum outro `@KafkaListener` em `alerts`. Se for implementado, terá que reimplementar a deduplicação por conta própria.
  - Isso precisa virar contrato documentado: "`alertaId` é a chave de idempotência; todo consumidor deve deduplicar".
  - A alternativa é tratar na origem: transação Kafka (consumir, processar e produzir com `read_committed`) ou registrar `alerta:<id>` no Redis antes de publicar.
- **A deduplicação é "verifica, depois age", sem atomicidade.** Ela faz `hasKey`, depois `enviar`, depois `set` (`notificacao/NotificacaoListener.java:32-37`).
  - O `@RetryableTopic` consome `alerts-retry-N` em outros containers, em paralelo ao `alerts`.
  - Então a cópia duplicada vinda do motor e a tentativa original em retry podem passar as duas pelo `hasKey`.
  - Além disso, se o `marcarNotificado` falhar depois do `enviar`, a notificação é reenviada. Isso é "pelo menos uma vez", o que é aceitável, mas precisa estar documentado.
- **Acoplamento.** Sim, aumentou. A notificação, que no diagrama é um serviço separado (`docs/arquitetura-motor-antifraude.drawio:34`), agora:
  - importa `com.mica.motor.idempotencia.IdempotenciaService` (`NotificacaoListener.java:4,19`);
  - depende do **mesmo Redis** do motor.
- No diagrama não existe seta entre Redis e notificação (arestas e5 e e9-e13, `drawio:64,76-96`).
- Ao separar os serviços, seria preciso copiar a classe ou dividir o keyspace (`notif:*` e `evento:*`). Ou seja, um banco compartilhado entre serviços.
- Com o Redis fora, a notificação agora também falha: são 4 tentativas e depois `alerts-dlt`. O "serviço auxiliar" passou a depender de mais uma coisa.
- **Duas responsabilidades.** O `IdempotenciaService` passou a ter dois papéis:
  - a máquina de estados do evento: `iniciar`, `concluir`, `liberar` (linhas 21-37);
  - a deduplicação de envio da notificação: `jaNotificado`, `marcarNotificado` (linhas 39-45).
- São dois contextos com TTL, chave e ciclo de vida diferentes, agrupados só pela tecnologia.
- **Proposta:** um `DeduplicadorNotificacao` dentro de `notificacao`, atrás de uma porta própria (por exemplo `RegistroEnvios`) com um adaptador Redis próprio. O `IdempotenciaService` fica só com o motor.
- **`geradoEm = t.dataHora()`** (`domain/Alerta.java:15-19`):
  - Resolve o conteúdo instável, mas distorce o significado: o campo `geradoEm` agora guarda a hora da transação.
  - Também elimina o único lugar onde se mediria a latência de ponta a ponta (do evento ao alerta), que é o requisito de 500 ms.
  - Melhor: renomear para `dataHoraTransacao`, ou manter os dois campos. A deduplicação deve ser pelo `alertaId` e não exigir payload idêntico.

##### N2: resolvido localmente, frágil em produção
- Os três `NewTopic` estão em `kafka/KafkaConfig.java:19-32`, e `transactions-dlt` agora existe antes do primeiro erro.
- **Dois mecanismos de criação de tópicos.**
  - `alerts-retry-*` e `alerts-dlt` continuam sendo criados pelo `@RetryableTopic`, com partições e réplicas padrão do broker.
  - O número 24 aparece duas vezes: `KafkaConfig.java:17` e `docker-compose.yml:14` (`KAFKA_NUM_PARTITIONS`). São duas fontes de verdade.
- **`replicas(1)` fixo no código não serve para produção.**
  - Num cluster de 3 ou mais brokers, criaria tópicos com RF=1, sem tolerância a falha de broker. Isso anula o `acks=all` (`application.properties:17`).
  - O KafkaAdmin não altera o RF de tópicos que já existem. Em produção, o valor no código ou é ignorado (tópico já criado pela plataforma) ou é perigoso (tópico criado pela aplicação).
  - **Correção:** levar partições e RF para properties por perfil, ou desligar `spring.kafka.admin.auto-create` em produção e criar os tópicos via IaC. Ali também entram `min.insync.replicas=2` e `retention.ms` das DLTs, o que ajuda na LGPD.
- **Ownership invertido.** O motor cria `transactions`, um tópico cujo dono é o produtor upstream ("Serviço interno do banco", `drawio:7`). Criar `alerts` e `transactions-dlt` faz sentido; criar `transactions`, não.

##### P5: quase resolvido
- `validar()` cobre payload `null`, `eventId`, `valor`, `dataHora` e `accountId` (`TransacaoListener.java:66-73`).
- **Falta `transactionId`.** Com ele nulo:
  - o `alertaId` vira `"null:VALOR_ALTO"` (`Alerta.java:17`);
  - o primeiro alerta é notificado e marca `notif:null:VALOR_ALTO` por 24 h (`IdempotenciaService.java:44`);
  - **todas as outras transações sem `transactionId` têm a notificação suprimida por 24 h**;
  - na `FrequenciaRegra`, o `zset.add` com membro nulo (`regras/FrequenciaRegra.java:30`) deve gerar uma exceção do Redis, que é retentável.
- Só há teste de `eventId` nulo (`TransacaoListenerTest.java:117-125`). Não há teste para os campos obrigatórios.

##### N5: resolvido, mas no pacote errado
- `IllegalArgumentException` saiu da lista de não-retentáveis. Ela agora tem só `JacksonException` e `EventoInvalidoException` (`KafkaConfig.java:43`).
- **Pacote:**
  - `EventoInvalidoException` (`kafka/EventoInvalidoException.java`) representa uma invariante do domínio (`Transacao` válida), não do transporte.
  - `EventoEmProcessamentoException` é um estado da idempotência e da aplicação.
- Com as duas em `kafka`, a validação ficou no adaptador (`TransacaoListener.validar`). Qualquer outra entrada, como replay, REST ou batch, teria que duplicá-la.
- **Proposta:** validar no construtor compacto do record `Transacao`, ou num validador em `domain`, com a exceção também em `domain`.
  - Bônus: se a validação ficar no construtor do record, o Jackson embrulha o erro numa `JacksonException`, que já é não-retentável.
  - O `KafkaConfig`, que é o adaptador, continua só mapeando a exceção.

---

#### Tarefa 2 — Problemas novos introduzidos por b83719b

1. **ALTA: o backoff de 3 s e o TTL de 5 min estão desalinhados** (`KafkaConfig.java:40` e `IdempotenciaService.java:23`).
   - Todo crash ou rebalance no meio do processamento manda o evento em andamento para a `transactions-dlt`.
   - A DLT não tem consumidor, métrica nem alarme.
   - Também acontece com um rebalance sem crash: a instância B manda para a DLT um evento que a instância A ainda conclui. O replay posterior vê `CONCLUIDO`, então o resultado está certo, mas a DLT fica com ruído.
2. **ALTA: suspensão das notificações por `transactionId` nulo** (Tarefa 1, P5): o `alertaId` `null:<regra>` colide na deduplicação e silencia as notificações por 24 h.
3. **MÉDIA: acoplamento entre notificação e motor.**
   - A notificação depende de `IdempotenciaService` e do Redis do motor (`NotificacaoListener.java:4,19,32,37`).
   - Isso contradiz o diagrama e a própria "Simplificação consciente" do README (linha 102), e cria um novo ponto único de falha para a notificação.
   - O `IdempotenciaService` ficou com duas responsabilidades.
4. **MÉDIA: deduplicação sem atomicidade e só em um consumidor.** É "verifica, depois age", com corrida entre `alerts` e `alerts-retry-N`. O tópico `alerts` continua recebendo duplicatas para qualquer outro consumidor.
5. **MÉDIA: o `geradoEm` mudou de significado** (`Alerta.java:15-19`), sem renomear. Isso impede medir a latência evento→alerta (requisito de 500 ms).
6. **MÉDIA: um round-trip a mais no Redis por evento.**
   - O caminho feliz agora faz `setIfAbsent` e `set` (`IdempotenciaService.java:22,32`). Antes era um só `SETNX`.
   - Com os 4 comandos da `FrequenciaRegra` (`FrequenciaRegra.java:30-34`), são 6 round-trips por transação, sem pipeline.
   - No pico de 25k TPS, isso dá uns **150k ops/s numa única instância de Redis**, na thread do listener.
   - A notificação soma mais 2 comandos por alerta.
7. **MÉDIA: topologia de tópicos fixa no código** (`KafkaConfig.java:17,21,26,31`):
   - `replicas(1)` e 24 partições fixos, duplicados no compose;
   - criação do tópico upstream `transactions`;
   - DLTs sem `retention.ms`, guardando o payload completo (accountId e valor, ponto de LGPD);
   - `alerts-dlt` e os tópicos de retry criados por outro mecanismo.
8. **BAIXA: exceções em `kafka`** em vez de `domain` ou da aplicação, e validação de domínio dentro do adaptador.
9. **BAIXA: lacunas de teste.**
   - Não há teste de `marcarNotificado` (TTL) nem de `jaNotificado` retornando `false` (`src/test/.../idempotencia/IdempotenciaServiceTest.java:65-69`).
   - Não há teste de campos obrigatórios vazios.
   - Não há teste de integração que prove o caminho `EM_PROCESSAMENTO → DLT`.

Pontos da rodada 2 que continuam abertos e foram agravados pelo item 1:
- **N3/N4:** `get(5s)` síncrono com `max.block.ms` de 60 s na thread do listener (`kafka/AlertaPublisher.java:24`).
- **A3:** cache de regras vazio marca eventos como `CONCLUIDO` sem avaliar (`regras/RegraCache.java:17`).

---

#### Tarefa 3 — README (`/workspaces/motor-antifraude/README.md`) contra código, diagrama e compose

##### Diagrama ASCII e componentes
- **L5, "publica alertas para o antifraude interno"; L12, "→ Antifraude interno":** o componente não existe no código, porque só `NotificacaoListener` consome `alerts`. Ele existe no diagrama (`drawio:19`, aresta e4 em `drawio:61`), mas como consumidor externo. O README deveria dizer "consumidor externo, fora do escopo".
- **L13, `Notificação → [alerts-dlt]`:** correto (`@RetryableTopic` em `NotificacaoListener.java:28`). Mas o ASCII omite:
  - a `transactions-dlt` do motor (`KafkaConfig.java:25-27,37`), que também não aparece no drawio, onde só a notificação tem DLQ (`drawio:94`);
  - os tópicos `alerts-retry-*`;
  - o Redis usado pela notificação;
  - a API de Regras e o Prometheus/Grafana.
- **L17, "`transactions` (24 partições, chave `accountId`): as transações de uma conta ficam sempre na mesma partição":**
  - As 24 partições batem (`KafkaConfig.java:17`, `docker-compose.yml:14`).
  - Mas quem escolhe a chave é o produtor externo, e o motor não produz em `transactions`.
  - **O próprio exemplo do README (L50-51) publica sem chave**, porque `kafka-console-producer` sem `parse.key` não define chave. Então a ordem por conta não vale na demo.
  - Quem usa `accountId` como chave é o `AlertaPublisher`, no tópico `alerts` (`AlertaPublisher.java:24`).
  - O texto deveria dizer: "contrato esperado do produtor: chave = accountId".
- **L18, "Java 21 + Spring Boot":** o Java bate (`pom.xml:30`). O Boot é o **4.2.0** (`pom.xml:8`), com Jackson 3 (`tools.jackson`). Vale citar a versão, porque muda APIs. O drawio diz só "Java + Spring" (`drawio:13`).
- **L19, "Redis: idempotência por eventId e janelas deslizantes":** incompleto. O Redis agora também faz a deduplicação da notificação (`notif:<alertaId>`, `IdempotenciaService.java:39-45`).
- **L22:** correto quanto ao retry e à DLQ, mas omite a deduplicação, que é nova.

##### Como rodar
- **L26, "Java 21+":** consistente com `pom.xml:30`. A L109 admite que o ambiente usa Java 25, o que é compatível.
- **L29, "Kafka, Redis, Postgres, Prometheus, Grafana":** o compose sobe os 5 (`docker-compose.yml:2-38`). Porém:
  - o Prometheus sobe sem `prometheus.yml` montado, então só coleta a si mesmo;
  - o Grafana sobe sem datasource;
  - `prom/prometheus` e `grafana/grafana` estão sem tag (usam `latest`);
  - não há healthcheck.
- **L51/L57, `/opt/kafka/bin/...`:** está correto para `apache/kafka:3.8.0` (`docker-compose.yml:3`), que instala o Kafka em `/opt/kafka`. O `localhost:9092` dentro do container também funciona (`KAFKA_LISTENERS`, linha 9).
- **Ordem dos passos:** os tópicos agora são criados pelo KafkaAdmin quando o motor sobe. Se alguém produzir antes de subir o motor, o auto-create do broker cria o tópico com 24 partições (L14). Funciona, mas por dois caminhos diferentes.

##### Tabela "Requisitos do case" (L77-88)
- **L81, "Alertas internos e externos: Feito":** impreciso. O alerta "interno" é só a publicação no tópico, sem consumidor interno.
- **L82, "Funcionar com serviço auxiliar fora: Feito… regras mantidas em memória se o Postgres cair":** parcialmente falso.
  - Vale só se o Postgres cair **depois** da primeira carga (`RegraCache.java:36-38`).
  - Na subida, o cache é `List.of()` (`RegraCache.java:17`), e os eventos são marcados `CONCLUIDO` sem nenhuma regra.
  - Com o JPA e `ddl-auto=update` (`application.properties:6`), é provável que o motor nem suba sem o Postgres. Não executei para confirmar.
  - Um único `tipo` inválido congela a recarga de todas as regras, e o log diz, enganosamente, "Postgres indisponível" (`RegraCache.java:37,48`).
  - **O Redis é um serviço auxiliar sem fallback:** fora do ar, todas as transações vão para a DLT em uns 3 s, e a notificação também falha.
  - "Feito" é exagero.
- **L83, "Idempotência: SETNX… liberado em caso de falha":** desatualizado.
  - Agora há os estados `PROCESSANDO` (5 min) e `CONCLUIDO` (24 h) (`IdempotenciaService.java:21-33`).
  - Num crash, a marca **não** é liberada: o evento vai para a DLT.
  - Falta citar a deduplicação na notificação.
- **L84, "Regras sem redeploy: Parcial":** correto.
- **L85/L86, "Proposto":** honesto. Mas o L86, "Regras em memória", omite que cada transação faz 6 chamadas síncronas ao Redis e que um alerta bloqueia em `get(5s)`.
- **L88, "Observabilidade: Parcial":** correto. O endpoint `prometheus` está exposto (`application.properties:14`) e o registry está no pom (`pom.xml:59-61`).

##### Decisões técnicas
- **L92, "particionamento por `accountId` garante ordem":**
  - O motor não garante isso, porque depende do produtor. Os retries via DLT também quebram a ordem.
  - "vários consumidores (antifraude, notificação)": só a notificação existe.
- **L94, "adicionar um tipo não mexe no restante do motor": falso.** É preciso editar o `switch` em `regras/RegraCache.java:42-49` e redeployar. Isso contradiz a própria L84.
  - "o motor sobrevive ao Postgres fora do ar" só vale depois da primeira carga.
- **L96, "O `eventId` é gravado com `SETNX` e TTL de 24 h. Se o processamento falhar, a marca é apagada": falso.**
  - O `SETNX` grava `PROCESSANDO` com TTL de **5 min** (`IdempotenciaService.java:22-23`).
  - As 24 h são de um `SET` feito no `concluir` (linha 32).
  - Em crash, a marca não é apagada.
  - "permite que quem consome o alerta descarte repetidos": hoje só a notificação faz isso.
- **L98, "nenhum alerta some":** exagero. Com RF=1 (`KafkaConfig.java:21-31`) e um único broker, o `acks=all` equivale a `acks=1`, e a perda do disco do broker perde alertas. O texto também omite que a confirmação síncrona bloqueia a partição (N3/N4).
- **L100:** "3 vezes" está correto (`FixedBackOff(1000L, 3)`). Faltou citar "campo obrigatório vazio" e que "em processamento" também acaba na DLT.
- **L102, "com grupo de consumidores próprio":** correto (`NotificacaoListener.java:29`). Omite que ela agora compartilha o `IdempotenciaService` e o Redis com o motor.

##### Estratégia de testes
- **L107, "84% das instruções e 80% dos desvios":** não bate com `target/site/jacoco/jacoco.xml`. Lá estão 653 de 754 instruções (**86,6%**) e 31 de 40 desvios (**77,5%**). O relatório pode ser de outra execução, mas os números precisam ser regerados.
- **L109, "Análise estática: SpotBugs… O PMD… foi removido": invertido.**
  - O `pom.xml` **não tem** o plugin SpotBugs. Ele foi removido no commit 93b474c, e `spotbugs-exclude.xml` ficou órfão.
  - O `pom.xml` **tem** o `maven-pmd-plugin` (`pom.xml:126-134`).
- **L106:** "cache de regras (… tipo inválido)" está correto (`RegraCacheTest.java:65-75`), mas o teste valida justamente o congelamento descrito acima. Não cita os testes novos de deduplicação da notificação nem de `EM_PROCESSAMENTO`.

##### Segurança e LGPD (L113-118)
- **"logs sem dados pessoais": falso no código atual.**
  - `NotificacaoGateway.java:23-24` loga `accountId`.
  - `TransacaoListener.java:50-51` loga `transactionId`.
  - As DLTs guardam o payload inteiro, e os headers de exceção guardam mensagens com `alertaId` e `eventId` (`AlertaPublisher.java:29`, `TransacaoListener.java:71`).
  - Melhor reescrever como "meta", citando o que hoje viola isso.
- "retenção definida para tópicos": nenhum `NewTopic` define `retention.ms`.
- L117, sobre a senha no `application.properties`, está correto (`application.properties:4-5`).
- **Falta citar** o `/simulacao` exposto sem `@Profile` (`api/SimulacaoController.java:16-20`). A L102 menciona o endpoint, mas não o risco.

##### Observabilidade (L120-124)
- É uma proposta e está coerente. Mas a métrica "latência evento → alerta (p99)" ficou mais difícil depois que o `geradoEm` passou a guardar a hora da transação (`Alerta.java:19`).
- O compose não tem scrape configurado, e o motor roda no host, então o Prometheus no container precisaria de `host.docker.internal`.

##### Limitações conhecidas (L126-133)
- **L131, "se o Postgres estiver fora na subida, nada é avaliado até ele voltar": impreciso.** Os eventos não ficam esperando: são marcados `CONCLUIDO` e **perdidos** de forma permanente (`TransacaoListener.java:48-55` com a lista vazia).
- **L132, "A notificação não tem idempotência própria": desatualizado.** O commit b83719b adicionou a deduplicação (`NotificacaoListener.java:32-37`). Ela não é "própria" no sentido arquitetural, porque usa o serviço do motor; reescreva assim. O "nem circuit breaker" continua verdadeiro: o pom não tem resilience4j, e o diagrama promete um (`drawio:34`).
- L128-130 e L133 estão corretas.
- **Limitações importantes que faltam:**
  - a `transactions-dlt` não tem consumidor nem replay, e todo crash no meio do processamento manda o evento para lá;
  - o envio síncrono do alerta bloqueia a partição (`AlertaPublisher.java:24`, N3/N4);
  - o tópico `alerts` pode ter duplicatas, e só a notificação deduplica;
  - o Redis é ponto único de falha do motor e da notificação;
  - Kafka com um broker e RF=1 fixo no código;
  - a `FrequenciaRegra` dá falso positivo em replay ou evento fora de ordem (`zCard` em vez de `zCount` na janela, `FrequenciaRegra.java:31-34`);
  - `ddl-auto=update`;
  - regra cadastrada com `limite` ou `janelaSegundos` nulos causa NPE em toda transação ou congela o cache;
  - "Antifraude interno" não implementado.

##### Evoluções (L135-143)
- Coerentes. A 4 ("carregar as regras antes de consumir") e a 5 ("reprocessamento da DLQ") atacam pontos reais.
- **Faltam:**
  - transação Kafka ou EOS (ou publicação assíncrona em lote) no lugar do `get(5s)`;
  - topologia de tópicos e RF externalizados por ambiente;
  - store de deduplicação próprio da notificação;
  - pipeline ou Lua também para a idempotência, não só para a frequência;
  - contrato de `alerts` com `alertaId` como chave de idempotência.

---

#### As 3 mudanças de maior impacto
1. **Fechar o ciclo da DLT de transações.** Alinhar o retry de `EventoEmProcessamentoException` ao TTL (retry não bloqueante ou backoff maior que 5 min) e criar o reprocessador, a métrica e o alarme da `transactions-dlt`. Sem isso, P1 e P2 só trocam "perda silenciosa" por "perda manual".
2. **Tirar a deduplicação de alertas do motor e resolver na origem.**
   - Transação Kafka (consumir, processar e produzir) ou registro `alerta:<id>` antes de publicar.
   - Na notificação, um deduplicador próprio e atômico (`SET NX` antes de enviar, com estado), sem importar `idempotencia`.
   - Documentar `alertaId` como contrato para todo consumidor de `alerts`.
   - Validar `transactionId`.
3. **Corrigir o README e a topologia declarada.**
   - Remover as afirmações falsas: L94, L96, L109, "logs sem dados pessoais" e L132.
   - Marcar o Antifraude interno como externo.
   - Incluir `transactions-dlt` e o Redis da notificação no ASCII e no drawio.
   - Levar partições e RF para configuração por ambiente.

#### Arquivos relevantes
- /workspaces/motor-antifraude/README.md
- /workspaces/motor-antifraude/docker-compose.yml
- /workspaces/motor-antifraude/docs/arquitetura-motor-antifraude.drawio
- /workspaces/motor-antifraude/motor-deteccao/pom.xml
- /workspaces/motor-antifraude/motor-deteccao/src/main/resources/application.properties
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/TransacaoListener.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/KafkaConfig.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/AlertaPublisher.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/EventoInvalidoException.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/kafka/EventoEmProcessamentoException.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/idempotencia/IdempotenciaService.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoListener.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/notificacao/NotificacaoGateway.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/domain/Alerta.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/RegraCache.java
- /workspaces/motor-antifraude/motor-deteccao/src/main/java/com/mica/motor/regras/FrequenciaRegra.java
- /workspaces/motor-antifraude/motor-deteccao/target/site/jacoco/jacoco.xml

---

## Relatório do agente caca-bugs

_Pendente: será incluído quando o agente terminar._
