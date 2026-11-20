package io.smarthappy.module.wms.label;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.module.wms.label.PrintTrackDtos.ProbeTarget;
import io.smarthappy.module.wms.label.ZebraStatus.Probe;
import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 라벨 정기 작업 (docs/65 18.1) — 설치형(DIRECT) 프린터 상태 확인: ~HQES + odometer → 프린터 상태·PRINTED 확인.
 *
 * 세 단계: ① 회사마다 트랜잭션 + {@link JobLock} 으로 대상만 읽는다 ② **트랜잭션 밖**에서 TCP(외부 연결을 트랜잭션 안에 두지 않는다)
 * ③ 회사 범위 트랜잭션에서 {@link PrintTrackService#apply}(상태 행 잠금 — 두 인스턴스가 같이 돌아도 증가량을 두 번 쓰지 않는다).
 * 스케줄이 꺼져 있으면(테스트) {@link #probe()} 를 직접 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabelJobs {

    static final String JOB = "wms-printer-probe";
    static final String JOB_LEASE = "wms-print-lease";
    static final String POLICY_PROBE_SEC = "WMS_PRINTER_PROBE_SEC";
    static final int MAX_PER_COMP = 20;

    private final TenantRunner runner;
    private final JobLock lock;
    private final PrintTrackMapper mapper;
    private final PolicyService policy;
    private final ZebraProbe zebra;
    private final PrintTrackService track;
    private final PrintAgentService agents;

    /** 에이전트 lease 만료 → UNKNOWN(docs/65 19.2) — 자동으로 다시 나눠 주지 않는다 */
    @Scheduled(fixedDelayString = "${happyboot.jobs.wms-print-lease-ms:10000}",
            initialDelayString = "${happyboot.jobs.wms-print-lease-ms:10000}")
    public int expireLeases() {
        int[] n = {0};
        var result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(JOB_LEASE)) {
                return;
            }
            int k = agents.expireLeases();
            n[0] += k;
            if (k > 0) {
                log.info("인쇄 에이전트 lease 만료 compCd={} {}건 → 결과 불명", compCd, k);
            }
        });
        if (!result.allSucceeded()) {
            log.error("인쇄 lease 판정 실패 회사 {}", result.failed().keySet());
        }
        return n[0];
    }

    @Scheduled(fixedDelayString = "${happyboot.jobs.wms-printer-probe-ms:10000}",
            initialDelayString = "${happyboot.jobs.wms-printer-probe-ms:10000}")
    public int probe() {
        Map<String, List<ProbeTarget>> targets = new LinkedHashMap<>();
        var result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(JOB)) {
                return;
            }
            List<ProbeTarget> t = mapper.probeTargets(policy.intValue(POLICY_PROBE_SEC), MAX_PER_COMP);
            if (!t.isEmpty()) {
                targets.put(compCd, t);
            }
        });
        if (!result.allSucceeded()) {
            log.error("프린터 상태 확인 대상 읽기 실패 회사 {}", result.failed().keySet());
        }
        int n = 0;
        for (Map.Entry<String, List<ProbeTarget>> e : targets.entrySet()) {
            for (ProbeTarget t : e.getValue()) {
                Probe p = zebra.probe(t.getHost(), t.getPort());
                try {
                    var r = runner.call(e.getKey(), () -> track.apply(t.getPrinterId(), p, "DIRECT", null));
                    n++;
                    if (r.printed() + r.errors() > 0) {
                        log.info("프린터 확인 compCd={} printer={} 상태 {} · 인쇄 확인 {} · 오류 {}", e.getKey(), t.getPrinterId(),
                                r.printerState(), r.printed(), r.errors());
                    }
                } catch (RuntimeException ex) {
                    log.error("프린터 확인 반영 실패 compCd={} printer={}", e.getKey(), t.getPrinterId(), ex);
                }
            }
        }
        return n;
    }
}
