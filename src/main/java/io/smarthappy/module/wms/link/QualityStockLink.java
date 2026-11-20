package io.smarthappy.module.wms.link;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 판정·무효·NCR 종결 → 재고 상태 계약 (docs/30 12.15, P4 2부 B3 — QMS 검토 docs/29_P3 21장). QMS 가 **같은 트랜잭션** 안에서 부른다.
 * QMS 는 id 만 넘기고 WMS 가 QMS 표(`qms_insp`·`qms_ncr`·`qms_ncr_disp`)를 읽기만 한다:
 * <ul>
 * <li>판정(RECEIVE·FINAL 만): 그 LOT 의 QC 재고 전부를 같은 위치에서 OK → AVAILABLE, NG → BLOCKED({@code STATUS_OUT/IN} 짝)</li>
 * <li>무효: 그 판정의 원장 짝을 되돌린다. 처분이 반영된 LOT 이면 409 {@code WMS_QUALITY_DISPOSED}, 재고가 모자라면 409 {@code INSUFFICIENT_STOCK}</li>
 * <li>NCR 종결: 처분 수량 — 특채(USE_AS_IS) BLOCKED → AVAILABLE, 폐기(SCRAP) 원장 {@code SCRAP}. 그 밖 처분은 BLOCKED 그대로.
 *     BLOCKED 가 모자라면 409 {@code WMS_QUALITY_SHORT}, NCR 단위 ≠ 품목 기본 단위면 409 {@code WMS_QUALITY_UNIT}</li>
 * </ul>
 * 규칙:
 * <ul>
 * <li>공장 정책 {@code WMS_QUALITY_LINK} = N(기본)인 공장의 재고는 건드리지 않는다. LOT 비관리 품목은 반영하지 않는다(로그만, D-B27)</li>
 * <li>409 는 칸 오류가 없는 {@code BusinessException} — QMS 는 그대로 던진다(판정·무효·종결이 같이 거부, 21.1 WB-2)</li>
 * <li>잠금: QMS 가 잡은 qms_insp/qms_ncr 뒤에 lot_master(공유) → 기간 → inv_stock 칸 순서. WMS 는 QMS 표를 잠그지 않는다(12.15.7)</li>
 * <li>WMS 가 없는 설치본은 {@link #NONE}</li>
 * </ul>
 */
public interface QualityStockLink {

    /** 반영 없음 — WMS 모듈이 없는 설치본(빈 없음) */
    QualityStockLink NONE = new QualityStockLink() {
        @Override
        public void onInspJudged(long inspId) {
            // WMS 없음
        }

        @Override
        public void onInspVoided(long inspId) {
            // WMS 없음
        }

        @Override
        public void onNcrClosed(long ncrId) {
            // WMS 없음
        }
    };

    /** 검사 판정 확정 직후(InspService.judge — qms_insp 가 JUDGED 로 바뀐 뒤) */
    void onInspJudged(long inspId);

    /** 확정 검사 무효 직후(InspService.voidInsp) — 판정이 바꾼 재고 상태를 되돌림. 판정 전 검사면 아무것도 안 함 */
    void onInspVoided(long inspId);

    /** NCR 종결 직후(NcrService.close · 특채 결재 승인 NcrApprovalDocType) — 처분 수량 반영 */
    void onNcrClosed(long ncrId);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static QualityStockLink of(ObjectProvider<QualityStockLink> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
