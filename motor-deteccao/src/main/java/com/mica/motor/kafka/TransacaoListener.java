package com.mica.motor.kafka;

import tools.jackson.databind.ObjectMapper;
import com.mica.motor.domain.Transacao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransacaoListener {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "transactions")
    public void consumir(String mensagem) throws Exception {
        Transacao transacao = objectMapper.readValue(mensagem, Transacao.class);
        log.info("Recebida transação {} da conta {} no valor de {}",
                transacao.transactionId(), transacao.accountId(), transacao.valor());
    }
}
