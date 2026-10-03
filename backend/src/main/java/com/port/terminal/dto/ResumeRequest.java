package com.port.terminal.dto;

/** 原机械恢复后，对暂停中的未完成指令发起恢复确认 */
public record ResumeRequest(java.util.List<Long> itemIds) {
}
