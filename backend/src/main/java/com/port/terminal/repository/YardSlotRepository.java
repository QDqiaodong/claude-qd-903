package com.port.terminal.repository;

import com.port.terminal.domain.SlotKey;
import com.port.terminal.domain.YardSlot;
import com.port.terminal.dto.ZoneOccupancyDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface YardSlotRepository extends JpaRepository<YardSlot, SlotKey> {
    List<YardSlot> findByIdYardZoneAndIdBayBetweenOrderByIdBayAscIdRowAscIdTierAsc(
            String yardZone, Integer bayStart, Integer bayEnd);

    long countByIdYardZoneAndIdBayAndStatus(String yardZone, Integer bay, String status);

    long countByIdTierAndStatus(Integer tier, String status);

    @Query("select s from YardSlot s where s.id.yardZone = :z and s.id.bay = :b order by s.id.tier desc, s.id.row")
    List<YardSlot> findBayMatrix(@Param("z") String yardZone, @Param("b") Integer bay);

    @Query("select s from YardSlot s where s.id.yardZone = :z and s.id.bay = :b and s.id.row = :r and s.id.tier = :t")
    Optional<YardSlot> findExactSlot(@Param("z") String yardZone, @Param("b") Integer bay,
                                     @Param("r") Integer row, @Param("t") Integer tier);

    @Query("""
            select new com.port.terminal.dto.ZoneOccupancyDTO(
              s.id.yardZone, count(s),
              sum(case when s.status = 'OCCUPIED' then 1 else 0 end),
              sum(case when s.status = 'RESERVED' then 1 else 0 end),
              sum(case when s.status = 'EMPTY' then 1 else 0 end))
            from YardSlot s group by s.id.yardZone order by s.id.yardZone
            """)
    List<ZoneOccupancyDTO> summarizeOccupancy();
}
