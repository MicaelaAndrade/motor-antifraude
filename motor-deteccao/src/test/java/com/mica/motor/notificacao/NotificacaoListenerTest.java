package com.mica.motor.notificacao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mica.motor.domain.Alerta;
import com.mica.motor.idempotencia.IdempotenciaService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificacaoListenerTest {

    private NotificacaoGateway gateway;
    private IdempotenciaService idempotencia;
    private NotificacaoListener listener;

    private final Alerta alerta = new Alerta("tx-1:VALOR_ALTO", "tx-1", "conta-1",
            "VALOR_ALTO", new BigDecimal("20000"), Instant.parse("2026-11-25T12:00:00Z"));

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        gateway = mock(NotificacaoGateway.class);
        idempotencia = mock(IdempotenciaService.class);
        when(objectMapper.readValue("json", Alerta.class)).thenReturn(alerta);
        listener = new NotificacaoListener(objectMapper, gateway, idempotencia);
    }

    @Test
    void enviaEMarcaQuandoAindaNaoNotificado() {
        when(idempotencia.jaNotificado("tx-1:VALOR_ALTO")).thenReturn(false);

        listener.notificar("json");

        verify(gateway).enviar(alerta);
        verify(idempotencia).marcarNotificado("tx-1:VALOR_ALTO");
    }

    @Test
    void naoEnviaDeNovoAlertaRepetido() {
        when(idempotencia.jaNotificado("tx-1:VALOR_ALTO")).thenReturn(true);

        listener.notificar("json");

        verify(gateway, never()).enviar(any());
    }
}