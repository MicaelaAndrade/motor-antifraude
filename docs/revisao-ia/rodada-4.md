# Revisão por IA — Rodada 4

- **Data:** 2026-11-24
- **Commit revisado:** `a675e6d`
- **Agentes:** `caca-bugs`, `arquiteto` e `codigo-morto` em paralelo
- **Escopo:** só conferir se README.md e AI_USAGE.md batem com o código, o `pom.xml`, o `docker-compose.yml`, o `prometheus.yml` e os relatórios em `docs/revisao-ia/`. Sem sugestões novas de arquitetura ou de melhoria.
- **Observação:** nenhum agente alterou arquivos. Os achados abaixo foram consolidados e os principais conferidos no código pela sessão principal.
- **Rodada anterior:** [rodada-3.md](rodada-3.md)

## Resultado geral

Nenhuma afirmação falsa sobre versões, portas, serviços, endpoints, tópicos, comandos, TTLs, retries ou cobertura. Os agentes verificaram cerca de 55 a 70 afirmações como verdadeiras. As divergências estão concentradas no resumo das rodadas do AI_USAGE.md e em detalhes do README.

## Testes e cobertura (comando do README executado)

```bash
cd motor-deteccao
./mvnw test -Dtest='*RegraTest,IdempotenciaServiceTest,TransacaoListenerTest,RegraCacheTest,Notificacao*Test'
```

- **Resultado:** BUILD SUCCESS, 26 testes, 0 falhas, 0 erros, 0 ignorados.
  - IdempotenciaServiceTest 6, TransacaoListenerTest 7, RegraCacheTest 4, ValorAltoRegraTest 3, FrequenciaRegraTest 2, NotificacaoGatewayTest 2, NotificacaoListenerTest 2.
  - O filtro cobre as 7 classes unitárias; só `MotorDeteccaoApplicationTests` (`contextLoads`) fica de fora, como o README diz.
- **JaCoCo:** instruções 589/771 = **76,4%**; desvios 34/46 = **73,9%**.
- **Sem cobertura (0%):** `RegraController`, `SimulacaoController`, `AlertaPublisher`, `KafkaConfig`, `MotorDeteccaoApplication`, exatamente a lista do README.md:114.

## Git

- `git status` **não está limpo**: `.vscode/settings.json` modificado (adiciona `java.compile.nullAnalysis.mode`). Este `rodada-4.md` também fica não rastreado até ser commitado.
- Rastreados: `README.md`, `AI_USAGE.md`, `prometheus.yml`, `docs/revisao-ia/rodada-1.md`, `rodada-2.md`, `rodada-3.md` e `.claude/agents/*.md`.

## O que corrigir na documentação (do mais grave ao menos grave)

### 1. AI_USAGE.md:20 — rodada 2 dita "Corrigidos", mas a rodada 3 marcou itens como parciais
*Apontado por: caca-bugs, arquiteto, codigo-morto.*

`rodada-3.md:17,19` marca o alerta duplicado (N1) e o crash no meio (P1) como **Parcial**; o próprio README.md:138 e :140 admite isso. A DLQ foi resolvida com a declaração dos tópicos (`KafkaConfig.java:19-32`), que o texto não cita.

**Texto sugerido:**
> - **Rodada 2:** alerta duplicado no retry; DLQ não declarada; crash no meio do processamento ainda perdia o evento; uma falha ao liberar a marca escondia o erro original; campos nulos eram retentados à toa. Tratei com a idempotência em dois estados, a declaração dos tópicos de DLQ, a deduplicação na notificação e a validação de entrada. A rodada 3 confirmou como resolvidos a falha ao liberar a marca e os campos nulos; alerta duplicado e crash ficaram resolvidos em parte: o crash não perde mais o evento em silêncio, mas o manda para a `transactions-dlt`, e o tópico `alerts` ainda pode receber o mesmo alerta, que a notificação descarta (ver Limitações no README).

### 2. AI_USAGE.md:19 — rodada 1 "Todos corrigidos" contradiz a linha 21
*Apontado por: caca-bugs, arquiteto.*

A rodada 2 classificou a correção da idempotência como parcial (`rodada-2.md:18,22`), e o `accountId` continuou no log do `NotificacaoGateway` até a rodada 3 (`rodada-3.md:77`; AI_USAGE.md:21).

**Texto sugerido:**
> - **Rodada 1:** a marca de idempotência era gravada antes do processamento, então uma falha no meio fazia o evento ser ignorado na nova tentativa; o tópico de transações não tinha DLQ; o envio do alerta não era confirmado; e o `accountId` aparecia nos logs. Corrigi a DLQ, o envio confirmado e o log do motor. A idempotência ficou parcial (um crash no meio ainda perdia o evento, tratado na rodada 2) e o `accountId` só saiu do log da notificação na rodada 3.

