package com.port.terminal.dto;

import java.util.List;

/** 整批暂停 / 等待原机械短时恢复 */
public record BatchPauseRequest(List<Long> itemIds) {
}
