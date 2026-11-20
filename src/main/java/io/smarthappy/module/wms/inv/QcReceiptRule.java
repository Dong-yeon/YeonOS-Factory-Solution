package io.smarthappy.module.wms.inv;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 입고 상태 QC 고정 (docs/30 12.15.6) — 공장 스위치 {@code WMS_QUALITY_LINK} = Y 이고 LOT 관리 품목에 그 날 유효한 검사계획
 * (자재·구매 입고 = RECEIVE, 생산 입고 = FINAL)이 있으면 화면·위치 기본 상태 대신 QC. 판정이 풀어 준다(12.15.3).
 * 계획 조회는 QMS 읽기 계약 {@link InspPlanLookup}(예외 없음 — QMS 없는 설치본이면 빈 목록 → 그대로).
 * 이미 판정된 LOT 에 더 들어오는 몫(생산 부분 입고, B-2 — 12.15.13): 살아 있는 최신 판정이 NG 면 BLOCKED(판정을 이어받음),
 * OK 면 계획대로 QC(추가분은 재검사 — 판정은 그 검사 표본의 결론)
 */
@Component
@RequiredArgsConstructor
class QcReceiptRule {

    private final PolicyService policy;
    private final BizClock clock;
    private final ObjectProvider<InspPlanLookup> plans;
    private final QualityStockMapper judged;

    /** 들어갈 상태 — 새 LOT(자재·구매 입고)·화면 안내용. 조건이 맞으면 QC, 아니면 requested 그대로 */
    String status(String plantCd, long itemId, String lotCtrlYn, String inspType, String requested) {
        return status(plantCd, itemId, null, lotCtrlYn, inspType, requested);
    }

    /** 들어갈 상태 — 이미 있는 LOT 에 더 들어오면(lotId) 그 LOT 의 살아 있는 최신 판정이 NG 일 때 BLOCKED */
    String status(String plantCd, long itemId, Long lotId, String lotCtrlYn, String inspType, String requested) {
        if (!"Y".equals(lotCtrlYn) || !"Y".equals(policy.value(QualityStockPosting.POLICY, plantCd))) {
            return requested;
        }
        if (lotId != null && "NG".equals(judged.latestResult(lotId))) {
            return "BLOCKED";
        }
        return InspPlanLookup.of(plans).lines(itemId, null, inspType, clock.today()).isEmpty() ? requested : "QC";
    }
}
