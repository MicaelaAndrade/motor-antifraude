package com.mica.motor.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Transacao(
        String eventId,
        String transactionId,
        String accountId,
        BigDecimal valor,
        String pais,
        String estabelecimento,
        Instant dataHora
) {}