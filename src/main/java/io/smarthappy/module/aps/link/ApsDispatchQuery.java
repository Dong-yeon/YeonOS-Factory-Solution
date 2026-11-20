package io.smarthappy.module.aps.link;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 일일 작업 배정 읽기 계약 (docs/38 18.4, docs/63 G5·AP-10 ⓐ) — POP·출력이 APS 의 설비별 순서·계획 시각을 읽을 때. APS 표를 직접 조회하지 않는다.
 *
 * - 기준 = 공장의 <b>현행 계획</b>(마지막으로 반영된 계획). 없으면 빈 결과
 * - 순서·시각은 계산 때 값(반영은 지시 날짜·설비만 옮긴다 — 시각 칸은 MES 에 없음). 반영 뒤 사람이 지시를 바꾸면 다음 계산·반영까지 다를 수 있다
 * - 트랜잭션 안에서 부른다(읽기 전용). 다른 회사·공장은 안 나온다(RLS + 공장 조건)
 * - 순서 어김은 막지 않는다(AP-10 — 표시만)
 * APS 가 없는 설치본은 {@link #NONE}.
 */
public interface ApsDispatchQuery {

    /** slots 한 번에 받을 지시 공정 수 — 넘으면 IllegalArgumentException */
    int MAX_IDS = 500;

    /**
     * 계획 자리 하나. resKind = EQUIP·NODE·OUTSRC, seq = 같은 자원·같은 날 안에서 1부터(시작 순). woProcId = 발행 지시 공정이면 그 id,
     * 계산 때 계획 상태였던 지시는 null
     */
    record Slot(long planId, String planNo, int seq, String resKind, Long equipId, String equipCd, Long nodeId, String nodeCd, long woId,
            String woNo, Long woProcId, int opSeq, String procCd, OffsetDateTime startAt, OffsetDateTime endAt) {
    }

    /** 조회 없음 — APS 모듈이 없는 설치본 */
    ApsDispatchQuery NONE = new ApsDispatchQuery() {
        @Override
        public List<Slot> sequence(String plantCd, LocalDate day) {
            return List.of();
        }

        @Override
        public Map<Long, Slot> slots(String plantCd, Collection<Long> woProcIds) {
            return Map.of();
        }
    };

    /** 그날(공장 시간대 [00:00, 다음날 00:00)) 시작하는 현행 계획 공정 — 자원(설비 → 작업장 → 외주, 코드 순) × 시작 시각 순 */
    List<Slot> sequence(String plantCd, LocalDate day);

    /**
     * 현행 계획에서 그 지시 공정들의 자리(POP 작업 목록 정렬용). seq 는 그 공정이 시작하는 날 그 자원 안 순번. 계획에 없는 id 는 빠진다.
     * 중복 id 는 한 번
     */
    Map<Long, Slot> slots(String plantCd, Collection<Long> woProcIds);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ApsDispatchQuery of(ObjectProvider<ApsDispatchQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
