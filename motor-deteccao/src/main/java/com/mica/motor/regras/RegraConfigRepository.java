package com.mica.motor.regras;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegraConfigRepository extends JpaRepository<RegraConfig, String> {
    List<RegraConfig> findByAtivaTrue();
}