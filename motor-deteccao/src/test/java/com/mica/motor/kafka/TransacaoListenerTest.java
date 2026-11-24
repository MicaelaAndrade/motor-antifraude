package com.mica.motor.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mica.motor.domain.Alerta;
import com.mica.motor.domain.Transacao;
import com.mica.motor.idempotencia.IdempotenciaService;
import com.mica.motor.idempotencia.IdempotenciaService.Inicio;
import com.mica.motor.regras.Regra;
import com.mica.motor.regras.RegraCache;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TransacaoListenerTest {

    private ObjectMapper objectMapper;
    private IdempotenciaService idempotencia;
    private AlertaPublisher publisher;
    private Regra regra;
    private TransacaoListener listener;

    private final Transacao transacao = new Transacao("ev-1", "tx-1", "conta-1",
            new BigDecimal("20000"), "BR", "Loja", Instant.parse("2026-11-25T12:00:00Z"));

    @BeforeEach
    void setUp() {
        objectMapper = mock(ObjectMapper.class);
        idempotencia = mock(IdempotenciaService.class);
        publisher = mock(AlertaPublisher.class);
        RegraCache regraCache = mock(RegraCache.class);
        regra = mock(Regra.class);

        when(objectMapper.readValue("json", Transacao.class)).thenReturn(transacao);
        when(regraCache.atuais()).thenReturn(List.of(regra));
        when(regra.id()).thenReturn("VALOR_ALTO");

        listener = new TransacaoListener(objectMapper, idempotencia, publisher, regraCache);
    }

    @Test
    void publicaAlertaEConcluiQuandoSuspeita() {
        when(idempotencia.iniciar("ev-1")).thenReturn(Inicio.NOVO);
        when(regra.ehSuspeita(transacao)).thenReturn(true);

        listener.consumir("json");

        verify(publisher).publicar(any(Alerta.class));
        verify(idempotencia).concluir("ev-1");
    }

    @Test
    void naoPublicaQuandoNaoSuspeitaMasConclui() {
        when(idempotencia.iniciar("ev-1")).thenReturn(Inicio.NOVO);
        when(regra.ehSuspeita(transacao)).thenReturn(false);

        listener.consumir("json");

        verify(publisher, never()).publicar(any(Alerta.class));
        verify(idempotencia).concluir("ev-1");
    }

    @Test
    void ignoraEventoJaConcluido() {
        when(idempotencia.iniciar("ev-1")).thenReturn(Inicio.CONCLUIDO);

        listener.consumir("json");

        verify(regra, never()).ehSuspeita(any());
        verify(publisher, never()).publicar(any(Alerta.class));
    }

    @Test
    void eventoPelaMetadeFalhaEmVezDeSumir() {
        when(idempotencia.iniciar("ev-1")).thenReturn(Inicio.EM_PROCESSAMENTO);

        assertThrows(EventoEmProcessamentoException.class, () -> listener.consumir("json"));
        verify(publisher, never()).publicar(any(Alerta.class));
    }

    @Test
    void liberaENaoConcluiQuandoPublicacaoFalha() {
        when(idempotencia.iniciar("ev-1")).thenReturn(Inicio.NOVO);
        when(regra.ehSuspeita(transacao)).thenReturn(true);
        doThrow(new IllegalStateException("broker fora")).when(publisher).publicar(any(Alerta.class));

        assertThrows(IllegalStateException.class, () -> listener.consumir("json"));

        verify(idempotencia).liberar("ev-1");
        verify(idempotencia, never()).concluir(anyString());
    }

    @Test
    void falhaAoLiberarNaoEscondeOErroOriginal() {
        when(idempotencia.iniciar("ev-1")).thenReturn(Inicio.NOVO);
        when(regra.ehSuspeita(transacao)).thenReturn(true);
        doThrow(new IllegalStateException("broker fora")).when(publisher).publicar(any(Alerta.class));
        doThrow(new RuntimeException("redis fora")).when(idempotencia).liberar("ev-1");

        IllegalStateException erro = assertThrows(IllegalStateException.class, () -> listener.consumir("json"));

        assertEquals("broker fora", erro.getMessage());
        assertEquals(1, erro.getSuppressed().length);
    }

    @Test
    void eventoSemIdEhInvalido() {
        Transacao semId = new Transacao(null, "tx-2", "conta-1",
                new BigDecimal("10"), "BR", "Loja", Instant.now());
        when(objectMapper.readValue("sem-id", Transacao.class)).thenReturn(semId);

        assertThrows(EventoInvalidoException.class, () -> listener.consumir("sem-id"));
        verify(idempotencia, never()).iniciar(anyString());
    }

    @Test
    void eventoSemTransactionIdEhInvalido() {
        Transacao semTransactionId = new Transacao("ev-2", null, "conta-1",
                new BigDecimal("10"), "BR", "Loja", Instant.now());
        when(objectMapper.readValue("sem-tx", Transacao.class)).thenReturn(semTransactionId);

        assertThrows(EventoInvalidoException.class, () -> listener.consumir("sem-tx"));
        verify(idempotencia, never()).iniciar(anyString());
    }
}