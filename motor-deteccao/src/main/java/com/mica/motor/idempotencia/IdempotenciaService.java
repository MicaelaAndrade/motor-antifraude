package com.mica.motor.idempotencia;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotenciaService {

    private final StringRedisTemplate redis;

    public IdempotenciaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // true = primeira vez que vemos o evento; false = duplicado
    public boolean registrar(String eventId) {
        Boolean novo = redis.opsForValue()
                .setIfAbsent(chave(eventId), "1", Duration.ofHours(24));
        return Boolean.TRUE.equals(novo);
    }

    // chamado quando o processamento falha: a nova tentativa não é vista como duplicada
    public void liberar(String eventId) {
        redis.delete(chave(eventId));
    }

    private String chave(String eventId) {
        return "evento:" + eventId;
    }
}