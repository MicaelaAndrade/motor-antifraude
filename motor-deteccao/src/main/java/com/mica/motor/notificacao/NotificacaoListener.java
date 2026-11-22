package com.mica.motor.notificacao;

import com.mica.motor.domain.Alerta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class NotificacaoListener {

    private final ObjectMapper objectMapper;
    private final NotificacaoGateway gateway;

    public NotificacaoListener(ObjectMapper objectMapper, NotificacaoGateway gateway) {
        this.objectMapper = objectMapper;
        this.gateway = gateway;
    }

    @RetryableTopic(attempts = "4")
    @KafkaListener(topics = "alerts", groupId = "notificacao")
    public void notificar(String mensagem) {
        Alerta alerta = objectMapper.readValue(mensagem, Alerta.class);
        log.info("Tentando notificar alerta {}", alerta.alertaId());
        gateway.enviar(alerta);
    }

    @DltHandler
    public void falhouDeVez(String mensagem) {
        log.error("Alerta foi para a DLQ depois de 4 tentativas: {}", mensagem);
    }
}