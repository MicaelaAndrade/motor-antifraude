package com.mica.motor.idempotencia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class IdempotenciaServiceTest {

    private ValueOperations<String, String> valores;
    private IdempotenciaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        service = new IdempotenciaService(redis);
    }

    @Test
    void eventoNovoEhRegistrado() {
        when(valores.setIfAbsent(eq("evento:ev-1"), eq("1"), any(Duration.class))).thenReturn(true);
        assertTrue(service.registrar("ev-1"));
    }

    @Test
    void eventoRepetidoEhRecusado() {
        when(valores.setIfAbsent(eq("evento:ev-1"), eq("1"), any(Duration.class))).thenReturn(false);
        assertFalse(service.registrar("ev-1"));
    }
}