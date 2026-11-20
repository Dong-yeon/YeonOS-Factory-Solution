package io.smarthappy.platform.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.support.PostgresTestBase;

/** 백업 건강 상태 (01 7.2) — 상태 파일 + WAL 아카이브 통계, 기준 초과는 경고만 */
class BackupHealthTest extends PostgresTestBase {

    private static final Path DIR = Path.of("build/test-backup-status");

    @Autowired
    BackupHealthService health;

    @AfterEach
    void clean() throws Exception {
        Files.deleteIfExists(DIR.resolve("last-backup.json"));
        Files.deleteIfExists(DIR.resolve("last-restore-test.json"));
        Files.deleteIfExists(DIR.resolve("last-files-backup.json"));
    }

    private static void write(String name, String json) throws Exception {
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve(name), json);
    }

    @Test
    void 기록이_없으면_경고() {
        BackupHealthService.Health h = health.check(OffsetDateTime.now());
        assertThat(h.status()).isEqualTo("WARN");
        assertThat(h.warnings()).anyMatch(w -> w.startsWith("backup")).anyMatch(w -> w.startsWith("restoreTest"));
    }

    @Test
    void 최근_성공이면_백업_항목은_정상_오래됐거나_실패면_경고() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        write("last-backup.json", "{\"status\":\"OK\",\"kind\":\"diff\",\"finishedAt\":\"" + now.minusHours(3) + "\"}");
        write("last-restore-test.json", "{\"status\":\"OK\",\"finishedAt\":\"" + now.minusDays(40) + "\"}");
        BackupHealthService.Health h = health.check(now);
        assertThat(h.warnings()).noneMatch(w -> w.startsWith("backup"))
                .anyMatch(w -> w.startsWith("restoreTest") && w.contains("시간 전"));
        write("last-backup.json", "{\"status\":\"FAIL\",\"finishedAt\":\"" + now.minusHours(1) + "\"}");
        assertThat(health.check(now).warnings()).anyMatch(w -> w.contains("실패"));
    }

    /** docs/67 F8 — 첨부 파일 백업도 따로 본다: 기록 없음·실패·오래됨은 경고, DB 백업만 성공해도 OK 가 아니다 */
    @Test
    void 첨부_파일_백업도_기록이_없거나_실패면_경고() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        write("last-backup.json", "{\"status\":\"OK\",\"kind\":\"diff\",\"finishedAt\":\"" + now.minusHours(3) + "\"}");
        write("last-restore-test.json", "{\"status\":\"OK\",\"finishedAt\":\"" + now.minusDays(1) + "\"}");
        assertThat(health.check(now).warnings()).anyMatch(w -> w.startsWith("filesBackup") && w.contains("기록 없음"));
        write("last-files-backup.json", "{\"status\":\"OK\",\"kind\":\"files\",\"finishedAt\":\"" + now.minusHours(2) + "\",\"detail\":\"copied=3\"}");
        BackupHealthService.Health ok = health.check(now);
        assertThat(ok.warnings()).noneMatch(w -> w.startsWith("filesBackup"));
        assertThat(ok.lastFilesBackup()).containsEntry("detail", "copied=3");
        write("last-files-backup.json", "{\"status\":\"FAIL\",\"kind\":\"files\",\"finishedAt\":\"" + now.minusHours(1) + "\"}");
        assertThat(health.check(now).warnings()).anyMatch(w -> w.startsWith("filesBackup") && w.contains("실패"));
        write("last-files-backup.json", "{\"status\":\"OK\",\"kind\":\"files\",\"finishedAt\":\"" + now.minusHours(30) + "\"}");
        assertThat(health.check(now).warnings()).anyMatch(w -> w.startsWith("filesBackup") && w.contains("시간 전"));
    }

    @Test
    void 깨진_상태_파일과_너무_큰_파일은_경고로() throws Exception {
        write("last-backup.json", "not json");
        assertThat(health.check(OffsetDateTime.now()).warnings()).anyMatch(w -> w.startsWith("backup"));
        write("last-backup.json", "{\"status\":\"OK\",\"finishedAt\":\"" + "x".repeat(5000) + "\"}");
        assertThat(health.check(OffsetDateTime.now()).warnings()).anyMatch(w -> w.startsWith("backup"));
    }

    @Test
    void WAL_아카이브_상태를_앱_계정으로_읽는다() {
        BackupHealthService.Health h = health.check(OffsetDateTime.now());
        assertThat(h.walArchive()).containsKey("archiveOn").doesNotContainKey("error");
        // 테스트 컨테이너는 아카이브를 켜지 않았다 → PITR 불가 경고
        assertThat(h.warnings()).anyMatch(w -> w.contains("archive_mode"));
    }
}
