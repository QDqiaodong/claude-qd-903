package com.port.terminal.common;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 集装箱码头统一业务词典。 */
public final class Dict {
    private Dict() {
    }

    public static final List<String> CONTAINER_TYPES = List.of("20GP", "40GP", "40HQ", "45HC", "REEFER");
    public static final List<String> SHIPPING_COMPANIES = List.of("中远海运", "马士基", "地中海航运", "达飞轮船", "长荣海运");
    public static final List<String> OPERATION_TYPES = List.of("LOAD", "UNLOAD", "BOTH");
    public static final List<String> INSTRUCTION_TYPES = List.of("UNLOAD", "LOAD", "SHIFT", "PICKUP");
    public static final List<String> SLOT_STATUSES = List.of("EMPTY", "OCCUPIED", "RESERVED", "DISABLED");
    public static final List<String> PRIORITIES = List.of("NORMAL", "URGENT", "VESSEL_CUTOFF");

    public static final Map<String, String> BERTH_STATUS = Map.of(
            "IDLE", "空闲", "BERTHED", "在泊", "MAINTENANCE", "维护");
    public static final Map<String, String> CRANE_STATUS = Map.of(
            "WORKING", "作业", "STANDBY", "待机", "FAULT", "故障");
    public static final Map<String, String> SLOT_STATUS = Map.of(
            "EMPTY", "空", "OCCUPIED", "占用", "RESERVED", "预留", "DISABLED", "禁用");
    public static final Map<String, String> INSTRUCTION_STATUS = Map.of(
            "PENDING", "待执行", "EXECUTING", "执行中", "COMPLETED", "已完成", "CANCELLED", "已取消");
    public static final Map<String, String> PICKUP_STATUS = Map.of(
            "BLOCKED", "不可提", "AVAILABLE", "可提", "OUT", "已出场");

    public static final Set<String> FORTY_FOOT_TYPES = Set.of("40GP", "40HQ", "45HC");
    public static final String REEFER_TYPE = "REEFER";
    public static final String NORMAL_HEAVY_ZONE = "普通重箱区";

    public static boolean isFortyFoot(String type) {
        return type != null && FORTY_FOOT_TYPES.contains(type);
    }

    public static boolean isReefer(String type) {
        return REEFER_TYPE.equals(type);
    }

    public static boolean isValidContainerType(String type) {
        return type != null && CONTAINER_TYPES.contains(type);
    }
}
