# Uso de IA neste case

O case pede que o uso de IA seja explícito. Este arquivo descreve onde usei, como usei e o que ficou sob minha responsabilidade.

## Ferramentas

- **Claude (principalmente por voz):** discussão de arquitetura, decisões e revisão dos achados foram conversadas por voz; o código, o diagrama e a documentação foram gerados em documentos compartilhados, que eu revisava antes de usar no projeto.
- **Claude Code com agentes:** três agentes de revisão definidos em `.claude/agents/`: `caca-bugs` (bugs e falhas de confiabilidade), `arquiteto` (coerência com a arquitetura e os requisitos) e `codigo-morto` (código sem uso, imports sobrando e duplicação). Nas rodadas 3 e 4, os três também conferiram a documentação contra o código.

## Como foi o fluxo

1. **Arquitetura.** Li o case e discuti as opções com o Claude: mensageria (Kafka ou RabbitMQ), onde guardar as regras, como garantir idempotência e como manter a detecção com um serviço auxiliar fora. O diagrama em `docs/` saiu dessa conversa e foi ajustado por mim.
2. **Código.** O Claude gerou o código em partes pequenas. Eu revisei cada parte, compilei, rodei e testei manualmente antes de seguir para a próxima. Quando algo quebrava (por exemplo, a mudança para Jackson 3 no Spring Boot 4), eu trazia o erro e corrigíamos juntos.
3. **Revisão por agentes.** Depois da primeira versão funcionando, rodei quatro rodadas de revisão. Na primeira, o `caca-bugs` rodou sozinho e depois os outros dois em paralelo; nas seguintes, os três em paralelo. Eles só liam o código, sem alterar arquivos. Os relatórios completos estão em `docs/revisao-ia/rodada-1.md` a `rodada-4.md`.
4. **Correção.** Para cada rodada, eu li os achados, escolhi o que corrigir antes da entrega e o que documentar como limitação, apliquei as correções, rodei os testes e conferi o comportamento na mão.

## O que os agentes encontraram

- **Rodada 1:** a marca de idempotência era gravada antes do processamento, então uma falha no meio fazia o evento ser ignorado na nova tentativa; o tópico de transações não tinha DLQ; o envio do alerta não era confirmado; e o `accountId` aparecia nos logs. Corrigi a DLQ, o envio confirmado e o log do motor. A idempotência ficou parcial (tratada na rodada 2) e o `accountId` só saiu do log da notificação na rodada 3.
- **Rodada 2:** alerta duplicado no retry; DLQ não declarada; crash no meio do processamento ainda perdia o evento; uma falha ao liberar a marca escondia o erro original; campos nulos eram retentados à toa. Tratei com a idempotência em dois estados, a declaração dos tópicos de DLQ, a deduplicação na notificação e a validação de entrada. A rodada 3 confirmou como resolvidos a falha ao liberar a marca e os campos nulos. Alerta duplicado e crash ficaram resolvidos em parte: o crash não perde mais o evento em silêncio, mas o manda para a `transactions-dlt`, e o `alerts` ainda pode receber o mesmo alerta, que a notificação descarta (ver Limitações no README).
- **Rodada 3:** conferiu as correções e o README. Apontou que o README tinha afirmações falsas (cobertura, ferramentas de análise estática, o modelo antigo de idempotência, logs) e limitações novas. Corrigi o README, validei o `transactionId` e tirei o `accountId` do log da notificação. Os pontos maiores (consumidor da DLQ, dependência do Redis) ficaram documentados em Limitações.
- **Rodada 4:** conferiu só a documentação contra o código. Não encontrou afirmações falsas sobre versões, comandos, tópicos ou configurações; apontou ajustes de precisão no README e neste arquivo, e um teste que não testava o que o nome dizia. Corrigi os dois.

## Decisões que foram minhas

- **Kafka em vez de RabbitMQ**, mesmo com mais experiência em RabbitMQ, pela vazão e pela ordem por conta.
- **Priorização:** uma fatia ponta a ponta funcionando, em vez de cobrir tudo pela metade.
- **O que corrigir e o que documentar** em cada rodada de revisão.
- **Não seguir toda a prioridade dos agentes:** na rodada 3, eles marcaram como prioridade alta o consumidor da DLQ e o controle de dono da marca de idempotência. Avaliei que eram mudanças grandes para o prazo, com risco de quebrar o que já funcionava, e preferi documentar como limitação e evolução.
- **Retirar SpotBugs e PMD**, depois de testar: falsos positivos e incompatibilidade com o JDK do ambiente.
- **Tratar a revisão da IA como entrada, não como verdade:** a partir da rodada 2, conferi os achados no código e rodando os testes antes de aceitar.

## Limites do uso de IA

- Os agentes descrevem o comportamento das bibliotecas sem executar o código; por isso conferi os achados mais graves rodando a aplicação.

## Responsabilidade

O código foi gerado com ajuda, mas eu sou responsável por ele e consigo explicar cada parte.