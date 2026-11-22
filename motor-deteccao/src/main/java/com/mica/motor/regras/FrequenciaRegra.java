package com.mica.motor.regras;

import com.mica.motor.domain.Transacao;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

public class FrequenciaRegra implements Regra {

    private final StringRedisTemplate redis;
    private final int maxTransacoes;
    private final Duration janela;

    public FrequenciaRegra(StringRedisTemplate redis, int maxTransacoes, Duration janela) {
        this.redis = redis;
        this.maxTransacoes = maxTransacoes;
        this.janela = janela;
    }

    @Override
    public String id() {
        return "FREQUENCIA";
    }

    @Override
    public boolean ehSuspeita(Transacao transacao) {
        String chave = "freq:" + transacao.accountId();
        long agora = transacao.dataHora().toEpochMilli();

        var zset = redis.opsForZSet();
        zset.add(chave, transacao.transactionId(), agora);
        zset.removeRangeByScore(chave, 0, agora - janela.toMillis());
        redis.expire(chave, janela);

        Long quantidade = zset.zCard(chave);
        return quantidade != null && quantidade >= maxTransacoes;
    }
}