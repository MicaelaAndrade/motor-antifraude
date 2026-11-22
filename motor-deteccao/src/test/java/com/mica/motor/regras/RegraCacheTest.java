package com.mica.motor.regras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RegraCacheTest {

    private RegraConfigRepository repository;
    private RegraCache cache;

    @BeforeEach
    void setUp() {
        repository = mock(RegraConfigRepository.class);
        cache = new RegraCache(repository, mock(StringRedisTemplate.class));
    }

    private RegraConfig config(String tipo, String limite, Integer janela) {
        RegraConfig c = new RegraConfig();
        c.setId(tipo);
        c.setTipo(tipo);
        c.setLimite(new BigDecimal(limite));
        c.setJanelaSegundos(janela);
        c.setAtiva(true);
        return c;
    }

    @Test
    void comecaSemRegras() {
        assertTrue(cache.atuais().isEmpty());
    }

    @Test
    void carregaRegrasDosDoisTipos() {
        when(repository.findByAtivaTrue()).thenReturn(List.of(
                config("VALOR_ALTO", "10000", null),
                config("FREQUENCIA", "5", 120)));

        cache.recarregar();

        assertEquals(2, cache.atuais().size());
        assertEquals("VALOR_ALTO", cache.atuais().get(0).id());
        assertEquals("FREQUENCIA", cache.atuais().get(1).id());
    }

    @Test
    void postgresForaDoArMantemUltimasRegras() {
        when(repository.findByAtivaTrue())
                .thenReturn(List.of(config("VALOR_ALTO", "10000", null)))
                .thenThrow(new RuntimeException("Postgres caiu"));

        cache.recarregar();   // carrega 1 regra
        cache.recarregar();   // banco falha

        assertEquals(1, cache.atuais().size());
    }

    @Test
    void tipoDesconhecidoNaoDerrubaOMotor() {
        when(repository.findByAtivaTrue())
                .thenReturn(List.of(config("VALOR_ALTO", "10000", null)))
                .thenReturn(List.of(config("TIPO_INVENTADO", "1", null)));

        cache.recarregar();
        cache.recarregar();

        assertEquals(1, cache.atuais().size());
    }
}