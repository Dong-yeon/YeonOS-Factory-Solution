package io.smarthappy.module.plm.link;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * LOT → 설계 변경 조회 계약 (docs/39 12.4) — LOT 추적·출하 게이트·감사 보고서가 쓴다. PLM 표를 직접 조회하지 않는다.
 * 결과 = (가) 리비전 경로(LOT → 지시 → 스냅샷 BOM·라우팅 리비전 → 그 리비전을 확정한 ECO) + (나) 변경 표시, ECO 하나에 한 줄.
 */
public interface EcoTrace {

    /** 연결 근거 — REVISION = (가), MARK = (나) */
    enum Via {
        REVISION, MARK
    }

    /** ECO 한 줄 — 고객 승인 상태는 출하 게이트가 본다(NA·PENDING·APPROVED·REJECTED) */
    record EcoRef(long ecoId, String ecoNo, String title, LocalDate effectiveDate, String custApprovalStatus, Via via) {
    }

    /** 조회 없음 — PLM 모듈이 없는 설치본(빈 없음). PLM 이 있으면 {@code EcoLotTrace}(3b) */
    EcoTrace NONE = lotId -> List.of();

    /** 이 LOT 에 걸린 ECO 목록(다른 회사 LOT 이면 빈 목록 — RLS) */
    List<EcoRef> ecosOfLot(long lotId);

    /** 표시된 LOT 한 줄 (plm_eco_lot) — reason = FIRST_LOT·4M_WINDOW·TEMP, seqNo = 그 ECO 안의 도착 순 */
    record MarkedLot(long lotId, String lotNo, String reason, int seqNo, OffsetDateTime occurredAt) {
    }

    /**
     * 이 ECO 가 표시한 LOT 목록, 도착 순(seq_no) — QMS 초품 입력 목록용(docs/29_P3 11.3 ②). 이유 필터는 쓰는 쪽에서.
     * 다른 회사·없는 ECO 면 빈 목록. 기본 구현(PLM 없음)은 빈 목록
     */
    default List<MarkedLot> markedLots(long ecoId) {
        return List.of();
    }

    /** 품목의 첫 LOT 표시 한 줄 — 어느 ECO 의 몇 번째 첫 LOT 인가 */
    record FirstLot(long ecoId, String ecoNo, long lotId, String lotNo, int seqNo, OffsetDateTime occurredAt) {
    }

    /** {@link #firstLotsOfItem} 상한 — {@link EcoLookup#MAX_IDS} 와 같은 관례 */
    int MAX_FIRST_LOTS = 500;

    /**
     * 이 품목 LOT 에 붙은 FIRST_LOT 표시(적용된 ECO 만), 최근 표시부터 limit 개 — QMS 착수 게이트 초품 미판정 경고 후보
     * (docs/29_P3 12.9 R-1). 읽기만·잠금 없음이라 착수 트랜잭션(공정 행 잠금 뒤)에서 불러도 된다.
     * 다른 회사 품목·없는 품목이면 빈 목록. limit 이 1~{@link #MAX_FIRST_LOTS} 밖이면 IllegalArgumentException
     */
    default List<FirstLot> firstLotsOfItem(long itemId, int limit) {
        return List.of();
    }

    /**
     * 출하 확인 한 줄 — 이 LOT 에 걸린(리비전 경로 + 표시) 적용 ECO 의 PPAP 패키지가 아직 고객 승인(APPROVED)이 아니다.
     * reason = PPAP_OPEN(작성 중·제출·거절 → block) · PPAP_INTERIM(임시 승인 → 경고만, block = false). ppapStatus = 패키지 상태 그대로
     */
    record ShipCheck(long lotId, long ecoId, String ecoNo, String reason, String ppapStatus, boolean block) {
    }

    /**
     * 출하하려는 LOT 들의 변경 확인 — WMS 출하 게이트용(docs/39 17장, docs/02 2.3 "고객 승인 전 변경 LOT 출하 금지").
     * ECO 고객 승인 자체는 적용 게이트(ck_plm_eco_cust_gate)가 이미 보장하므로 여기서는 PPAP 판정만 본다. 문제 없는 LOT 은 결과에 없다.
     * 읽기만·잠금 없음(출하 트랜잭션의 lot_master 잠금 뒤에 불러도 된다). 없는·다른 회사 LOT·null·중복은 빠짐, 순서 lot_id·eco_id.
     * {@link EcoLookup#MAX_IDS} 초과는 IllegalArgumentException. 기본 구현(PLM 없음)은 빈 목록
     */
    default List<ShipCheck> shipChecks(Collection<Long> lotIds) {
        return List.of();
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static EcoTrace of(ObjectProvider<EcoTrace> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
