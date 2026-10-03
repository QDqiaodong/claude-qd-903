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
 * 接管方案中的单条指令处置记录。
 * 状态流转：
 *   FROZEN_PENDING/FROZEN_EXECUTING（故障冻结）
 *     -> PAUSED（调度员暂停 / 等待原机械短时恢复）
 *     -> HANDED_OVER（已转交接管机械，已完成阶段不回滚）
 *     -> HANDOVER_REJECTED（接管机械拒绝接收，仍可改派）
 *     -> RESUMED（原机械恢复后确认恢复）
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "takeover_items")
public class TakeoverItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long sessionId;
    private Long instructionId;
    private String instructionNo;
    /** FROZEN_PENDING / FROZEN_EXECUTING / PAUSED / HANDED_OVER / HANDOVER_REJECTED / RESUMED */
    private String itemState;
    private Boolean wasExecuting;
    /** 冻结瞬间指令所处阶段，转交执行中指令时只能从该已确认阶段接续 */
    private String frozenStage;
    private Boolean frozenStageConfirmed;
    private String takeoverCraneCode;
    /** 最近一次未能转交/恢复的原因，前端逐条展示 */
    private String failureReason;
    private String decidedBy;
    private LocalDateTime decidedAt;
    @Version
    private Integer version;
}
