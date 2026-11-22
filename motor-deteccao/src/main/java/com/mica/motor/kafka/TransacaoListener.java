package com.mica.motor.kafka;

import com.mica.motor.domain.Alerta;
import com.mica.motor.domain.Transacao;
import com.mica.motor.idempotencia.IdempotenciaService;
import com.mica.motor.regras.Regra;
import com.mica.motor.regras.RegraCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class TransacaoListener {

    private final ObjectMapper objectMapper;
    private final IdempotenciaService idempotencia;
    private final AlertaPublisher publisher;
    private final RegraCache regraCache;

    public TransacaoListener(ObjectMapper objectMapper,
                             IdempotenciaService idempotencia,
                             AlertaPublisher publisher,
                             RegraCache regraCache) {
        this.objectMapper = objectMapper;
        this.idempotencia = idempotencia;
        this.publisher = publisher;
        this.regraCache = regraCache;
    }

    @KafkaListener(topics = "transactions")
    public void consumir(String mensagem) {
        Transacao transacao = objectMapper.readValue(mensagem, Transacao.class);

        if (!idempotencia.registrar(transacao.eventId())) {
            log.info("Evento {} duplicado, ignorado", transacao.eventId());
            return;
        }

        for (Regra regra : regraCache.atuais()) {
            if (regra.ehSuspeita(transacao)) {
                log.warn("SUSPEITA: transação {} da conta {} quebrou a regra {}",
                        transacao.transactionId(), transacao.accountId(), regra.id());
                publisher.publicar(Alerta.de(transacao, regra.id()));
            }
        }
    }
}