package io.smarthappy.module.qms.link;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 4M 변경 세부 조회 계약 (docs/29_P3 20장 B안, 사용자 결정 Q-r) — "어떤 ECO 가 그 품목에 승인·적용됐나" 는 PLM 계약
 * ({@code EcoLookup.releasedFor·approvedFor})이 답하고, 다른 모듈(PMS APQP)은 그 ECO id 로 여기서 4M 세부만 읽는다.
 * QMS 는 PLM 승인 규칙을 다시 구현하지 않는다(원본 한 곳).
 * 읽기만·잠금 없음·**예외 없음**(null·빈 목록·없는/다른 회사 ECO·4M 세부 없는 ECO 는 빠짐 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다).
 * QMS 가 없는 설치본은 {@link #NONE}.
 */
public interface ChangeLookup {

    /** 한 번에 받는 ECO id 상한 — 넘으면 작은 id 부터 이만큼만 */
    int MAX_IDS = 500;

    /**
     * ECO 하나의 4M 세부.
     * m4Types = MAN·MACHINE·MATERIAL·METHOD·MEASURE·ENV·SUPPLIER·SITE(저장 순서), charIds·inspPlanIds = 영향 특성·검사계획(행 번호 순)
     */
    record ChangeDetail(long ecoId, String ecoNo, List<String> m4Types, String beforeDesc, String afterDesc, String reasonDesc,
            String impactDesc, String revertCond, List<Long> charIds, List<Long> inspPlanIds) {
    }

    /** 조회 없음 — QMS 모듈이 없는 설치본(빈 없음) */
    ChangeLookup NONE = ecoIds -> List.of();

    /** ECO id 들의 4M 세부, ECO id 순. 세부가 없는 id 는 빠진다(결과 수 ≤ 받은 수) */
    List<ChangeDetail> details(Collection<Long> ecoIds);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ChangeLookup of(ObjectProvider<ChangeLookup> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
