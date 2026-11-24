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
        validar(transacao);
        String eventId = transacao.eventId();

        switch (idempotencia.iniciar(eventId)) {
            case CONCLUIDO -> {
                log.info("Evento {} já processado, ignorado", eventId);
                return;
            }
            case EM_PROCESSAMENTO -> throw new EventoEmProcessamentoException(eventId);
            case NOVO -> { }
        }

        try {
            for (Regra regra : regraCache.atuais()) {
                if (regra.ehSuspeita(transacao)) {
                    log.warn("SUSPEITA: transação {} quebrou a regra {}",
                            transacao.transactionId(), regra.id());
                    publisher.publicar(Alerta.de(transacao, regra.id()));
                }
            }
            idempotencia.concluir(eventId);
        } catch (RuntimeException | Error e) {
            try {
                idempotencia.liberar(eventId);
            } catch (RuntimeException falhaAoLiberar) {
                e.addSuppressed(falhaAoLiberar);
            }
            throw e;
        }
    }

    private void validar(Transacao t) {
        if (t == null || t.eventId() == null || t.eventId().isBlank()) {
            throw new EventoInvalidoException("Evento sem eventId");
        }
        if (t.valor() == null || t.dataHora() == null || t.accountId() == null) {
            throw new EventoInvalidoException("Evento " + t.eventId() + " com campo obrigatório vazio");
        }
    }
}