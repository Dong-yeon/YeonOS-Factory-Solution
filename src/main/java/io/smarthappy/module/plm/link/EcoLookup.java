package io.smarthappy.module.plm.link;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * ECO 조회 계약 (docs/29_P3 11.3 ①·12.1 Q5-5, docs/39 16장·16.2) — QMS 4M·초품이 ECO 머리·대상을 읽을 때. PLM 표를 직접 조회하지 않는다.
 * 다른 회사·없는 ECO 는 빈 값(RLS). PLM 이 없는 설치본은 {@link #NONE}.
 */
public interface EcoLookup {

    /**
     * ECO 요약 — 대상 id 는 유형별(BOM·ROUTING 리비전은 이 계약에 넣지 않는다: 리비전 경로는 {@link EcoTrace}).
     * changeKind = DESIGN·PROCESS_4M·TEMP, status = DRAFT·PENDING·APPROVED·RELEASED·CANCELLED,
     * markLots null = 회사 정책, markUntil = 4M·임시 표시 끝 날짜(포함),
     * approvedOn = 결재 승인일·releasedOn = 적용일(회사 날짜, 없으면 null — docs/39 24장 R7)
     */
    record EcoView(long ecoId, String ecoNo, String title, String changeKind, String status, LocalDate effectiveDate,
            Integer markLots, LocalDate markUntil, String custApprovalStatus, List<Long> itemIds, List<Long> equipIds,
            List<Long> procIds, List<Long> docRevIds, LocalDate approvedOn, LocalDate releasedOn) {
    }

    /** 조회 없음 — PLM 모듈이 없는 설치본(빈 없음) */
    EcoLookup NONE = ecoId -> Optional.empty();

    Optional<EcoView> eco(long ecoId);

    /** 한 번에 받을 수 있는 ECO 수 — 넘으면 IllegalArgumentException(목록 화면은 페이지 단위로 부른다) */
    int MAX_IDS = 500;

    /** ECO 번호로(대소문자 무시) — QMS "ECO 번호로 열기". 다른 회사·없는 번호 = 빈 값 */
    default Optional<EcoView> byNo(String ecoNo) {
        return Optional.empty();
    }

    /**
     * 여러 ECO 한 번에(N+1 방지) — 없는·다른 회사 id 는 빠진다, 순서 = eco_id. 중복 id 는 한 번.
     * {@link #MAX_IDS} 초과는 IllegalArgumentException
     */
    default List<EcoView> ecos(Collection<Long> ecoIds) {
        return List.of();
    }

    /** ECO 의 PPAP 패키지 요약 — status = DRAFT·SUBMITTED·APPROVED·INTERIM·REJECTED, decidedOn = 고객 판정일(판정 전 null) */
    record PpapView(long ppapId, String status, LocalDate decidedOn) {
    }

    /**
     * ECO 의 PPAP 패키지(ECO 하나에 하나, docs/39 14.4) — PMS PM7 "프로젝트 ECO 의 PPAP 상태"(docs/39 16.4).
     * 없음·다른 회사·없는 ECO 는 빈 값. 읽기만·잠금 없음. 기본 구현(PLM 없음)은 빈 값
     */
    default Optional<PpapView> ppap(long ecoId) {
        return Optional.empty();
    }

    /** 품목별 ECO 목록 — 상한 {@link #MAX_IDS} 을 넘으면 앞쪽만 + truncated (docs/39 24장 R5) */
    record EcoPage(List<EcoView> rows, boolean truncated) {
        public static final EcoPage EMPTY = new EcoPage(List.of(), false);
    }

    /**
     * 그 품목에 since(회사 날짜, 포함) 이후 **적용(RELEASED)** 된 ECO — PMS APQP A3 (docs/39 24장).
     * 품목 범위 = {@link EcoView#itemIds()} 정의(대상 행의 품목, 역전개 없음). 순서 = 적용 시각·eco_id.
     * 읽기만·잠금 없음·예외 없음: 없는·다른 회사 품목·since null = 빈 페이지. 기본 구현(PLM 없음)은 빈 페이지
     */
    default EcoPage releasedFor(long itemId, LocalDate since) {
        return EcoPage.EMPTY;
    }

    /**
     * 그 품목에 since 이후 **결재 승인** 된 ECO(지금 APPROVED 또는 RELEASED — 승인 뒤 취소·반려 제외), 순서 = 승인 시각·eco_id.
     * QMS ChangeLookup 은 이 목록에 4M 세부만 붙인다(docs/29_P3 20장 Q-r B안). 그 밖 규칙은 {@link #releasedFor}
     */
    default EcoPage approvedFor(long itemId, LocalDate since) {
        return EcoPage.EMPTY;
    }

    /**
     * ECO 의 고객 칸(plm_eco.cust_id) — QMS 4M 고객 통지 판단(docs/29_P3 52장 CN-3·CN-8). 없는·다른 회사 ECO·고객 칸이 빈 ECO = 빈 값.
     * 읽기만·잠금 없음. 기본 구현(PLM 없음)은 빈 값
     */
    default Optional<Long> custId(long ecoId) {
        return Optional.empty();
    }

    /** ECO 의 고객 통지(PCN) 한 건 — status = SENT·ACK·APPROVED·REJECTED, respondedOn = 응답 전 null (docs/39 14.5) */
    record PcnView(long pcnId, String pcnNo, long custId, LocalDate notifiedOn, LocalDate responseDue, String status,
            LocalDate respondedOn) {
    }

    /**
     * ECO 의 PCN 목록(통지일·id 순) — QMS 4M 고객 통지 판단(docs/29_P3 52장 CN-8). 다른 회사·없는 ECO = 빈 목록.
     * 읽기만·잠금 없음. 기본 구현(PLM 없음)은 빈 목록
     */
    default List<PcnView> pcns(long ecoId) {
        return List.of();
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static EcoLookup of(ObjectProvider<EcoLookup> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
