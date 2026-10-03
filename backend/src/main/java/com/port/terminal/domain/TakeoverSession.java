package com.port.terminal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 机械故障接管方案：一台故障机械在一次故障期间只允许存在一份 OPEN 方案。
 * version 为乐观锁，两名调度员并发提交时后提交者会收到方案已变更冲突。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "takeover_sessions")
public class TakeoverSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sessionNo;
    private String failedCraneCode;
    /** OPEN / CLOSED */
    private String status;
    @Version
    private Integer version;
    private String openedBy;
    private String closedBy;
    private String faultReason;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private String closedNote;
}
