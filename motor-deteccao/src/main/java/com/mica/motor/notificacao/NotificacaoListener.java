package com.mica.motor.notificacao;

import com.mica.motor.domain.Alerta;
import com.mica.motor.idempotencia.IdempotenciaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class NotificacaoListener {

    private final ObjectMapper objectMapper;
    private final NotificacaoGateway gateway;
    private final IdempotenciaService idempotencia;

    public NotificacaoListener(ObjectMapper objectMapper, NotificacaoGateway gateway,
                               IdempotenciaService idempotencia) {
        this.objectMapper = objectMapper;
        this.gateway = gateway;
        this.idempotencia = idempotencia;
    }

    @RetryableTopic(attempts = "4", exclude = JacksonException.class)
    @KafkaListener(topics = "alerts", groupId = "notificacao")
    public void notificar(String mensagem) {
        Alerta alerta = objectMapper.readValue(mensagem, Alerta.class);
        if (idempotencia.jaNotificado(alerta.alertaId())) {
            log.info("Alerta {} já notificado, ignorado", alerta.alertaId());
            return;
        }
        gateway.enviar(alerta);
        idempotencia.marcarNotificado(alerta.alertaId());
    }

    @DltHandler
    public void falhouDeVez(String mensagem) {
        // sem o payload no log (LGPD): a mensagem completa fica no tópico alerts-dlt
        log.error("Alerta foi para a DLQ depois de 4 tentativas");
    }
}