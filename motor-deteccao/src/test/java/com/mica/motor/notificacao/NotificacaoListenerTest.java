package com.mica.motor.notificacao;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mica.motor.domain.Alerta;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificacaoListenerTest {

    @Test
    void encaminhaAlertaParaOGateway() {
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        NotificacaoGateway gateway = mock(NotificacaoGateway.class);
        Alerta alerta = new Alerta("tx-1:VALOR_ALTO", "tx-1", "conta-1",
                "VALOR_ALTO", new BigDecimal("20000"), Instant.now());
        when(objectMapper.readValue("json", Alerta.class)).thenReturn(alerta);

        NotificacaoListener listener = new NotificacaoListener(objectMapper, gateway);
        listener.notificar("json");

        verify(gateway).enviar(alerta);
    }
}