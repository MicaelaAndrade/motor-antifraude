# Motor de Detecção de Transações Suspeitas

Case técnico – Engenharia de Software Backend (Itaú).

O motor recebe eventos de transações em tempo real pelo Kafka, aplica regras de detecção configuráveis e publica alertas para o antifraude interno e para a notificação do cliente. O foco foi uma fatia ponta a ponta funcionando, com as decisões e simplificações documentadas.

## Arquitetura

Diagrama: `docs/arquitetura-motor-antifraude.drawio` (abre no draw.io).

```
Serviço interno → [transactions] → Motor de Detecção → [alerts] → Antifraude interno
                                   │   │                      └→ Notificação (push/e-mail) → [alerts-dlt]
                                 Redis Postgres
```

- **Kafka, tópico `transactions`** (24 partições, chave `accountId`): as transações de uma conta ficam sempre na mesma partição, em ordem.
- **Motor (Java 21 + Spring Boot):** valida, checa idempotência, aplica as regras ativas e publica o alerta.
- **Redis:** idempotência por `eventId` e janelas deslizantes da regra de frequência.
- **Postgres:** cadastro das regras (tabela `regra_config`).
- **Kafka, tópico `alerts`:** desacopla a detecção de quem consome o alerta.
- **Notificação:** consome `alerts` com retry em tópicos e DLQ (`alerts-dlt`).

## Como rodar

Pré-requisitos: Docker e Java 21+.

```bash
# 1. infraestrutura (Kafka, Redis, Postgres, Prometheus, Grafana)
docker compose up -d

# 2. motor
cd motor-deteccao
./mvnw spring-boot:run
```

Criar as regras (em outro terminal):

```bash
curl -X PUT localhost:8080/regras/VALOR_ALTO -H "Content-Type: application/json" \
  -d '{"tipo":"VALOR_ALTO","limite":10000,"ativa":true}'

curl -X PUT localhost:8080/regras/FREQUENCIA -H "Content-Type: application/json" \
  -d '{"tipo":"FREQUENCIA","limite":5,"janelaSegundos":120,"ativa":true}'
```

Enviar uma transação (na raiz do projeto):

```bash
echo '{"eventId":"ev-1","transactionId":"tx-1","accountId":"conta-123","valor":15000.00,"pais":"BR","estabelecimento":"Loja X","dataHora":"2026-11-25T12:00:00Z"}' \
  | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic transactions
```

