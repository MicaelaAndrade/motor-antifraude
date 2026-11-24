package com.mica.motor.kafka;

// outro processamento do mesmo evento ficou pela metade: tenta de novo, e se não der, DLQ
public class EventoEmProcessamentoException extends RuntimeException {
    public EventoEmProcessamentoException(String eventId) {
        super("Evento " + eventId + " ainda marcado como em processamento");
    }
}