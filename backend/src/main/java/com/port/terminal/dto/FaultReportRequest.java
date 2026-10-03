package com.port.terminal.dto;

import jakarta.validation.constraints.NotBlank;

public record FaultReportRequest(
        @NotBlank(message = "故障原因不能为空") String faultReason) {
}
