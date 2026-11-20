package io.smarthappy.module.production;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.LotDtos.LotRow;
import io.smarthappy.module.production.LotDtos.TraceRow;
import io.smarthappy.module.production.LotDtos.TraceView;
import io.smarthappy.module.production.LotReportDtos.LotReport;
import io.smarthappy.module.production.LotReportDtos.Section;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * LOT 역추적 보고서 (prd-lot-report, P2 7b, docs/25 4.10 — 06 M2 감사 대응). LOT 번호 하나로 출처(역추적)·행선(정추적)·
 * 생산 이력(실적·재작업 처리)·공정 조건(파라미터 실측)·완료판정·설비 상태 구간·입고를 한 번에. 원장을 읽기만 한다 —
 * 결과 캐시 없음, 절마다 행 상한 + 잘림 표시. 생산 이력의 대상은 이 LOT 과 역추적에 나온 생산 LOT(자재 LOT 은 출처 표에서 공급사 LOT 으로).
 */
@Service
@RequiredArgsConstructor
public class LotReportService {

    public static final String PROG = "prd-lot-report";
    static final int SECTION_LIMIT = 500;
    /** 생산 이력·조건을 모을 생산 LOT 의 최대 개수(역추적이 넓으면 앞에서부터) */
    static final int MAX_PRODUCED_LOTS = 200;

    private final LotService lots;
    private final LotReportMapper mapper;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public LotReport report(String lotNo) {
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        LotRow lot = lots.byNo(lotNo);
        TraceView back = lots.trace(lot.getLotId(), "BACK");
        TraceView fwd = lots.trace(lot.getLotId(), "FWD");
        Set<Long> produced = new LinkedHashSet<>();
        if (lot.getWoId() != null) {
            produced.add(lot.getLotId());
        }
        for (TraceRow r : back.rows()) {
            if ("PRODUCED".equals(r.getOrigin()) && produced.size() < MAX_PRODUCED_LOTS) {
                produced.add(r.getLotId());
            }
        }
        List<Long> lotIds = new ArrayList<>(produced);
        List<Long> procIds = lotIds.isEmpty() ? List.of() : mapper.procIds(lotIds);
        List<Long> woIds = lotIds.isEmpty() ? List.of() : mapper.woIds(lotIds);
        var rcv = section(n -> mapper.receipts(lot.getLotId(), n));
        String received = rcv.rows().stream().map(r -> new java.math.BigDecimal(r.getQty())).reduce(java.math.BigDecimal.ZERO,
                java.math.BigDecimal::add).stripTrailingZeros().toPlainString();
        return new LotReport(lot, received, back, fwd,
                lotIds.isEmpty() ? new Section<>(List.of(), false) : section(n -> mapper.production(lotIds, n)),
                procIds.isEmpty() ? new Section<>(List.of(), false) : section(n -> mapper.params(procIds, n)),
                woIds.isEmpty() ? new Section<>(List.of(), false) : section(n -> mapper.closes(woIds, n)),
                woIds.isEmpty() ? new Section<>(List.of(), false) : section(n -> mapper.states(woIds, n)),
                rcv, clock.now().toOffsetDateTime(), me.loginId(), clock.zone().getId());
    }

    /** 상한 + 1 로 읽어 잘림 판정 */
    private static <T> Section<T> section(IntFunction<List<T>> q) {
        List<T> rows = q.apply(SECTION_LIMIT + 1);
        boolean cut = rows.size() > SECTION_LIMIT;
        return new Section<>(cut ? rows.subList(0, SECTION_LIMIT) : rows, cut);
    }
}
