package io.smarthappy.platform.metrics;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.smarthappy.platform.ops.BackupHealthService;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * DB 상태에서 읽는 지표 (docs/52 Y2) — 1분마다 다시 센다(수집 요청마다 DB 를 읽지 않게).
 *
 * <ul>
 * <li>hb_outbox_pending / hb_outbox_failed (토픽별) — 보낼 것(NEW·SENDING) / 실패로 멈춘 것(FAIL, 사람이 재전송·종결할 때까지 남는다)</li>
 * <li>hb_outbox_oldest_pending_seconds (토픽별) — 가장 오래 기다린 보낼 것. 릴레이가 멈추면 이것이 먼저 오른다</li>
 * <li>hb_backup_age_hours — 마지막 백업 성공 뒤 시간(상태 파일, {@link BackupHealthService}). 기록이 없으면 값 없음(NaN)</li>
 * </ul>
 * 회사마다 자기 범위로 센다(TenantRunner — RLS 그대로). 회사 꼬리표가 꺼져 있으면 토픽별로 합친다.
 */
@Slf4j
@Component
public class OpsGauges {

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BackupHealthService backup;
    private final HbMetrics metrics;
    private final MultiGauge pending;
    private final MultiGauge failed;
    private final MultiGauge oldest;
    private final AtomicReference<Double> backupAgeHours = new AtomicReference<>(Double.NaN);

    public OpsGauges(TenantRunner runner, JdbcTemplate jdbc, BackupHealthService backup, HbMetrics metrics) {
        this.runner = runner;
        this.jdbc = jdbc;
        this.backup = backup;
        this.metrics = metrics;
        this.pending = MultiGauge.builder("hb.outbox.pending").description("아웃박스 보낼 것(NEW·SENDING)").register(metrics.registry());
        this.failed = MultiGauge.builder("hb.outbox.failed").description("아웃박스 실패로 멈춘 것(FAIL)").register(metrics.registry());
        this.oldest = MultiGauge.builder("hb.outbox.oldest.pending").description("가장 오래 기다린 보낼 것")
                .baseUnit("seconds").register(metrics.registry());
        Gauge.builder("hb.backup.age", backupAgeHours, AtomicReference::get).description("마지막 백업 성공 뒤 시간")
                .baseUnit("hours").register(metrics.registry());
    }

    private record Key(String topic, String comp) {
    }

    /** 다시 센다 — 스케줄이 꺼져 있으면(시험) 직접 부른다 */
    @Scheduled(fixedDelayString = "${happyboot.jobs.metrics-refresh-ms:60000}", initialDelay = 15000)
    public void refresh() {
        Map<Key, double[]> acc = new HashMap<>();
        boolean byComp = metrics.compTagEnabled();
        TenantRunner.BatchResult r = runner.forEachActiveComp(compCd -> jdbc.query("""
                select topic,
                       count(*) filter (where status in ('NEW', 'SENDING')),
                       count(*) filter (where status = 'FAIL'),
                       coalesce(extract(epoch from now() - min(created_at) filter (where status in ('NEW', 'SENDING'))), 0)
                  from sys_outbox where status in ('NEW', 'SENDING', 'FAIL') group by topic""", rs -> {
            double[] v = acc.computeIfAbsent(new Key(rs.getString(1), byComp ? compCd : null), k -> new double[3]);
            v[0] += rs.getLong(2);
            v[1] += rs.getLong(3);
            v[2] = Math.max(v[2], rs.getDouble(4));
        }));
        if (!r.allSucceeded()) {
            log.warn("운영 지표: 아웃박스 집계 실패 회사 {}", r.failed().keySet());
        }
        List<MultiGauge.Row<?>> p = new ArrayList<>();
        List<MultiGauge.Row<?>> f = new ArrayList<>();
        List<MultiGauge.Row<?>> o = new ArrayList<>();
        acc.forEach((k, v) -> {
            Tags tags = k.comp() == null ? Tags.of("topic", k.topic()) : Tags.of("topic", k.topic(), HbMetrics.COMP, k.comp());
            p.add(MultiGauge.Row.of(tags, v[0]));
            f.add(MultiGauge.Row.of(tags, v[1]));
            o.add(MultiGauge.Row.of(tags, v[2]));
        });
        pending.register(p, true);
        failed.register(f, true);
        oldest.register(o, true);
        backupAgeHours.set(backupAge(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    double backupAge(OffsetDateTime now) {
        Map<String, Object> last = backup.check(now).lastBackup();
        if (last == null || !"OK".equals(last.get("status"))) {
            return Double.NaN;
        }
        try {
            return Duration.between(OffsetDateTime.parse(String.valueOf(last.get("finishedAt"))), now).toMinutes() / 60.0;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
