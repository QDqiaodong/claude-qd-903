package com.port.terminal.service;

import com.port.terminal.common.BizException;
import com.port.terminal.common.Dict;
import com.port.terminal.domain.Container;
import com.port.terminal.domain.SlotKey;
import com.port.terminal.domain.YardSlot;
import com.port.terminal.domain.YardZone;
import com.port.terminal.dto.SlotActionRequest;
import com.port.terminal.dto.ZoneOccupancyDTO;
import com.port.terminal.repository.ContainerRepository;
import com.port.terminal.repository.YardSlotRepository;
import com.port.terminal.repository.YardZoneRepository;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class YardService {
    private final YardZoneRepository zoneRepository;
    private final YardSlotRepository slotRepository;
    private final ContainerRepository containerRepository;

    public YardService(YardZoneRepository zoneRepository, YardSlotRepository slotRepository,
                       ContainerRepository containerRepository) {
        this.zoneRepository = zoneRepository;
        this.slotRepository = slotRepository;
        this.containerRepository = containerRepository;
    }

    public List<YardZone> zones() {
        return zoneRepository.findAll();
    }

    public List<YardSlot> slots(String zone, Integer bayStart, Integer bayEnd) {
        return slotRepository.findByIdYardZoneAndIdBayBetweenOrderByIdBayAscIdRowAscIdTierAsc(zone, bayStart, bayEnd);
    }

    public List<YardSlot> bayMatrix(String zone, Integer bay) {
        return slotRepository.findBayMatrix(zone, bay);
    }

    public YardSlot exactSlot(String zone, Integer bay, Integer row, Integer tier) {
        return slotRepository.findExactSlot(zone, bay, row, tier)
                .orElseThrow(() -> new BizException("箱位 " + format(zone, bay, row, tier) + " 不存在"));
    }

    public List<ZoneOccupancyDTO> occupancy() {
        return slotRepository.summarizeOccupancy();
    }

    public Map<String, Object> bayStats(String zone, Integer bay) {
        List<YardSlot> matrix = slotRepository.findBayMatrix(zone, bay);
        if (matrix.isEmpty()) throw new BizException("指定贝位不存在");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("yardZone", zone);
        result.put("bay", bay);
        result.put("total", matrix.size());
        result.put("occupied", slotRepository.countByIdYardZoneAndIdBayAndStatus(zone, bay, "OCCUPIED"));
        result.put("empty", slotRepository.countByIdYardZoneAndIdBayAndStatus(zone, bay, "EMPTY"));
        return result;
    }

    public Map<String, Object> tierStats(Integer tier) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tier", tier);
        result.put("empty", slotRepository.countByIdTierAndStatus(tier, "EMPTY"));
        result.put("occupied", slotRepository.countByIdTierAndStatus(tier, "OCCUPIED"));
        return result;
    }

    @Transactional
    public Container place(SlotActionRequest request) {
        Container container = requireContainer(request.containerNo());
        if (container.getExitTime() != null) throw new BizException("箱号 " + request.containerNo() + " 已出场");
        if (container.getCurrentYardZone() != null) {
            throw new BizException("箱号 " + request.containerNo() + " 已在 "
                    + format(container.getCurrentYardZone(), container.getCurrentBay(), container.getCurrentRow(), container.getCurrentTier()));
        }
        occupy(container, request);
        return containerRepository.save(container);
    }

    @Transactional
    public Container relocate(SlotActionRequest request) {
        Container container = requireContainer(request.containerNo());
        if (container.getCurrentYardZone() == null) throw new BizException("该箱尚未落位，不能执行翻倒");
        List<YardSlot> targets = validateTargets(container, request, true);
        freeCurrent(container);
        applyOccupancy(container, request, targets);
        return containerRepository.save(container);
    }

    @Transactional
    public Container pickup(String containerNo) {
        Container container = requireContainer(containerNo);
        if (!"AVAILABLE".equals(container.getPickupStatus())) {
            throw new BizException("箱号 " + containerNo + " 当前不是可提状态");
        }
        if (!Boolean.TRUE.equals(container.getPaidInspection())) {
            throw new BizException("箱号 " + containerNo + " 尚未完成付费查验标记");
        }
        freeCurrent(container);
        container.setExitTime(java.time.LocalDateTime.now());
        container.setPickupStatus("OUT");
        container.setCurrentYardZone(null);
        container.setCurrentBay(null);
        container.setCurrentRow(null);
        container.setCurrentTier(null);
        return containerRepository.save(container);
    }

    private void occupy(Container container, SlotActionRequest request) {
        List<YardSlot> targets = validateTargets(container, request, false);
        applyOccupancy(container, request, targets);
    }

    private List<YardSlot> validateTargets(Container container, SlotActionRequest request, boolean flip) {
        YardZone zone = zoneRepository.findByZoneCode(request.yardZone())
                .orElseThrow(() -> new BizException("箱区 " + request.yardZone() + " 不存在"));
        if (request.bay() > zone.getBayCount() || request.row() > zone.getRowCount() || request.tier() > zone.getTierCount()) {
            throw new BizException("目标箱位超出箱区范围");
        }
        if (Dict.isReefer(container.getType()) && !Boolean.TRUE.equals(zone.getPowerOutlet())) {
            throw new BizException("冷藏箱必须放在有电源插座的箱区");
        }
        if (Boolean.TRUE.equals(container.getDangerous()) && Dict.NORMAL_HEAVY_ZONE.equals(zone.getZoneType())) {
            throw new BizException("危险品箱不得放在普通重箱区");
        }
        String limit = zone.getContainerTypeLimit();
        if (limit != null && !"ALL".equals(limit) && !List.of(limit.split(",")).contains(container.getType())) {
            throw new BizException("箱型 " + container.getType() + " 不符合 " + zone.getZoneCode() + " 区限制 " + limit);
        }

        YardSlot first = exactSlot(request.yardZone(), request.bay(), request.row(), request.tier());
        requireEmpty(first, flip);
        if (!Dict.isFortyFoot(container.getType())) return List.of(first);

        int adjacentRow = request.row() + 1;
        if (adjacentRow > zone.getRowCount()) {
            throw new BizException("40 尺箱必须占用同一贝位内相邻两个 20 尺箱位");
        }
        YardSlot second = exactSlot(request.yardZone(), request.bay(), adjacentRow, request.tier());
        requireEmpty(second, flip);
        return List.of(first, second);
    }

    private void requireEmpty(YardSlot slot, boolean flip) {
        if (!"EMPTY".equals(slot.getStatus())) {
            if ("OCCUPIED".equals(slot.getStatus())) {
                throw new BizException((flip ? "翻倒指令的目标箱位必须为空；" : "箱位已占用；")
                        + "当前占用箱号：" + slot.getOccupiedContainerNo());
            }
            throw new BizException((flip ? "翻倒指令的目标箱位必须为空；" : "目标箱位不可用；")
                    + "当前状态：" + slot.getStatus());
        }
    }

    private void applyOccupancy(Container container, SlotActionRequest request, List<YardSlot> targets) {
        for (YardSlot slot : targets) {
            slot.setStatus("OCCUPIED");
            slot.setOccupiedContainerNo(container.getContainerNo());
        }
        slotRepository.saveAll(targets);
        container.setCurrentYardZone(request.yardZone());
        container.setCurrentBay(request.bay());
        container.setCurrentRow(request.row());
        container.setCurrentTier(request.tier());
    }

    private void freeCurrent(Container container) {
        if (container.getCurrentYardZone() == null) return;
        List<YardSlot> current = slotRepository.findByIdYardZoneAndIdBayBetweenOrderByIdBayAscIdRowAscIdTierAsc(
                container.getCurrentYardZone(), container.getCurrentBay(), container.getCurrentBay()).stream()
                .filter(slot -> container.getContainerNo().equals(slot.getOccupiedContainerNo()))
                .toList();
        current.forEach(slot -> {
            slot.setStatus("EMPTY");
            slot.setOccupiedContainerNo(null);
        });
        slotRepository.saveAll(current);
    }

    private Container requireContainer(String containerNo) {
        return containerRepository.findByContainerNo(containerNo)
                .orElseThrow(() -> new BizException("箱号 " + containerNo + " 不存在"));
    }

    private String format(String zone, Integer bay, Integer row, Integer tier) {
        return zone + "-" + String.format("%02d-%02d-%d", bay, row, tier);
    }
}
