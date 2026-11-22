package com.mica.motor.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mica.motor.domain.Alerta;
import com.mica.motor.domain.Transacao;
import com.mica.motor.idempotencia.IdempotenciaService;
import com.mica.motor.regras.Regra;
import com.mica.motor.regras.RegraCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TransacaoListenerTest {

    private ObjectMapper objectMapper;
    private IdempotenciaService idempotencia;
    private AlertaPublisher publisher;
    private RegraCache regraCache;
    private Regra regra;
    private TransacaoListener listener;

    private final Transacao transacao = new Transacao("ev-1", "tx-1", "conta-1",
            new BigDecimal("20000"), "BR", "Loja", Instant.now());

    @BeforeEach
    void setUp() {
        objectMapper = mock(ObjectMapper.class);
        idempotencia = mock(IdempotenciaService.class);
        publisher = mock(AlertaPublisher.class);
        regraCache = mock(RegraCache.class);
        regra = mock(Regra.class);

        when(objectMapper.readValue("json", Transacao.class)).thenReturn(transacao);
        when(regraCache.atuais()).thenReturn(List.of(regra));
        when(regra.id()).thenReturn("VALOR_ALTO");

        listener = new TransacaoListener(objectMapper, idempotencia, publisher, regraCache);
    }

    @Test
    void publicaAlertaQuandoSuspeita() {
        when(idempotencia.registrar("ev-1")).thenReturn(true);
        when(regra.ehSuspeita(transacao)).thenReturn(true);

        listener.consumir("json");

        verify(publisher).publicar(any(Alerta.class));
    }

    @Test
    void naoPublicaQuandoNaoSuspeita() {
        when(idempotencia.registrar("ev-1")).thenReturn(true);
        when(regra.ehSuspeita(transacao)).thenReturn(false);

        listener.consumir("json");

        verify(publisher, never()).publicar(any(Alerta.class));
    }

    @Test
    void ignoraEventoDuplicado() {
        when(idempotencia.registrar("ev-1")).thenReturn(false);

        listener.consumir("json");

        verify(regra, never()).ehSuspeita(any());
        verify(publisher, never()).publicar(any(Alerta.class));
    }
}