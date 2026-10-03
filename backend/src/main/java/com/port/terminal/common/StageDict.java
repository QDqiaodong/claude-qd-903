package com.port.terminal.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业指令阶段词典：不同指令类型有各自的阶段链。
 * repeatRisk 0-100：从该阶段转交时重复操作风险（越接近落箱，箱位/船图已推进越多，重复风险越高）。
 * vesselImpactMinutes：从该阶段转交预计给船舶作业线增加的停滞/返工分钟数。
 */
public final class StageDict {
    private StageDict() {
    }

    public record StageDef(String code, String label, int order, int repeatRisk, int vesselImpactMinutes) {
    }

    /** 阶段链按指令类型定义，顺序即现场作业顺序 */
    private static final Map<String, List<StageDef>> CHAINS = new LinkedHashMap<>();

    static {
        CHAINS.put("UNLOAD", List.of(
                new StageDef("SLINGING", "船上挂钩", 1, 20, 6),
                new StageDef("LIFTING", "起吊离舱", 2, 45, 12),
                new StageDef("TROLLEYING", "小车牵引", 3, 70, 18),
                new StageDef("LANDING", "落箱入位", 4, 90, 25)));
        CHAINS.put("LOAD", List.of(
                new StageDef("SLINGING", "堆场挂钩", 1, 20, 6),
                new StageDef("LIFTING", "起吊离场", 2, 45, 12),
                new StageDef("TROLLEYING", "小车牵引", 3, 70, 18),
                new StageDef("LANDING", "落箱上船", 4, 90, 25)));
        CHAINS.put("SHIFT", List.of(
                new StageDef("SLINGING", "原位挂钩", 1, 20, 0),
                new StageDef("LIFTING", "起吊移箱", 2, 50, 0),
                new StageDef("TROLLEYING", "水平移行", 3, 75, 0),
                new StageDef("LANDING", "新位落箱", 4, 90, 0)));
        CHAINS.put("PICKUP", List.of(
                new StageDef("SLINGING", "箱区挂钩", 1, 20, 0),
                new StageDef("LIFTING", "起吊装车", 2, 60, 0),
                new StageDef("LANDING", "落车加固", 3, 90, 0)));
    }

    public static List<StageDef> chain(String instructionType) {
        return CHAINS.getOrDefault(instructionType, List.of());
    }

    public static StageDef def(String instructionType, String stageCode) {
        if (stageCode == null) return null;
        return chain(instructionType).stream().filter(s -> s.code().equals(stageCode)).findFirst().orElse(null);
    }

    /** 该阶段之后的剩余阶段（从已确认阶段接续执行用） */
    public static List<StageDef> remaining(String instructionType, String fromStageCode) {
        List<StageDef> chain = chain(instructionType);
        int from = 0;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).code().equals(fromStageCode)) { from = i; break; }
        }
        return chain.subList(from, chain.size());
    }

    public static final String WAIT_RECOVERY = "WAIT_RECOVERY";
    public static final String FROM_CONFIRMED_STAGE = "FROM_CONFIRMED_STAGE";

    /** 执行中指令两种处置方式对船舶进度与重复操作的影响（静态评估） */
    public static Map<String, Object> impactAssessment(String instructionType, String stageCode) {
        StageDef stage = def(instructionType, stageCode);
        Map<String, Object> wait = new LinkedHashMap<>();
        wait.put("mode", WAIT_RECOVERY);
        wait.put("label", "等待原机械短时恢复");
        wait.put("vesselDelayMinutes", stage == null ? 10 : stage.vesselImpactMinutes() + 15);
        wait.put("repeatRisk", 0);
        wait.put("repeatRiskText", "不产生重复操作，但恢复时间不确定，超时将顺延船舶作业线");
        wait.put("summary", "指令原地冻结等待，原机械恢复后从断点继续；若短时无法恢复，船舶进度持续停滞。");

        Map<String, Object> handover = new LinkedHashMap<>();
        handover.put("mode", FROM_CONFIRMED_STAGE);
        handover.put("label", "从已确认阶段转交");
        handover.put("vesselDelayMinutes", stage == null ? 8 : Math.max(4, stage.vesselImpactMinutes() - 4));
        handover.put("repeatRisk", stage == null ? 30 : stage.repeatRisk());
        handover.put("repeatRiskText", stage == null ? "阶段未确认，接管机械需重新对位"
                : "从「" + stage.label() + "」接续，该阶段之前的动作需复核，存在重复挂钩/对位风险");
        handover.put("summary", "已完成阶段不回滚，接管机械从已确认阶段接续；阶段越早重复越多，但船舶等待更短。");
        return Map.of("waitRecovery", wait, "handover", handover);
    }
}
