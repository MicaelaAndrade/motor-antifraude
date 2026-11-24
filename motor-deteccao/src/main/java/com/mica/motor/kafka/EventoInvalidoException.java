package com.mica.motor.kafka;

// erro de dado: tentar de novo não resolve, vai direto para a DLQ
public class EventoInvalidoException extends RuntimeException {
    public EventoInvalidoException(String mensagem) {
        super(mensagem);
    }
}