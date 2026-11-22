package com.mica.motor.api;

import com.mica.motor.notificacao.NotificacaoGateway;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/simulacao")
public class SimulacaoController {

    private final NotificacaoGateway gateway;

    public SimulacaoController(NotificacaoGateway gateway) {
        this.gateway = gateway;
    }

    @PutMapping("/notificacao/{disponivel}")
    public String alterar(@PathVariable boolean disponivel) {
        gateway.setDisponivel(disponivel);
        return disponivel ? "notificacao no ar" : "notificacao fora do ar";
    }
}