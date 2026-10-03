package com.port.terminal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 接管台方案详情：故障机械、指令明细（含当前阶段）、可接管机械评估、决策流水、船舶进度 */
public class TakeoverDetailDTO {
    private Long id;
    private String sessionNo;
    private String failedCraneCode;
    private String failedCraneType;
    private String status;
    private Integer version;
    private String openedBy;
    private String faultReason;
    private LocalDateTime openedAt;
    private String closedBy;
    private LocalDateTime closedAt;
    private String closedNote;
    private CraneBrief failedCrane;
    private List<ItemView> items;
    private List<CandidateView> candidates;
    private List<DecisionView> decisions;
    private List<VesselProgressView> vesselProgress;

    public static class CraneBrief {
        public String code;
        public String craneType;
        public BigDecimal ratedLoad;
        public String workArea;
        public Integer ratedEfficiency;
        public Integer inServiceLoad;
        public String status;
    }

    public static class ItemView {
        public Long id;
        public Long instructionId;
        public String instructionNo;
        public String itemState;
        public boolean wasExecuting;
        public String frozenStage;
        public boolean frozenStageConfirmed;
        public String takeoverCraneCode;
        public String failureReason;
        public String decidedBy;
        public LocalDateTime decidedAt;
        public Integer version;
        // 指令快照
        public String containerNo;
        public String containerType;
        public BigDecimal weight;
        public Boolean dangerous;
        public String instructionType;
        public String priority;
        public String instructionStatus;
        public String currentStage;
        public boolean stageConfirmed;
        public Long vesselScheduleId;
        public String vesselName;
        public String fromPosition;
        public String toPosition;
        public List<Map<String, Object>> stageChain;
        public Map<String, Object> impact;
    }

    public static class CandidateView {
        public String code;
        public String craneType;
        public BigDecimal ratedLoad;
        public String workArea;
        public Integer ratedEfficiency;
        public Integer inServiceLoad;
        public Integer capacity;
        public Integer freeCapacity;
        public String status;
        public boolean eligible;
        public List<String> reasons;
    }

    public static class DecisionView {
        public Long id;
        public String instructionNo;
        public String action;
        public String fromState;
        public String toState;
        public String takeoverCraneCode;
        public String reason;
        public String operator;
        public LocalDateTime createdAt;
    }

    public static class VesselProgressView {
        public Long vesselScheduleId;
        public String vesselName;
        public String voyageNo;
        public long totalInstructions;
        public long completedInstructions;
        public long pendingInstructions;
        public long frozenInstructions;
        public int completionPercent;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionNo() { return sessionNo; }
    public void setSessionNo(String sessionNo) { this.sessionNo = sessionNo; }
    public String getFailedCraneCode() { return failedCraneCode; }
    public void setFailedCraneCode(String failedCraneCode) { this.failedCraneCode = failedCraneCode; }
    public String getFailedCraneType() { return failedCraneType; }
    public void setFailedCraneType(String failedCraneType) { this.failedCraneType = failedCraneType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getOpenedBy() { return openedBy; }
    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }
    public String getFaultReason() { return faultReason; }
    public void setFaultReason(String faultReason) { this.faultReason = faultReason; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public String getClosedNote() { return closedNote; }
    public void setClosedNote(String closedNote) { this.closedNote = closedNote; }
    public CraneBrief getFailedCrane() { return failedCrane; }
    public void setFailedCrane(CraneBrief failedCrane) { this.failedCrane = failedCrane; }
    public List<ItemView> getItems() { return items; }
    public void setItems(List<ItemView> items) { this.items = items; }
    public List<CandidateView> getCandidates() { return candidates; }
    public void setCandidates(List<CandidateView> candidates) { this.candidates = candidates; }
    public List<DecisionView> getDecisions() { return decisions; }
    public void setDecisions(List<DecisionView> decisions) { this.decisions = decisions; }
    public List<VesselProgressView> getVesselProgress() { return vesselProgress; }
    public void setVesselProgress(List<VesselProgressView> vesselProgress) { this.vesselProgress = vesselProgress; }
}
