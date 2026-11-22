package com.mica.motor.kafka;

import com.mica.motor.domain.Transacao;
import com.mica.motor.regras.Regra;
import com.mica.motor.regras.ValorAltoRegra;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class TransacaoListener {

    private final ObjectMapper objectMapper;
    private final List<Regra> regras = List.of(
            new ValorAltoRegra(new BigDecimal("10000"))
    );

    public TransacaoListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "transactions")
    public void consumir(String mensagem) {
        Transacao transacao = objectMapper.readValue(mensagem, Transacao.class);

        for (Regra regra : regras) {
            if (regra.ehSuspeita(transacao)) {
                log.warn("SUSPEITA: transação {} da conta {} quebrou a regra {}",
                        transacao.transactionId(), transacao.accountId(), regra.id());
            }
        }
    }
}