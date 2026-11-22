package com.mica.motor.kafka;

import com.mica.motor.domain.Alerta;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlertaPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public AlertaPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publicar(Alerta alerta) {
        String json = objectMapper.writeValueAsString(alerta);
        kafka.send("alerts", alerta.accountId(), json);
    }
}