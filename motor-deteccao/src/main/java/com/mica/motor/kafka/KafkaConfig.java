package com.mica.motor.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer dlq = new DeadLetterPublishingRecoverer(template,
                (registro, erro) -> new TopicPartition(registro.topic() + "-dlt", -1));

        // 3 novas tentativas, com 1 segundo entre elas; depois vai para a DLQ
        DefaultErrorHandler handler = new DefaultErrorHandler(dlq, new FixedBackOff(1000L, 3));

        // erro de dado não melhora tentando de novo: vai direto para a DLQ
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}