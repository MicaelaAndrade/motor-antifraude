package com.mica.motor.regras;

import com.mica.motor.domain.Transacao;
import java.math.BigDecimal;

public class ValorAltoRegra implements Regra {

    private final BigDecimal limite;

    public ValorAltoRegra(BigDecimal limite) {
        this.limite = limite;
    }

    @Override
    public String id() {
        return "VALOR_ALTO";
    }

    @Override
    public boolean ehSuspeita(Transacao transacao) {
        return transacao.valor().compareTo(limite) > 0;
    }
}