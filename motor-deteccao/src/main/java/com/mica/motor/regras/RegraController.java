package com.mica.motor.api;

import com.mica.motor.regras.RegraConfig;
import com.mica.motor.regras.RegraConfigRepository;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/regras")
public class RegraController {

    private final RegraConfigRepository repository;

    public RegraController(RegraConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<RegraConfig> listar() {
        return repository.findAll();
    }

    @PutMapping("/{id}")
    public RegraConfig salvar(@PathVariable String id, @RequestBody RegraConfig regra) {
        regra.setId(id);
        return repository.save(regra);
    }
}