### 3. README.md:113 — "rejeita campos obrigatórios vazios" não tem teste correspondente
*Apontado por: caca-bugs, arquiteto, codigo-morto. Conferido.*

O único teste de validação, `eventoSemTransactionIdEhInvalido` (`TransacaoListenerTest.java:117-125`), monta a transação com `eventId` nulo e `transactionId = "tx-2"`, ou seja, testa a falta de `eventId` (primeiro `if`, `TransacaoListener.java:67`). O segundo `if` (`valor`, `dataHora`, `accountId`, `transactionId`, linha 70) não tem teste; o JaCoCo mostra 8 desvios descobertos em `validar`. Obs.: o nome do teste também está errado; renomear é mudança de código, fora do escopo desta rodada.

**Texto sugerido:**
> listener (publica só quando suspeita, ignora evento concluído, falha em vez de perder evento interrompido, não esconde o erro original, rejeita evento sem `eventId`; os demais campos obrigatórios são validados no código, mas ainda sem teste)

### 4. README.md:142 — consequência da regra com campo nulo está imprecisa
*Apontado por: caca-bugs.*

VALOR_ALTO com `limite` nulo é aceita (`RegraCache.java:43`) e gera NPE em toda transação (`ValorAltoRegra.java:21`), que vai para a `transactions-dlt`. Tipo nulo/desconhecido ou FREQUENCIA com campo nulo lança exceção em `criar` (`RegraCache.java:42-48`), o `catch` (`RegraCache.java:36-38`) descarta a recarga inteira e o log diz "Postgres indisponível".

**Texto sugerido:**
> - A API de regras não tem autenticação nem validação de entrada. Uma regra VALOR_ALTO sem `limite` faz toda transação falhar e ir para a `transactions-dlt`; uma regra com tipo inválido, ou FREQUENCIA com campo nulo, impede a recarga de todas as regras: o cache fica na última versão válida e o log atribui o erro ao Postgres.

### 5. README.md:9 — o diagrama é a arquitetura-alvo, não a implementada
*Apontado por: arquiteto, codigo-morto. Conferido.*

O drawio mostra "retry e circuit breaker" (o README.md:144 diz que não há), "Base de clientes", o bloco de Segurança com TLS/mTLS e mascaramento (proposta no README.md:123-126), uma única DLQ só da notificação (sem `transactions-dlt`) e a API de Regras como componente separado.

**Texto sugerido:**
> Diagrama da arquitetura-alvo: `docs/arquitetura-motor-antifraude.drawio` (abre no draw.io). O esquema abaixo mostra o que está implementado: notificação e API de regras rodam dentro do motor, há também a `transactions-dlt`, e circuit breaker, base de clientes e o bloco de segurança do diagrama ainda são proposta.

### 6. README.md:24 — o Grafana não coleta métricas
*Apontado por: caca-bugs, arquiteto. Conferido.*

Quem coleta é o Prometheus (`prometheus.yml:5-8`, `application.properties:14`). O Grafana sobe sem datasource nem painel (`docker-compose.yml:39-42`).

**Texto sugerido:**
> - **Prometheus:** coleta as métricas do motor em `/actuator/prometheus`. **Grafana:** sobe no compose sem datasource nem painel; precisa ser apontado ao Prometheus manualmente.

### 7. README.md:137 — a `alerts-dlt` tem consumidor
*Apontado por: caca-bugs, arquiteto. Conferido.*

O `@DltHandler` (`NotificacaoListener.java:40-44`) consome a `alerts-dlt` e só registra um log, sem reprocessar.

**Texto sugerido:**
> - Ninguém reprocessa `transactions-dlt` e `alerts-dlt` (a `alerts-dlt` só gera um log de erro): o que cai lá precisa de replay manual.

### 8. AI_USAGE.md:15 e :30 — conferência dos achados não valeu para a rodada 1
*Apontado por: arquiteto. Conferido.*

`rodada-1.md:6` diz: "Os achados não foram conferidos no código nesta rodada."

**Texto sugerido (linha 30):**
> - **Tratar a revisão da IA como entrada, não como verdade:** a partir da rodada 2, conferi os achados no código e rodando os testes antes de aceitar.

### 9. AI_USAGE.md:14 — rodada 1 não foi toda em paralelo; número de rodadas
*Apontado por: arquiteto (paralelo); os três (contagem). Conferido em `rodada-1.md:5`.*

