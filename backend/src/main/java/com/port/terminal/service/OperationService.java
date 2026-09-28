package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.GateTransaction;
import com.port.terminal.domain.WorkInstruction;
import com.port.terminal.dto.InstructionRequest;
import com.port.terminal.dto.SlotActionRequest;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.WorkInstructionRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OperationService {
    private final WorkInstructionRepository instructionRepository;
    private final ContainerRepository containerRepository;
    private final YardService yardService;
    private final EntityManager entityManager;

    public OperationService(WorkInstructionRepository instructionRepository, ContainerRepository containerRepository,
                            YardService yardService, EntityManager entityManager) {
        this.instructionRepository = instructionRepository;
        this.containerRepository = containerRepository;
        this.yardService = yardService;
        this.entityManager = entityManager;
    }

    public List<GateTransaction> gates() {
        return entityManager.createQuery(
                        "select g from GateTransaction g order by g.passingTime desc", GateTransaction.class)
                .setMaxResults(30).getResultList();
    }

    public List<WorkInstruction> instructions() {
        return instructionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public WorkInstruction create(InstructionRequest request) {
        Container container = containerRepository.findByContainerNo(request.containerNo())
                .orElseThrow(() -> new BizException("箱号 " + request.containerNo() + " 不存在"));
        if (!List.of("UNLOAD", "LOAD", "SHIFT", "PICKUP").contains(request.instructionType())) {
            throw new BizException("指令类型无效");
        }
        if ("SHIFT".equals(request.instructionType())) {
            requireTarget(request);
            if (!"EMPTY".equals(yardService.exactSlot(request.toYardZone(), request.toBay(), request.toRow(), request.toTier()).getStatus())) {
                throw new BizException("翻倒指令的目标箱位必须为空");
            }
        }
        if ("PICKUP".equals(request.instructionType())) {
            if (!"AVAILABLE".equals(container.getPickupStatus())) throw new BizException("提箱时箱号必须处于可提状态");
            if (!Boolean.TRUE.equals(container.getPaidInspection())) throw new BizException("提箱前必须完成付费查验标记");
        }
        WorkInstruction instruction = new WorkInstruction();
        instruction.setInstructionNo("WI" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmssSSS")));
        instruction.setContainerNo(container.getContainerNo());
        instruction.setFromYardZone(container.getCurrentYardZone());
        instruction.setFromBay(container.getCurrentBay());
        instruction.setFromRow(container.getCurrentRow());
        instruction.setFromTier(container.getCurrentTier());
        instruction.setToYardZone(request.toYardZone());
        instruction.setToBay(request.toBay());
        instruction.setToRow(request.toRow());
        instruction.setToTier(request.toTier());
        instruction.setCraneCode(request.craneCode());
        instruction.setInstructionType(request.instructionType());
        instruction.setPriority(request.priority() == null ? "NORMAL" : request.priority());
        instruction.setStatus("PENDING");
        instruction.setVesselScheduleId(request.vesselScheduleId());
        instruction.setCreatedAt(LocalDateTime.now());
        return instructionRepository.save(instruction);
    }

    @Transactional
    public WorkInstruction complete(Long id) {
        WorkInstruction instruction = instructionRepository.findById(id)
                .orElseThrow(() -> new BizException("作业指令不存在"));
        if ("COMPLETED".equals(instruction.getStatus())) throw new BizException("作业指令已完成");
        if ("SHIFT".equals(instruction.getInstructionType())) {
            yardService.relocate(new SlotActionRequest(instruction.getContainerNo(), instruction.getToYardZone(),
                    instruction.getToBay(), instruction.getToRow(), instruction.getToTier()));
        } else if ("PICKUP".equals(instruction.getInstructionType())) {
            yardService.pickup(instruction.getContainerNo());
        }
        instruction.setStatus("COMPLETED");
        instruction.setCompletedAt(LocalDateTime.now());
        return instructionRepository.save(instruction);
    }

    private void requireTarget(InstructionRequest request) {
        if (request.toYardZone() == null || request.toBay() == null || request.toRow() == null || request.toTier() == null) {
            throw new BizException("翻倒指令必须指定完整目标箱位");
        }
    }
}
