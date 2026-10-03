package com.port.terminal.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 接管决策流水：暂停、转交、拒绝、恢复、关闭，失败原因与每次决定都保留。 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "takeover_decisions")
public class TakeoverDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long sessionId;
    private Long instructionId;
    private String instructionNo;
    /** FAULT_REPORTED / PAUSE / WAIT_RECOVERY / HANDOVER / HANDOVER_REJECT / RESUME / CLOSE */
    private String action;
    private String fromState;
    private String toState;
    private String failedCraneCode;
    private String takeoverCraneCode;
    private String reason;
    private String operator;
    private LocalDateTime createdAt;
}
