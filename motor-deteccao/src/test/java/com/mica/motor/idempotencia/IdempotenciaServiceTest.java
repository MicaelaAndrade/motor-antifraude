package com.mica.motor.idempotencia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mica.motor.idempotencia.IdempotenciaService.Inicio;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class IdempotenciaServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valores;
    private IdempotenciaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        service = new IdempotenciaService(redis);
    }

    @Test
    void eventoNovoComecaEmProcessamento() {
        when(valores.setIfAbsent(eq("evento:ev-1"), eq("PROCESSANDO"), any(Duration.class))).thenReturn(true);
        assertEquals(Inicio.NOVO, service.iniciar("ev-1"));
    }

    @Test
    void eventoJaConcluidoEhDuplicado() {
        when(valores.setIfAbsent(eq("evento:ev-1"), eq("PROCESSANDO"), any(Duration.class))).thenReturn(false);
        when(valores.get("evento:ev-1")).thenReturn("CONCLUIDO");
        assertEquals(Inicio.CONCLUIDO, service.iniciar("ev-1"));
    }

    @Test
    void eventoPelaMetadeNaoEhTratadoComoDuplicado() {
        when(valores.setIfAbsent(eq("evento:ev-1"), eq("PROCESSANDO"), any(Duration.class))).thenReturn(false);
        when(valores.get("evento:ev-1")).thenReturn("PROCESSANDO");
        assertEquals(Inicio.EM_PROCESSAMENTO, service.iniciar("ev-1"));
    }

    @Test
    void concluirGravaEstadoFinalPor24Horas() {
        service.concluir("ev-1");
        verify(valores).set("evento:ev-1", "CONCLUIDO", Duration.ofHours(24));
    }

    @Test
    void liberarApagaAChave() {
        service.liberar("ev-1");
        verify(redis).delete("evento:ev-1");
    }

    @Test
    void reconheceAlertaJaNotificado() {
        when(redis.hasKey("notif:tx-1:VALOR_ALTO")).thenReturn(true);
        assertTrue(service.jaNotificado("tx-1:VALOR_ALTO"));
    }
}