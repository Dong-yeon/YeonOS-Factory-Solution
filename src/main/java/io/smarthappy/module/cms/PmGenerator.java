package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsPmDtos.GenResult;
import io.smarthappy.module.cms.CmsPmDtos.PlanRow;
import io.smarthappy.module.cms.CmsPmDtos.RunRow;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 예방점검 예정 생성 (docs/33 4.3·4.4, D6) — 정기 작업(CmsJobs)·화면의 "지금 생성"·카운터 읽기 입력이 같은 함수를 쓴다.
 * 달력 주기: 사용 중 계획 중 (다음 예정일 − 선행일) ≤ 대상 공장의 오늘. 사용량 주기: 누적 ≥ 다음 예정 사용량 − 선행 사용량
 * (예정일 = 대상 공장의 오늘, 멱등 키에 예정 사용량). 둘 다 그 계획에 열린 예정이 없을 때만.
 * 멱등: 계획 행 잠금 + 다시 확인 + unique(계획, 예정일, 예정 사용량) 두 곳 — 두 번 돌려도·동시에 돌려도 한 번만 생긴다.
 */
@Service
@RequiredArgsConstructor
public class PmGenerator {

    /** 선행일 상한(정책 최대 90) + 시간대 차이 하루 */
    static final int MAX_LOOKAHEAD = 91;

    private final CmsPmMapper mapper;
    private final MwoService mwos;
    private final PolicyService policies;
    private final BizClock clock;

    /** 회사 범위 트랜잭션 안에서만(요청 또는 TenantRunner) */
    @Transactional(propagation = Propagation.MANDATORY)
    public GenResult generate(String by) {
        int defaultLead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        LocalDate upTo = clock.today().plusDays(MAX_LOOKAHEAD);
        Map<String, ZoneId> zones = new HashMap<>();
        int[] n = new int[2];
        for (PlanRow cand : mapper.findPlansToGenerate(upTo)) {
            one(cand.getPlanId(), defaultLead, zones, by, n);
        }
        return new GenResult(n[0], n[1]);
    }

    /** 카운터 읽기 입력 뒤 같은 트랜잭션 — 그 카운터의 사용량 계획만 ("사용량 도달 시 같은 트랜잭션에서 예정 생성") */
    @Transactional(propagation = Propagation.MANDATORY)
    public GenResult generateForCounter(long counterId, String by) {
        int defaultLead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        Map<String, ZoneId> zones = new HashMap<>();
        int[] n = new int[2];
        for (Long planId : mapper.findUsagePlansOfCounter(counterId)) {
            one(planId, defaultLead, zones, by, n);
        }
        return new GenResult(n[0], n[1]);
    }

    /** 계획 하나: 잠금 → 다시 읽기 → 조건 확인 → 작업지시(WO)·점검 실행. n[0] = 실행 수, n[1] = 작업지시 수 */
    private void one(long planId, int defaultLead, Map<String, ZoneId> zones, String by, int[] n) {
        mapper.lockPlan(planId);
        PlanRow p = mapper.findPlan(planId);
        if (!"Y".equals(p.getUseYn()) || p.getOpenRunId() != null) {
            return;
        }
        ZoneId z = zones.computeIfAbsent(p.getPlantCd() == null ? "" : p.getPlantCd(),
                k -> k.isEmpty() ? clock.zone() : clock.zone(k));
        LocalDate today = clock.instant().atZone(z).toLocalDate();
        LocalDate due;
        BigDecimal dueUsage = null;
        if (p.isUsage()) {
            if (!PmDue.usageReached(p.getNextDueUsage(), p.getCounterTotal(), p.getUsageLead())) {
                return;
            }
            due = today;
            dueUsage = p.getNextDueUsage();
        } else {
            int lead = p.getLeadDays() != null ? p.getLeadDays() : defaultLead;
            if (p.getNextDueDate().minusDays(lead).isAfter(today)) {
                return;
            }
            due = p.getNextDueDate();
        }
        RunRow r = new RunRow();
        r.setPlanId(p.getPlanId());
        r.setTargetType(p.getTargetType());
        r.setEquipId(p.getEquipId());
        r.setAssetId(p.getAssetId());
        r.setTmplId(p.getTmplId());
        r.setDueDate(due);
        r.setDueUsage(dueUsage);
        if ("WO".equals(p.getExecMode())) {
            r.setMwoId(mwos.createPm(p, due, dueUsage, by));
            n[1]++;
        }
        long runId = mapper.insertRun(r, by);
        mapper.snapshotResults(runId, p.getTmplId(), by);
        n[0]++;
    }
}
