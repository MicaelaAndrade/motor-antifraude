package com.mica.motor.regras;

import com.mica.motor.domain.Transacao;

public interface Regra {
    String id();
    boolean ehSuspeita(Transacao transacao);
}