package com.mica.motor.idempotencia;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotenciaService {

    public enum Inicio { NOVO, CONCLUIDO, EM_PROCESSAMENTO }

    static final String PROCESSANDO = "PROCESSANDO";
    static final String CONCLUIDO = "CONCLUIDO";

    private final StringRedisTemplate redis;

    public IdempotenciaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Inicio iniciar(String eventId) {
        Boolean novo = redis.opsForValue()
                .setIfAbsent(chave(eventId), PROCESSANDO, Duration.ofMinutes(5));
        if (Boolean.TRUE.equals(novo)) {
            return Inicio.NOVO;
        }
        String atual = redis.opsForValue().get(chave(eventId));
        return CONCLUIDO.equals(atual) ? Inicio.CONCLUIDO : Inicio.EM_PROCESSAMENTO;
    }

    public void concluir(String eventId) {
        redis.opsForValue().set(chave(eventId), CONCLUIDO, Duration.ofHours(24));
    }

    public void liberar(String eventId) {
        redis.delete(chave(eventId));
    }

    public boolean jaNotificado(String alertaId) {
        return Boolean.TRUE.equals(redis.hasKey("notif:" + alertaId));
    }

    public void marcarNotificado(String alertaId) {
        redis.opsForValue().set("notif:" + alertaId, "1", Duration.ofHours(24));
    }

    private String chave(String eventId) {
        return "evento:" + eventId;
    }
}