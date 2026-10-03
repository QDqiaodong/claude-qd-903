package com.port.terminal.repository;

import com.port.terminal.domain.TakeoverDecision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TakeoverDecisionRepository extends JpaRepository<TakeoverDecision> {
    List<TakeoverDecision> findBySessionIdOrderByIdAsc(Long sessionId);
}
