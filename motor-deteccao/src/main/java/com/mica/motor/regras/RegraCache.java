package com.mica.motor.regras;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RegraCache {

    private final RegraConfigRepository repository;
    private final StringRedisTemplate redis;
    private final AtomicReference<List<Regra>> regras = new AtomicReference<>(List.of());

    public RegraCache(RegraConfigRepository repository, StringRedisTemplate redis) {
        this.repository = repository;
        this.redis = redis;
    }

    public List<Regra> atuais() {
        return regras.get();
    }

    @Scheduled(fixedDelay = 10000)
    public void recarregar() {
        try {
            List<Regra> novas = repository.findByAtivaTrue().stream()
                    .map(this::criar)
                    .toList();
            regras.set(novas);
            log.debug("{} regras carregadas", novas.size());
        } catch (Exception e) {
            log.error("Postgres indisponível, mantendo as últimas regras", e);
        }
    }

    private Regra criar(RegraConfig config) {
        return switch (config.getTipo()) {
            case "VALOR_ALTO" -> new ValorAltoRegra(config.getLimite());
            case "FREQUENCIA" -> new FrequenciaRegra(
                    redis,
                    config.getLimite().intValue(),
                    Duration.ofSeconds(config.getJanelaSegundos()));
            default -> throw new IllegalArgumentException("Tipo desconhecido: " + config.getTipo());
        };
    }
}