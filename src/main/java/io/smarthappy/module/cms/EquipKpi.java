package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 설비 KPI 판정 (C9, docs/33 11.8) — 순수 함수. 원장에서 모은 "합" 만 받고, 비율은 합한 분자 ÷ 합한 분모로만 낸다
 * (02 2.5 "합산한 뒤 나눈다" — 일별·설비별 비율의 평균을 내지 않는다).
 *
 * <pre>
 * 수집 시간   covered = 상태 구간이 덮은 시간(기간·지금으로 자름), 미수집 = 기간 − covered(따로 보임, 수집 장치 있는 설비만 — 기간을 0 으로 넘김)
 * 계획 시간   planned = covered − UNKNOWN(통신 끊김) − NST(비가동 계획) − SDT(계획 정지)     ← 분류는 실효(사후 사유 우선, C7)
 * 가용률      = PRD / planned
 * 성능        = Σ(표준 사이클/사이클당 수량 × 생산 수) / PRD       — 표준 사이클 없는 실적이 하나라도 있으면 빈칸(D21)
 * 품질        = 양품 / 생산 수(양품 + 폐기 + 재작업 + 수리)
 * OEE         = Σ(표준 시간 × 양품) / planned                      — 완전 생산 시간 ÷ 계획 시간(곱이 아니라 합한 분자·분모)
 * 고장 횟수   = 기간 안에 시작한 실효 UDT 구간 수
 * MTBF        = PRD / 고장 횟수,  MTTR = UDT 시간 / 고장 횟수      — 고장 0 이면 빈칸
 * 수집 없음   = 구간이 하나도 없으면 가용·성능·OEE·MTBF·MTTR 빈칸(D19 — 작업지시 수기 정지로 채우지 않는다)
 * PM 준수율   = 기한 안 완료 / 기간 안 끝난 예정
 * </pre>
 */
public final class EquipKpi {

    static final int RATIO_SCALE = 4;

    private EquipKpi() {
    }

    /** 합할 수 있는 원값(초·수량·횟수) — 설비·날짜·라인 어디서든 그대로 더한다 */
    public record Raw(long periodSec, long coveredSec, long unknownSec, long nstSec, long sdtSec, long udtSec, long prdSec, long failures,
            BigDecimal idealSec, BigDecimal idealGoodSec, long ctMissing, BigDecimal goodQty, BigDecimal totalQty, long pmDone,
            long pmOnTime) {

        static final Raw ZERO = new Raw(0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

        Raw plus(Raw o) {
            return new Raw(periodSec + o.periodSec, coveredSec + o.coveredSec, unknownSec + o.unknownSec, nstSec + o.nstSec, sdtSec + o.sdtSec,
                    udtSec + o.udtSec, prdSec + o.prdSec, failures + o.failures, idealSec.add(o.idealSec), idealGoodSec.add(o.idealGoodSec),
                    ctMissing + o.ctMissing, goodQty.add(o.goodQty), totalQty.add(o.totalQty), pmDone + o.pmDone, pmOnTime + o.pmOnTime);
        }

        long plannedSec() {
            return coveredSec - unknownSec - nstSec - sdtSec;
        }
    }

    /** 판정 결과 — 비율은 0~1(소수 4자리, 반올림), 시간은 분(소수 1자리). 낼 수 없으면 null */
    public record Result(boolean collected, long plannedSec, long uncoveredSec, BigDecimal availability, BigDecimal performance, BigDecimal quality,
            BigDecimal oee, BigDecimal mtbfMin, BigDecimal mttrMin, BigDecimal pmCompliance) {
    }

    static Result of(Raw r) {
        boolean collected = r.coveredSec() > 0;
        long planned = Math.max(0, r.plannedSec());
        BigDecimal availability = collected ? ratio(BigDecimal.valueOf(r.prdSec()), BigDecimal.valueOf(planned)) : null;
        boolean ctOk = r.ctMissing() == 0 && r.totalQty().signum() > 0;
        BigDecimal performance = collected && ctOk ? ratio(r.idealSec(), BigDecimal.valueOf(r.prdSec())) : null;
        BigDecimal quality = ratio(r.goodQty(), r.totalQty());
        BigDecimal oee = collected && ctOk ? ratio(r.idealGoodSec(), BigDecimal.valueOf(planned)) : null;
        BigDecimal mtbf = collected && r.failures() > 0 ? minutes(r.prdSec(), r.failures()) : null;
        BigDecimal mttr = collected && r.failures() > 0 ? minutes(r.udtSec(), r.failures()) : null;
        BigDecimal pm = ratio(BigDecimal.valueOf(r.pmOnTime()), BigDecimal.valueOf(r.pmDone()));
        return new Result(collected, planned, Math.max(0, r.periodSec() - r.coveredSec()), availability, performance, quality, oee, mtbf, mttr, pm);
    }

    /**
     * 작업지시 기준 원값(docs/33 16.3 F3·16.5 G2) — 상태 원장과 섞지 않는다. calSec = 기간 달력 시간(지금에서 자름, 수집 없는 설비만 합한다).
     * 설비·라인·공장 어디서든 그대로 더한다
     */
    public record WoRaw(long failures, long downSec, long downCount, long calSec, long respSec, long respCount) {

        static final WoRaw ZERO = new WoRaw(0, 0, 0, 0, 0, 0);

        WoRaw plus(WoRaw o) {
            return new WoRaw(failures + o.failures, downSec + o.downSec, downCount + o.downCount, calSec + o.calSec, respSec + o.respSec,
                    respCount + o.respCount);
        }
    }

    /**
     * 작업지시 기준 값. MTBF·MTTR(작업지시)는 **수집 없는 설비만**(scope = 그 설비들의 합, 없으면 null → 칸 전부 null) —
     * MTBF = (달력 − Σ정지) ÷ 고장 수, MTTR = Σ정지 ÷ 정지 기록 수. MTTA = Σ(착수 − 신고) ÷ 착수 칸이 있는 수 — 모든 설비(all).
     * 분모 0 이면 null, 분 소수 1자리
     */
    public record WoKpi(Long failures, Long downSec, Long downCount, Long calSec, BigDecimal mtbfMin, BigDecimal mttrMin, long respSec,
            long respCount, BigDecimal mttaMin) {
    }

    static WoKpi wo(WoRaw scope, WoRaw all) {
        BigDecimal mtta = all.respCount() > 0 ? minutes(all.respSec(), all.respCount()) : null;
        if (scope == null) {
            return new WoKpi(null, null, null, null, null, null, all.respSec(), all.respCount(), mtta);
        }
        BigDecimal mtbf = scope.failures() > 0 ? minutes(Math.max(0, scope.calSec() - scope.downSec()), scope.failures()) : null;
        BigDecimal mttr = scope.downCount() > 0 ? minutes(scope.downSec(), scope.downCount()) : null;
        return new WoKpi(scope.failures(), scope.downSec(), scope.downCount(), scope.calSec(), mtbf, mttr, all.respSec(), all.respCount(), mtta);
    }

    /** 분모 0 이하면 null */
    static BigDecimal ratio(BigDecimal num, BigDecimal den) {
        if (den == null || num == null || den.signum() <= 0) {
            return null;
        }
        return num.divide(den, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal minutes(long sec, long count) {
        return BigDecimal.valueOf(sec).divide(BigDecimal.valueOf(60L * count), 1, RoundingMode.HALF_UP);
    }
}
