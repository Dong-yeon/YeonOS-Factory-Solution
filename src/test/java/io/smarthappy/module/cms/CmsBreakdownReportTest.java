package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.cms.link.BreakdownReport.Report;
import io.smarthappy.module.cms.link.BreakdownReport.ReportResult;
import io.smarthappy.module.cms.link.BreakdownReport.Symptom;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C20 POP 고장 신고 계약 (docs/33 11.16) — 현상 선택지(클래스 한정·사용 중), 신고 = 고장·신고 상태·작업자·단말·행위자,
 * 같은 요청 id = 처음 번호, 칸 오류(설비·공장·작업자·단말·현상·요청 id), 호출자 트랜잭션 필수·롤백이면 번호·멱등 기록도 없음,
 * 직접 SQL(셋이 함께·사무 계정 없음·다른 회사·바꾸기 금지), A사로 B사.
 */
@AutoConfigureMockMvc
class CmsBreakdownReportTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<BreakdownReport> link;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long prs1;
    long prs2;
    long wld1;
    long w1;
    long wOff;
    long t1;
    long uid;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("BK");
        uid = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, uid, "CMS", "cms-mwo", "SEARCH");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        long press = cls(comp, "PRESS");
        long weld = cls(comp, "WELD");
        prs1 = equip(comp, "P1", "PRS-01", press);
        prs2 = equip(comp, "P2", "PRS-02", press);
        wld1 = equip(comp, "P1", "WLD-01", weld);
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, sort_no, created_by, updated_by) values (?, 'SYMPTOM', 'LEAK', '누유', 2, 't', 't')", comp);
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, sort_no, created_by, updated_by) values (?, 'SYMPTOM', 'NOISE', '소음', 1, 't', 't')", comp);
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, equip_class_id, created_by, updated_by) values (?, 'SYMPTOM', 'SPARK', '스패터', ?, 't', 't')", comp, weld);
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, use_yn, created_by, updated_by) values (?, 'SYMPTOM', 'OLD', '옛 현상', 'N', 't', 't')", comp);
        w1 = worker(comp, "W-001", "박현장", "Y");
        wOff = worker(comp, "W-OLD", "퇴사자", "N");
        t1 = terminal(comp, "TAB-01", "P1");
    }

    private long cls(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String plant, String cd, long cls) {
        return accounts.query(c, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, ?, ?, ?, ?, 't', 't') returning equip_id",
                Long.class, c, plant, cd, cd + " 설비", cls);
    }

    private long worker(String c, String cd, String nm, String useYn) {
        return accounts.query(c, "insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, use_yn, created_by, updated_by) values (?, ?, ?, 'P1', ?, 't', 't') returning worker_id",
                Long.class, c, cd, nm, useYn);
    }

    private long terminal(String c, String cd, String plant) {
        return accounts.query(c, "insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by) values (?, ?, ?, ?, 't', 't') returning terminal_id",
                Long.class, c, cd, cd, plant);
    }

    private BreakdownReport impl() {
        return BreakdownReport.of(link);
    }

    /** POP 서비스처럼 — 단말 행위자로 트랜잭션을 열고 그 안에서 부른다 */
    private ReportResult report(String c, Report r) {
        return runner.callAs(c, "t" + r.terminalId() + ":TAB", () -> impl().report(r));
    }

    private BusinessException fail(String c, Report r) {
        return catchThrowableOfType(BusinessException.class, () -> report(c, r));
    }

    private static String field(BusinessException e) {
        return e.fieldErrors().get(0).field() + ":" + e.fieldErrors().get(0).code();
    }

    @Test
    void 현상_선택지는_사용중_클래스한정은_그클래스만_정렬순서() {
        assertThat(impl()).isNotSameAs(BreakdownReport.NONE);
        assertThat(runner.call(comp, () -> impl().symptoms(prs1))).extracting(Symptom::symptomCd).containsExactly("NOISE", "LEAK");
        assertThat(runner.call(comp, () -> impl().symptoms(wld1))).extracting(Symptom::symptomCd).containsExactly("SPARK", "NOISE", "LEAK");   // 순서 0(기본) → 1 → 2
        assertThat(runner.call(comp, () -> impl().symptoms(999_999_999L))).isEmpty();
        assertThat(BreakdownReport.NONE.symptoms(prs1)).isEmpty();
        assertThat(catchThrowable(() -> BreakdownReport.NONE.report(new Report(prs1, "LEAK", null, w1, t1, "req-00000001"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 신고는_고장_신고상태_작업자_단말_행위자_같은요청은_처음번호() throws Exception {
        ReportResult r = report(comp, new Report(prs1, "leak", "  유압 호스 누유  ", w1, t1, "req-pop-0001"));
        assertThat(r.duplicate()).isFalse();
        assertThat(r.reqNo()).startsWith("MWO-");
        Map<String, Object> row = runner.call(comp, () -> jdbc.queryForMap("""
                select m.mwo_type, m.status, m.requested_by, m.reported_worker_id, m.reported_terminal_id, m.report_req_id, m.created_by,
                       f.fail_cd, m.symptom_text, m.plant_cd, m.title
                  from cms_mwo m left join cms_fail_code f on f.fail_id = m.symptom_id where m.mwo_no = ?""", r.reqNo()));
        assertThat(row).containsEntry("mwo_type", "BM").containsEntry("status", "REQ").containsEntry("reported_worker_id", w1)
                .containsEntry("reported_terminal_id", t1).containsEntry("report_req_id", "req-pop-0001").containsEntry("created_by", "t" + t1 + ":TAB-01")
                .containsEntry("fail_cd", "LEAK").containsEntry("symptom_text", "유압 호스 누유").containsEntry("plant_cd", "P1").containsEntry("title", "누유");
        assertThat(row.get("requested_by")).isNull();
        // 같은 요청 id 다시 = 처음 번호, 새 행 없음
        ReportResult again = report(comp, new Report(prs1, "NOISE", null, w1, t1, "req-pop-0001"));
        assertThat(again).isEqualTo(new ReportResult(r.reqNo(), true));
        assertThat(accounts.query(comp, "select count(*) from cms_mwo", Long.class)).isEqualTo(1L);
        // 화면(작업지시 상세)에 작업자·단말
        mvc.perform(get("/api/cms/mwos/" + accounts.query(comp, "select mwo_id from cms_mwo", Long.class)).with(accounts.as(comp, uid, "admin1")))
                .andExpect(jsonPath("$.data.reportedWorkerNm").value("박현장")).andExpect(jsonPath("$.data.reportedTerminalCd").value("TAB-01"));
        // 글만 있어도 된다(제목 = 글 앞부분)
        assertThat(report(comp, new Report(prs1, null, "벨트 끊어짐", w1, t1, "req-pop-0002")).duplicate()).isFalse();
    }

    @Test
    void 칸오류_설비_공장_작업자_단말_현상_요청id() {
        assertThat(field(fail(comp, new Report(999_999_999L, "LEAK", null, w1, t1, "req-x-000001")))).isEqualTo("equipId:NO_SUCH_CODE");
        assertThat(field(fail(comp, new Report(prs2, "LEAK", null, w1, t1, "req-x-000002")))).isEqualTo("equipId:PLANT");     // P2 설비 ← P1 단말
        accounts.exec(comp, "update mst_equip set use_yn = 'N' where equip_id = ?", wld1);
        assertThat(field(fail(comp, new Report(wld1, "LEAK", null, w1, t1, "req-x-000003")))).isEqualTo("equipId:INACTIVE");
        assertThat(field(fail(comp, new Report(prs1, "LEAK", null, wOff, t1, "req-x-000004")))).isEqualTo("workerId:NO_SUCH_CODE");
        assertThat(field(fail(comp, new Report(prs1, "LEAK", null, 999_999_999L, t1, "req-x-000005")))).isEqualTo("workerId:NO_SUCH_CODE");
        assertThat(field(fail(comp, new Report(prs1, "LEAK", null, w1, 999_999_999L, "req-x-000006")))).isEqualTo("terminalId:NO_SUCH_CODE");
        assertThat(field(fail(comp, new Report(prs1, null, null, w1, t1, "req-x-000007")))).isEqualTo("symptomCd:REQUIRED");
        assertThat(field(fail(comp, new Report(prs1, null, "  ", w1, t1, "req-x-000008")))).isEqualTo("symptomCd:REQUIRED");
        assertThat(field(fail(comp, new Report(prs1, null, "가".repeat(201), w1, t1, "req-x-000009")))).isEqualTo("symptomText:TOO_LONG");
        assertThat(field(fail(comp, new Report(prs1, "OLD", null, w1, t1, "req-x-000010")))).isEqualTo("symptomCd:INACTIVE");
        assertThat(field(fail(comp, new Report(prs1, "SPARK", null, w1, t1, "req-x-000011")))).isEqualTo("symptomCd:CLASS_MISMATCH");
        assertThat(field(fail(comp, new Report(prs1, "LEAK", null, w1, t1, "short")))).isEqualTo("clientReqId:FORMAT");
        assertThat(field(fail(comp, new Report(prs1, "LEAK", null, w1, t1, "has space 01")))).isEqualTo("clientReqId:FORMAT");
        assertThat(accounts.query(comp, "select count(*) from cms_mwo", Long.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_inbox where consumer_cd = 'CMS_POP_BREAKDOWN'", Long.class)).isZero();
    }

    @Test
    void 호출자_트랜잭션_필수_롤백이면_번호와_멱등기록도_없음() {
        // 트랜잭션 밖 = 거부(MANDATORY)
        assertThat(catchThrowable(() -> impl().report(new Report(prs1, "LEAK", null, w1, t1, "req-tx-00001"))))
                .isInstanceOfAny(IllegalTransactionStateException.class, IllegalStateException.class);
        // 호출 쪽(POP)이 뒤에서 실패해 롤백 → 작업지시·멱등 기록 없음 → 같은 요청 id 로 다시 하면 새로 만든다
        assertThat(catchThrowable(() -> runner.callAs(comp, "t" + t1 + ":TAB", () -> {
            impl().report(new Report(prs1, "LEAK", null, w1, t1, "req-tx-00002"));
            throw new IllegalStateException("POP 뒤 단계 실패");
        }))).hasMessage("POP 뒤 단계 실패");
        assertThat(accounts.query(comp, "select count(*) from cms_mwo", Long.class)).isZero();
        assertThat(report(comp, new Report(prs1, "LEAK", null, w1, t1, "req-tx-00002")).duplicate()).isFalse();
    }

    @Test
    void 직접SQL_셋이함께_사무계정없음_다른회사_바꾸기금지_B사는_A사_설비를_못쓴다() {
        ReportResult r = report(comp, new Report(prs1, "LEAK", null, w1, t1, "req-sql-0001"));
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where mwo_no = ?", Long.class, r.reqNo());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set reported_worker_id = ? where mwo_id = ?", wOff, mwo))))
                .contains("ck_cms_mwo_reporter_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set report_req_id = 'other-req-1' where mwo_id = ?", mwo))))
                .contains("ck_cms_mwo_reporter_fixed");
        String ins = """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, requested_by,
                                     reported_worker_id, reported_terminal_id, report_req_id, created_by, updated_by)
                values (?, ?, 'BM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), ?, ?, ?, ?, 't', 't')""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "X-1", prs1, uid, w1, t1, "req-sql-0002"))))
                .contains("ck_cms_mwo_pop_reporter");                                                 // 사무 계정 + 작업자
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "X-2", prs1, null, w1, null, "req-sql-0003"))))
                .contains("ck_cms_mwo_pop_reporter");                                                 // 단말 빠짐
        String other = accounts.newComp("BL");
        accounts.addPlant(other, "P1", "1공장");
        long bw = worker(other, "W-B01", "B사", "Y");
        long bt = terminal(other, "TAB-B", "P1");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "X-3", prs1, null, bw, t1, "req-sql-0004"))))
                .contains("fk_cms_mwo_reported_worker_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "X-4", prs1, null, w1, bt, "req-sql-0005"))))
                .contains("fk_cms_mwo_reported_terminal_comp");
        // B사 POP 이 A사 설비 id 로 신고 → 설비 없음(RLS), 같은 요청 id 라도 회사가 다르면 따로
        long bcls = cls(other, "PRESS");
        long bprs = equip(other, "P1", "PRS-01", bcls);
        assertThat(field(fail(other, new Report(prs1, null, "x", bw, bt, "req-sql-0001")))).isEqualTo("equipId:NO_SUCH_CODE");
        assertThat(report(other, new Report(bprs, null, "x", bw, bt, "req-sql-0001")).duplicate()).isFalse();
        assertThat(runner.call(other, () -> impl().symptoms(prs1))).isEmpty();
        assertThat(List.of(accounts.query(comp, "select count(*) from cms_mwo", Long.class))).containsExactly(1L);
    }
}
