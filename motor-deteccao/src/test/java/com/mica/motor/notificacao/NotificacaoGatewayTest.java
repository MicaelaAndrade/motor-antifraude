package com.mica.motor.notificacao;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mica.motor.domain.Alerta;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificacaoGatewayTest {

    private final NotificacaoGateway gateway = new NotificacaoGateway();
    private final Alerta alerta = new Alerta("tx-1:VALOR_ALTO", "tx-1", "conta-1",
            "VALOR_ALTO", new BigDecimal("20000"), Instant.now());

    @Test
    void enviaQuandoProvedorDisponivel() {
        assertDoesNotThrow(() -> gateway.enviar(alerta));
    }

    @Test
    void falhaQuandoProvedorForaDoAr() {
        gateway.setDisponivel(false);
        assertThrows(IllegalStateException.class, () -> gateway.enviar(alerta));
    }
}