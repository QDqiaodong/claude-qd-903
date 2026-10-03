package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.ConflictException;
import com.port.terminal.common.StageDict;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.TakeoverDecision;
import com.port.terminal.domain.TakeoverItem;
import com.port.terminal.domain.TakeoverSession;
import com.port.terminal.domain.VesselSchedule;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.BatchPauseRequest;
import com.port.terminal.dto.ConfirmStageRequest;
import com.port.terminal.dto.HandoverRequest;
import com.port.terminal.dto.ResumeRequest;
import com.port.terminal.dto.TakeoverDetailDTO;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.repository.TakeoverDecisionRepository;
import com.port.terminal.repository.TakeoverItemRepository;
import com.port.terminal.repository.TakeoverSessionRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 机械故障接管台核心服务。
 * 关键约束：
 *  - 故障立即冻结待执行/执行中指令并停止派新（指令统一置 PAUSED，执行中指令保留冻结阶段）；
 *  - 同一故障机械同一时刻只有一份 OPEN 方案（会话乐观锁 + 明细唯一约束）；
 *  - 接管必须逐项校验作业区域 / 额定能力 / 当前负荷，超能力任务留下拒绝原因不转走；
 *  - 已开始指令只能在「等待短时恢复」和「从已确认阶段转交」间选择，已完成阶段不回滚；
 *  - 原机械恢复不自动抢回，只允许对暂停中未完成指令发起恢复确认。
 */
@Service
public class TakeoverService {

    /** 仍可被处置的明细状态 */
    private static final List<String> OUTSTANDING_STATES =
            List.of("FROZEN_PENDING", "FROZEN_EXECUTING", "PAUSED", "HANDOVER_REJECTED");
    private static final Set<String> ACTIVE_INSTRUCTION_STATUS = Set.of("PENDING", "EXECUTING", "PAUSED", "TAKEN_OVER");

    private final TakeoverSessionRepository sessionRepository;
    private final TakeoverItemRepository itemRepository;
    private final TakeoverDecisionRepository decisionRepository;
    private final CraneRepository craneRepository;
    private final WorkInstructionRepository instructionRepository;
    private final ContainerRepository containerRepository;
    private final EntityManager entityManager;

    public TakeoverService(TakeoverSessionRepository sessionRepository, TakeoverItemRepository itemRepository,
                           TakeoverDecisionRepository decisionRepository, CraneRepository craneRepository,
                           WorkInstructionRepository instructionRepository, ContainerRepository containerRepository,
                           EntityManager entityManager) {
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.decisionRepository = decisionRepository;
        this.craneRepository = craneRepository;
        this.instructionRepository = instructionRepository;
        this.containerRepository = containerRepository;
        this.entityManager = entityManager;
    }

    /* ============================ 故障上报（自动开台） ============================ */

