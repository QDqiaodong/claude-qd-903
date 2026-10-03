package com.port.terminal.repository;

import com.port.terminal.domain.TakeoverItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TakeoverItemRepository extends JpaRepository<TakeoverItem> {
    List<TakeoverItem> findBySessionIdOrderByIdAsc(Long sessionId);
    List<TakeoverItem> findByInstructionId(Long instructionId);
    long countBySessionIdAndItemStateIn(Long sessionId, List<String> states);
}
