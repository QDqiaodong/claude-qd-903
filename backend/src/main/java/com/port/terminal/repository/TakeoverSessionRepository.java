package com.port.terminal.repository;

import com.port.terminal.domain.TakeoverSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TakeoverSessionRepository extends JpaRepository<TakeoverSession> {
    Optional<TakeoverSession> findByFailedCraneCodeAndStatus(String failedCraneCode, String status);
    List<TakeoverSession> findByFailedCraneCodeOrderByOpenedAtDesc(String failedCraneCode);
    List<TakeoverSession> findByStatusOrderByOpenedAtDesc(String status);
}
