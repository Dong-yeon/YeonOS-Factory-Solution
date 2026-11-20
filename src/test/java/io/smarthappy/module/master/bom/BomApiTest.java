package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * BOM 관리 (P1 3a, docs/18) — 리비전·확정본 불변·순환 금지(API·직접 SQL·동시)·기간 겹침·확정 검사·단위 환산·A/B사·권한.
 */
@AutoConfigureMockMvc
class BomApiTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/master/boms";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long userId;
    RequestPostProcessor admin;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("BM");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        item(comp, "FG1", "EA", "N");
        item(comp, "SA1", "EA", "N");
        item(comp, "SA2", "EA", "N");
        item(comp, "RM1", "KG", "Y");
        item(comp, "RM2", "KG", "N");
    }

    // ---------- 도우미 ----------

    private long item(String c, String cd, String unit, String lot) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                      created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, ?, 't', 't') returning item_id""", Long.class, c, cd, cd + " 품목",
                unit, lot);
    }

    private long itemId(String c, String cd) {
        return accounts.query(c, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions createBom(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(BASE), body).with(who));
    }

    private long bom(String itemCd) throws Exception {
        MvcResult r = createBom(admin, "{\"itemCd\":\"" + itemCd + "\"}").andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ver(long bomId) {
        return accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, bomId);
    }

    private static String line(String key, int no, String itemCd, String qty, String extra) {
        return "{\"rowKey\":\"" + key + "\",\"lineNo\":" + no + ",\"itemCd\":\"" + itemCd + "\",\"qty\":\"" + qty + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions lines(RequestPostProcessor who, long bomId, int rowVer, String created, String updated,
            String deleted) throws Exception {
        String body = "{\"created\":[" + (created == null ? "" : created) + "],\"updated\":["
                + (updated == null ? "" : updated) + "],\"deleted\":[" + (deleted == null ? "" : deleted) + "]}";
        return mvc.perform(json(post(BASE + "/" + bomId + "/lines/batch").param("rowVer", "" + rowVer), body)
                .with(who));
    }

    private ResultActions addLines(long bomId, String... created) throws Exception {
        return lines(admin, bomId, ver(bomId), String.join(",", created), null, null);
    }

    private ResultActions release(long bomId) throws Exception {
        return mvc.perform(json(post(BASE + "/" + bomId + "/release"), "{\"rowVer\":" + ver(bomId) + "}").with(admin));
    }

    private long newRev(long bomId, String validFrom) throws Exception {
        MvcResult r = mvc.perform(json(post(BASE + "/" + bomId + "/new-revision"),
                "{\"rowVer\":" + ver(bomId) + (validFrom == null ? "" : ",\"validFrom\":\"" + validFrom + "\"") + "}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 확정된 BOM 하나: parent ← child (수량 1) */
    private long released(String parent, String child) throws Exception {
        long id = bom(parent);
        addLines(id, line("n1", 10, child, "1", null)).andExpect(status().isOk());
        release(id).andExpect(status().isOk());
        return id;
    }

    /** DB 가 막은 제약 이름 — 메시지가 아니라 서버 오류의 constraint 필드로 (트리거 RAISE … CONSTRAINT 포함) */
    private static void assertConstraint(String name, ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).isNotNull();
        assertThat(PgErrors.constraint(t)).as(String.valueOf(t)).contains(name);
    }

    // ---------- 정상 ----------

    @Test
    void 작성_행저장_확정_새리비전_이전본_자동마감() throws Exception {
        long a0 = bom("FG1");
        mvc.perform(get(BASE + "/" + a0).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bom.rev").value("A0"))
                .andExpect(jsonPath("$.data.bom.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.bom.validFrom").value(today.toString()))
                .andExpect(jsonPath("$.data.bom.baseQty").value(1));
        addLines(a0, line("n1", 10, "sa1", "2", null), line("n2", 20, "RM1", "1.5", "\"scrapPct\":\"3.5\""),
                line("n3", 30, "RM2", "0.25", "\"fixedYn\":\"Y\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created.length()").value(3));
        mvc.perform(get(BASE + "/" + a0).with(admin))
                .andExpect(jsonPath("$.data.bom.lineCnt").value(3))
                .andExpect(jsonPath("$.data.lines[0].itemCd").value("SA1"))
                .andExpect(jsonPath("$.data.lines[0].unitCd").value("EA"))          // 비우면 자품목 기본 단위
                .andExpect(jsonPath("$.data.lines[0].issueMethod").value("BACKFLUSH"))
                .andExpect(jsonPath("$.data.lines[1].issueMethod").value("MANUAL"))   // LOT 관리 자재 기본
                .andExpect(jsonPath("$.data.lines[1].scrapPct").value(3.5))
                .andExpect(jsonPath("$.data.lines[2].fixedYn").value("Y"));
        release(a0).andExpect(status().isOk());

        // 확정본은 고칠 수 없다 — 머리·행 모두
        mvc.perform(json(put(BASE + "/" + a0), "{\"baseQty\":\"2\",\"rowVer\":" + ver(a0) + "}").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        addLines(a0, line("n4", 40, "SA2", "1", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));

        // 새 리비전 A1 (내일부터) — 행 복사, 확정하면 A0 종료일이 내일로 마감
        long a1 = newRev(a0, today.plusDays(1).toString());
        mvc.perform(get(BASE + "/" + a1).with(admin))
                .andExpect(jsonPath("$.data.bom.rev").value("A1"))
                .andExpect(jsonPath("$.data.bom.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.lines.length()").value(3));
        // 작성 중이 이미 있으면 또 만들 수 없다
        mvc.perform(json(post(BASE + "/" + a0 + "/new-revision"), "{\"rowVer\":" + ver(a0) + "}").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_DRAFT_EXISTS"));
        release(a1).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select valid_to from mst_bom where bom_id = ?", LocalDate.class, a0))
                .isEqualTo(today.plusDays(1));
        // 시작일을 비우면: 원본(A1)이 내일 시작이므로 그 다음 날 — 바로 확정할 수 있다
        long a2 = newRev(a1, null);
        assertThat(accounts.query(comp, "select valid_from from mst_bom where bom_id = ?", LocalDate.class, a2))
                .isEqualTo(today.plusDays(2));
        release(a2).andExpect(status().isOk());
        mvc.perform(get(BASE).param("itemCd", "FG1").with(admin))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[0].rev").value("A2"));                 // 기본 정렬: 리비전 내림차순
    }

    @Test
    void 순번을_한번에_서로_바꾸고_행을_지운다() throws Exception {
        long id = bom("FG1");
        addLines(id, line("n1", 10, "SA1", "1", null), line("n2", 20, "SA2", "1", null)).andExpect(status().isOk());
        long l1 = accounts.query(comp, "select line_id from mst_bom_line where bom_id = ? and line_no = 10", Long.class, id);
        long l2 = accounts.query(comp, "select line_id from mst_bom_line where bom_id = ? and line_no = 20", Long.class, id);
        lines(admin, id, ver(id), null,
                "{\"rowKey\":\"u1\",\"lineId\":" + l1 + ",\"rowVer\":0,\"lineNo\":20,\"itemCd\":\"SA1\",\"qty\":\"1\"},"
                        + "{\"rowKey\":\"u2\",\"lineId\":" + l2 + ",\"rowVer\":0,\"lineNo\":10,\"itemCd\":\"SA2\",\"qty\":\"1\"}",
                null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select line_no from mst_bom_line where line_id = ?", Integer.class, l1))
                .isEqualTo(20);
        lines(admin, id, ver(id), null, null, "{\"rowKey\":\"d1\",\"lineId\":" + l1 + ",\"rowVer\":1}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where bom_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    // ---------- 순환 ----------

    @Test
    void 순환_자기자신_직접_간접_모두_행오류() throws Exception {
        released("SA1", "RM1");
        released("FG1", "SA1");
        long sa1Draft = newRev(accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) "
                + "where i.item_cd = 'SA1'", Long.class), null);
        // SA1 아래에 FG1 (FG1 → SA1 이 이미 있음) = 직접 순환
        addLines(sa1Draft, line("c1", 20, "FG1", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value("c1"))
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        // 자기 자신
        addLines(sa1Draft, line("c2", 20, "SA1", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        // 간접: RM1 아래에 FG1 (FG1 → SA1 → RM1)
        long rm1 = bom("RM1");
        addLines(rm1, line("c3", 10, "FG1", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        // 작성 중(미확정) 리비전도 구조에 포함 — RM2 를 SA2 아래에 넣은 초안이 있으면 RM2 아래 SA2 는 순환
        long sa2 = bom("SA2");
        addLines(sa2, line("c4", 10, "RM2", "1", null)).andExpect(status().isOk());
        long rm2 = bom("RM2");
        addLines(rm2, line("c5", 10, "SA2", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        // 폐기하면 구조에서 빠진다
        mvc.perform(json(post(BASE + "/" + sa2 + "/obsolete"), "{\"rowVer\":" + ver(sa2) + "}").with(admin))
                .andExpect(status().isOk());
        addLines(rm2, line("c6", 10, "SA2", "1", null)).andExpect(status().isOk());
    }

    @Test
    void 순환은_직접_SQL_도_DB_가_막는다() throws Exception {
        released("FG1", "SA1");
        long sa1 = bom("SA1");
        assertConstraint("ck_mst_bom_cycle", () -> accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method,
                                          created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""", comp, sa1, itemId(comp, "FG1")));
    }

    @Test
    void 동시에_A아래B_B아래A_를_저장하면_뒤쪽이_순환으로_실패() throws Exception {
        long fg = bom("FG1");
        long sa = bom("SA1");
        long fgItem = itemId(comp, "FG1");
        long saItem = itemId(comp, "SA1");
        String sql = """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method,
                                          created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""";
        CountDownLatch firstInserted = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update(sql, comp, fg, saItem);     // FG1 → SA1, 커밋 전
            firstInserted.countDown();
            try {
                Thread.sleep(500);                 // 두 번째가 잠금에서 기다리는 동안 커밋을 미룬다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(firstInserted.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> runner.run(comp,
                () -> jdbc.update(sql, comp, sa, fgItem)));   // SA1 → FG1
        first.get(10, TimeUnit.SECONDS);
        assertConstraint("ck_mst_bom_cycle", () -> second.get(10, TimeUnit.SECONDS));
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line", Integer.class)).isEqualTo(1);
    }

    // ---------- 확정 검사 · 기간 ----------

    @Test
    void 확정_검사_빈BOM_환산없음_대체비율_지난날짜_사용중지품목() throws Exception {
        long empty = bom("SA2");
        release(empty).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[*].code", hasItem("NO_LINES")));

        long id = bom("FG1");
        addLines(id, line("n1", 10, "RM1", "500", "\"unitCd\":\"G\""),
                line("n2", 20, "RM2", "1", "\"altGrp\":\"a1\",\"altPriority\":1,\"altPct\":\"60\""),
                line("n3", 30, "SA1", "1", "\"altGrp\":\"A1\",\"altPriority\":2,\"altPct\":\"30\""))
                .andExpect(status().isOk());
        accounts.exec(comp, "update mst_bom set valid_from = valid_from - 1 where bom_id = ?", id);
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'SA1'");
        release(id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", hasItem("PAST")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("NO_CONV")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("ALT_SUM")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("INACTIVE")))
                .andExpect(jsonPath("$.errors.length()").value(5));    // PAST·NO_CONV·ALT_SUM×2·INACTIVE

        // 고친다: 날짜·환산(1 G = 0.001 KG)·비율·품목
        accounts.exec(comp, "update mst_bom set valid_from = valid_from + 1 where bom_id = ?", id);
        accounts.exec(comp, "update mst_item set use_yn = 'Y' where item_cd = 'SA1'");
        long rm1 = itemId(comp, "RM1");
        mvc.perform(json(post("/api/master/items/" + rm1 + "/unit-conv/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"k1\",\"fromUnit\":\"G\",\"factor\":\"0.001\"}]}").with(admin))
                .andExpect(status().isOk());
        long l3 = accounts.query(comp, "select line_id from mst_bom_line where bom_id = ? and line_no = 30", Long.class, id);
        lines(admin, id, ver(id), null, "{\"rowKey\":\"u3\",\"lineId\":" + l3 + ",\"rowVer\":0,\"lineNo\":30,"
                + "\"itemCd\":\"SA1\",\"qty\":\"1\",\"altGrp\":\"A1\",\"altPriority\":2,\"altPct\":\"40\"}", null)
                .andExpect(status().isOk());
        release(id).andExpect(status().isOk());
    }

    @Test
    void 같은날_이후_시작하는_확정본과는_겹침_오류_직접SQL은_배제제약() throws Exception {
        long a0 = bom("FG1");
        addLines(a0, line("n1", 10, "SA1", "1", null)).andExpect(status().isOk());
        accounts.exec(comp, "update mst_bom set valid_from = valid_from + 5 where bom_id = ?", a0);
        release(a0).andExpect(status().isOk());
        long a1 = newRev(a0, today.plusDays(5).toString());       // 같은 날 시작
        release(a1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_PERIOD_OVERLAP"));
        assertThat(accounts.query(comp, "select status from mst_bom where bom_id = ?", String.class, a1))
                .isEqualTo("DRAFT");
        // 서비스를 거치지 않고 확정 — 배제 제약이 막는다
        assertConstraint("ex_mst_bom_period", () -> accounts.exec(comp,
                "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", a1));
    }

    @Test
    void 확정본_불변은_직접_SQL_도_막는다_종료일_앞당기기만_허용() throws Exception {
        long id = released("FG1", "SA1");
        assertConstraint("ck_mst_bom_immutable", () -> accounts.exec(comp, "update mst_bom set base_qty = 2 where bom_id = ?", id));
        assertConstraint("ck_mst_bom_line_draft", () -> accounts.exec(comp, "update mst_bom_line set qty = 2 where bom_id = ?", id));
        assertConstraint("ck_mst_bom_line_draft", () -> accounts.exec(comp, "delete from mst_bom_line where bom_id = ?", id));
        // 앱 계정은 머리 DELETE 권한 자체가 없다 (42501)
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_bom where bom_id = ?", id))))
                .contains("42501");
        assertConstraint("ck_mst_bom_transition",
                () -> accounts.exec(comp, "update mst_bom set status = 'DRAFT' where bom_id = ?", id));
        assertThat(accounts.exec(comp, "update mst_bom set valid_to = valid_from + 30 where bom_id = ?", id))
                .isEqualTo(1);
        assertConstraint("ck_mst_bom_immutable", () -> accounts.exec(comp, "update mst_bom set valid_to = valid_from + 60 where bom_id = ?", id));
    }

    // ---------- 이상 사용 ----------

    @Test
    void 행_검증_오류를_모두_모아서_알린다() throws Exception {
        long id = bom("FG1");
        addLines(id, line("e1", 10, "NOPE", "1", null),
                line("e2", 20, "SA1", "0", null),
                line("e3", 30, "SA1", "1.1234567", null),
                line("e4", 40, "RM1", "1", "\"scrapPct\":\"100\""),
                line("e5", 50, "SA2", "1", "\"lineType\":\"PHANTOM\",\"issueMethod\":\"MANUAL\""),
                line("e6", 60, "RM2", "1", "\"altGrp\":\"A1\""),
                line("e7", 60, "SA2", "1e3", null),
                line("e8", 70, "SA2", "1", "\"unitCd\":\"NOUNIT\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e1')].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e2')].code").value("POSITIVE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e3')].code").value("SCALE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e4')].code").value("RANGE_PCT"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e5')].field").value("issueMethod"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e6')].field", hasItem("altPriority")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e6')].field", hasItem("altPct")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e7')].code").value("FORMAT"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e8')].code").value("CODE"));
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line", Integer.class)).isZero();
        // 순번 중복 (같은 저장 안)
        addLines(id, line("d1", 10, "SA1", "1", null), line("d2", 10, "SA2", "1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", hasItem("DUPLICATE")));
    }

    @Test
    void 머리_버전이_다르면_행저장_409_같은_품목_작성중_둘은_안된다() throws Exception {
        long id = bom("FG1");
        int v = ver(id);
        lines(admin, id, v, line("n1", 10, "SA1", "1", null), null, null).andExpect(status().isOk());
        lines(admin, id, v, line("n2", 20, "SA2", "1", null), null, null).andExpect(status().isConflict());
        createBom(admin, "{\"itemCd\":\"FG1\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_DRAFT_EXISTS"));
        // 사용 중지 품목·없는 품목으로는 BOM 을 만들 수 없다
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'SA2'");
        createBom(admin, "{\"itemCd\":\"SA2\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        createBom(admin, "{\"itemCd\":\"NOPE\",\"rev\":\"a-1\",\"baseQty\":\"-1\",\"validFrom\":\"2026-02-30\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void 단위환산_기본단위_중복_사용불가단위_거부() throws Exception {
        long rm1 = itemId(comp, "RM1");
        String url = "/api/master/items/" + rm1 + "/unit-conv/batch";
        mvc.perform(json(post(url).param("rowVer", "0"), "{\"created\":["
                + "{\"rowKey\":\"k1\",\"fromUnit\":\"KG\",\"factor\":\"1\"},"
                + "{\"rowKey\":\"k2\",\"fromUnit\":\"ROLL\",\"factor\":\"850\"},"
                + "{\"rowKey\":\"k3\",\"fromUnit\":\"ROLL\",\"factor\":\"900\"},"
                + "{\"rowKey\":\"k4\",\"fromUnit\":\"NOUNIT\",\"factor\":\"1\"},"
                + "{\"rowKey\":\"k5\",\"fromUnit\":\"G\",\"factor\":\"0\"}]}").with(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='k1')].code").value("CODE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='k3')].code").value("DUPLICATE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='k4')].code").value("CODE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='k5')].code").value("POSITIVE"));
        mvc.perform(json(post(url).param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"k2\",\"fromUnit\":\"ROLL\",\"factor\":\"850\"}]}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/items/" + rm1 + "/unit-conv").with(admin))
                .andExpect(jsonPath("$.data[0].fromUnit").value("ROLL"))
                .andExpect(jsonPath("$.data[0].toUnit").value("KG"))
                .andExpect(jsonPath("$.data[0].factor").value(850));
        // 품목 버전이 올라갔다 — 옛 버전으로 다시 저장하면 409
        mvc.perform(json(post(url).param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"k9\",\"fromUnit\":\"TON\",\"factor\":\"1000\"}]}").with(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void B사_사용자가_A사_BOM을_조회_수정_확정_못하고_A사_품목을_행에_못쓴다() throws Exception {
        long aBom = bom("FG1");
        addLines(aBom, line("n1", 10, "SA1", "1", null)).andExpect(status().isOk());
        String other = accounts.newComp("BM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        int v = ver(aBom);
        mvc.perform(get(BASE + "/" + aBom).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(BASE).with(b)).andExpect(jsonPath("$.total").value(0));
        lines(b, aBom, v, line("x", 20, "SA2", "1", null), null, null).andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/" + aBom + "/release"), "{\"rowVer\":" + v + "}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/" + aBom + "/obsolete"), "{\"rowVer\":" + v + "}").with(b))
                .andExpect(status().isNotFound());
        // B사는 A사 품목 코드로 BOM 을 만들 수 없다(B사에 그 품목 없음)
        createBom(b, "{\"itemCd\":\"FG1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL: B사 BOM 에 A사 품목 id — 트리거가 막는다
        item(other, "BFG", "EA", "N");
        long bBom = accounts.query(other, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't') returning bom_id""", Long.class, other,
                itemId(other, "BFG"));
        assertConstraint("fk_mst_bom_line_item_comp", () -> accounts.exec(other, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method,
                                          created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""", other, bBom, itemId(comp, "SA1")));
        // A사 BOM 에 B사 행을 끼워 넣기 — 머리 회사 확인
        assertConstraint("fk_mst_bom_line_bom_comp", () -> accounts.exec(other, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method,
                                          created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""", other, aBom, itemId(other, "BFG")));
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where bom_id = ?", Integer.class, aBom))
                .isEqualTo(1);
    }

    @Test
    void 권한_조회만_있으면_저장_확정_폐기_403() throws Exception {
        long id = bom("FG1");
        addLines(id, line("n1", 10, "SA1", "1", null)).andExpect(status().isOk());
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-bom", "SEARCH", "SAVE");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get(BASE + "/" + id).with(r)).andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/" + id + "/release"), "{\"rowVer\":" + ver(id) + "}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/" + id + "/obsolete"), "{\"rowVer\":" + ver(id) + "}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/master/items/" + itemId(comp, "RM1") + "/unit-conv/batch").param("rowVer", "0"),
                "{\"created\":[]}").with(r)).andExpect(status().isForbidden());
    }
}
