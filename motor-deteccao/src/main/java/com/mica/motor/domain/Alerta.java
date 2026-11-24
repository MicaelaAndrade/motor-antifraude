package com.mica.motor.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Alerta(
        String alertaId,
        String transactionId,
        String accountId,
        String regraId,
        BigDecimal valor,
        Instant geradoEm
) {
    public static Alerta de(Transacao t, String regraId) {
        // usa a hora da transação: o mesmo alerta tem sempre o mesmo conteúdo
        return new Alerta(
                t.transactionId() + ":" + regraId,
                t.transactionId(), t.accountId(), regraId,
                t.valor(), t.dataHora());
    }
}