Na rodada 1, o `caca-bugs` rodou sozinho e depois os outros dois em paralelo. Com este relatório, passam a ser quatro rodadas.

**Texto sugerido:**
> 3. **Revisão por agentes.** Depois da primeira versão funcionando, rodei quatro rodadas de revisão (na primeira, o `caca-bugs` sozinho e depois os outros dois em paralelo; nas seguintes, os três em paralelo). Eles só liam o código, sem alterar arquivos. Os relatórios completos estão em `docs/revisao-ia/rodada-1.md` a `rodada-4.md`.

E acrescentar em "O que os agentes encontraram":
> - **Rodada 4:** conferiu só a documentação contra o código. Apontou imprecisões no resumo das rodadas, no diagrama, no Grafana e na descrição dos testes, que corrigi.

### 10. README.md:116 — "três rodadas"

**Texto sugerido:**
> - **Revisão com agentes de IA:** quatro rodadas (a quarta só conferiu a documentação), com os relatórios em `docs/revisao-ia/`. Ver `AI_USAGE.md`.

### 11. README.md:93 e :101 — "banco" deveria ser "Postgres"
*Apontado por: caca-bugs.*

O Postgres não é consultado por transação, mas toda transação faz 2 comandos no Redis (idempotência) e mais 4 por regra FREQUENCIA ativa (`IdempotenciaService.java:22-32`, `FrequenciaRegra.java:30-34`).

**Texto sugerido:**
> Linha 93: `Regras em memória, sem consulta ao Postgres por transação; falta métrica de ponta a ponta`
> Linha 101: "Nenhuma transação consulta o Postgres, o que protege a latência, ..."

### 12. README.md:13 — nome do tópico de retry (não confirmado em execução)
*Apontado por: os três, com base no comportamento padrão do spring-kafka.*

`@RetryableTopic(attempts = "4", ...)` sem backoff explícito (`NotificacaoListener.java:28`) usa intervalo fixo, que por padrão reaproveita um único tópico `alerts-retry`. Confirmar com `kafka-topics.sh --list` antes de mudar.

**Texto sugerido:** `[alerts-retry]` (ou manter `[alerts-retry*]` se a listagem mostrar vários).

### 13. AI_USAGE.md:8 — papel do `codigo-morto`
*Apontado por: arquiteto, codigo-morto.*

`.claude/agents/codigo-morto.md:3` não inclui a conferência do README; ela veio do pedido da rodada 3, feito aos três agentes.

**Texto sugerido:**
> `codigo-morto` (código sem uso, imports sobrando e duplicação). Na rodada 3 e na 4, os três agentes também conferiram a documentação.

### 14. README.md:114 — arredondamento dos desvios

Medido 73,9%. "cerca de 73%" não é falso, mas o arredondamento correto é 74%.

**Texto sugerido:** "cerca de 76% das instruções e 74% dos desvios nos unitários."

### 15. README.md:122 — logs do framework (baixa confiança, não executado)
*Apontado por: caca-bugs.*

Os `log.*` da aplicação de fato não trazem `accountId` nem valores. Porém o error handler do spring-kafka loga a exceção, e uma `JacksonException` de tipo inválido pode trazer o valor rejeitado.

**Texto sugerido (opcional):**
> - **Feito:** os logs escritos pelo motor e pela notificação não trazem `accountId` nem valores (mensagens de erro do framework para JSON malformado podem trazer o valor rejeitado); a DLT da notificação não registra o payload.

## Conferido e correto

- Versões: Java 21 e Spring Boot 4.2 (`pom.xml:8,30`); JaCoCo com report na fase `test` (`pom.xml:107-125`); SpotBugs e PMD ausentes do pom.
- Tópicos `transactions`, `transactions-dlt` e `alerts` com 24 partições; chave `accountId` em `alerts`; `notif:<alertaId>`; tabela `regra_config`.
- Idempotência em dois estados (5 min / 24 h), `addSuppressed`, erros não retentáveis, 3 novas tentativas com 1 s, 4 tentativas na notificação, `acks=all`, produtor idempotente, 6 threads, recarga das regras a cada 10 s.
- Comandos `curl`, `docker compose exec kafka /opt/kafka/bin/...`, `/simulacao/notificacao/{bool}`, `/actuator/prometheus` e scrape target `host.docker.internal:8080`.
- Correções afirmadas no AI_USAGE.md:21 e :28 (validação de `transactionId`, `accountId` fora do log da notificação, decisões sobre DLQ e dono da marca) estão no código.
- Links internos em README.md, AI_USAGE.md e `docs/revisao-ia/` resolvem.
