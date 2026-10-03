package com.port.terminal.dto;

/** 执行中指令确认当前阶段（调度员现场确认箱位/挂钩状态后才能转交接续） */
public record ConfirmStageRequest(String stage) {
}
