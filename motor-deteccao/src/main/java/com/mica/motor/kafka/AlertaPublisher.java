package com.mica.motor.kafka;

import com.mica.motor.domain.Alerta;
import java.util.concurrent.TimeUnit;
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
        try {
            // espera o broker confirmar; se falhar, a exceção faz o evento ser reprocessado
            kafka.send("alerts", alerta.accountId(), json).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio do alerta interrompido", e);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao publicar alerta " + alerta.alertaId(), e);
        }
    }
}