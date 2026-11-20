package io.smarthappy.platform.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.smarthappy.platform.config.HappyBootProperties;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 업무 운영 지표 (docs/52 Y2) — "조용히 틀리는 것"의 운영판. 화면 오류 없이 쌓이는 것(아웃박스 실패·재전송·RLS 차단·작업 지연)을
 * 경보로 바꾼다. Prometheus 이름은 점 → 밑줄 + 단위·_total 이 붙는다(hb.login.fail → hb_login_fail_total).
 *
 * <ul>
 * <li>꼬리표는 코드가 정한 작은 집합만(토픽·작업명·종류). 사용자 입력(아이디·회사코드 입력값·URL)은 꼬리표로 쓰지 않는다 — 시계열 폭발</li>
 * <li>회사 꼬리표(comp)는 {@link MetricsProperties#compTag()} 이고 SaaS 에서만. 로그인 실패는 회사코드가 입력값이라 켜도 달지 않는다</li>
 * <li>개수 지표는 이 프로세스 기준(재기동하면 0부터) — Prometheus 의 increase()/rate() 로 본다</li>
 * </ul>
 */
@Slf4j
@Component
public class HbMetrics {

    static final String COMP = "comp";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,50}$");

    private final MeterRegistry registry;
    private final boolean compTag;
    private final long startedAt = System.currentTimeMillis();
    /** 작업명 → 마지막 성공(커밋) 시각(ms) */
    private final Map<String, Long> jobSuccess = new ConcurrentHashMap<>();

    public HbMetrics(MeterRegistry registry, MetricsProperties props, HappyBootProperties boot) {
        this.registry = registry;
        this.compTag = props.compTag() && !boot.deploy().onprem();
        if (props.compTag() && boot.deploy().onprem()) {
            log.warn("happyboot.metrics.comp-tag 는 SaaS 전용 — 설치형(onprem)에서는 무시합니다");
        }
    }

    /** 회사 꼬리표를 다는가 (SaaS + 설정 켬) */
    public boolean compTagEnabled() {
        return compTag;
    }

    /** 수신 중복 — 같은 메시지 id·멱등키가 다시 왔다(InboxService.firstTime = false). consumer 는 앞 마디만(API·ERP_… 등) */
    public void inboxDuplicate(String consumerCd) {
        String consumer = consumerCd == null ? "-" : consumerCd.split(":", 2)[0];
        counter("hb.inbox.duplicate", "수신 중복(멱등으로 버린 재전송)", Tags.of("consumer", safe(consumer))).increment();
    }

    /** POP 재전송 — 단말이 같은 client_req_id 로 다시 보냈다(처음 저장한 행으로 응답). kind = event·result·param */
    public void popResend(String kind) {
        counter("hb.pop.resend", "POP 재전송(같은 요청 id)", Tags.of("kind", safe(kind))).increment();
    }

    /** 접속 로그 이벤트 중 지표로 세는 것 — 로그인 실패·시도 제한. 회사코드가 입력값이라 회사 꼬리표 없음 */
    public void accessEvent(AccessEvent event) {
        if (event == AccessEvent.LOGIN_FAIL) {
            Counter.builder("hb.login.fail").description("로그인 실패").register(registry).increment();
        } else if (event == AccessEvent.LOGIN_THROTTLED) {
            Counter.builder("hb.login.throttled").description("로그인 시도 제한(IP·IP+회사)").register(registry).increment();
        }
    }

    /** RLS 위반(42501 ExecWithCheckOptions)을 403 TENANT_FORBIDDEN 으로 막았다 — 1건이라도 코드 결함 신호 */
    public void tenantForbidden() {
        counter("hb.tenant.forbidden", "RLS 위반 차단(403 TENANT_FORBIDDEN)", Tags.empty()).increment();
    }

    /** 엑셀 내보내기 자리가 없어 거절(EXPORT_BUSY) — 자리는 기다리지 않고 바로 거절한다(docs/52 Y2 "대기" 의 실제 모양) */
    public void excelBusy() {
        counter("hb.excel.busy", "엑셀 내보내기 자리 없음(EXPORT_BUSY)", Tags.empty()).increment();
    }

    /** 엑셀 내보내기 걸린 시간(성공·실패 모두) */
    public void excelExport(Duration took) {
        Timer.Builder b = Timer.builder("hb.excel.export").description("엑셀 내보내기 시간");
        b.tags(withComp(Tags.empty())).register(registry).record(took);
    }

    /** 정기 작업 성공(JobLock 을 잡은 트랜잭션이 커밋됨) — hb_job_lag_seconds 의 기준 */
    public void jobSucceeded(String jobName) {
        String job = safe(jobName);
        boolean first = jobSuccess.put(job, System.currentTimeMillis()) == null;
        if (first) {
            Gauge.builder("hb.job.lag", this, m -> m.lagSeconds(job)).description("정기 작업 마지막 성공 뒤 지난 시간")
                    .baseUnit("seconds").tags("job", job).register(registry);
        }
    }

    /** 마지막 성공 뒤 초 — 기동 뒤 아직 한 번도 성공하지 않았으면 기동 뒤 초 */
    double lagSeconds(String job) {
        Long at = jobSuccess.get(job);
        return (System.currentTimeMillis() - (at == null ? startedAt : at)) / 1000.0;
    }

    MeterRegistry registry() {
        return registry;
    }

    private Counter counter(String name, String description, Tags tags) {
        return Counter.builder(name).description(description).tags(withComp(tags)).register(registry);
    }

    /** 회사 꼬리표 — 켜져 있을 때만, 회사 범위 밖이면 "-" */
    Tags withComp(Tags tags) {
        if (!compTag) {
            return tags;
        }
        return tags.and(COMP, TenantContext.currentCompCd().orElse("-"));
    }

    private static String safe(String v) {
        return v != null && NAME.matcher(v).matches() ? v : "other";
    }
}
