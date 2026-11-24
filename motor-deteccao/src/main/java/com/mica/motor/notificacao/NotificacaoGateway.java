package com.mica.motor.notificacao;

import com.mica.motor.domain.Alerta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificacaoGateway {

    // simula o provedor de push/e-mail fora do ar
    private volatile boolean disponivel = true;

    public void setDisponivel(boolean disponivel) {
        this.disponivel = disponivel;
        log.warn("Provedor de notificação agora está {}", disponivel ? "DISPONÍVEL" : "FORA DO AR");
    }

    public void enviar(Alerta alerta) {
        if (!disponivel) {
            throw new IllegalStateException("Provedor de notificação indisponível");
        }
        log.info("Notificação enviada para o alerta {}", alerta.alertaId());
    }
}