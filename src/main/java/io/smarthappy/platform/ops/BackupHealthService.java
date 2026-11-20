package io.smarthappy.platform.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 백업 건강 상태 (01 7.2, 9단계) — 마지막 성공 백업 · 마지막 복원 시험 · WAL 아카이브 상태.
 *
 * 백업 스크립트(deploy/backup)는 DB 에 쓰지 않고 상태 파일(JSON)을 남긴다 — 백업 도구에 DB 쓰기 권한을 주지 않기 위해
 * (01 7.2 의 sys_backup_check 표 대신, 2026-10-15). WAL 은 PostgreSQL 통계(pg_stat_archiver)를 읽는다.
 * 기준을 넘으면 경고만(기동은 막지 않는다) — 기동 로그와 운영자 콘솔에 표시.
 */
@Slf4j
@Service
public class BackupHealthService implements ApplicationRunner {

    static final Duration BACKUP_MAX_AGE = Duration.ofHours(26);
    static final Duration RESTORE_TEST_MAX_AGE = Duration.ofDays(35);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final Path dir;

    public BackupHealthService(JdbcTemplate jdbc, @Value("${happyboot.backup.status-dir:./data/backup-status}") String dir) {
        this.jdbc = jdbc;
        this.dir = Path.of(dir);
    }

    public record Health(String status, List<String> warnings, Map<String, Object> lastBackup,
            Map<String, Object> lastRestoreTest, Map<String, Object> walArchive,
            Map<String, Object> lastFilesBackup) {
    }

    @Override
    public void run(ApplicationArguments args) {
        Health h = check(OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if (!h.warnings().isEmpty()) {
            log.warn("백업 건강 상태 경고: {}", h.warnings());
        } else {
            log.info("백업 건강 상태 OK");
        }
    }

    public Health check(OffsetDateTime now) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> backup = read("last-backup.json");
        Map<String, Object> restore = read("last-restore-test.json");
        // 첨부 파일 백업(docs/67 F8) — DB 만 돌아오고 문서가 사라지지 않게. 스크립트의 files 단계가 남긴다
        Map<String, Object> files = read("last-files-backup.json");
        judge("backup", backup, BACKUP_MAX_AGE, now, warnings);
        judge("restoreTest", restore, RESTORE_TEST_MAX_AGE, now, warnings);
        judge("filesBackup", files, BACKUP_MAX_AGE, now, warnings);
        Map<String, Object> wal = wal();
        if (wal.get("lastFailedAt") != null && (wal.get("lastArchivedAt") == null
                || ((OffsetDateTime) wal.get("lastFailedAt")).isAfter((OffsetDateTime) wal.get("lastArchivedAt")))) {
            warnings.add("WAL 아카이브가 마지막에 실패했습니다");
        }
        if (Boolean.FALSE.equals(wal.get("archiveOn"))) {
            warnings.add("WAL 아카이브(archive_mode)가 꺼져 있습니다 — 시점 복구(PITR) 불가");
        }
        return new Health(warnings.isEmpty() ? "OK" : "WARN", warnings, backup, restore, wal, files);
    }

    private static void judge(String what, Map<String, Object> s, Duration maxAge, OffsetDateTime now,
            List<String> warnings) {
        if (s == null) {
            warnings.add(what + ": 기록 없음");
            return;
        }
        if (!"OK".equals(s.get("status"))) {
            warnings.add(what + ": 마지막 결과가 실패");
        }
        try {
            OffsetDateTime at = OffsetDateTime.parse(String.valueOf(s.get("finishedAt")));
            if (Duration.between(at, now).compareTo(maxAge) > 0) {
                warnings.add(what + ": 마지막 성공이 " + Duration.between(at, now).toHours() + "시간 전");
            }
        } catch (RuntimeException e) {
            warnings.add(what + ": 시각 형식 오류");
        }
    }

    /** 상태 파일 (4KB 이하, 허용 키만) */
    private Map<String, Object> read(String name) {
        Path f = dir.resolve(name);
        try {
            if (!Files.isRegularFile(f) || Files.size(f) > 4096) {
                return null;
            }
            Map<?, ?> raw = JSON.readValue(Files.readString(f), Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String k : new String[] {"status", "kind", "finishedAt", "detail"}) {
                if (raw.get(k) instanceof String v) {
                    out.put(k, v.length() > 300 ? v.substring(0, 300) : v);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return Map.of("status", "UNREADABLE");
        }
    }

    private Map<String, Object> wal() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            m.put("archiveOn", !"off".equals(jdbc.queryForObject("select current_setting('archive_mode')", String.class)));
            jdbc.query("""
                    select archived_count, last_archived_time, failed_count, last_failed_time from pg_stat_archiver""",
                    rs -> {
                        m.put("archivedCount", rs.getLong(1));
                        m.put("lastArchivedAt", rs.getObject(2, OffsetDateTime.class));
                        m.put("failedCount", rs.getLong(3));
                        m.put("lastFailedAt", rs.getObject(4, OffsetDateTime.class));
                    });
        } catch (RuntimeException e) {
            m.put("error", "조회 실패");
        }
        return m;
    }
}
