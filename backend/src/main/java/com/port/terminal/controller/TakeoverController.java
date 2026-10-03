package com.port.terminal.controller;

import com.port.terminal.common.ApiResponse;
import com.port.terminal.domain.Crane;
import com.port.terminal.domain.TakeoverSession;
import com.port.terminal.dto.BatchPauseRequest;
import com.port.terminal.dto.ConfirmStageRequest;
import com.port.terminal.dto.FaultReportRequest;
import com.port.terminal.dto.HandoverRequest;
import com.port.terminal.dto.ResumeRequest;
import com.port.terminal.dto.TakeoverDetailDTO;
import com.port.terminal.repository.CraneRepository;
import com.port.terminal.service.TakeoverService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/takeover")
public class TakeoverController {
    private final TakeoverService takeoverService;
    private final CraneRepository craneRepository;

    public TakeoverController(TakeoverService takeoverService, CraneRepository craneRepository) {
        this.takeoverService = takeoverService;
        this.craneRepository = craneRepository;
    }

    /** 当前所有进行中的故障接管方案（同一故障机械只会出现一份） */
    @GetMapping("/sessions")
    public ApiResponse<List<TakeoverSession>> sessions() {
        return ApiResponse.success(takeoverService.openSessions());
    }

    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<TakeoverDetailDTO> detail(@PathVariable Long sessionId,
                                                 @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator) {
        return ApiResponse.success(takeoverService.detail(sessionId, operator));
    }

    /** 岸桥/场桥转为故障：立即冻结关联指令并生成唯一接管方案 */
    @PostMapping("/cranes/{craneId}/fault")
    public ApiResponse<TakeoverDetailDTO> reportFault(@PathVariable Long craneId,
                                                      @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator,
                                                      @Valid @RequestBody FaultReportRequest request) {
        try {
            return ApiResponse.success("故障接管台已开启，关联指令已冻结并停止派新",
                    takeoverService.reportFault(craneId, request.faultReason(), operator));
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // 两名调度员同时开台：事务已回滚，在边界外回落到先成立的那一份方案
            Crane crane = craneRepository.findById(craneId).orElseThrow(() -> dup);
            TakeoverSession winner = takeoverService.openByCraneCode(crane.getCode()).orElseThrow(() -> dup);
            return ApiResponse.success("另一调度员已开启该机械的接管方案，已为你打开同一份方案",
                    takeoverService.detail(winner.getId(), operator));
        }
    }

    /** 整批暂停（执行中指令即“等待原机械短时恢复”） */
    @PostMapping("/sessions/{sessionId}/pause")
    public ApiResponse<TakeoverDetailDTO> pause(@PathVariable Long sessionId,
                                                @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator,
                                                @RequestBody BatchPauseRequest request) {
        return ApiResponse.success("所选指令已整批暂停", takeoverService.batchPause(sessionId, request, operator));
    }

    /** 执行中指令现场阶段确认，确认后才能从该阶段转交接续 */
    @PostMapping("/sessions/{sessionId}/items/{itemId}/confirm-stage")
    public ApiResponse<TakeoverDetailDTO> confirmStage(@PathVariable Long sessionId, @PathVariable Long itemId,
                                                       @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator,
                                                       @RequestBody ConfirmStageRequest request) {
        return ApiResponse.success("当前作业阶段已确认",
                takeoverService.confirmStage(sessionId, itemId, request, operator));
    }

    /** 整批转交给同类型且可用的接管机械，逐项校验区域/能力/负荷 */
    @PostMapping("/sessions/{sessionId}/handover")
    public ApiResponse<TakeoverDetailDTO> handover(@PathVariable Long sessionId,
                                                   @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator,
                                                   @Valid @RequestBody HandoverRequest request) {
        return ApiResponse.success("转交处理完成，单条结果见指令明细",
                takeoverService.handover(sessionId, request, operator));
    }

    /** 原机修复登记：只把机械置为待机，不自动抢回任何指令 */
    @PostMapping("/sessions/{sessionId}/crane-recovered")
    public ApiResponse<TakeoverDetailDTO> craneRecovered(@PathVariable Long sessionId,
                                                         @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator) {
        return ApiResponse.success("原机械已修复为待机，暂停指令仍需恢复确认",
                takeoverService.markCraneRecovered(sessionId, operator));
    }

    /** 原机械恢复后，对暂停中的未完成指令发起恢复确认（不自动抢回已转交指令） */
    @PostMapping("/sessions/{sessionId}/resume")
    public ApiResponse<TakeoverDetailDTO> resume(@PathVariable Long sessionId,
                                                 @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator,
                                                 @RequestBody ResumeRequest request) {
        return ApiResponse.success("所选指令已恢复给原机械", takeoverService.resume(sessionId, request, operator));
    }

    @PostMapping("/sessions/{sessionId}/close")
    public ApiResponse<TakeoverDetailDTO> close(@PathVariable Long sessionId,
                                                @RequestHeader(value = "X-Operator", defaultValue = "调度员") String operator,
                                                @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return ApiResponse.success("接管方案已关闭", takeoverService.close(sessionId, note, operator));
    }
}
