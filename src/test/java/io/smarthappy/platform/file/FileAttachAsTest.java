package io.smarthappy.platform.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.file.FileService.FileInfo;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 업무 계약 첨부 FileService.attachAs (C25 Q1, docs/33 11.17) — 권한 검사 없는 새 경로라 검사·트랜잭션 경계를 시험으로 고정한다.
 * 이상 사용: 트랜잭션 밖 거부 · actor 형식 · 거부 목록 · 시그니처 위장 · 크기 위장 · 잠금 · A사 대상 · 롤백되면 실제 파일 없음
 */
class FileAttachAsTest extends PostgresTestBase {

    private static final String PROG = "sys-my-info";
    private static final String ACTOR = "t12:POP-01";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0};

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    FileService files;

    @Autowired
    FileStorage storage;

    @Autowired
    PolicyService policies;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("FAS");
    }

    private FileInfo attach(String compCd, String targetId, String name, byte[] content, String actor) {
        return runner.call(compCd, () -> files.attachAs(PROG, targetId, name, content.length,
                new ByteArrayInputStream(content), actor));
    }

    private HbErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e.code();
        }
        return null;
    }

    private boolean stored(String key) {
        try (InputStream in = storage.open(key)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private int fileRows(String compCd) {
        return accounts.query(compCd, "select count(*) from sys_file", Integer.class);
    }

    @Test
    void 업무_트랜잭션_안에서_붙이면_같은_검사로_남고_행위자_글자와_로그() {
        FileInfo f = attach(comp, "ROW-1", "고장사진.pdf", PDF, ACTOR);
        assertThat(f.createdBy()).isEqualTo(ACTOR);
        assertThat(f.sha256()).hasSize(64);
        assertThat(f.sizeBytes()).isEqualTo(PDF.length);
        assertThat(stored(f.storageKey())).isTrue();
        assertThat(accounts.query(comp, "select created_by || ':' || target_type || ':' || target_id from sys_file where file_id = ?",
                String.class, f.fileId())).isEqualTo(ACTOR + ":" + PROG + ":ROW-1");
        // 로그인 사용자 없는 경로(단말)도 행위자 글자로 접속 로그 — 커밋 뒤에만
        assertThat(accounts.query(comp, """
                select count(*) from sys_access_log
                 where event_cd = 'FILE_UPLOAD' and login_id = ? and detail like '%attachAs%'""", Integer.class, ACTOR)).isEqualTo(1);
    }

    @Test
    void 업무가_롤백되면_메타도_실제_파일도_없다() {
        List<String> keys = new ArrayList<>();
        assertThatThrownBy(() -> runner.run(comp, () -> {
            keys.add(files.attachAs(PROG, "ROW-2", "a.pdf", PDF.length, new ByteArrayInputStream(PDF), ACTOR).storageKey());
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(keys).hasSize(1);
        assertThat(stored(keys.get(0))).as("커밋 안 된 첨부의 실제 파일은 지운다").isFalse();
        assertThat(fileRows(comp)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'FILE_UPLOAD'", Integer.class))
                .as("올림 로그는 커밋 뒤에만").isZero();
    }

    @Test
    void 트랜잭션_밖에서_부르면_거부() {
        assertThatThrownBy(() -> files.attachAs(PROG, "ROW-3", "a.pdf", PDF.length, new ByteArrayInputStream(PDF), ACTOR))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void actor_형식() {
        for (String bad : Arrays.asList(null, "", "   ", "x".repeat(51), "t1‮exe", "a\nb")) {
            assertThat(codeOf(() -> attach(comp, "ROW-4", "a.pdf", PDF, bad))).as("actor=" + bad).isEqualTo(HbErrorCode.INVALID_VALUE);
        }
        assertThat(attach(comp, "ROW-4", "a.pdf", PDF, "x".repeat(50)).createdBy()).hasSize(50);
        // 대상 형식도 upload 와 같다
        assertThat(codeOf(() -> runner.call(comp, () -> files.attachAs("../etc", "ROW-4", "a.pdf", PDF.length,
                new ByteArrayInputStream(PDF), ACTOR)))).isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThat(fileRows(comp)).isEqualTo(1);
    }

    @Test
    void 거부_목록_시그니처_위장_크기_위장_빈_파일() {
        runner.run(comp, () -> policies.set(Policies.FILE_ALLOWED_EXT, null, "pdf,exe", "t"));
        assertThat(codeOf(() -> attach(comp, "ROW-5", "run.exe", EXE, ACTOR))).as("허용 목록에 넣어도 항상 거부")
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(codeOf(() -> attach(comp, "ROW-5", "invoice.pdf", EXE, ACTOR))).as("확장자만 pdf")
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(codeOf(() -> attach(comp, "ROW-5", "invoice‮fdp.exe", EXE, ACTOR))).as("양방향 문자 이름")
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(codeOf(() -> attach(comp, "ROW-5", "empty.pdf", new byte[0], ACTOR))).isEqualTo(HbErrorCode.FILE_REJECTED);
        runner.run(comp, () -> policies.set(Policies.FILE_MAX_MB, null, "1", "t"));
        byte[] big = Arrays.copyOf(PDF, 1024 * 1024 + 10);
        assertThat(codeOf(() -> runner.call(comp, () -> files.attachAs(PROG, "ROW-5", "big.pdf", 10,
                new ByteArrayInputStream(big), ACTOR)))).as("선언 크기를 속여도 실제 바이트로").isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(fileRows(comp)).isZero();
        // 거부 로그는 업무가 롤백돼도 남는다(따로 커밋), 행위자 글자로
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'FILE_REJECTED' and login_id = ?",
                Integer.class, ACTOR)).isEqualTo(5);
    }

    @Test
    void 잠긴_대상에는_못_붙인다() {
        assertThat(codeOf(() -> attach(comp, "LOCKED-1", "a.pdf", PDF, ACTOR))).isEqualTo(HbErrorCode.FILE_LOCKED);
        assertThat(fileRows(comp)).isZero();
    }

    @Test
    void A사_대상_파일은_A사_범위로만_남고_B사에서_안_보인다() {
        String other = accounts.newComp("FAT");
        FileInfo f = attach(comp, "ROW-6", "a.pdf", PDF, ACTOR);
        assertThat(f.storageKey()).startsWith(comp + "/");
        assertThat(fileRows(other)).isZero();
        assertThat(accounts.query(other, "select count(*) from sys_file where file_id = ?", Integer.class, f.fileId())).isZero();
        // B사 트랜잭션에서 A사 대상 id 를 넘겨도 행은 B사(지금 회사 범위)로 — 대상 소유 확인은 부르는 업무 계약의 몫
        FileInfo g = attach(other, "ROW-6", "b.pdf", PDF, ACTOR);
        assertThat(accounts.query(other, "select comp_cd from sys_file where file_id = ?", String.class, g.fileId())).isEqualTo(other);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_id = 'ROW-6'", Integer.class)).isEqualTo(1);
    }

    // ---------- 좁히는 조건 AttachOptions · 개수 countAttached (docs/33 11.17) ----------

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private FileService.Attached attachWith(String targetId, String name, byte[] content, String actor,
            FileService.AttachOptions opt) {
        return runner.call(comp, () -> files.attachAs(PROG, targetId, name, content.length, new ByteArrayInputStream(content),
                actor, opt));
    }

    @Test
    void onlyExt_는_회사_허용_목록과의_교집합_넓히지_못한다() {
        runner.run(comp, () -> policies.set(Policies.FILE_ALLOWED_EXT, null, "pdf", "t"));
        var imagesOnly = new FileService.AttachOptions(Set.of("png", "jpg"), null, false);
        assertThat(codeOf(() -> attachWith("ROW-7", "a.pdf", PDF, ACTOR, imagesOnly))).as("좁힘: pdf 는 이미지 아님")
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(codeOf(() -> attachWith("ROW-7", "a.png", PNG, ACTOR, imagesOnly))).as("못 넓힘: 회사 목록에 png 없음")
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        var pdfToo = new FileService.AttachOptions(Set.of("PDF", "png"), null, false);
        assertThat(attachWith("ROW-7", "a.pdf", PDF, ACTOR, pdfToo).duplicate()).isFalse();
    }

    @Test
    void maxMb_는_FILE_MAX_MB_보다_클_수_없다_작은_쪽() {
        byte[] big = Arrays.copyOf(PDF, 1024 * 1024 + 10);
        runner.run(comp, () -> policies.set(Policies.FILE_MAX_MB, null, "5", "t"));
        assertThat(codeOf(() -> attachWith("ROW-8", "big.pdf", big, ACTOR, new FileService.AttachOptions(null, 1, false))))
                .as("계약이 더 작게").isEqualTo(HbErrorCode.FILE_REJECTED);
        runner.run(comp, () -> policies.set(Policies.FILE_MAX_MB, null, "1", "t"));
        assertThat(codeOf(() -> attachWith("ROW-8", "big.pdf", big, ACTOR, new FileService.AttachOptions(null, 100, false))))
                .as("계약이 크게 줘도 회사 정책이 이긴다").isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(codeOf(() -> attachWith("ROW-8", "a.pdf", PDF, ACTOR, new FileService.AttachOptions(null, 0, false))))
                .isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThat(fileRows(comp)).isZero();
    }

    @Test
    void reuseSameContent_는_같은_대상_사용_중_파일만() {
        var reuse = new FileService.AttachOptions(null, null, true);
        FileService.Attached first = attachWith("ROW-9", "a.pdf", PDF, ACTOR, reuse);
        FileService.Attached again = attachWith("ROW-9", "다른이름.pdf", PDF, ACTOR, reuse);
        assertThat(again.duplicate()).isTrue();
        assertThat(again.file().fileId()).isEqualTo(first.file().fileId());
        assertThat(attachWith("ROW-10", "a.pdf", PDF, ACTOR, reuse).duplicate()).as("다른 대상은 새 파일").isFalse();
        assertThat(attachWith("ROW-9", "a.pdf", PDF, ACTOR, FileService.AttachOptions.NONE).duplicate())
                .as("옵션 없으면 같은 내용도 새 행").isFalse();
        accounts.exec(comp, "update sys_file set use_yn = 'N' where target_id = 'ROW-9'");
        assertThat(attachWith("ROW-9", "a.pdf", PDF, ACTOR, reuse).duplicate()).as("지운 파일은 다시 쓰지 않는다").isFalse();
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_id = 'ROW-9'", Integer.class)).isEqualTo(3);
    }

    @Test
    void countAttached_는_행위자_접두어로만_세고_빈_접두어는_거부() {
        attach(comp, "ROW-11", "a.pdf", PDF, "t12:POP-01");
        attach(comp, "ROW-11", "b.pdf", PDF, "t13:POP-02");
        attach(comp, "ROW-11", "c.pdf", PDF, "u5:kim");
        assertThat(runner.call(comp, () -> files.countAttached(PROG, "ROW-11", "t"))).isEqualTo(2);
        assertThat(runner.call(comp, () -> files.countAttached(PROG, "ROW-11", "u5:"))).isEqualTo(1);
        assertThat(runner.call(comp, () -> files.countAttached(PROG, "ROW-11", "%"))).as("와일드카드는 글자 그대로").isZero();
        assertThat(codeOf(() -> runner.call(comp, () -> files.countAttached(PROG, "ROW-11", " "))))
                .isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThatThrownBy(() -> files.countAttached(PROG, "ROW-11", "t")).isInstanceOf(IllegalTransactionStateException.class);
        String other = accounts.newComp("FAU");
        assertThat(runner.call(other, () -> files.countAttached(PROG, "ROW-11", "t"))).as("A사로 B사").isZero();
    }
}
