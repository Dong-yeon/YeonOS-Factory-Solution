package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
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
 * BOM 3a 이상 사용 (QA 세션, docs/qa/bom-3a.md) — 경계값·대체 그룹·리비전 채번·상태 전이·기간·동시성·순환·단위 환산·A/B사·권한.
 * BomApiTest 에서 이미 본 것은 다시 보지 않는다. 결함n_ 테스트는 QA 에서 찾은 결함의 재현(수정 후 통과).
 */
@AutoConfigureMockMvc
class BomAbuseTest extends PostgresTestBase {

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
        comp = accounts.newComp("BQ");
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

    // ---------- 도우미 (BomApiTest 와 같은 패턴) ----------

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

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long bom(String itemCd) throws Exception {
        return data(createBom(admin, "{\"itemCd\":\"" + itemCd + "\"}"));
    }

    private int ver(long bomId) {
        return accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, bomId);
    }

    private String col(long bomId, String col) {
        return accounts.query(comp, "select " + col + "::text from mst_bom where bom_id = ?", String.class, bomId);
    }

    private static String line(String key, int no, String itemCd, String qty, String extra) {
        return "{\"rowKey\":\"" + key + "\",\"lineNo\":" + no + ",\"itemCd\":\"" + itemCd + "\",\"qty\":\"" + qty + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private static String batch(String created, String updated, String deleted) {
        return "{\"created\":[" + (created == null ? "" : created) + "],\"updated\":["
                + (updated == null ? "" : updated) + "],\"deleted\":[" + (deleted == null ? "" : deleted) + "]}";
    }

    private MockHttpServletRequestBuilder linesReq(RequestPostProcessor who, long bomId, int rowVer, String body) {
        return json(post(BASE + "/" + bomId + "/lines/batch").param("rowVer", "" + rowVer), body).with(who);
    }

    private ResultActions lines(RequestPostProcessor who, long bomId, int rowVer, String created, String updated,
            String deleted) throws Exception {
        return mvc.perform(linesReq(who, bomId, rowVer, batch(created, updated, deleted)));
    }

    private ResultActions addLines(long bomId, String... created) throws Exception {
        return lines(admin, bomId, ver(bomId), String.join(",", created), null, null);
    }

    private MockHttpServletRequestBuilder actionReq(RequestPostProcessor who, long bomId, String action, int rowVer) {
        return json(post(BASE + "/" + bomId + "/" + action), "{\"rowVer\":" + rowVer + "}").with(who);
    }

    private ResultActions release(long bomId) throws Exception {
        return mvc.perform(actionReq(admin, bomId, "release", ver(bomId)));
    }

    private ResultActions obsolete(long bomId) throws Exception {
        return mvc.perform(actionReq(admin, bomId, "obsolete", ver(bomId)));
    }

    private ResultActions newRevReq(long bomId, String validFrom) throws Exception {
        return mvc.perform(json(post(BASE + "/" + bomId + "/new-revision"),
                "{\"rowVer\":" + ver(bomId) + (validFrom == null ? "" : ",\"validFrom\":\"" + validFrom + "\"") + "}")
                .with(admin));
    }

    private long newRev(long bomId, String validFrom) throws Exception {
        return data(newRevReq(bomId, validFrom));
    }

    private ResultActions updateHead(long bomId, String fields) throws Exception {
        return mvc.perform(json(put(BASE + "/" + bomId), "{" + fields + ",\"rowVer\":" + ver(bomId) + "}").with(admin));
    }

    private long released(String parent, String child) throws Exception {
        long id = bom(parent);
        addLines(id, line("n1", 10, child, "1", null)).andExpect(status().isOk());
        release(id).andExpect(status().isOk());
        return id;
    }

    private long lineId(long bomId, int lineNo) {
        return accounts.query(comp, "select line_id from mst_bom_line where bom_id = ? and line_no = ?", Long.class,
                bomId, lineNo);
    }

    /** SQL 로 작성 중 머리를 넣고(리비전 지정) 바로 폐기 — 리비전 이력만 만든다 */
    private void obsoleteRevBySql(String itemCd, String rev) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, ?, (now() at time zone 'Asia/Seoul')::date, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, comp, rev, itemCd);
        accounts.exec(comp, "update mst_bom set status = 'OBSOLETE' where bom_id = ?", id);
    }

    private int itemVer(long itemId) {
        return accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, itemId);
    }

    private ResultActions conv(RequestPostProcessor who, long itemId, int itemVer, String body) throws Exception {
        return mvc.perform(json(post("/api/master/items/" + itemId + "/unit-conv/batch").param("rowVer", "" + itemVer),
                body).with(who));
    }

    private static void assertConstraint(String name, ThrowingCallable call) {
        Throwable t = catchThrowable(call);
        assertThat(t).as("DB 가 막아야 한다: " + name).isNotNull();
        assertThat(PgErrors.constraint(t)).as(String.valueOf(t)).contains(name);
    }

    /** 다른 스레드에서 API 호출 (동시성) */
    private CompletableFuture<MvcResult> async(MockHttpServletRequestBuilder req) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(req).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** 회사 범위 트랜잭션 안에서 work 를 하고 holdMs 동안 커밋을 미룬다 — 그 사이 다른 요청이 같은 행·인덱스에서 기다리게 */
    private CompletableFuture<Void> holdTx(Runnable work, CountDownLatch done, long holdMs) {
        return CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            work.run();
            done.countDown();
            try {
                Thread.sleep(holdMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // =====================================================================
    // 1. 경계값
    // =====================================================================

    @Test
    void 경계_순번_0_9999_10000_정수범위밖() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 0, "SA1", "1", null), line("b", 10000, "SA2", "1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='a')].code").value("CODE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='b')].code").value("CODE"));
        addLines(id, line("c", 9999, "SA1", "1", null), line("d", 1, "SA2", "1", null)).andExpect(status().isOk());
        // int 범위를 넘는 순번 — JSON 해석 실패는 400(500 아님)
        mvc.perform(linesReq(admin, id, ver(id), "{\"created\":[{\"rowKey\":\"e\",\"lineNo\":3000000000,"
                + "\"itemCd\":\"RM1\",\"qty\":\"1\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void 경계_수량_정수12자리_13자리_소수6자리_손실률_99_99() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "999999999999.999999", null),
                line("b", 20, "SA2", "1", "\"scrapPct\":\"99.99\""),
                line("c", 30, "RM1", "000000000001", null))                            // 앞자리 0 은 자릿수에 안 센다
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select qty from mst_bom_line where bom_id = ? and line_no = 10",
                BigDecimal.class, id)).isEqualByComparingTo("999999999999.999999");
        addLines(id, line("d", 40, "RM2", "9999999999999", null),
                line("e", 50, "RM2", "1", "\"scrapPct\":\"99.999\""),
                line("f", 60, "RM2", "-1", null),
                line("g", 70, "RM2", "1,000", null),
                line("h", 80, "RM2", "1", "\"opSeq\":10000"),
                line("i", 90, "RM2", "1", "\"altGrp\":\"G\",\"altPriority\":0,\"altPct\":\"100\""),
                line("j", 100, "RM2", "1", "\"position\":\"" + "P".repeat(101) + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='d')].code").value("INT_DIGITS"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e')].code").value("SCALE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='f')].code").value("POSITIVE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='g')].code").value("FORMAT"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='h')].code").value("CODE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='i')].code").value("CODE"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='j')].code").value("TOO_LONG"));
    }

    @Test
    void 경계_대체비율_100_허용_100_01_거부() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", "\"altGrp\":\"G1\",\"altPriority\":1,\"altPct\":\"100.01\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE_PCT"));
        // 그룹에 행 하나, 비율 100 → 확정 가능
        addLines(id, line("b", 10, "SA1", "1", "\"altGrp\":\"G1\",\"altPriority\":1,\"altPct\":\"100\""))
                .andExpect(status().isOk());
        release(id).andExpect(status().isOk());
    }

    @Test
    void 경계_리비전_소문자_5자리_특수문자_대체번호_0_100() throws Exception {
        createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"abcde\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"A-1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rev"));
        createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"A 1\"}").andExpect(status().isBadRequest());
        createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"가1\"}").andExpect(status().isBadRequest());
        createBom(admin, "{\"itemCd\":\"SA1\",\"altNo\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("altNo"));
        createBom(admin, "{\"itemCd\":\"SA1\",\"altNo\":100}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("altNo"));
        createBom(admin, "{\"itemCd\":\"SA1\",\"altNo\":70000}").andExpect(status().isBadRequest());
        long id = data(createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"  b1 \"}"));
        assertThat(col(id, "rev")).isEqualTo("B1");
        // 같은 리비전 다시(작성 중 폐기 후)
        obsolete(id).andExpect(status().isOk());
        createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"B1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void 경계_시작일_2999_12_31_1999_잘못된날짜() throws Exception {
        for (String bad : new String[] {"1999-12-31", "3000-01-01", "2026-02-29", "2026-13-01", "20261019",
                "2026-1-5", "2026-10-19T00:00"}) {
            createBom(admin, "{\"itemCd\":\"SA1\",\"validFrom\":\"" + bad + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("validFrom"));
        }
        long id = data(createBom(admin, "{\"itemCd\":\"SA1\",\"validFrom\":\"2999-12-31\"}"));
        addLines(id, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        release(id).andExpect(status().isOk());
    }

    /** 결함 #5: 2999-12-31 시작 확정본에서 시작일을 비우고 새 리비전 → 3000-01-01 (머리 검증의 연도 범위 2000~2999 밖) */
    @Test
    void 결함5_새리비전_기본시작일이_허용범위_2999년을_넘는다() throws Exception {
        long id = data(createBom(admin, "{\"itemCd\":\"SA1\",\"validFrom\":\"2999-12-31\"}"));
        addLines(id, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        release(id).andExpect(status().isOk());
        ResultActions r = newRevReq(id, null);
        int st = r.andReturn().getResponse().getStatus();
        if (st == 200) {
            long rev = data(r);
            assertThat(LocalDate.parse(col(rev, "valid_from")).getYear()).isLessThanOrEqualTo(2999);
        } else {
            assertThat(st).isEqualTo(400);
        }
    }

    @Test
    void 경계_머리_기준수량_변경번호_길이() throws Exception {
        createBom(admin, "{\"itemCd\":\"SA1\",\"baseQty\":\"0\",\"changeNo\":\"" + "C".repeat(31) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='baseQty')].code").value("POSITIVE"))
                .andExpect(jsonPath("$.errors[?(@.field=='changeNo')].code").value("TOO_LONG"));
        long id = data(createBom(admin, "{\"itemCd\":\"SA1\",\"baseQty\":\"999999999999.999999\"}"));
        assertThat(new BigDecimal(col(id, "base_qty"))).isEqualByComparingTo("999999999999.999999");
    }

    // =====================================================================
    // 2. 대체 그룹
    // =====================================================================

    @Test
    void 대체그룹_둘_섞임_한쪽만_합100_아님이면_그쪽_행만_오류_소문자는_대문자로() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", "\"altGrp\":\"g1\",\"altPriority\":1,\"altPct\":\"60\""),
                line("b", 20, "SA2", "1", "\"altGrp\":\"G1\",\"altPriority\":2,\"altPct\":\"40\""),
                line("c", 30, "RM1", "1", "\"altGrp\":\"g2\",\"altPriority\":1,\"altPct\":\"50\""),
                line("d", 40, "RM2", "1", "\"altGrp\":\"G2\",\"altPriority\":2,\"altPct\":\"40\""))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(distinct alt_grp, ',') from mst_bom_line where bom_id = ?",
                String.class, id)).isEqualTo("G1,G2");
        long c = lineId(id, 30);
        long d = lineId(id, 40);
        release(id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].rowKey", hasItem("L" + c)))
                .andExpect(jsonPath("$.errors[*].rowKey", hasItem("L" + d)));
    }

    @Test
    void 대체그룹_가상조립_부산물_행에는_거부_직접SQL도() throws Exception {
        long id = bom("FG1");
        addLines(id, line("p", 10, "SA1", "1", "\"lineType\":\"PHANTOM\",\"altGrp\":\"G1\",\"altPriority\":1,\"altPct\":\"100\""),
                line("y", 20, "SA2", "1", "\"lineType\":\"BYPRODUCT\",\"altGrp\":\"G1\",\"altPriority\":1,\"altPct\":\"100\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='p')].field").value("altGrp"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='y')].field").value("altGrp"));
        assertConstraint("ck_mst_bom_line_alt", () -> accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, line_type, qty, unit_cd, alt_grp,
                                          alt_priority, alt_pct, created_by, updated_by)
                values (?, ?, 10, ?, 'PHANTOM', 1, 'EA', 'G1', 1, 100, 't', 't')""", comp, id, itemId(comp, "SA1")));
    }

    /** 결함 #2: 같은 대체 그룹에 우선순위 1 이 둘(또는 하나도 없음)이어도 확정된다 — docs/18 "우선순위 1 이 기본 소요" 가 모호해짐 */
    @Test
    void 결함2_대체그룹_우선순위_중복이면_확정_거부() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", "\"altGrp\":\"G1\",\"altPriority\":1,\"altPct\":\"50\""),
                line("b", 20, "SA2", "1", "\"altGrp\":\"G1\",\"altPriority\":1,\"altPct\":\"50\""))
                .andExpect(status().isOk());
        release(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_RELEASE_CHECK"));
    }

    @Test
    void 대체그룹_우선순위_1이_없어도_확정_거부_고치면_확정() throws Exception {
        long id = bom("FG1");
        addLines(id, line("c", 30, "RM1", "1", "\"altGrp\":\"G2\",\"altPriority\":2,\"altPct\":\"30\""),
                line("d", 40, "RM2", "1", "\"altGrp\":\"G2\",\"altPriority\":3,\"altPct\":\"70\""))
                .andExpect(status().isOk());
        release(id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("altPriority"))
                .andExpect(jsonPath("$.errors[0].code").value("ALT_PRIORITY"));
        long c = lineId(id, 30);
        lines(admin, id, ver(id), null, "{\"rowKey\":\"u\",\"lineId\":" + c + ",\"rowVer\":0,\"lineNo\":30,"
                + "\"itemCd\":\"RM1\",\"qty\":\"1\",\"altGrp\":\"G2\",\"altPriority\":1,\"altPct\":\"30\"}", null)
                .andExpect(status().isOk());
        release(id).andExpect(status().isOk());
    }

    // =====================================================================
    // 3. 리비전 자동 채번
    // =====================================================================

    @Test
    void 리비전_A9_다음은_B0_사용자_REV1_은_무시() throws Exception {
        obsoleteRevBySql("SA1", "A9");
        long b0 = bom("SA1");
        assertThat(col(b0, "rev")).isEqualTo("B0");
        // 사용자가 직접 넣은 REV1 이 섞여도 자동 채번은 문자1+숫자1 형식만 본다
        obsoleteRevBySql("SA2", "A3");
        long manual = data(createBom(admin, "{\"itemCd\":\"SA2\",\"rev\":\"rev1\"}"));
        assertThat(col(manual, "rev")).isEqualTo("REV1");
        obsolete(manual).andExpect(status().isOk());
        assertThat(col(bom("SA2"), "rev")).isEqualTo("A4");
        // "B12"(3자리)·"Z"(1자리)도 자동 형식 아님
        obsoleteRevBySql("RM1", "B12");
        obsoleteRevBySql("RM1", "Z");
        assertThat(col(bom("RM1"), "rev")).isEqualTo("A0");
    }

    @Test
    void 리비전_Z9_다음은_AA0_ZZ9_다음만_소진_새리비전은_리비전을_직접_받는다() throws Exception {
        // QA 3a #4 수정: 자동 채번이 두 글자(AA0~ZZ9)로 이어진다
        obsoleteRevBySql("SA1", "Z9");
        long aa0 = data(createBom(admin, "{\"itemCd\":\"SA1\"}"));
        assertThat(col(aa0, "rev")).isEqualTo("AA0");
        addLines(aa0, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        release(aa0).andExpect(status().isOk());
        assertThat(col(newRev(aa0, today.plusDays(1).toString()), "rev")).isEqualTo("AA1");
        // ZZ9 다음만 소진 — 그때도 새 리비전은 리비전을 직접 넣어 만들 수 있다
        obsoleteRevBySql("SA2", "ZZ9");
        createBom(admin, "{\"itemCd\":\"SA2\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_REV_EXHAUSTED"));
        long m = data(createBom(admin, "{\"itemCd\":\"SA2\",\"rev\":\"M1\"}"));
        addLines(m, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        release(m).andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/" + m + "/new-revision"), "{\"rowVer\":" + ver(m) + ",\"validFrom\":\""
                + today.plusDays(1) + "\",\"rev\":\"x-1\"}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rev"));
        mvc.perform(json(post(BASE + "/" + m + "/new-revision"), "{\"rowVer\":" + ver(m) + ",\"validFrom\":\""
                + today.plusDays(1) + "\",\"rev\":\"m1\"}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(json(post(BASE + "/" + m + "/new-revision"), "{\"rowVer\":" + ver(m) + ",\"validFrom\":\""
                + today.plusDays(1) + "\",\"rev\":\"m2\"}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 사용자가_첫_리비전을_B5로_넣으면_자동은_B6부터_Z9_다음도_이어진다() throws Exception {
        long b5 = data(createBom(admin, "{\"itemCd\":\"SA1\",\"rev\":\"B5\"}"));
        addLines(b5, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        release(b5).andExpect(status().isOk());
        assertThat(col(newRev(b5, today.plusDays(1).toString()), "rev")).isEqualTo("B6");
        long z9 = data(createBom(admin, "{\"itemCd\":\"SA2\",\"rev\":\"Z9\"}"));
        addLines(z9, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        release(z9).andExpect(status().isOk());
        assertThat(col(newRev(z9, today.plusDays(1).toString()), "rev")).isEqualTo("AA0");
    }

    @Test
    void 자동_리비전_순서() {
        assertThat(BomService.nextAutoRev("A0")).isEqualTo("A1");
        assertThat(BomService.nextAutoRev("A9")).isEqualTo("B0");
        assertThat(BomService.nextAutoRev("Z9")).isEqualTo("AA0");
        assertThat(BomService.nextAutoRev("AZ9")).isEqualTo("BA0");
        assertThat(BomService.nextAutoRev("ZY9")).isEqualTo("ZZ0");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BomService.nextAutoRev("ZZ9"))
                .isInstanceOfSatisfying(io.smarthappy.platform.common.web.BusinessException.class, x -> assertThat(x.code())
                        .isEqualTo(io.smarthappy.platform.common.web.HbErrorCode.BOM_REV_EXHAUSTED));
    }

    // =====================================================================
    // 4. 상태 전이
    // =====================================================================

    @Test
    void 상태_폐기본에서_새리비전_확정_폐기후_같은품목_새BOM() throws Exception {
        long a0 = released("FG1", "SA1");
        obsolete(a0).andExpect(status().isOk());
        long a1 = newRev(a0, null);
        assertThat(col(a1, "valid_from")).isEqualTo(today.toString());   // 폐기본 원본 → 오늘
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where bom_id = ?", Integer.class, a1))
                .isEqualTo(1);
        release(a1).andExpect(status().isOk());
        assertThat(col(a0, "valid_to")).isNull();                        // 폐기본은 마감하지 않는다

        long d = bom("SA2");
        obsolete(d).andExpect(status().isOk());                          // 작성 중 → 폐기
        long d2 = bom("SA2");
        assertThat(col(d2, "rev")).isEqualTo("A1");
    }

    @Test
    void 상태_확정_두번_폐기_두번_옛버전_폐기_폐기본_확정() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", null)).andExpect(status().isOk());
        int v = ver(id);
        mvc.perform(actionReq(admin, id, "release", v)).andExpect(status().isOk());
        mvc.perform(actionReq(admin, id, "release", v)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        int v2 = ver(id);
        mvc.perform(actionReq(admin, id, "obsolete", v)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));            // 옛 버전
        mvc.perform(actionReq(admin, id, "obsolete", v2)).andExpect(status().isOk());
        mvc.perform(actionReq(admin, id, "obsolete", ver(id))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        mvc.perform(actionReq(admin, id, "release", ver(id))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        mvc.perform(json(post(BASE + "/" + id + "/release"), "{}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(BASE + "/" + id + "/release"), "").with(admin)).andExpect(status().isBadRequest());
        // 폐기본 행 수정
        lines(admin, id, ver(id), line("b", 20, "SA2", "1", null), null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
    }

    // =====================================================================
    // 5. 기간
    // =====================================================================

    @Test
    void 기간_A0오늘_A1_10일_확정후_A2_5일은_겹침_실패하면_A0마감도_되돌린다() throws Exception {
        long a0 = released("FG1", "SA1");
        long a1 = newRev(a0, today.plusDays(10).toString());
        release(a1).andExpect(status().isOk());
        assertThat(col(a0, "valid_to")).isEqualTo(today.plusDays(10).toString());
        long a2 = newRev(a1, today.plusDays(5).toString());
        release(a2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_PERIOD_OVERLAP"));
        // A0 을 +5 로 마감한 뒤 A1 에서 실패 — 전체 롤백이라 A0 은 +10 그대로
        assertThat(col(a0, "valid_to")).isEqualTo(today.plusDays(10).toString());
        assertThat(col(a2, "status")).isEqualTo("DRAFT");
        // 시작일을 A1 뒤(+15)로 바꾸면 A1 만 마감
        updateHead(a2, "\"validFrom\":\"" + today.plusDays(15) + "\"").andExpect(status().isOk());
        release(a2).andExpect(status().isOk());
        assertThat(col(a1, "valid_to")).isEqualTo(today.plusDays(15).toString());
        assertThat(col(a0, "valid_to")).isEqualTo(today.plusDays(10).toString());
        // 기준일마다 유효 확정본은 하나
        for (int d : new int[] {0, 9, 10, 14, 15, 400}) {
            assertThat(accounts.query(comp, """
                    select count(*) from mst_bom where status = 'RELEASED'
                       and daterange(valid_from, valid_to, '[)') @> ?::date""", Integer.class,
                    today.plusDays(d).toString())).as("D+" + d).isEqualTo(1);
        }
    }

    @Test
    void 관찰_종료일이_있는_확정본_앞에_끼워넣기는_불가_새확정본은_항상_열린기간() throws Exception {
        long s0 = bom("SA1");
        addLines(s0, line("a", 10, "RM1", "1", null)).andExpect(status().isOk());
        updateHead(s0, "\"validFrom\":\"" + today.plusDays(5) + "\"").andExpect(status().isOk());
        release(s0).andExpect(status().isOk());
        accounts.exec(comp, "update mst_bom set valid_to = ? where bom_id = ?", today.plusDays(10), s0);
        long s1 = newRev(s0, today.toString());
        release(s1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_PERIOD_OVERLAP"));
    }

    // =====================================================================
    // 6. 동시성
    // =====================================================================

    @Test
    void 동시_같은BOM_행저장_두사람_뒤쪽은_머리잠금_기다린뒤_409() throws Exception {
        long id = bom("FG1");
        int v = ver(id);
        long sa1 = itemId(comp, "SA1");
        CountDownLatch locked = new CountDownLatch(1);
        // 사용자 1 의 행 저장(머리 버전 증가 + 행 추가)이 커밋 전
        CompletableFuture<Void> first = holdTx(() -> {
            jdbc.update("update mst_bom set row_ver = row_ver + 1 where bom_id = ?", id);
            jdbc.update("""
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                    values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""", comp, id, sa1);
        }, locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(linesReq(admin, id, v, batch(line("b", 10, "SA2", "1", null), null, null)));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(409);
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where bom_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void 동시_행저장_중에_확정하면_확정이_409_검사_안된_행으로_확정되지_않는다() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", null)).andExpect(status().isOk());
        int v = ver(id);
        long rm1 = itemId(comp, "RM1");
        CountDownLatch locked = new CountDownLatch(1);
        // 사용자 1: 환산 없는 단위(G) 행을 넣는 중 — 확정 검사를 통과하면 안 되는 행
        CompletableFuture<Void> first = holdTx(() -> {
            jdbc.update("update mst_bom set row_ver = row_ver + 1 where bom_id = ?", id);
            jdbc.update("""
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                    values (?, ?, 20, ?, 1, 'G', 'MANUAL', 't', 't')""", comp, id, rm1);
        }, locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> rel = async(actionReq(admin, id, "release", v));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = rel.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(409);
        assertThat(col(id, "status")).isEqualTo("DRAFT");
    }

    @Test
    void 동시_확정_중에_행저장하면_행저장이_409() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", null)).andExpect(status().isOk());
        int v = ver(id);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't', row_ver = row_ver + 1
                 where bom_id = ?""", id), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> save = async(linesReq(admin, id, v, batch(line("b", 20, "SA2", "1", null), null, null)));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = save.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(409);
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where bom_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    /** 결함 #7: 같은 품목을 동시에 신규 — 뒤쪽 요청이 리비전을 비웠는데도 "리비전 중복"(400) 으로 나온다. 기대는 409 BOM_DRAFT_EXISTS */
    @Test
    void 결함7_동시_같은품목_신규_두번_뒤쪽은_BOM_DRAFT_EXISTS() throws Exception {
        long fg = itemId(comp, "FG1");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", comp, fg), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(BASE), "{\"itemCd\":\"FG1\"}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(409);
        assertThat((String) JsonPath.read(body(r), "$.code")).isEqualTo("BOM_DRAFT_EXISTS");
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isEqualTo(1);
    }

    @Test
    void 동시_같은품목_신규_리비전이_다르면_BOM_DRAFT_EXISTS() throws Exception {
        long fg = itemId(comp, "FG1");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'X1', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", comp, fg), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(BASE), "{\"itemCd\":\"FG1\"}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(409);
        assertThat((String) JsonPath.read(body(r), "$.code")).isEqualTo("BOM_DRAFT_EXISTS");
    }

    @Test
    void 동시_새리비전_두번_뒤쪽은_409_작성중은_하나() throws Exception {
        long a0 = released("FG1", "SA1");
        int v = ver(a0);
        long fg = itemId(comp, "FG1");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'A1', (now() at time zone 'Asia/Seoul')::date + 1, 't', 't')""", comp, fg), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(actionReq(admin, a0, "new-revision", v));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(409);
        assertThat(accounts.query(comp, "select count(*) from mst_bom where status = 'DRAFT'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 동시_실제_API_새리비전_8건_동시호출_작성중은_하나() throws Exception {
        long a0 = released("FG1", "SA1");
        int v = ver(a0);
        List<CompletableFuture<MvcResult>> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            all.add(async(actionReq(admin, a0, "new-revision", v)));
        }
        int ok = 0;
        for (CompletableFuture<MvcResult> f : all) {
            MvcResult r = f.get(20, TimeUnit.SECONDS);
            int st = r.getResponse().getStatus();
            assertThat(st).as(body(r)).isIn(200, 409);
            ok += st == 200 ? 1 : 0;
        }
        assertThat(ok).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from mst_bom where status = 'DRAFT'", Integer.class))
                .isEqualTo(1);
    }

    // =====================================================================
    // 7. 순환
    // =====================================================================

    @Test
    void 순환_깊이10_체인_끝에서_맨위로() throws Exception {
        for (int i = 0; i <= 10; i++) {
            item(comp, "L" + i, "EA", "N");
        }
        long last = 0;
        for (int i = 0; i < 10; i++) {
            last = bom("L" + i);
            addLines(last, line("a", 10, "L" + (i + 1), "1", null)).andExpect(status().isOk());
        }
        long tail = bom("L10");
        addLines(tail, line("c", 10, "L0", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        addLines(tail, line("c", 10, "L5", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        assertConstraint("ck_mst_bom_cycle", () -> accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""", comp, tail, itemId(comp, "L0")));
        // 기존 행의 자품목을 바꿔서 순환 — 수정 경로
        long l9 = accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) where i.item_cd = 'L9'",
                Long.class);
        long l9line = lineId(l9, 10);
        lines(admin, l9, ver(l9), null, "{\"rowKey\":\"u\",\"lineId\":" + l9line + ",\"rowVer\":0,\"lineNo\":10,"
                + "\"itemCd\":\"L0\",\"qty\":\"1\"}", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
    }

    @Test
    void 순환_다이아몬드는_순환이_아니다() throws Exception {
        item(comp, "D", "EA", "N");
        long a = bom("FG1");
        addLines(a, line("b", 10, "SA1", "1", null), line("c", 20, "SA2", "1", null)).andExpect(status().isOk());
        long b = bom("SA1");
        addLines(b, line("d", 10, "D", "1", null)).andExpect(status().isOk());
        long c = bom("SA2");
        addLines(c, line("d", 10, "D", "1", null)).andExpect(status().isOk());
        long d = bom("D");
        addLines(d, line("r", 10, "RM1", "1", null)).andExpect(status().isOk());
        for (long id : new long[] {d, b, c, a}) {
            release(id).andExpect(status().isOk());
        }
        // 같은 자품목을 한 BOM 에 두 번(다른 순번)도 순환 아님
        long a1 = newRev(a, today.plusDays(1).toString());
        addLines(a1, line("dd", 30, "D", "2", null)).andExpect(status().isOk());
        addLines(a1, line("dd2", 40, "D", "1", null)).andExpect(status().isOk());
        // 그러나 D 아래 FG1 은 순환
        long d1 = newRev(d, today.plusDays(1).toString());
        addLines(d1, line("x", 20, "FG1", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
    }

    @Test
    void 순환_대체번호_2_의_BOM_을_통한_순환() throws Exception {
        long alt2 = data(createBom(admin, "{\"itemCd\":\"FG1\",\"altNo\":2}"));
        addLines(alt2, line("a", 10, "SA1", "1", null)).andExpect(status().isOk());
        assertThat(col(alt2, "alt_no")).isEqualTo("2");
        long sa1 = bom("SA1");
        addLines(sa1, line("c", 10, "FG1", "1", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
    }

    /** 결함 #1: 작성 중 머리의 item_id 를 직접 SQL 로 바꾸면 순환 검사를 거치지 않는다 (V015 머리 주석은 직접 SQL 도 막는다고 함) */
    @Test
    void 결함1_작성중_머리_모품목을_직접SQL로_바꾸면_순환이_생긴다() throws Exception {
        released("FG1", "SA1");                     // FG1 ← SA1
        long d = bom("RM2");
        addLines(d, line("a", 10, "FG1", "1", null)).andExpect(status().isOk());   // RM2 ← FG1 (정상)
        long sa1 = itemId(comp, "SA1");
        assertConstraint("ck_mst_bom_key_immutable",
                () -> accounts.exec(comp, "update mst_bom set item_id = ? where bom_id = ?", sa1, d));
        assertConstraint("ck_mst_bom_key_immutable",
                () -> accounts.exec(comp, "update mst_bom set alt_no = 2 where bom_id = ?", d));
    }

    // =====================================================================
    // 8. 단위 환산
    // =====================================================================

    /**
     * 옛 기준 환산 — V018 이후엔 쓰이는 품목의 기본 단위를 바꿀 수 없으므로, 그 전에 만들어진 데이터처럼
     * 기본 단위(KG → G)를 먼저 바꾸고 to_unit = KG 인 ROLL 환산을 SQL 로 넣는다. 반환 conv_id
     */
    private long staleRollConv(long itemId) {
        accounts.exec(comp, "update mst_item set unit_cd = 'G', row_ver = row_ver + 1 where item_id = ?", itemId);
        return accounts.query(comp, """
                insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by)
                values (?, ?, 'ROLL', 'KG', 850, 't', 't') returning conv_id""", Long.class, comp, itemId);
    }

    @Test
    void 환산_옛기준_환산은_확정검사에서_NO_CONV_삭제후_같은단위_재추가하면_새기준으로() throws Exception {
        long rm1 = itemId(comp, "RM1");
        long convId = staleRollConv(rm1);
        long id = bom("FG1");
        addLines(id, line("a", 10, "RM1", "1", "\"unitCd\":\"ROLL\"")).andExpect(status().isOk());
        release(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_CONV"));
        // 같은 배치에서 삭제(사용 안 함) + 같은 단위 추가 → 같은 행을 되살리며 to_unit = 새 기본 단위
        conv(admin, rm1, itemVer(rm1), "{\"created\":[{\"rowKey\":\"n\",\"fromUnit\":\"ROLL\",\"factor\":\"850000\"}],"
                + "\"deleted\":[{\"rowKey\":\"d\",\"convId\":" + convId + ",\"rowVer\":0}]}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/items/" + rm1 + "/unit-conv").with(admin))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].convId").value(convId))
                .andExpect(jsonPath("$.data[0].toUnit").value("G"));
        release(id).andExpect(status().isOk());
    }

    /** 결함 #3: 기본 단위를 바꾼 뒤 환산 "수정"(배율만 고침)은 to_unit 을 옛 기본 단위로 남긴다 → 화면엔 옛 기준 단위, 확정 검사는 계속 NO_CONV */
    @Test
    void 결함3_기본단위_변경후_환산_수정이_to_unit_을_고치지_않는다() throws Exception {
        long rm1 = itemId(comp, "RM1");
        long convId = staleRollConv(rm1);
        conv(admin, rm1, itemVer(rm1), "{\"updated\":[{\"rowKey\":\"u\",\"convId\":" + convId
                + ",\"rowVer\":0,\"fromUnit\":\"ROLL\",\"factor\":\"850000\"}]}").andExpect(status().isOk());
        // 기대: 목록이 옛 기준(KG) 환산을 "새 기준" 처럼 보여 주지 않는다 — 수정하면 새 기본 단위 기준이 되거나, 옛 기준 행은 구분 표시
        mvc.perform(get("/api/master/items/" + rm1 + "/unit-conv").with(admin))
                .andExpect(jsonPath("$.data[0].toUnit").value("G"));
        long id = bom("FG1");
        addLines(id, line("a", 10, "RM1", "1", "\"unitCd\":\"ROLL\"")).andExpect(status().isOk());
        release(id).andExpect(status().isOk());
    }

    @Test
    void 환산_사용안함_후_따로_같은단위_재추가는_같은행_되살림() throws Exception {
        long rm1 = itemId(comp, "RM1");
        conv(admin, rm1, itemVer(rm1), "{\"created\":[{\"rowKey\":\"k\",\"fromUnit\":\"ROLL\",\"factor\":\"850\"}]}")
                .andExpect(status().isOk());
        long convId = accounts.query(comp, "select conv_id from mst_unit_conv where item_id = ?", Long.class, rm1);
        conv(admin, rm1, itemVer(rm1), "{\"deleted\":[{\"rowKey\":\"d\",\"convId\":" + convId + ",\"rowVer\":0}]}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/items/" + rm1 + "/unit-conv").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        conv(admin, rm1, itemVer(rm1), "{\"created\":[{\"rowKey\":\"k\",\"fromUnit\":\"ROLL\",\"factor\":\"900\"}]}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/items/" + rm1 + "/unit-conv").with(admin))
                .andExpect(jsonPath("$.data[0].convId").value(convId))
                .andExpect(jsonPath("$.data[0].factor").value(900));
        // 이미 지운 행을 다시 지우기 → 409
        conv(admin, rm1, itemVer(rm1), "{\"deleted\":[{\"rowKey\":\"d\",\"convId\":" + convId + ",\"rowVer\":1}]}")
                .andExpect(status().isConflict());
    }

    /** 결함 #6: 환산 수정으로 단위를 "사용 안 함" 상태의 단위로 바꾸면 고유키 충돌이 409 CONFLICT("다른 사람이 수정") 로 나온다 */
    @Test
    void 결함6_환산_수정으로_사용안함_단위와_겹치면_DUPLICATE_가_아니라_CONFLICT() throws Exception {
        long rm1 = itemId(comp, "RM1");
        conv(admin, rm1, itemVer(rm1), "{\"created\":[{\"rowKey\":\"a\",\"fromUnit\":\"ROLL\",\"factor\":\"850\"},"
                + "{\"rowKey\":\"b\",\"fromUnit\":\"G\",\"factor\":\"0.001\"}]}").andExpect(status().isOk());
        long roll = accounts.query(comp, "select conv_id from mst_unit_conv where from_unit = 'ROLL'", Long.class);
        long g = accounts.query(comp, "select conv_id from mst_unit_conv where from_unit = 'G'", Long.class);
        conv(admin, rm1, itemVer(rm1), "{\"deleted\":[{\"rowKey\":\"d\",\"convId\":" + g + ",\"rowVer\":0}]}")
                .andExpect(status().isOk());
        conv(admin, rm1, itemVer(rm1), "{\"updated\":[{\"rowKey\":\"u\",\"convId\":" + roll
                + ",\"rowVer\":0,\"fromUnit\":\"G\",\"factor\":\"0.001\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void 환산_다른품목의_convId_로_수정삭제는_409_값_그대로() throws Exception {
        long rm1 = itemId(comp, "RM1");
        long rm2 = itemId(comp, "RM2");
        conv(admin, rm1, itemVer(rm1), "{\"created\":[{\"rowKey\":\"a\",\"fromUnit\":\"ROLL\",\"factor\":\"850\"}]}")
                .andExpect(status().isOk());
        long roll = accounts.query(comp, "select conv_id from mst_unit_conv where item_id = ?", Long.class, rm1);
        conv(admin, rm2, itemVer(rm2), "{\"updated\":[{\"rowKey\":\"u\",\"convId\":" + roll
                + ",\"rowVer\":0,\"fromUnit\":\"ROLL\",\"factor\":\"1\"}]}").andExpect(status().isConflict());
        conv(admin, rm2, itemVer(rm2), "{\"deleted\":[{\"rowKey\":\"d\",\"convId\":" + roll + ",\"rowVer\":0}]}")
                .andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select factor from mst_unit_conv where conv_id = ?", BigDecimal.class, roll))
                .isEqualByComparingTo("850");
    }

    // ---------- 품목 기본 단위 변경 금지 (V018, docs/18 §10 "3a QA 반영") ----------

    private ResultActions putItem(String cd, String unit) throws Exception {
        long id = itemId(comp, cd);
        return mvc.perform(json(put("/api/master/items/" + id), "{\"itemCd\":\"" + cd + "\",\"itemNm\":\"" + cd
                + " 품목\",\"itemType\":\"RM\",\"procType\":\"BUY\",\"unitCd\":\"" + unit + "\",\"lotCtrlYn\":\"N\","
                + "\"rowVer\":" + itemVer(id) + "}").with(admin));
    }

    @Test
    void 기본단위_BOM_자품목_모품목_폐기본_사용중환산이면_IN_USE_다른칸은_수정가능() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", null)).andExpect(status().isOk());
        putItem("SA1", "SET").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"))
                .andExpect(jsonPath("$.errors[0].field").value("unitCd"))
                .andExpect(jsonPath("$.errors[0].code").value("IN_USE"));
        putItem("FG1", "SET").andExpect(status().isConflict());                 // 모품목(기준 수량이 그 단위)
        putItem("SA1", "EA").andExpect(status().isOk());                        // 단위 그대로면 다른 칸 수정 가능
        // 폐기본에만 쓰여도 막는다(새 리비전으로 되살릴 수 있다)
        obsolete(id).andExpect(status().isOk());
        putItem("SA1", "SET").andExpect(status().isConflict());
        // 사용 중 환산만 있어도 막고, 사용 안 함으로 바꾸면 풀린다
        long rm2 = itemId(comp, "RM2");
        conv(admin, rm2, itemVer(rm2), "{\"created\":[{\"rowKey\":\"k\",\"fromUnit\":\"ROLL\",\"factor\":\"850\"}]}")
                .andExpect(status().isOk());
        putItem("RM2", "G").andExpect(status().isConflict());
        long convId = accounts.query(comp, "select conv_id from mst_unit_conv where item_id = ?", Long.class, rm2);
        conv(admin, rm2, itemVer(rm2), "{\"deleted\":[{\"rowKey\":\"d\",\"convId\":" + convId + ",\"rowVer\":0}]}")
                .andExpect(status().isOk());
        putItem("RM2", "G").andExpect(status().isOk());
        // 되살리면 새 기본 단위 기준
        conv(admin, rm2, itemVer(rm2), "{\"created\":[{\"rowKey\":\"k\",\"fromUnit\":\"ROLL\",\"factor\":\"850000\"}]}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/items/" + rm2 + "/unit-conv").with(admin))
                .andExpect(jsonPath("$.data[0].toUnit").value("G"));
        // 아무 데도 안 쓰인 품목은 바꿀 수 있다
        putItem("SA2", "SET").andExpect(status().isOk());
    }

    @Test
    void 기본단위_직접SQL도_DB가_막는다() throws Exception {
        released("FG1", "SA1");
        assertConstraint("fk_mst_item_unit_in_use",
                () -> accounts.exec(comp, "update mst_item set unit_cd = 'SET' where item_cd = 'SA1'"));
        assertConstraint("fk_mst_item_unit_in_use",
                () -> accounts.exec(comp, "update mst_item set unit_cd = 'SET' where item_cd = 'FG1'"));
        // 단위 아닌 칸·같은 단위로의 갱신은 통과
        assertThat(accounts.exec(comp, "update mst_item set item_nm = 'x', unit_cd = unit_cd where item_cd = 'SA1'"))
                .isEqualTo(1);
    }

    @Test
    void 기본단위_변경과_BOM행_추가가_동시면_확정검사가_어긋난_단위를_막는다() throws Exception {
        long sa1 = itemId(comp, "SA1");
        long d = bom("FG1");
        CountDownLatch locked = new CountDownLatch(1);
        // 사용자 1: SA1 을 BOM 행에 넣는 중(커밋 전) — 사용자 2 의 서비스 검사는 아직 "안 쓰임" 으로 본다
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'BACKFLUSH', 't', 't')""", comp, d, sa1), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        MvcResult r = async(json(put("/api/master/items/" + sa1), "{\"itemCd\":\"SA1\",\"itemNm\":\"SA1 품목\","
                + "\"itemType\":\"RM\",\"procType\":\"BUY\",\"unitCd\":\"SET\",\"lotCtrlYn\":\"N\",\"rowVer\":"
                + itemVer(sa1) + "}").with(admin)).get(10, TimeUnit.SECONDS);
        first.get(10, TimeUnit.SECONDS);
        String unit = accounts.query(comp, "select unit_cd from mst_item where item_id = ?", String.class, sa1);
        long lines = accounts.query(comp, "select count(*) from mst_bom_line where item_id = ?", Long.class, sa1);
        assertThat(lines).isEqualTo(1);
        if (unit.equals("SET")) {
            // 둘 다 지나갔다(품목 트리거는 커밋 전 행을 못 본다) — 행은 작성 중 BOM 에만 들어갈 수 있고 확정 검사가 어긋난 단위를 막는다
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            release(d).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_CONV"));
        } else {
            assertThat(r.getResponse().getStatus()).isEqualTo(409);
        }
    }

    // =====================================================================
    // 9. A사/B사
    // =====================================================================

    @Test
    void B사가_A사_lineId_로_자기BOM_행수정_삭제_요청바디_조작() throws Exception {
        long aBom = bom("FG1");
        addLines(aBom, line("n1", 10, "SA1", "1", null)).andExpect(status().isOk());
        long aLine = lineId(aBom, 10);
        String other = accounts.newComp("BQ");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        accounts.grant(other, bUser, "MASTER", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        item(other, "SA1", "EA", "N");
        item(other, "BFG", "EA", "N");
        MvcResult r = mvc.perform(json(post(BASE), "{\"itemCd\":\"BFG\"}").with(b)).andExpect(status().isOk()).andReturn();
        long bBom = ((Number) JsonPath.read(body(r), "$.data")).longValue();
        int bv = accounts.query(other, "select row_ver from mst_bom where bom_id = ?", Integer.class, bBom);
        lines(b, bBom, bv, null, "{\"rowKey\":\"u\",\"lineId\":" + aLine + ",\"rowVer\":0,\"lineNo\":10,"
                + "\"itemCd\":\"SA1\",\"qty\":\"99\"}", null).andExpect(status().isConflict());
        bv = accounts.query(other, "select row_ver from mst_bom where bom_id = ?", Integer.class, bBom);
        lines(b, bBom, bv, null, null, "{\"rowKey\":\"d\",\"lineId\":" + aLine + ",\"rowVer\":0}")
                .andExpect(status().isConflict());
        // A사 BOM id 로 직접(본인 회사 품목 코드) — 404
        lines(b, aBom, ver(aBom), null, "{\"rowKey\":\"u\",\"lineId\":" + aLine + ",\"rowVer\":0,\"lineNo\":10,"
                + "\"itemCd\":\"SA1\",\"qty\":\"99\"}", null).andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/" + aBom + "/new-revision"), "{\"rowVer\":" + ver(aBom) + "}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(put(BASE + "/" + aBom), "{\"baseQty\":\"5\",\"rowVer\":" + ver(aBom) + "}").with(b))
                .andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select qty from mst_bom_line where line_id = ?", BigDecimal.class, aLine))
                .isEqualByComparingTo("1");
        assertThat(accounts.query(comp, "select base_qty from mst_bom where bom_id = ?", BigDecimal.class, aBom))
                .isEqualByComparingTo("1");
        // B사 단위 환산 API 에 A사 itemId
        long aRm1 = itemId(comp, "RM1");
        mvc.perform(get("/api/master/items/" + aRm1 + "/unit-conv").with(b)).andExpect(status().isNotFound());
        conv(b, aRm1, 0, "{\"created\":[{\"rowKey\":\"k\",\"fromUnit\":\"ROLL\",\"factor\":\"1\"}]}")
                .andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from mst_unit_conv", Integer.class)).isZero();
        // B사 품목 찾기로 A사 품목 코드
        mvc.perform(get(BASE + "/item").param("itemCd", "FG1").with(b)).andExpect(status().isNotFound());
    }

    // =====================================================================
    // 10. 권한 조합 (API)
    // =====================================================================

    @Test
    void 권한_조합별_API_응답() throws Exception {
        long id = bom("FG1");
        addLines(id, line("n1", 10, "SA1", "1", null)).andExpect(status().isOk());
        RequestPostProcessor s = user("u_s", "R_S", "SEARCH");
        RequestPostProcessor sv = user("u_sv", "R_SV", "SAVE");
        RequestPostProcessor ap = user("u_ap", "R_AP", "APPROVE");
        RequestPostProcessor dl = user("u_dl", "R_DL", "DELETE");

        // 조회만
        mvc.perform(get(BASE).with(s)).andExpect(status().isOk());
        mvc.perform(json(post(BASE), "{\"itemCd\":\"SA2\"}").with(s)).andExpect(status().isForbidden());
        mvc.perform(linesReq(s, id, ver(id), batch(line("x", 20, "SA2", "1", null), null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(actionReq(s, id, "release", ver(id))).andExpect(status().isForbidden());
        mvc.perform(actionReq(s, id, "obsolete", ver(id))).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/export"), "{}").with(s)).andExpect(status().isForbidden());
        // 저장만 (조회 없음)
        mvc.perform(get(BASE).with(sv)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + id).with(sv)).andExpect(status().isForbidden());
        mvc.perform(linesReq(sv, id, ver(id), batch(line("x", 20, "SA2", "1", null), null, null)))
                .andExpect(status().isOk());
        mvc.perform(actionReq(sv, id, "release", ver(id))).andExpect(status().isForbidden());
        // 확정만
        mvc.perform(get(BASE + "/" + id).with(ap)).andExpect(status().isForbidden());
        mvc.perform(linesReq(ap, id, ver(id), batch(line("y", 30, "RM2", "1", null), null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(actionReq(ap, id, "obsolete", ver(id))).andExpect(status().isForbidden());
        mvc.perform(actionReq(ap, id, "release", ver(id))).andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/" + id + "/new-revision"), "{\"rowVer\":" + ver(id) + "}").with(ap))
                .andExpect(status().isForbidden());
        // 폐기만
        mvc.perform(actionReq(dl, id, "obsolete", ver(id))).andExpect(status().isOk());
        // 엑셀 올리기: 템플릿 받기는 조회 권한, 올리기(작업 생성)는 저장 권한
        mvc.perform(post("/api/platform/import/templates/MST_BOM/file").with(csrf()).with(s))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/platform/import/templates/MST_BOM/jobs")
                .file(new MockMultipartFile("file", "bom.xlsx", "application/octet-stream", new byte[] {1, 2, 3}))
                .with(csrf()).with(s)).andExpect(status().isForbidden());
    }

    private RequestPostProcessor user(String login, String role, String... actions) {
        long u = accounts.newUser(comp, login, PW);
        accounts.grant(comp, u, role, "mst-bom", actions);
        return accounts.as(comp, u, login);
    }

    // =====================================================================
    // 11. 그 밖의 요청 조작
    // =====================================================================

    @Test
    void 조작_행키_중복_빈배치_null_원소_다른BOM_lineId() throws Exception {
        long id = bom("FG1");
        addLines(id, line("k", 10, "SA1", "1", null), line("k", 20, "SA2", "1", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        mvc.perform(linesReq(admin, id, ver(id), "{\"created\":[null]}")).andExpect(status().isBadRequest());
        mvc.perform(linesReq(admin, id, ver(id), "{\"created\":[{\"rowKey\":\"<script>\",\"lineNo\":10,"
                + "\"itemCd\":\"SA1\",\"qty\":\"1\"}]}")).andExpect(status().isBadRequest());
        // 빈 배치는 버전만 올리고 통과
        int v = ver(id);
        mvc.perform(linesReq(admin, id, v, "{}")).andExpect(status().isOk());
        // 같은 회사 다른 BOM 의 lineId 로 수정·삭제
        long other = bom("SA1");
        addLines(other, line("o", 10, "RM1", "1", null)).andExpect(status().isOk());
        long otherLine = lineId(other, 10);
        lines(admin, id, ver(id), null, "{\"rowKey\":\"u\",\"lineId\":" + otherLine + ",\"rowVer\":0,\"lineNo\":10,"
                + "\"itemCd\":\"RM1\",\"qty\":\"77\"}", null).andExpect(status().isConflict());
        lines(admin, id, ver(id), null, null, "{\"rowKey\":\"d\",\"lineId\":" + otherLine + ",\"rowVer\":0}")
                .andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select qty from mst_bom_line where line_id = ?", BigDecimal.class, otherLine))
                .isEqualByComparingTo("1");
        // 같은 행을 수정과 삭제에 동시에
        addLines(id, line("n", 10, "SA1", "1", null)).andExpect(status().isOk());
        long l = lineId(id, 10);
        lines(admin, id, ver(id), null, "{\"rowKey\":\"u\",\"lineId\":" + l + ",\"rowVer\":0,\"lineNo\":10,"
                + "\"itemCd\":\"SA1\",\"qty\":\"5\"}", "{\"rowKey\":\"d\",\"lineId\":" + l + ",\"rowVer\":0}")
                .andExpect(status().is4xxClientError());
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where line_id = ?", Integer.class, l))
                .isEqualTo(1);
        // 숫자를 JSON 숫자로 (문자열 아님)
        mvc.perform(linesReq(admin, id, ver(id), "{\"created\":[{\"rowKey\":\"j\",\"lineNo\":20,\"itemCd\":\"SA2\","
                + "\"qty\":1.5}]}")).andExpect(status().isOk());
        // 정렬 키 조작
        mvc.perform(get(BASE).param("sort", "item_cd;drop table x,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("status", "DRAFT' or '1'='1").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("itemCd", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(BASE).param("itemNm", "_").with(admin)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 확정검사_오류행키는_L_lineId_필드는_altPct_화면이_접힌칸을_펼칠수있게() throws Exception {
        long id = bom("FG1");
        addLines(id, line("a", 10, "SA1", "1", "\"altGrp\":\"G\",\"altPriority\":1,\"altPct\":\"10\"")).andExpect(status().isOk());
        long l = lineId(id, 10);
        release(id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value("L" + l))
                .andExpect(jsonPath("$.errors[0].field").value("altPct"))
                .andExpect(jsonPath("$.errors[0].message").value(not("")));
    }
}
