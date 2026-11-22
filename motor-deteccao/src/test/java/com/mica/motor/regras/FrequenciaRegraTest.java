package com.mica.motor.regras;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mica.motor.domain.Transacao;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class FrequenciaRegraTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zset;
    private FrequenciaRegra regra;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        regra = new FrequenciaRegra(redis, 5, Duration.ofMinutes(2));
    }

    private Transacao transacao() {
        return new Transacao("ev-1", "tx-1", "conta-1",
                new BigDecimal("100"), "BR", "Loja", Instant.now());
    }

    @Test
    void quintaTransacaoNaJanelaEhSuspeita() {
        when(zset.zCard("freq:conta-1")).thenReturn(5L);
        assertTrue(regra.ehSuspeita(transacao()));
    }

    @Test
    void quartaTransacaoNaJanelaNaoEhSuspeita() {
        when(zset.zCard("freq:conta-1")).thenReturn(4L);
        assertFalse(regra.ehSuspeita(transacao()));
    }
}