    @Transactional
    public TakeoverDetailDTO reportFault(Long craneId, String reason, String operator) {
        Crane crane = craneRepository.findById(craneId).orElseThrow(() -> new BizException("机械不存在"));

        Optional<TakeoverSession> existing =
                sessionRepository.findByFailedCraneCodeAndStatus(crane.getCode(), "OPEN");
        if (existing.isPresent()) {
            // 已有有效方案：不重复开台，第二个上报人直接看到既有方案（一份方案口径）
            return detail(existing.get().getId(), operator);
        }

        LocalDateTime now = LocalDateTime.now();
        TakeoverSession session = new TakeoverSession();
        session.setSessionNo("TS" + now.format(DateTimeFormatter.ofPattern("MMddHHmmssSSS")));
        session.setFailedCraneCode(crane.getCode());
        session.setStatus("OPEN");
        session.setOpenedBy(operator);
        session.setFaultReason(reason);
        session.setOpenedAt(now);
        // 两名调度员几乎同时为同一机械开台时，uk_open_session_per_crane 唯一索引兜底，
        // DataIntegrityViolationException 在 Controller 事务边界外回落到先成立的方案
        sessionRepository.saveAndFlush(session);

        // 立即汇总该机械关联的待执行 + 执行中 + 已接管未完成指令，全部冻结、停止派新。
        // 接管机械再次故障时，上次已完成阶段不回滚，这批指令在新方案中继续冻结、可再次改派。
        List<WorkInstruction> actives = instructionRepository
                .findByCraneCodeAndStatusIn(crane.getCode(), List.of("PENDING", "EXECUTING", "TAKEN_OVER"));
        for (WorkInstruction instruction : actives) {
            TakeoverItem item = new TakeoverItem();
            item.setSessionId(session.getId());
            item.setInstructionId(instruction.getId());
            item.setInstructionNo(instruction.getInstructionNo());
            boolean executing = "EXECUTING".equals(instruction.getStatus())
                    || ("TAKEN_OVER".equals(instruction.getStatus()) && instruction.getCurrentStage() != null);
            item.setItemState(executing ? "FROZEN_EXECUTING" : "FROZEN_PENDING");
            item.setWasExecuting(executing);
            item.setFrozenStage(instruction.getCurrentStage());
            item.setFrozenStageConfirmed(Boolean.TRUE.equals(instruction.getStageConfirmed()));
            itemRepository.save(item);
            // 仅改状态：故障冻结，绝不允许只换机械编号导致双机同跑
            instruction.setStatus("PAUSED");
        }
        // 机械当前在执行负荷清零（指令已冻结），并置故障态
        crane.setStatus("FAULT");
        crane.setInServiceLoad(0);

        log(session.getId(), null, null, "FAULT_REPORTED", null, "OPEN", crane.getCode(), null, reason, operator, now);
        return detail(session.getId(), operator);
    }

    /* ============================ 方案列表与详情 ============================ */

    public List<TakeoverSession> openSessions() {
        return sessionRepository.findByStatusOrderByOpenedAtDesc("OPEN");
    }

    public Optional<TakeoverSession> openByCraneCode(String craneCode) {
        return sessionRepository.findByFailedCraneCodeAndStatus(craneCode, "OPEN");
    }

    @Transactional
    public TakeoverDetailDTO detail(Long sessionId, String operator) {
        TakeoverSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException("接管方案不存在"));
        Crane failedCrane = craneRepository.findByCode(session.getFailedCraneCode()).orElse(null);

        TakeoverDetailDTO dto = new TakeoverDetailDTO();
        dto.setId(session.getId());
        dto.setSessionNo(session.getSessionNo());
        dto.setFailedCraneCode(session.getFailedCraneCode());
        dto.setFailedCraneType(failedCrane == null ? null : failedCrane.getCraneType());
        dto.setStatus(session.getStatus());
        dto.setVersion(session.getVersion());
        dto.setOpenedBy(session.getOpenedBy());
        dto.setFaultReason(session.getFaultReason());
        dto.setOpenedAt(session.getOpenedAt());
        dto.setClosedBy(session.getClosedBy());
        dto.setClosedAt(session.getClosedAt());
        dto.setClosedNote(session.getClosedNote());
        dto.setFailedCrane(toBrief(failedCrane));

