package com.mica.motor.kafka;

import com.mica.motor.domain.Transacao;
import com.mica.motor.regras.Regra;
import com.mica.motor.regras.ValorAltoRegra;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.mica.motor.idempotencia.IdempotenciaService;
import com.mica.motor.domain.Alerta;

@Slf4j
@Component
public class TransacaoListener {

    private final ObjectMapper objectMapper;
    private final IdempotenciaService idempotencia;
    private final AlertaPublisher publisher;

    private final List<Regra> regras = List.of(
            new ValorAltoRegra(new BigDecimal("10000")));

    public TransacaoListener(ObjectMapper objectMapper, IdempotenciaService idempotencia,
            AlertaPublisher publisher) {
        this.objectMapper = objectMapper;
        this.idempotencia = idempotencia;
        this.publisher = publisher;
    }

    @KafkaListener(topics = "transactions")
    public void consumir(String mensagem) {
        Transacao transacao = objectMapper.readValue(mensagem, Transacao.class);

        if (!idempotencia.registrar(transacao.eventId())) {
            log.info("Evento {} duplicado, ignorado", transacao.eventId());
            return;
        }

        for (Regra regra : regras) {
            if (regra.ehSuspeita(transacao)) {
                log.warn("SUSPEITA: transação {} da conta {} quebrou a regra {}",
                        transacao.transactionId(), transacao.accountId(), regra.id());
                          publisher.publicar(Alerta.de(transacao, regra.id()));
            }
        }

    }
}