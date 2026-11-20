package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA P1 3c — BOM 비교·복사·일괄 대체 이상 사용 (docs/qa/bom-routing-3b-5.md). TESTING.md "P1 3c" 표에 없는 것:
 * 미리보기 뒤 대상이 바뀐 실행(409·동작 바뀜), 순환이 생기는 대체(자기·간접·미리보기 뒤 생긴 간선), 확정본·남의 id 실행,
 * 미래 확정본, 이미 새 자품목이 있는 작성 중, 단위 경고, 중복 요청·동시 실행, 비교의 기준 수량, A/B사.
 */
@AutoConfigureMockMvc
class BomChangeAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String REPLACE = "/api/master/bom-replace";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QC");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(comp, userId, "MASTER", "mst-bom-replace", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-bom-where", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        for (String cd : List.of("FG", "P1", "P2", "P3", "SA", "X", "Y", "Z", "BOLT")) {
            item(comp, cd, "EA");
        }
        item(comp, "XK", "KG");
    }

    private void item(String c, String cd, String unit) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", c, cd, cd + " 품목", unit);
    }

    /** BOM [from, to) — pairs = {자품목, 수량}, 순번 10, 20, … release 면 확정 */
    private long bom(String parent, String rev, String base, LocalDate from, LocalDate to, boolean release, String... pairs) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, valid_to, base_qty, created_by, updated_by)
                select ?, item_id, ?, ?, ?, ?::numeric, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, comp, rev, from, to, base, parent);
        for (int i = 0; i < pairs.length; i += 2) {
            accounts.exec(comp, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                    select ?, ?, ?, item_id, ?::numeric, unit_cd, 'BACKFLUSH', 't', 't' from mst_item where item_cd = ?""",
                    comp, id, (i / 2 + 1) * 10, pairs[i + 1], pairs[i]);
        }
        if (release) {
            accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        }
        return id;
    }

    private long bom(String parent, String rev, LocalDate from, boolean release, String... pairs) {
        return bom(parent, rev, "1", from, null, release, pairs);
    }

    private int bomCount() {
        return accounts.query(comp, "select count(*) from mst_bom", Integer.class);
    }

    private int ver(long bomId) {
        return accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, bomId);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions preview(String from, String to, LocalDate validFrom) throws Exception {
        var req = get(REPLACE + "/preview").param("fromItemCd", from).param("toItemCd", to);
        if (validFrom != null) {
            req.param("validFrom", validFrom.toString());
        }
        return mvc.perform(req.with(admin));
    }

    private static String body(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private static Map<String, Object> row(String previewBody, String parentCd) {
        List<Map<String, Object>> rows = JsonPath.read(previewBody, "$.data.rows[?(@.parentCd=='" + parentCd + "')]");
        assertThat(rows).as(parentCd).hasSize(1);
        return rows.get(0);
    }

    private static String replaceBody(String from, String to, LocalDate validFrom, Object... ids) {
        StringBuilder sb = new StringBuilder();
        for (Object id : ids) {
            sb.append(sb.length() == 0 ? "" : ",").append(id);
        }
        return "{\"fromItemCd\":\"" + from + "\",\"toItemCd\":\"" + to + "\""
                + (validFrom == null ? "" : ",\"validFrom\":\"" + validFrom + "\"") + ",\"bomIds\":[" + sb + "]}";
    }

    private ResultActions replace(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(REPLACE), body).with(who));
    }

    private CompletableFuture<MvcResult> async(MockHttpServletRequestBuilder req) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(req).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

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

    // =====================================================================
    // 1. 미리보기 뒤 대상이 바뀐 실행
    // =====================================================================

    @Test
    void 미리보기_뒤_그_모품목에_작성중이_생기면_실행_409_아무것도_안_만든다() throws Exception {
        long p1 = bom("P1", "A0", today.minusDays(10), true, "X", "2");
        long p2 = bom("P2", "A0", today.minusDays(10), true, "X", "1");
        String pv = body(preview("X", "Y", null).andExpect(status().isOk()));
        assertThat(row(pv, "P1").get("action")).isEqualTo("NEW_REV");
        // 다른 사람이 P1 새 리비전(작성 중, X 그대로)을 만든다
        bom("P1", "A1", today.plusDays(3), false, "X", "2");
        int before = bomCount();
        replace(admin, replaceBody("X", "Y", null, p1, p2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(bomCount()).isEqualTo(before);       // P2 새 리비전도 롤백
        // 새 미리보기는 P1 을 작성 중에 반영으로 바꿔 보여 준다
        String pv2 = body(preview("X", "Y", null));
        assertThat(row(pv2, "P1").get("action")).isEqualTo("DRAFT");
    }

    @Test
    void 미리보기_뒤_대체품이_모품목을_쓰게_되면_순환이라_실행_409() throws Exception {
        long p1 = bom("P1", "A0", today.minusDays(10), true, "X", "1");
        assertThat(row(body(preview("X", "Y", null)), "P1").get("action")).isEqualTo("NEW_REV");
        // 그 사이 Y 의 작성 중 BOM 에 P1 을 넣는다 → 이제 P1 ← Y 는 순환
        bom("Y", "A0", today, false, "P1", "1");
        int before = bomCount();
        replace(admin, replaceBody("X", "Y", null, p1)).andExpect(status().isConflict());
        assertThat(bomCount()).isEqualTo(before);
        assertThat(row(body(preview("X", "Y", null)), "P1").get("reason")).isEqualTo("CYCLE");
    }

    /**
     * 관찰: 미리보기 때 "작성 중에 반영"(DRAFT)이던 대상이 실행 전에 확정되면, 같은 BOM id 가 이번엔 NEW_REV 판정이라 409 가 아니라
     * 새 리비전을 만든다 — 사용자는 작성 중 A0 을 고친다고 보고 눌렀는데 결과는 새 리비전 A1. 데이터는 맞지만 미리보기와 동작이 다르다.
     */
    @Test
    void 관찰_미리보기_DRAFT_대상이_실행_전에_확정되면_다른_동작으로_실행된다() throws Exception {
        long p1 = bom("P1", "A0", today, false, "X", "1");
        LocalDate d = today.plusDays(2);
        Map<String, Object> r = row(body(preview("X", "Y", d)), "P1");
        assertThat(r.get("action")).isEqualTo("DRAFT");
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", p1);
        String done = body(replace(admin, replaceBody("X", "Y", d, p1)).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(done, "$.data[0].action")).isEqualTo("NEW_REV");
        assertThat((String) JsonPath.read(done, "$.data[0].rev")).isEqualTo("A1");
    }

    // =====================================================================
    // 2. 순환이 생기는 대체 · 확정본/남의 id 실행
    // =====================================================================

    @Test
    void 대체품이_자기_구조에_원래_자재를_쓰면_그_모품목은_CYCLE_로_건너뛴다() throws Exception {
        bom("P1", "A0", today.minusDays(10), true, "X", "1");
        long yBom = bom("Y", "A0", today.minusDays(10), true, "X", "1");       // Y ← X : X → Y 로 바꾸면 Y ← Y
        bom("SA", "A0", today.minusDays(10), true, "X", "1");
        bom("Z", "A0", today.minusDays(10), true, "SA", "1");                  // Z ← SA ← X
        bom("P2", "A0", today.minusDays(10), true, "Z", "1");
        // Y 가 Z 를 쓰게 해서 간접 순환: SA ← Y ← Z ← SA
        bom("Y", "A1", today.plusDays(5), false, "X", "1", "Z", "1");
        String pv = body(preview("X", "Y", null));
        assertThat(row(pv, "Y").get("reason")).isEqualTo("CYCLE");
        assertThat(row(pv, "SA").get("reason")).isEqualTo("CYCLE");
        assertThat(row(pv, "P1").get("action")).isEqualTo("NEW_REV");
        // 화면이 건너뛴 대상을 강제로 보내면 409
        replace(admin, replaceBody("X", "Y", null, yBom)).andExpect(status().isConflict());
    }

    @Test
    void 실행_대상_검증_확정본_남의_모품목_빈목록_천개초과_null() throws Exception {
        long p1 = bom("P1", "A0", today.minusDays(10), true, "X", "1");
        long sameDay = bom("P2", "A0", today, true, "X", "1");                 // SAME_DAY 로 건너뜀
        long unrelated = bom("P3", "A0", today.minusDays(10), true, "BOLT", "1");
        int before = bomCount();
        replace(admin, replaceBody("X", "Y", null, p1, sameDay)).andExpect(status().isConflict());
        replace(admin, replaceBody("X", "Y", null, unrelated)).andExpect(status().isConflict());
        replace(admin, replaceBody("X", "Y", null)).andExpect(status().isBadRequest());
        replace(admin, replaceBody("X", "Y", null, p1, "null")).andExpect(status().isBadRequest());
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            many.add(p1 + i);
        }
        replace(admin, replaceBody("X", "Y", null, many.toArray())).andExpect(status().isBadRequest());
        replace(admin, replaceBody("X", "Y", today.minusDays(1), p1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PAST"));
        assertThat(bomCount()).isEqualTo(before);
        // 같은 id 두 번은 한 번만 반영
        replace(admin, replaceBody("X", "Y", null, p1, p1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        assertThat(bomCount()).isEqualTo(before + 1);
    }

    // =====================================================================
    // 3. 미래 확정본 · 이미 대체품이 있는 작성 중 · 단위
    // =====================================================================

    /**
     * 결함 #3(수정됨): P1 A0 [-30, +5) X, A1 [+5, ∞) X. X → Y 를 +2 부터 바꾸면 미리보기는 A0 기준 NEW_REV(반영 가능).
     * 실행하면 A2(+2 시작) 가 생기지만 (a) A1(+5)과 겹쳐 **확정할 수 없고** (b) 확정할 수 있다 해도 +5 부터는 A1 이 X 를 다시 쓴다.
     * 미리보기가 "미래 확정본이 있음" 을 건너뜀(또는 경고)으로 보여야 한다.
     */
    @Test
    void 결함3_기준일_뒤_확정본이_있으면_미리보기가_건너뛰거나_경고해야_한다() throws Exception {
        bom("P1", "A0", "1", today.minusDays(30), today.plusDays(5), true, "X", "1");
        bom("P1", "A1", "1", today.plusDays(5), null, true, "X", "1");
        Map<String, Object> r = row(body(preview("X", "Y", today.plusDays(2))), "P1");
        assertThat(r.get("action")).isEqualTo("SKIP");
        assertThat(r.get("reason")).isEqualTo("FUTURE_REV");
        // 미래 확정본(+5) 뒤 기준일이면 그 리비전을 원본으로 새 리비전 — 막지 않는다
        Map<String, Object> later = row(body(preview("X", "Y", today.plusDays(6))), "P1");
        assertThat(later.get("action")).isEqualTo("NEW_REV");
        assertThat(later.get("sourceRev")).isEqualTo("A1");
    }

    @Test
    void 관찰_대체품이_이미_있는_작성중에_반영하면_같은_자품목이_두_행() throws Exception {
        long d = bom("P1", "A0", today, false, "X", "2", "Y", "3");
        Map<String, Object> r = row(body(preview("X", "Y", null)), "P1");
        assertThat(r.get("action")).isEqualTo("DRAFT");
        replace(admin, replaceBody("X", "Y", null, d)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].lines").value(1));
        // Y 2 + Y 3 두 행 — 합치지도 경고하지도 않는다(같은 키라 비교 화면에서도 두 줄)
        assertThat(accounts.query(comp, """
                select count(*) from mst_bom_line l join mst_item i on i.item_id = l.item_id where l.bom_id = ? and i.item_cd = 'Y'""",
                Integer.class, d)).isEqualTo(2);
    }

    @Test
    void 단위가_다른_대체품은_미리보기_경고_실행_후_확정은_NO_CONV() throws Exception {
        long p1 = bom("P1", "A0", today.minusDays(10), true, "X", "1");      // X 는 EA 행
        Map<String, Object> r = row(body(preview("X", "XK", null)), "P1");   // XK 기본 단위 KG
        assertThat(r.get("unitWarn")).isEqualTo(true);
        String done = body(replace(admin, replaceBody("X", "XK", null, p1)).andExpect(status().isOk()));
        long a1 = ((Number) JsonPath.read(done, "$.data[0].bomId")).longValue();
        accounts.exec(comp, "update mst_bom set valid_from = ? where bom_id = ?", today.plusDays(1), a1);
        mvc.perform(json(post("/api/master/boms/" + a1 + "/release"), "{\"rowVer\":" + ver(a1) + "}").with(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItem("NO_CONV")));
    }

    // =====================================================================
    // 4. 중복 요청 · 동시 실행
    // =====================================================================

    @Test
    void 같은_멱등키로_두_번_실행하면_두번째는_DUPLICATE_REQUEST() throws Exception {
        long p1 = bom("P1", "A0", today.minusDays(10), true, "X", "1");
        String b = replaceBody("X", "Y", null, p1);
        mvc.perform(json(post(REPLACE), b).header("X-Idempotency-Key", "qa-replace-0001").with(admin)).andExpect(status().isOk());
        int after = bomCount();
        mvc.perform(json(post(REPLACE), b).header("X-Idempotency-Key", "qa-replace-0001").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        // 키 없이 다시 보내면 미리보기가 다시 계산돼 P1 은 이제 DRAFT_OTHER → 409
        replace(admin, b).andExpect(status().isConflict());
        assertThat(bomCount()).isEqualTo(after);
    }

    @Test
    void 동시_실행_새리비전_대상은_뒤쪽이_409_전부_취소() throws Exception {
        long p1 = bom("P1", "A0", today.minusDays(10), true, "X", "1");
        long p2 = bom("P2", "A0", today.minusDays(10), true, "X", "1");
        long p1Item = accounts.query(comp, "select item_id from mst_item where item_cd = 'P1'", Long.class);
        CountDownLatch locked = new CountDownLatch(1);
        // 앞 사람의 실행이 P1 작성 중을 만든 채 커밋 전
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, ?, 'A1', ?, 't', 't')""", comp, p1Item, today), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        int before = bomCount();
        CompletableFuture<MvcResult> second = async(json(post(REPLACE), replaceBody("X", "Y", null, p2, p1)).with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(bomCount()).isEqualTo(before + 1);    // 앞 사람의 A1 만, 뒤쪽의 P2 새 리비전은 롤백
    }

    /** 관찰: 같은 작성 중 대상을 두 사람이 동시에 실행하면 뒤쪽은 바꿀 행이 0 인 채 "반영" 성공(200, lines 0) */
    @Test
    void 관찰_동시_실행_작성중_대상은_뒤쪽이_0행_반영으로_성공() throws Exception {
        long d = bom("P1", "A0", today, false, "X", "1");
        long y = accounts.query(comp, "select item_id from mst_item where item_cd = 'Y'", Long.class);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("update mst_bom_line set item_id = ? where bom_id = ?", y, d),
                locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(REPLACE), replaceBody("X", "Y", null, d)).with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        String b = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(b).isEqualTo(200);
        assertThat((Integer) JsonPath.read(b, "$.data[0].lines")).isZero();
    }

    // =====================================================================
    // 5. 비교 · 복사
    // =====================================================================

    /**
     * 결함 #4(수정됨): 비교는 행 수량만 보고 머리 기준 수량을 보지 않는다. A0(기준 1, SA 2) ↔ A1(기준 10, SA 2) 은 실제 소요가
     * 1/10 로 줄었는데 "같음", A0 ↔ A2(기준 10, SA 20) 는 실제 소요가 같은데 "변경". 화면 비교 표에도 기준 수량이 없다.
     */
    @Test
    void 결함4_비교는_기준수량으로_나눈_소요로_판정해야_한다() throws Exception {
        long a0 = bom("FG", "A0", "1", today.minusDays(10), null, true, "SA", "2");
        long a1 = bom("FG", "A1", "10", today, null, false, "SA", "2");
        mvc.perform(get("/api/master/boms/compare").param("leftId", "" + a0).param("rightId", "" + a1).with(admin))
                .andExpect(jsonPath("$.data.rows[0].status").value("CHANGED"))
                .andExpect(jsonPath("$.data.rows[0].changed[0]").value("qty"));
        // 기준·수량을 비례로(10, 20) — 소요 같음
        accounts.exec(comp, "update mst_bom_line set qty = 20 where bom_id = ?", a1);
        mvc.perform(get("/api/master/boms/compare").param("leftId", "" + a0).param("rightId", "" + a1).with(admin))
                .andExpect(jsonPath("$.data.rows[0].status").value("SAME"));
        // 고정 수량 행은 기준 수량과 무관 — 기준 1 ↔ 10 이어도 고정 2 ↔ 2 는 같음
        long b0 = bom("P2", "A0", "1", today.minusDays(10), null, false, "SA", "2");
        accounts.exec(comp, "update mst_bom_line set fixed_yn = 'Y' where bom_id = ?", b0);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b0);
        long b1 = bom("P2", "A1", "10", today, null, false, "SA", "2");
        accounts.exec(comp, "update mst_bom_line set fixed_yn = 'Y' where bom_id = ?", b1);
        mvc.perform(get("/api/master/boms/compare").param("leftId", "" + b0).param("rightId", "" + b1).with(admin))
                .andExpect(jsonPath("$.data.rows[0].status").value("SAME"));
    }

    @Test
    void 관찰_같은_키_여러_행은_순번_순서로_짝지어_순서만_바꿔도_변경() throws Exception {
        long a0 = bom("FG", "A0", today.minusDays(10), true, "SA", "1", "SA", "2");
        long a1 = bom("FG", "A1", today, false, "SA", "2", "SA", "1");
        mvc.perform(get("/api/master/boms/compare").param("leftId", "" + a0).param("rightId", "" + a1).with(admin))
                .andExpect(jsonPath("$.data.changedCount").value(2));
    }

    @Test
    void 비교_한쪽만_B사_BOM_이면_404_복사_대상이_A사_품목이면_NOT_FOUND() throws Exception {
        long a0 = bom("FG", "A0", today.minusDays(10), true, "SA", "1");
        String other = accounts.newComp("QC");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-bom", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "MASTER", "mst-bom-replace", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        item(other, "BFG", "EA");
        item(other, "BSA", "EA");
        long bBom = accounts.query(other, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'A0', ?, 't', 't' from mst_item where item_cd = 'BFG' returning bom_id""",
                Long.class, other, today);
        accounts.exec(other, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                select ?, ?, 10, item_id, 1, 'EA', 'BACKFLUSH', 't', 't' from mst_item where item_cd = 'BSA'""", other, bBom);
        mvc.perform(get("/api/master/boms/compare").param("leftId", "" + bBom).param("rightId", "" + a0).with(b))
                .andExpect(status().isNotFound());
        // B사 BOM 을 A사에만 있는 품목 코드(P1)로 복사
        mvc.perform(json(post("/api/master/boms/" + bBom + "/copy"), "{\"itemCd\":\"P1\"}").with(b))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // B사가 A사 BOM id 를 일괄 대체 실행 대상으로 (B사 품목 코드)
        replace(b, replaceBody("BSA", "BFG", null, a0)).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isEqualTo(1);
    }

    @Test
    void 복사_기준일_형식_과거_권한() throws Exception {
        long a0 = bom("FG", "A0", today.minusDays(10), true, "SA", "1");
        mvc.perform(json(post("/api/master/boms/" + a0 + "/copy"), "{\"itemCd\":\"P1\",\"validFrom\":\"2026-02-30\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("validFrom"));
        mvc.perform(json(post("/api/master/boms/" + a0 + "/copy"), "{\"itemCd\":\"p1\"}").with(admin))
                .andExpect(status().isOk());
        // 조회만 있는 사용자
        long v = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, v, "VIEW", "mst-bom", "SEARCH");
        mvc.perform(json(post("/api/master/boms/" + a0 + "/copy"), "{\"itemCd\":\"P2\"}")
                .with(accounts.as(comp, v, "viewer"))).andExpect(status().isForbidden());
    }
}