        List<TakeoverItem> items = itemRepository.findBySessionIdOrderByIdAsc(session.getId());
        List<TakeoverDetailDTO.ItemView> itemViews = new ArrayList<>();
        Map<Long, VesselSchedule> vesselCache = new HashMap<>();
        Map<Long, WorkInstruction> instructionCache = new HashMap<>();
        for (TakeoverItem item : items) {
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId()).orElse(null);
            if (instruction != null) instructionCache.put(instruction.getId(), instruction);
            itemViews.add(toItemView(item, instruction, failedCrane, vesselCache));
        }
        dto.setItems(itemViews);
        dto.setCandidates(buildCandidates(failedCrane, itemViews, instructionCache));
        dto.setDecisions(buildDecisions(session.getId()));
        dto.setVesselProgress(buildVesselProgress(items, instructionCache, vesselCache));
        return dto;
    }

    /* ============================ 整批暂停 / 等待短时恢复 ============================ */

    @Transactional
    public TakeoverDetailDTO batchPause(Long sessionId, BatchPauseRequest request, String operator) {
        TakeoverSession session = lockSession(sessionId);
        List<TakeoverItem> items = loadItems(session, request.itemIds());
        LocalDateTime now = LocalDateTime.now();
        for (TakeoverItem item : items) {
            requireOutstanding(item);
            String from = item.getItemState();
            item.setItemState("PAUSED");
            item.setFailureReason(null);
            item.setDecidedBy(operator);
            item.setDecidedAt(now);
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId()).orElseThrow();
            instruction.setStatus("PAUSED"); // 原地冻结，绝不转交
            String action = Boolean.TRUE.equals(item.getWasExecuting()) ? "WAIT_RECOVERY" : "PAUSE";
            log(session.getId(), item.getInstructionId(), item.getInstructionNo(), action, from, "PAUSED",
                    session.getFailedCraneCode(), null,
                    Boolean.TRUE.equals(item.getWasExecuting())
                            ? "执行中指令选择等待原机械短时恢复，船舶作业线暂时停滞" : "整批暂停，冻结派工",
                    operator, now);
        }
        touchSession(session);
        return safeDetail(sessionId, operator);
    }

    /* ============================ 执行中指令阶段确认 ============================ */

    @Transactional
    public TakeoverDetailDTO confirmStage(Long sessionId, Long itemId, ConfirmStageRequest request, String operator) {
        TakeoverSession session = lockSession(sessionId);
        TakeoverItem item = itemRepository.findById(itemId).orElseThrow(() -> new BizException("接管明细不存在"));
        if (!item.getSessionId().equals(session.getId())) throw new BizException("明细不属于当前接管方案");
        requireOutstanding(item);
        WorkInstruction instruction = instructionRepository.findById(item.getInstructionId()).orElseThrow();
        StageDict.StageDef stage = StageDict.def(instruction.getInstructionType(), request.stage());
        if (stage == null) throw new BizException("阶段不在该指令类型的阶段链上");
        item.setFrozenStage(stage.code());
        item.setFrozenStageConfirmed(true);
        instruction.setCurrentStage(stage.code());
        instruction.setStageConfirmed(true);
        log(session.getId(), item.getInstructionId(), item.getInstructionNo(), "CONFIRM_STAGE",
                item.getItemState(), item.getItemState(), session.getFailedCraneCode(), null,
                "现场确认当前阶段为「" + stage.label() + "」，可从该阶段接续", operator, LocalDateTime.now());
        touchSession(session);
        return safeDetail(sessionId, operator);
    }

    /* ============================ 整批转交接管机械 ============================ */

    @Transactional
    public TakeoverDetailDTO handover(Long sessionId, HandoverRequest request, String operator) {
        TakeoverSession session = lockSession(sessionId);
        if (request.itemIds() == null || request.itemIds().isEmpty()) throw new BizException("至少选择一条指令");
        Crane target = craneRepository.findByCode(request.takeoverCraneCode())
                .orElseThrow(() -> new BizException("接管机械不存在"));
        Crane failedCrane = craneRepository.findByCode(session.getFailedCraneCode())
                .orElseThrow(() -> new BizException("原故障机械不存在"));
        if (!failedCrane.getCraneType().equals(target.getCraneType())) {
            throw new BizException("接管机械必须与故障机械同类型（岸桥只能由岸桥接管、场桥只能由场桥接管）");
        }
        List<TakeoverItem> items = loadItems(session, request.itemIds());
        LocalDateTime now = LocalDateTime.now();

        for (TakeoverItem item : items) {
            requireOutstanding(item);
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId()).orElseThrow();

            // 执行中指令：必须走“从已确认阶段转交”，且阶段须现场确认
            if (Boolean.TRUE.equals(item.getWasExecuting())) {
                if (!StageDict.FROM_CONFIRMED_STAGE.equals(request.mode())) {
                    rejectItem(session, item, instruction, target,
                            "执行中指令必须选择“从已确认阶段转交”，或改为等待原机械短时恢复", operator, now);
                    continue;
                }
                if (!Boolean.TRUE.equals(item.getFrozenStageConfirmed()) || item.getFrozenStage() == null) {
                    rejectItem(session, item, instruction, target,
                            "执行中阶段未经现场确认，无法确定接续点，禁止整段重作", operator, now);
                    continue;
                }
            }

            List<String> blockers = blockers(target, instruction, 1);
            if (!blockers.isEmpty()) {
                rejectItem(session, item, instruction, target, String.join("；", blockers), operator, now);
                continue;
            }

            // 接收：已完成阶段不回滚，执行中指令从冻结阶段接续
            String from = item.getItemState();
            item.setItemState("HANDED_OVER");
            item.setTakeoverCraneCode(target.getCode());
            item.setFailureReason(null);
            item.setDecidedBy(operator);
            item.setDecidedAt(now);

            instruction.setCraneCode(target.getCode());
            instruction.setStatus("TAKEN_OVER");
            if (Boolean.TRUE.equals(item.getWasExecuting())) {
                instruction.setCurrentStage(item.getFrozenStage());
                instruction.setStageConfirmed(true);
            }
            target.setStatus("WORKING");
            target.setInServiceLoad(target.getInServiceLoad() == null ? 1 : target.getInServiceLoad() + 1);

            String reason = Boolean.TRUE.equals(item.getWasExecuting())
                    ? "从已确认阶段「" + StageDict.def(instruction.getInstructionType(), item.getFrozenStage()).label()
                        + "」接续，已完成阶段不回滚"
                    : "待执行指令整单接管";
            log(session.getId(), item.getInstructionId(), item.getInstructionNo(), "HANDOVER", from, "HANDED_OVER",
                    session.getFailedCraneCode(), target.getCode(), reason, operator, now);
        }
        touchSession(session);
        // 即使整批被拒也正常提交：拒绝原因必须落库并在明细中展示，未接收指令继续冻结
        return safeDetail(sessionId, operator);
    }

    private void rejectItem(TakeoverSession session, TakeoverItem item, WorkInstruction instruction,
                            Crane target, String reason, String operator, LocalDateTime now) {
        String from = item.getItemState();
        item.setItemState("HANDOVER_REJECTED");
        item.setFailureReason(reason);
        item.setDecidedBy(operator);
        item.setDecidedAt(now);
        // 未接收指令继续冻结，可再次改派
        instruction.setStatus("PAUSED");
        log(session.getId(), item.getInstructionId(), item.getInstructionNo(), "HANDOVER_REJECT", from,
                "HANDOVER_REJECTED", session.getFailedCraneCode(), target.getCode(), reason, operator, now);
    }

    /* ============================ 原机械恢复确认（不自动抢回） ============================ */

    @Transactional
    public TakeoverDetailDTO resume(Long sessionId, ResumeRequest request, String operator) {
        TakeoverSession session = lockSession(sessionId);
        Crane original = craneRepository.findByCode(session.getFailedCraneCode())
                .orElseThrow(() -> new BizException("原机械不存在"));
        if ("FAULT".equals(original.getStatus())) {
            throw new BizException("原机械仍处于故障态，必须先在机械管理中恢复为待机/作业后才能确认恢复");
        }
        List<TakeoverItem> items = loadItems(session, request.itemIds() == null ? List.of() : request.itemIds());
        LocalDateTime now = LocalDateTime.now();
        for (TakeoverItem item : items) {
            // 只能对暂停中的未完成指令（含接管被拒后继续冻结的）发起恢复；已转交指令不抢回
            if (!OUTSTANDING_STATES.contains(item.getItemState())) {
                throw new BizException("指令 " + item.getInstructionNo() + " 已转交完成，原机械不得自动抢回");
            }
            String from = item.getItemState();
            item.setItemState("RESUMED");
            item.setFailureReason(null);
            item.setDecidedBy(operator);
            item.setDecidedAt(now);
            WorkInstruction instruction = instructionRepository.findById(item.getInstructionId()).orElseThrow();
            instruction.setCraneCode(original.getCode());
            instruction.setStatus(Boolean.TRUE.equals(item.getWasExecuting()) ? "EXECUTING" : "PENDING");
            original.setInServiceLoad(original.getInServiceLoad() == null ? 1 : original.getInServiceLoad() + 1);
            log(session.getId(), item.getInstructionId(), item.getInstructionNo(), "RESUME", from, "RESUMED",
                    original.getCode(), null,
                    Boolean.TRUE.equals(item.getWasExecuting()) ? "原机械恢复，从冻结阶段继续执行" : "原机械恢复，指令重新派入",
                    operator, now);
        }
        touchSession(session);
        return safeDetail(sessionId, operator);
    }

    /* ============================ 原机修复登记（仅改机械状态，不抢回指令） ============================ */

    @Transactional
    public TakeoverDetailDTO markCraneRecovered(Long sessionId, String operator) {
        TakeoverSession session = lockSession(sessionId);
        Crane crane = craneRepository.findByCode(session.getFailedCraneCode())
                .orElseThrow(() -> new BizException("原机械不存在"));
        if (!"FAULT".equals(crane.getStatus())) {
            throw new BizException("原机械当前不是故障态，无需重复修复登记");
        }
        crane.setStatus("STANDBY");
        // 负荷保持 0：已转交指令不自动抢回，暂停指令必须经“原机恢复确认”才重新计入
        log(session.getId(), null, null, "CRANE_RECOVERED", "FAULT", "STANDBY",
                crane.getCode(), null, "原机械修复登记为待机；已转交指令不抢回，暂停指令待恢复确认",
                operator, LocalDateTime.now());
        touchSession(session);
        return safeDetail(sessionId, operator);
    }

    /* ============================ 关闭方案 ============================ */

    @Transactional
    public TakeoverDetailDTO close(Long sessionId, String note, String operator) {
        TakeoverSession session = lockSession(sessionId);
        long outstanding = itemRepository.countBySessionIdAndItemStateIn(session.getId(), OUTSTANDING_STATES);
        if (outstanding > 0) {
            throw new BizException("仍有 " + outstanding + " 条指令未处置（冻结/暂停/被拒），不能关闭接管方案");
        }
        session.setStatus("CLOSED");
        session.setClosedBy(operator);
        session.setClosedAt(LocalDateTime.now());
        session.setClosedNote(note);
        log(session.getId(), null, null, "CLOSE", "OPEN", "CLOSED",
                session.getFailedCraneCode(), null, note, operator, LocalDateTime.now());
        return safeDetail(sessionId, operator);
    }

    /* ============================ 接管资格判断（区域 / 能力 / 负荷） ============================ */

    /** 追加 incoming 条指令后的阻塞原因；空列表表示可接收 */
    private List<String> blockers(Crane target, WorkInstruction instruction, int incoming) {
        List<String> blockers = new ArrayList<>();
        // 1. 故障/不可用
        if ("FAULT".equals(target.getStatus())) blockers.add("接管机械自身处于故障态，不可用");
        // 2. 同类型（在调用方已按 craneType 过滤，这里兜底）
        // 3. 作业区域覆盖
        Container container = containerRepository.findByContainerNo(instruction.getContainerNo()).orElse(null);
        String requiredArea = requiredArea(instruction, container);
        if (!areaCovers(target.getWorkArea(), requiredArea)) {
            blockers.add("作业区域不覆盖：指令需要 " + requiredArea + "，该机械作业区域为 "
                    + (target.getWorkArea() == null ? "未配置" : target.getWorkArea()));
        }
        // 4. 额定能力（箱货总重不得超额定起重量）
        if (container != null && container.getWeight() != null && target.getRatedLoad() != null
                && container.getWeight().compareTo(target.getRatedLoad()) > 0) {
            blockers.add("超出额定能力：货重 " + container.getWeight().stripTrailingZeros().toPlainString()
                    + "t > 额定 " + target.getRatedLoad().stripTrailingZeros().toPlainString() + "t");
        }
        // 5. 当前负荷（在途指令数 + 本次追加不得超过并发能力）
        int capacity = target.getCapacity() == null ? 4 : target.getCapacity();
        int load = target.getInServiceLoad() == null ? 0 : target.getInServiceLoad();
        if (load + incoming > capacity) {
            blockers.add("当前负荷已满：在执行 " + load + "/" + capacity + "，无法再接收");
        }
        return blockers;
    }

    /** 指令所需作业区域：场桥看箱所在箱区，岸桥看船舶泊位 */
    private String requiredArea(WorkInstruction instruction, Container container) {
        if (instruction.getVesselScheduleId() != null) {
            VesselSchedule schedule = entityManager.find(VesselSchedule.class, instruction.getVesselScheduleId());
            if (schedule != null) return "B" + String.format("%02d", schedule.getBerthId());
        }
        if (container != null && container.getCurrentYardZone() != null) return container.getCurrentYardZone();
        return instruction.getFromYardZone() != null ? instruction.getFromYardZone() : "A";
    }

    /** workArea 支持逗号分隔多区域，如 "A,B" */
    private boolean areaCovers(String craneArea, String required) {
        if (craneArea == null || craneArea.isBlank()) return false;
        for (String part : craneArea.split(",")) {
            if (part.trim().equals(required)) return true;
        }
        return false;
    }

    /* ============================ 视图组装 ============================ */

    private TakeoverDetailDTO.ItemView toItemView(TakeoverItem item, WorkInstruction instruction, Crane failedCrane,
                                                  Map<Long, VesselSchedule> vesselCache) {
        TakeoverDetailDTO.ItemView view = new TakeoverDetailDTO.ItemView();
        view.id = item.getId();
        view.instructionId = item.getInstructionId();
        view.instructionNo = item.getInstructionNo();
        view.itemState = item.getItemState();
        view.wasExecuting = Boolean.TRUE.equals(item.getWasExecuting());
        view.frozenStage = item.getFrozenStage();
        view.frozenStageConfirmed = Boolean.TRUE.equals(item.getFrozenStageConfirmed());
        view.takeoverCraneCode = item.getTakeoverCraneCode();
        view.failureReason = item.getFailureReason();
        view.decidedBy = item.getDecidedBy();
        view.decidedAt = item.getDecidedAt();
        view.version = item.getVersion();
        if (instruction == null) return view;

        view.containerNo = instruction.getContainerNo();
        view.instructionType = instruction.getInstructionType();
        view.priority = instruction.getPriority();
        view.instructionStatus = instruction.getStatus();
        view.currentStage = instruction.getCurrentStage();
        view.stageConfirmed = Boolean.TRUE.equals(instruction.getStageConfirmed());
        view.vesselScheduleId = instruction.getVesselScheduleId();
        view.fromPosition = formatPosition(instruction.getFromYardZone(), instruction.getFromBay(),
                instruction.getFromRow(), instruction.getFromTier());
        view.toPosition = formatPosition(instruction.getToYardZone(), instruction.getToBay(),
                instruction.getToRow(), instruction.getToTier());
        Container container = containerRepository.findByContainerNo(instruction.getContainerNo()).orElse(null);
        if (container != null) {
            view.containerType = container.getType();
            view.weight = container.getWeight();
            view.dangerous = container.getDangerous();
        }
        if (instruction.getVesselScheduleId() != null) {
            VesselSchedule schedule = vesselCache.computeIfAbsent(instruction.getVesselScheduleId(),
                    id -> entityManager.find(VesselSchedule.class, id));
            if (schedule != null) view.vesselName = schedule.getVesselName();
        }
        // 阶段链 + 当前进度标记
        String activeStage = view.wasExecuting ? view.frozenStage : null;
        List<Map<String, Object>> chain = new ArrayList<>();
        for (StageDict.StageDef def : StageDict.chain(instruction.getInstructionType())) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", def.code());
            node.put("label", def.label());
            node.put("repeatRisk", def.repeatRisk());
            node.put("vesselImpactMinutes", def.vesselImpactMinutes());
            node.put("reached", activeStage != null && def.order() <= orderOf(instruction.getInstructionType(), activeStage));
            node.put("current", def.code().equals(activeStage));
            chain.add(node);
        }
        view.stageChain = chain;
        if (view.wasExecuting) {
            view.impact = StageDict.impactAssessment(instruction.getInstructionType(), view.frozenStage);
        }
        return view;
    }

    private List<TakeoverDetailDTO.CandidateView> buildCandidates(Crane failedCrane, List<TakeoverItem> items,
                                                                  Map<Long, WorkInstruction> instructionCache) {
        if (failedCrane == null) return List.of();
        List<Crane> sameType = craneRepository.findAll().stream()
                .filter(c -> failedCrane.getCraneType().equals(c.getCraneType()))
                .filter(c -> !c.getCode().equals(failedCrane.getCode()))
                .collect(Collectors.toList());
        // 以“任意一条待处置指令”的最坏情况给出候选总览；逐项判断以前端转交结果为准
        List<TakeoverDetailDTO.CandidateView> views = new ArrayList<>();
        for (Crane c : sameType) {
            TakeoverDetailDTO.CandidateView view = new TakeoverDetailDTO.CandidateView();
            view.code = c.getCode();
            view.craneType = c.getCraneType();
            view.ratedLoad = c.getRatedLoad();
            view.workArea = c.getWorkArea();
            view.ratedEfficiency = c.getRatedEfficiency();
            view.inServiceLoad = c.getInServiceLoad();
            view.capacity = c.getCapacity();
            view.freeCapacity = (c.getCapacity() == null ? 4 : c.getCapacity())
                    - (c.getInServiceLoad() == null ? 0 : c.getInServiceLoad());
            view.status = c.getStatus();
            List<String> baseReasons = new ArrayList<>();
            if ("FAULT".equals(c.getStatus())) baseReasons.add("该机械自身故障，不可用");
            if (c.getWorkArea() == null || c.getWorkArea().isBlank()) {
                baseReasons.add("未配置作业区域，无法核对区域可达性");
            }
            if (view.freeCapacity <= 0) baseReasons.add("当前负荷已满（" + c.getInServiceLoad() + "/" + c.getCapacity() + "）");
            view.reasons = baseReasons;
            view.eligible = baseReasons.isEmpty();
            views.add(view);
        }
        return views;
    }

    private List<TakeoverDetailDTO.DecisionView> buildDecisions(Long sessionId) {
        List<TakeoverDetailDTO.DecisionView> views = new ArrayList<>();
        for (TakeoverDecision d : decisionRepository.findBySessionIdOrderByIdAsc(sessionId)) {
            TakeoverDetailDTO.DecisionView view = new TakeoverDetailDTO.DecisionView();
            view.id = d.getId();
            view.instructionNo = d.getInstructionNo();
            view.action = d.getAction();
            view.fromState = d.getFromState();
            view.toState = d.getToState();
            view.takeoverCraneCode = d.getTakeoverCraneCode();
            view.reason = d.getReason();
            view.operator = d.getOperator();
            view.createdAt = d.getCreatedAt();
            views.add(view);
        }
        return views;
    }

    private List<TakeoverDetailDTO.VesselProgressView> buildVesselProgress(
            List<TakeoverItem> items, Map<Long, WorkInstruction> instructionCache,
            Map<Long, VesselSchedule> vesselCache) {
        Map<Long, List<TakeoverItem>> byVessel = new LinkedHashMap<>();
        for (TakeoverItem item : items) {
            WorkInstruction instruction = instructionCache.get(item.getInstructionId());
            if (instruction != null && instruction.getVesselScheduleId() != null) {
                byVessel.computeIfAbsent(instruction.getVesselScheduleId(), k -> new ArrayList<>()).add(item);
            }
        }
        List<TakeoverDetailDTO.VesselProgressView> result = new ArrayList<>();
        for (Map.Entry<Long, List<TakeoverItem>> entry : byVessel.entrySet()) {
            VesselSchedule schedule = vesselCache.computeIfAbsent(entry.getKey(),
                    id -> entityManager.find(VesselSchedule.class, id));
            List<WorkInstruction> all = entityManager.createQuery(
                            "select w from WorkInstruction w where w.vesselScheduleId = :id", WorkInstruction.class)
                    .setParameter("id", entry.getKey()).getResultList();
            long total = all.size();
            long completed = all.stream().filter(w -> "COMPLETED".equals(w.getStatus())).count();
            long pending = all.stream().filter(w -> ACTIVE_INSTRUCTION_STATUS.contains(w.getStatus())).count();
            long frozen = entry.getValue().stream()
                    .filter(i -> OUTSTANDING_STATES.contains(i.getItemState())).count();
            TakeoverDetailDTO.VesselProgressView view = new TakeoverDetailDTO.VesselProgressView();
            view.vesselScheduleId = entry.getKey();
            view.vesselName = schedule == null ? null : schedule.getVesselName();
            view.voyageNo = schedule == null ? null : schedule.getVoyageNo();
            view.totalInstructions = total;
            view.completedInstructions = completed;
            view.pendingInstructions = pending;
            view.frozenInstructions = frozen;
            view.completionPercent = total == 0 ? 0 : (int) (completed * 100 / total);
            result.add(view);
        }
        return result;
    }

    /* ============================ 内部工具 ============================ */

    private TakeoverSession lockSession(Long sessionId) {
        TakeoverSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException("接管方案不存在"));
        if (!"OPEN".equals(session.getStatus())) throw new BizException("接管方案已关闭，不能继续处置");
        return session;
    }

    private List<TakeoverItem> loadItems(TakeoverSession session, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) throw new BizException("至少选择一条指令");
        List<TakeoverItem> items = new ArrayList<>();
        for (Long id : itemIds) {
            TakeoverItem item = itemRepository.findById(id).orElseThrow(() -> new BizException("接管明细不存在"));
            if (!item.getSessionId().equals(session.getId())) throw new BizException("存在不属于本方案的指令，已中止");
            items.add(item);
        }
        return items;
    }

    private void requireOutstanding(TakeoverItem item) {
        if (!OUTSTANDING_STATES.contains(item.getItemState())) {
            throw new BizException("指令 " + item.getInstructionNo() + " 已完成处置（" + item.getItemState() + "），不能重复操作");
        }
    }

    private void touchSession(TakeoverSession session) {
        // 强制 @Version 自增：两名调度员并发提交时，后提交者的事务会在 flush 时收到乐观锁失败（409）
        entityManager.lock(session, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    private TakeoverDetailDTO safeDetail(Long sessionId, String operator) {
        entityManager.flush();
        return detail(sessionId, operator);
    }

    private void log(Long sessionId, Long instructionId, String instructionNo, String action, String from, String to,
                     String failedCrane, String takeoverCrane, String reason, String operator, LocalDateTime when) {
        TakeoverDecision decision = new TakeoverDecision();
        decision.setSessionId(sessionId);
        decision.setInstructionId(instructionId);
        decision.setInstructionNo(instructionNo);
        decision.setAction(action);
        decision.setFromState(from);
        decision.setToState(to);
        decision.setFailedCraneCode(failedCrane);
        decision.setTakeoverCraneCode(takeoverCrane);
        decision.setReason(reason);
        decision.setOperator(operator);
        decision.setCreatedAt(when);
        decisionRepository.save(decision);
    }

    private TakeoverDetailDTO.CraneBrief toBrief(Crane crane) {
        if (crane == null) return null;
        TakeoverDetailDTO.CraneBrief brief = new TakeoverDetailDTO.CraneBrief();
        brief.code = crane.getCode();
        brief.craneType = crane.getCraneType();
        brief.ratedLoad = crane.getRatedLoad();
        brief.workArea = crane.getWorkArea();
        brief.ratedEfficiency = crane.getRatedEfficiency();
        brief.inServiceLoad = crane.getInServiceLoad();
        brief.status = crane.getStatus();
        return brief;
    }

    private int orderOf(String type, String stageCode) {
        StageDict.StageDef def = StageDict.def(type, stageCode);
        return def == null ? 0 : def.order();
    }

    private String formatPosition(String zone, Integer bay, Integer row, Integer tier) {
        if (zone == null) return "船边";
        return zone + "-" + bay + "-" + row + "-" + tier;
    }

    /** 供 OperationService 调用：校验故障机械派工拦截 */
    public void assertDispatchAllowed(String craneCode) {
        Crane crane = craneRepository.findByCode(craneCode).orElse(null);
        if (crane != null && "FAULT".equals(crane.getStatus())) {
            throw new BizException("机械 " + craneCode + " 已故障，接管台已停止派入新任务");
        }
        boolean frozen = !sessionRepository.findByFailedCraneCodeAndStatus(craneCode, "OPEN").isEmpty();
        if (frozen) throw new BizException("机械 " + craneCode + " 存在进行中的故障接管方案，暂停派入新任务");
    }
}