Ver os alertas:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic alerts --from-beginning
```

Testes unitários e cobertura (não precisam de Docker):

```bash
cd motor-deteccao
./mvnw test -Dtest='*RegraTest,IdempotenciaServiceTest,TransacaoListenerTest,RegraCacheTest,Notificacao*Test'
# relatório: target/site/jacoco/index.html
```

## Demonstrações

1. **Regra alterada sem redeploy:** com o motor rodando, `PUT /regras/VALOR_ALTO` com `limite` 5000. Em até 10 segundos uma transação de R$ 7.000 passa a gerar alerta.
2. **Notificação fora do ar:** `curl -X PUT localhost:8080/simulacao/notificacao/false`. O motor continua detectando e publicando em `alerts`; a notificação tenta 4 vezes e manda para `alerts-dlt`. Para voltar: `.../notificacao/true`.
3. **Mensagem inválida:** um JSON quebrado em `transactions` vai para `transactions-dlt` em vez de ser descartado.
4. **Idempotência:** a mesma mensagem enviada duas vezes gera um alerta só.

## Requisitos do case

| Requisito | Situação | Como |
| --- | --- | --- |
| Receber eventos em tempo real | Feito | Consumidor Kafka no tópico `transactions` |
| Lógica de detecção | Feito | Regras de valor alto e de frequência (janela deslizante) |
| Alertas internos e externos | Feito | Tópico `alerts` + notificação simulada (push/e-mail) |
| Funcionar com serviço auxiliar fora | Feito | Detecção desacoplada da notificação; retry + DLQ; regras mantidas em memória se o Postgres cair |
| Idempotência | Feito | `SETNX` no Redis por `eventId`, liberado em caso de falha; `alertaId = transactionId:regraId` |
| Regras sem redeploy | Parcial | Parâmetros e ativação mudam em tempo real; um tipo novo de regra ainda exige deploy |
| 8.000 TPS / pico 25.000 | Proposto | Arquitetura particionada para escalar horizontalmente; não medido (ver Limitações) |
| Alerta em até 500 ms | Proposto | Regras em memória, sem consulta ao banco por transação; falta métrica de ponta a ponta |
| Segurança e LGPD | Proposto | Ver seção Segurança |
| Observabilidade | Parcial | Actuator + endpoint Prometheus; falta coletar e montar o painel |

## Decisões técnicas e trade-offs

**Kafka em vez de RabbitMQ.** Minha experiência anterior é com RabbitMQ. Escolhi Kafka porque o case pede vazão alta e ordem por conta: o particionamento por `accountId` garante ordem para as regras com estado, o log retido permite reprocessar, e vários consumidores (antifraude, notificação) leem o mesmo alerta. Custo: operar Kafka é mais complexo.

**Regras como dados.** Cada regra é uma linha no Postgres; o motor relê as regras ativas a cada 10 segundos e guarda em memória. Assim nenhuma transação consulta o banco (protege a latência) e o motor sobrevive ao Postgres fora do ar. Trade-off: uma mudança leva até 10 segundos para valer. Cada tipo de regra implementa a interface `Regra`, então adicionar um tipo não mexe no restante do motor.

**Idempotência com Redis.** O `eventId` é gravado com `SETNX` e TTL de 24 h. Se o processamento falhar, a marca é apagada para a nova tentativa não ser vista como duplicada. O `alertaId` (`transactionId:regraId`) permite que quem consome o alerta descarte repetidos.

**Alerta confirmado.** O motor espera o broker confirmar o envio (`acks=all`, produtor idempotente). Custa latência por alerta, mas só transações suspeitas geram alerta, e nenhum alerta some.

**Erros no consumo.** Falha passageira é tentada 3 vezes; erro de dado (JSON inválido, evento sem id) vai direto para `transactions-dlt`. Na notificação, o retry usa tópicos de retry para não travar a fila.

**Simplificações conscientes.** A notificação roda no mesmo projeto, com grupo de consumidores próprio; em produção seria um serviço separado. Push e e-mail são simulados. O endpoint `/simulacao` existe só para a demo.

## Estratégia de testes

- **Unitários (JUnit 5 + Mockito):** regras (incluindo o limite exato), idempotência, listener (publica só quando suspeita, ignora duplicado, libera a marca quando a publicação falha), cache de regras (Postgres fora do ar, tipo inválido) e notificação.
- **Cobertura (JaCoCo):** 84% das instruções e 80% dos desvios. Ficaram de fora os controllers e a classe main, que seriam cobertos por testes de integração.
- **Testes manuais de ponta a ponta:** os roteiros da seção Demonstrações.
- **Análise estática:** SpotBugs; os avisos EI_EXPOSE_REP eram falso positivo da injeção de dependência e foram filtrados. O PMD não lê as classes do Java 25 do ambiente e foi removido.
- **Revisão com agentes de IA:** ver `AI_USAGE.md`.
- **Próximos:** integração com Testcontainers (Kafka, Redis, Postgres reais) e teste de carga com k6 ou Gatling.

## Segurança e LGPD (proposta)

- **Em trânsito:** TLS no Kafka, Redis e Postgres; mTLS entre serviços.
- **Autenticação:** a API de regras atrás de OAuth2/JWT, com perfil de acesso para o time antifraude e auditoria de cada mudança.
- **Em repouso:** discos e backups criptografados; segredos fora do repositório (hoje a senha local está no `application.properties`, aceitável só para o ambiente de desenvolvimento).
- **LGPD:** o evento carrega só o necessário (ids, valor, país, estabelecimento); logs sem dados pessoais; CPF e cartão, se entrarem, mascarados ou tokenizados; retenção definida para tópicos e alertas.

## Observabilidade e operação (proposta para SRE)

- **Métricas:** vazão por tópico, lag do consumidor, latência evento → alerta (p99), taxa de alertas por regra, tamanho das DLQs.
- **Alertas de incidente:** queda de vazão ou lag crescendo; DLQ recebendo mensagens; aumento súbito de alertas de uma regra (possível falso positivo, que pode ser desativada pela API sem deploy); dependência fora do ar.
- **Logs e tracing:** logs estruturados com `transactionId` e tracing distribuído com OpenTelemetry.

## Limitações conhecidas

- Vazão e latência não foram medidas; o listener ainda roda com uma thread por instância.
- A regra de frequência faz 4 comandos separados no Redis (não atômicos).
- A API de regras não tem autenticação nem validação de entrada.
- O cache de regras começa vazio: se o Postgres estiver fora na subida, nada é avaliado até ele voltar.
- A notificação não tem idempotência própria nem circuit breaker.
- O Prometheus sobe, mas ainda não coleta as métricas do motor.

## Evoluções

1. Concorrência dos listeners e teste de carga para provar 8k/25k TPS.
2. Métrica de latência ponta a ponta e painel no Grafana.
3. Regra de frequência atômica com script Lua.
4. Carregar as regras antes de começar a consumir; validar a API.
5. Circuit breaker (Resilience4j) e reprocessamento da DLQ.
6. Notificação como serviço separado; novas regras (localização impossível, regra composta).
7. Deploy em Kubernetes com autoscaling pelo lag do consumidor.

## Uso de IA

Detalhado em `AI_USAGE.md`.