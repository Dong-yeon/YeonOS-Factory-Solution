package io.smarthappy.module.plm.link;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 변경 표시 계약 (docs/39 12.4) — 새 LOT 을 만든 MES 트랜잭션 안에서 부른다(5b 실적 확정, 7a 재작업·수리, 7b LOT 단위 재작업).
 * 이 LOT 에 걸리는 열린 설계 변경·4M 표시가 있으면 기록하고 그 ECO id 를 돌려준다 — MES 는 그 값을 투입 계보 `change_id` 에 적는다.
 *
 * - 표시일 뿐 게이트가 아니다: 없으면 빈 값, 예외로 실적을 막지 않는다
 * - "첫 N" 은 확정(도착) 순 — 오프라인 재전송이 늦게 와도 도착한 순서로 센다
 * - 잠금 순서는 MES 행(prd_wo_proc·lot_master) → ECO 행. PLM 쪽 ECO 상태 변경은 MES 표를 잠그지 않는다(교착 방지)
 * - MES 는 {@link #of(ObjectProvider)} 로 받는다 — PLM 이 없는 설치본은 {@link #NONE}
 */
public interface ChangeMarker {

    /** 표시 없음 — PLM 모듈이 없는 설치본(빈 없음). PLM 이 있으면 {@code EcoChangeMarker}(3b) */
    ChangeMarker NONE = (lotId, woId, equipId, occurredAt) -> Optional.empty();

    /**
     * @param lotId      새로 만든 LOT
     * @param woId       지시(LOT 단위 재작업처럼 지시가 없으면 null)
     * @param equipId    설비(모르면 null)
     * @param occurredAt 현장 발생 시각(표시 구간 판정용 — 순서 판정은 도착 순)
     * @return 표시한 ECO id, 없으면 빈 값
     */
    Optional<Long> mark(long lotId, Long woId, Long equipId, OffsetDateTime occurredAt);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ChangeMarker of(ObjectProvider<ChangeMarker> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
