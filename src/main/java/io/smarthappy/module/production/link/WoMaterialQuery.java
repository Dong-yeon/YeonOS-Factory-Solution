package io.smarthappy.module.production.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 지시별 남은 자재 소요 조회 계약 (docs/25 4.18) — APS 가 지시의 자재 필요량을 읽을 때. MES 표를 직접 조회하지 않는다.
 *
 * - 발행·진행 중 지시 = BOM 스냅샷(prd_wo_bom) 소요 − 이 지시의 LOT 투입 순수량(역분개 반영). LOT 아닌 자재는 투입 기록이 없어 전량 남은 소요(보수적)
 * - 계획 지시 = 발행 때와 같은 전개(계획 시작일 기준). 전개가 안 되면(환산·가상 조립 BOM 없음) {@code exploded = false}, 줄 없음
 * - 완료·마감·취소 지시, 없는·다른 회사 id 는 빠진다(RLS). 순서 = wo_id, 중복 id 는 한 번
 * - 새 지시 가정(APS CTP, docs/38 15장): {@link #routing}·{@link #explode} — 지시 생성·계획 지시와 같은 고르기·전개, 지시 id 없이
 * MES 가 없는 설치본은 {@link #NONE}(새 지시 가정은 라우팅 없음).
 */
public interface WoMaterialQuery {

    /** 한 번에 받을 수 있는 지시 수 — 넘으면 IllegalArgumentException */
    int MAX_IDS = 500;

    /** 남은 소요 한 줄 — (품목, 투입 공정 순번) 단위 합계, 수량은 자품목 기본 단위, 0 인 줄은 빠진다. opSeq null = 공정 지정 없음 */
    record Need(long itemId, Integer opSeq, BigDecimal remainingQty, String unitCd) {
    }

    /** status = PLANNED·RELEASED·IN_PROGRESS */
    record WoNeeds(long woId, String status, boolean exploded, List<Need> needs) {
    }

    /** 조회 없음 — MES 모듈이 없는 설치본(빈 없음) */
    WoMaterialQuery NONE = woIds -> List.of();

    List<WoNeeds> remaining(Collection<Long> woIds);

    /**
     * 그 날 새 지시를 만들면 쓸 확정 라우팅 id — 지시 생성과 같은 고르기(공장·품목, 그 날 유효한 RELEASED, 대안 번호 순 첫째).
     * 없으면(다른 회사 품목 포함 — RLS) null
     */
    default Long routing(long itemId, String plantCd, LocalDate on) {
        return null;
    }

    /**
     * 그 날 새 지시(수량 qty)의 자재 소요 — 계획 지시와 같은 전개(그 날 유효 BOM 첫째). woId = 0, status = PLANNED.
     * BOM 없음 = exploded true·줄 없음, 전개 실패(환산·가상 조립 BOM 없음) = exploded false
     */
    default WoNeeds explode(long itemId, BigDecimal qty, LocalDate on) {
        return new WoNeeds(0, "PLANNED", true, List.of());
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static WoMaterialQuery of(ObjectProvider<WoMaterialQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
