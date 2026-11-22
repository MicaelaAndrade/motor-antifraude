package com.mica.motor.regras;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mica.motor.domain.Transacao;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ValorAltoRegraTest {

    private final ValorAltoRegra regra = new ValorAltoRegra(new BigDecimal("10000"));

    private Transacao transacaoDe(String valor) {
        return new Transacao("ev-1", "tx-1", "conta-1",
                new BigDecimal(valor), "BR", "Loja", Instant.now());
    }

    @Test
    void acimaDoLimiteEhSuspeita() {
        assertTrue(regra.ehSuspeita(transacaoDe("10000.01")));
    }

    @Test
    void exatamenteNoLimiteNaoEhSuspeita() {
        assertFalse(regra.ehSuspeita(transacaoDe("10000.00")));
    }

    @Test
    void abaixoDoLimiteNaoEhSuspeita() {
        assertFalse(regra.ehSuspeita(transacaoDe("50.00")));
    }
}