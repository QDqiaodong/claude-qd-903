package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 整批转交接管机械。
 * mode：FROM_CONFIRMED_STAGE（从已确认阶段转交，执行中指令）
 * 待执行指令不带阶段，直接转交。
 */
public record HandoverRequest(
        @NotEmpty(message = "至少选择一条指令") List<Long> itemIds,
        @NotBlank(message = "必须指定接管机械") String takeoverCraneCode,
        String mode) {
}
