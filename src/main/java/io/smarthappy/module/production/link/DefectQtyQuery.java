package io.smarthappy.module.production.link;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 월별 불량 수량 조회 계약 (docs/25 4.48 — 사용자 결정 QMS 보류 4 COPQ 의 MES 몫). QMS 가 품질 비용(COPQ)을 셀 때 MES 표를 직접 조회하지 않는다.
 *
 * - 원천: 실적 불량(prd_result_defect — 종류 SCRAP·REWORK·REPAIR, source RESULT) + 재작업/수리 처리의 폐기(prd_rework.scrap_qty — kind SCRAP, source REWORK)
 * - **정정은 정정한 달에 음수로**(사용자 결정 C-1 (라), 2026-10-30): 원래 실적은 자기 달에 그대로 남고, 역분개 행(발생 시각 = 정정 시각)은 정정한 달에
 *   음수로 세며, 다시 넣은 실적은 넣은 달에 센다 — 지난 달 값이 나중에 조용히 바뀌지 않는다. 같은 달 안 정정은 상쇄되어 0(줄 없음).
 *   그래서 qty 는 **음수일 수 있다**(그 달에 정정만 있고 새 값이 더 작을 때)
 * - 월 = 회사 시간대로 본 발생 시각(occurred_at)의 연월. from·to 포함, from &gt; to 이거나 {@link #MAX_MONTHS} 개월을 넘으면 IllegalArgumentException
 * - plantCd null = 전체 공장, 지시의 공장으로 거른다. 품목 = 지시 품목, 수량·단위 = 실적(처리) 단위 그대로(환산 없음)
 * - 줄 = (월, 품목, 종류, 단위, 원천) 합계, 0 인 줄 없음. 순서 = 월 → 품목 → 원천 → 종류 → 단위
 * - 부르는 쪽 트랜잭션 안(없으면 읽기 전용으로 연다), 잠금 없음. 다른 회사 행은 RLS 로 보이지 않는다
 * MES 가 없는 설치본은 {@link #NONE}(빈 결과).
 */
public interface DefectQtyQuery {

    /** 한 번에 물을 수 있는 개월 수 */
    int MAX_MONTHS = 36;

    String RESULT = "RESULT";
    String REWORK = "REWORK";

    /** kind = SCRAP·REWORK·REPAIR, source = RESULT(실적 불량)·REWORK(재작업/수리 처리의 폐기) */
    record DefectQty(YearMonth month, long itemId, String kind, BigDecimal qty, String unitCd, String source) {
    }

    /** 조회 없음 — MES 모듈이 없는 설치본(빈 없음) */
    DefectQtyQuery NONE = (plantCd, from, to) -> List.of();

    List<DefectQty> monthly(String plantCd, YearMonth from, YearMonth to);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static DefectQtyQuery of(ObjectProvider<DefectQtyQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
