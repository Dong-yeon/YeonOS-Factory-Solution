package io.smarthappy.module.qms.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 검사계획 조회 계약 (docs/29_P3 18장, docs/41 A2) — APQP 관리계획서가 검사 행을 QMS 검사계획과 대조할 때. QMS 서비스·표를 직접 부르지 않는다.
 * 읽기만·잠금 없음·**예외 없음**(없거나 다른 회사·잘못된 인자면 빈 목록 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다).
 * QMS 가 없는 설치본은 {@link #NONE}.
 *
 * 고르는 규칙은 검사 실행과 같다: 그 날 유효한 **확정본** 하나(품목 계획 > 품목 클래스 계획, 같은 공정 > 공정 무관, 대체 번호 작은 것).
 * 규격·특별특성·자료형은 확정 때 찍은 스냅샷(특성 마스터가 나중에 바뀌어도 그 리비전의 값).
 */
public interface InspPlanLookup {

    /** 검사 유형 — RECEIVE·PROCESS·FINAL·FIRST_OFF·LAST_OFF·PATROL */
    Set<String> INSP_TYPES = Set.of("RECEIVE", "PROCESS", "FINAL", "FIRST_OFF", "LAST_OFF", "PATROL");

    /**
     * 검사계획 행 하나. inspPlanId·rev = 고른 확정본. 규격(nominal·lsl·usl·unitNm·scale)·specialCls·dataType(VARIABLE·ATTRIBUTE) = 확정 스냅샷,
     * gaugeClass = 계측기 클래스(VISUAL = 육안), samplingPlanCd = 샘플링 계획 코드, freqType = EVERY_LOT·EVERY_N·EVERY_HOURS·START·END
     * (EVERY_N·EVERY_HOURS 면 freqN)
     */
    record PlanLine(long inspPlanId, String rev, int lineNo, long charId, String charNo, String charNm, String specialCls, String dataType,
            BigDecimal nominal, BigDecimal lsl, BigDecimal usl, String unitNm, Integer scale, String methodNm, String gaugeClass,
            String samplingPlanCd, String freqType, Integer freqN, String reactionPlan) {
    }

    /**
     * 검사계획 머리 키 (PMS M4 — 같은 키 + 다른 리비전 = 겹침). 상태(DRAFT·RELEASED·OBSOLETE)와 상관없이 그대로.
     * itemId·itemClassId 중 하나만 값(계획 대상 = 품목 또는 품목 클래스), procId null = 공정 무관
     */
    record PlanKey(long inspPlanId, String inspType, Long itemId, Long itemClassId, Long procId, int altNo, String rev, String status) {
    }

    /** 한 번에 읽는 id 수 — 이보다 많으면 나눠 읽어 합친다(예외 없음 원칙이라 거부하지 않음) */
    int KEYS_CHUNK = 500;

    /** 조회 없음 — QMS 모듈이 없는 설치본(빈 없음) */
    InspPlanLookup NONE = new InspPlanLookup() {
        @Override
        public List<PlanLine> lines(long itemId, Long procId, String inspType, LocalDate onDate) {
            return List.of();
        }

        @Override
        public List<PlanKey> keys(Collection<Long> inspPlanIds) {
            return List.of();
        }
    };

    /**
     * 품목·공정(null = 공정 무관 계획만)·검사 유형의 onDate(회사 날짜)에 유효한 확정 검사계획 행, 행 번호 순.
     * 계획 없음·없는/다른 회사 품목·모르는 검사 유형·null 날짜는 빈 목록
     */
    List<PlanLine> lines(long itemId, Long procId, String inspType, LocalDate onDate);

    /**
     * 검사계획 id 들의 머리 키, inspPlanId 오름차순. 상태와 상관없이 반환(작성 중도). 없는·다른 회사 id 는 빠진다(RLS).
     * null·빈 입력 = 빈 목록, null·중복 id 는 거른다. {@link #KEYS_CHUNK} 개씩 나눠 읽어 합친다(개수 상한으로 거부하지 않음)
     */
    List<PlanKey> keys(Collection<Long> inspPlanIds);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static InspPlanLookup of(ObjectProvider<InspPlanLookup> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
