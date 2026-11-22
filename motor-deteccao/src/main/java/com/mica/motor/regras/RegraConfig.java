package com.mica.motor.regras;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;

@Entity
public class RegraConfig {

    @Id
    private String id;
    private String tipo;
    private BigDecimal limite;        // valor alto: reais | frequência: quantidade
    private Integer janelaSegundos;   // só para frequência
    private boolean ativa;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public BigDecimal getLimite() { return limite; }
    public void setLimite(BigDecimal limite) { this.limite = limite; }
    public Integer getJanelaSegundos() { return janelaSegundos; }
    public void setJanelaSegundos(Integer janelaSegundos) { this.janelaSegundos = janelaSegundos; }
    public boolean isAtiva() { return ativa; }
    public void setAtiva(boolean ativa) { this.ativa = ativa; }
}