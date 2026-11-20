package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.module.production.SerialDtos.CompareLine;
import io.smarthappy.module.production.SerialDtos.Installed;
import io.smarthappy.module.production.SerialDtos.SerialRow;
import io.smarthappy.module.production.link.CloseNotice;

/**
 * 공정 완료판정 때 장비 시리얼 부품 미장착 경고 (docs/77 5.3) — **막지 않는다**(사용자 결정 2026-11-16: 경고만).
 *
 * - 그 지시에 시리얼이 없으면 아무 말 없음(시리얼 관리 지시가 아님)
 * - 판정 공정의 op_seq 에서 장착할 BOM 묶음 + (마지막 공정이면) 공정 없는 묶음 → 덜 장착된(MISSING·SHORT) 시리얼이 있으면 SERIAL_PART_SHORT
 * - 마지막 공정에서 시리얼 수 &lt; 계획 수량이면 SERIAL_COUNT_SHORT
 * - 읽기만·잠금 없음(CloseNotice 규칙), 대비 계산은 화면 BOM 대비와 같은 함수(SerialCompare)
 */
@Component
@Order(200)
public class SerialPartNotice implements CloseNotice {

    static final String PART_SHORT = "SERIAL_PART_SHORT";
    static final String COUNT_SHORT = "SERIAL_COUNT_SHORT";

    private final SerialMapper mapper;
    private final SerialService service;

    public SerialPartNotice(SerialMapper mapper, SerialService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public List<Notice> notices(CloseContext ctx) {
        List<SerialRow> serials = mapper.findSerialsOfWo(ctx.woId());
        if (serials.isEmpty()) {
            return List.of();
        }
        Integer op = mapper.opSeqOf(ctx.woProcId());
        Integer lastOp = mapper.lastOpSeq(ctx.woId());
        boolean last = op != null && op.equals(lastOp);
        BigDecimal plan = new BigDecimal(serials.get(0).getPlanQty());
        Map<Long, List<Installed>> bySerial = new LinkedHashMap<>();
        serials.forEach(s -> bySerial.put(s.getSerialId(), new ArrayList<>()));
        mapper.installed(null, ctx.woId()).forEach(i -> bySerial.computeIfAbsent(i.getSerialId(), k -> new ArrayList<>()).add(i));
        List<Notice> out = new ArrayList<>();
        int shortSerials = 0;
        String firstSerial = null;
        String firstItem = null;
        Long firstId = null;
        for (SerialRow s : serials) {
            List<CompareLine> lines = service.compareLines(ctx.woId(), plan, bySerial.get(s.getSerialId()));
            CompareLine miss = lines.stream()
                    .filter(c -> SerialCompare.isShort(c) && (Objects.equals(c.opSeq(), op) || (last && c.opSeq() == null)))
                    .findFirst().orElse(null);
            if (miss != null) {
                shortSerials++;
                if (firstSerial == null) {
                    firstSerial = s.getSerialNo();
                    firstItem = miss.itemCd();
                    firstId = s.getUnitLotId();
                }
            }
        }
        if (shortSerials > 0) {
            out.add(new Notice(PART_SHORT, "prd.close.serialPartShort", List.of(shortSerials, firstSerial, firstItem), "LOT", firstId));
        }
        if (last && serials.size() < plan.intValue()) {
            out.add(new Notice(COUNT_SHORT, "prd.close.serialCountShort", List.of(serials.size(), plan.toPlainString()), null, null));
        }
        return out;
    }
}
