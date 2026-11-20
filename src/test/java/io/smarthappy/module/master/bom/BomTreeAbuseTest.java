package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA P1 3b — BOM 전개·역전개 이상 사용 (docs/qa/bom-routing-3b-5.md). TESTING.md "P1 3b" 표에 없는 것:
 * 기준일 경계(시작·종료 당일), 대체·가상·부산물·손실률·고정이 섞인 소요량, 같은 품목 여러 경로 합산, 정책 상한 정확히·초과,
 * 공유 하위 구조(같은 자품목 여러 행)의 경로 폭주, A/B사·권한·엑셀.
 */
@AutoConfigureMockMvc
class BomTreeAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String TREE = "/api/master/bom-tree";
    private static final String WHERE = "/api/master/bom-where";

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
        comp = accounts.newComp("QT");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom-tree", "SEARCH", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-bom-where", "SEARCH", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    // ---------- 도우미 (직접 SQL — 전개는 조회만 본다) ----------

    private void items(String... spec) {
        for (String s : spec) {
            String[] p = s.split(":");
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                          created_by, updated_by)
                    values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", comp, p[0], p[0] + " 품목", p[1]);
        }
    }

    /** 확정 BOM [from, to). lines = {자품목, 수량, 단위, 손실률, 유형, 고정, 대체그룹, 순위, 비율} (뒤는 생략 가능) */
    private long bom(String parent, String rev, String base, LocalDate from, LocalDate to, String[]... lines) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, valid_to, base_qty, created_by, updated_by)
                select ?, item_id, ?, ?, ?, ?::numeric, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, comp, rev, from, to, base, parent);
        for (int i = 0; i < lines.length; i++) {
            String[] l = lines[i];
            String type = l.length > 4 ? l[4] : "NORMAL";
            accounts.exec(comp, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, scrap_pct, line_type,
                                              fixed_yn, issue_method, alt_grp, alt_priority, alt_pct, created_by, updated_by)
                    select ?, ?, ?, item_id, ?::numeric, ?, ?::numeric, ?, ?, ?, ?, ?::smallint, ?::numeric, 't', 't'
                      from mst_item where item_cd = ?""", comp, id, (i + 1) * 10, l[1], l[2], l.length > 3 ? l[3] : "0",
                    type, l.length > 5 ? l[5] : "N", "NORMAL".equals(type) ? "BACKFLUSH" : null, l.length > 6 ? l[6] : null,
                    l.length > 7 ? l[7] : null, l.length > 8 ? l[8] : null, l[0]);
        }
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        return id;
    }

    /** 모품목 아래 같은 자품목 n 행 (순번 10, 20, …) — 경로가 여러 갈래로 늘어나는 구조 */
    private void sameChildLines(String parent, String child, int n, String qty) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'A0', ?, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, comp, today.minusDays(1), parent);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                select ?, ?, g * 10, i.item_id, ?::numeric, 'EA', 'BACKFLUSH', 't', 't'
                  from generate_series(1, ?) g, mst_item i where i.item_cd = ?""", comp, id, qty, n, child);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
    }

    private void policy(String key, String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by)
                values (?, '*', ?, ?, 't', 't')""", comp, key, value);
    }

    private ResultActions explode(RequestPostProcessor who, String... kv) throws Exception {
        var req = get(TREE);
        for (int i = 0; i < kv.length; i += 2) {
            req.param(kv[i], kv[i + 1]);
        }
        return mvc.perform(req.with(who));
    }

    private ResultActions where(RequestPostProcessor who, String... kv) throws Exception {
        var req = get(WHERE);
        for (int i = 0; i < kv.length; i += 2) {
            req.param(kv[i], kv[i + 1]);
        }
        return mvc.perform(req.with(who));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private static BigDecimal dec(String body, String path) {
        return new BigDecimal(JsonPath.read(body, path).toString());
    }

    /** SUM 결과에서 품목·유형으로 행 하나 */
    private static Map<String, Object> sumRow(String body, String itemCd, String lineType) {
        List<Map<String, Object>> rows = JsonPath.read(body,
                "$.data.rows[?(@.itemCd=='" + itemCd + "' && @.lineType=='" + lineType + "')]");
        assertThat(rows).as(itemCd + "/" + lineType).hasSize(1);
        return rows.get(0);
    }

    // =====================================================================
    // 1. 기준일 경계 — 시작일 당일 포함, 종료일 당일 제외([from, to))
    // =====================================================================

    @Test
    void 기준일_경계_시작일_당일은_포함_종료일_당일은_다음_리비전() throws Exception {
        items("FG:EA", "SA:EA", "RM1:KG");
        bom("FG", "A0", "1", today.minusDays(30), today.plusDays(20), new String[] {"SA", "1", "EA"});
        bom("SA", "A0", "1", today.minusDays(10), today.plusDays(5), new String[] {"RM1", "1", "KG"});
        bom("SA", "A1", "1", today.plusDays(5), null, new String[] {"RM1", "2", "KG"});

        // SA 시작 전날 = SA 는 말단(전개 안 됨), 시작 당일 = A0
        String before = body(explode(admin, "itemCd", "FG", "date", today.minusDays(11).toString()).andExpect(status().isOk())
                .andReturn());
        assertThat(JsonPath.<List<String>>read(before, "$.data.rows[*].itemCd")).containsExactly("SA");
        assertThat((Object) JsonPath.read(before, "$.data.rows[0].childRev")).isNull();
        explode(admin, "itemCd", "FG", "date", today.minusDays(10).toString())
                .andExpect(jsonPath("$.data.rows[0].childRev").value("A0"))
                .andExpect(jsonPath("$.data.rows[1].reqQty").value(1));
        // A0 종료일 전날 = A0, 종료일 당일 = A1 (같은 날 두 리비전이 동시에 나오지 않는다)
        explode(admin, "itemCd", "FG", "date", today.plusDays(4).toString())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].childRev").value("A0"));
        explode(admin, "itemCd", "FG", "date", today.plusDays(5).toString())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].childRev").value("A1"))
                .andExpect(jsonPath("$.data.rows[1].reqQty").value(2));
        // 루트 FG: 시작 당일 OK, 시작 전날·종료 당일은 유효 BOM 없음
        explode(admin, "itemCd", "FG", "date", today.minusDays(30).toString()).andExpect(status().isOk());
        explode(admin, "itemCd", "FG", "date", today.minusDays(31).toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOM_NO_EFFECTIVE"));
        explode(admin, "itemCd", "FG", "date", today.plusDays(20).toString()).andExpect(status().isNotFound());
        // 역전개도 같은 경계: 종료일 당일엔 A1 만
        where(admin, "itemCd", "RM1", "date", today.plusDays(5).toString())
                .andExpect(jsonPath("$.data.rows[0].rev").value("A1"))
                .andExpect(jsonPath("$.data.rows[0].parentCd").value("SA"));
        where(admin, "itemCd", "RM1", "date", today.plusDays(4).toString())
                .andExpect(jsonPath("$.data.rows[0].rev").value("A0"));
        // FG 종료 당일: SA 는 쓰는 곳이 없어 최상위
        where(admin, "itemCd", "RM1", "date", today.plusDays(20).toString())
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].top").value(true));
    }

    // =====================================================================
    // 2. 섞인 소요량 — 대체 그룹·가상 조립·부산물·손실률·고정 수량, 같은 품목 여러 경로
    // =====================================================================

    /**
     * <pre>
     * FG (기준 2)            SA (기준 5)                      PH (기준 1, 가상)
     *  10 SA 4 손실 10%       10 RM1 10 KG 손실 2%             10 RM1 3 KG
     *  20 PH 2 가상 손실 5%   20 JIG 2 고정 손실 50%           20 JIG 1 고정
     *  30 SA 2 (같은 SA 두 번째 경로)                          30 BOLTA 10 대체 G1 1순위 70%
     *                                                          40 BOLTB 10 대체 G1 2순위 30%
     *                                                          50 CHIP 0.2 KG 부산물
     * FG 10개: SA① = 4/2×10×1.1 = 22, SA② = 2/2×10 = 10, PH = 2/2×10×1.05 = 10.5
     *   RM1 = 10/5×22×1.02 (44.88) + 10/5×10×1.02 (20.4) + 3×10.5 (31.5) = 96.78 KG
     *   JIG(고정) = 2×1.5 (3) + 3 + 1 = 7, BOLTA = 10×10.5×0.7 = 73.5, BOLTB = 31.5, CHIP = 0.2×10.5 = 2.1
     * </pre>
     */
    private void mixed() {
        items("FG:EA", "SA:EA", "PH:EA", "RM1:KG", "JIG:EA", "BOLTA:EA", "BOLTB:EA", "CHIP:KG");
        LocalDate from = today.minusDays(10);
        bom("FG", "A0", "2", from, null, new String[] {"SA", "4", "EA", "10"}, new String[] {"PH", "2", "EA", "5", "PHANTOM"},
                new String[] {"SA", "2", "EA"});
        bom("SA", "A0", "5", from, null, new String[] {"RM1", "10", "KG", "2"},
                new String[] {"JIG", "2", "EA", "50", "NORMAL", "Y"});
        bom("PH", "A0", "1", from, null, new String[] {"RM1", "3", "KG"}, new String[] {"JIG", "1", "EA", "0", "NORMAL", "Y"},
                new String[] {"BOLTA", "10", "EA", "0", "NORMAL", "N", "G1", "1", "70"},
                new String[] {"BOLTB", "10", "EA", "0", "NORMAL", "N", "G1", "2", "30"},
                new String[] {"CHIP", "0.2", "KG", "0", "BYPRODUCT"});
    }

    @Test
    void 섞인_소요량_다단_경로_순서와_행별_필요량() throws Exception {
        mixed();
        String b = body(explode(admin, "itemCd", "FG", "qty", "10").andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[*].itemCd")).containsExactly("SA", "RM1", "JIG", "PH", "RM1",
                "JIG", "BOLTA", "BOLTB", "CHIP", "SA", "RM1", "JIG");
        assertThat(dec(b, "$.data.rows[0].reqQty")).isEqualByComparingTo("22");
        assertThat(dec(b, "$.data.rows[1].reqQty")).isEqualByComparingTo("44.88");
        assertThat(dec(b, "$.data.rows[2].reqQty")).isEqualByComparingTo("3");       // 고정 2 × (1 + 50%)
        assertThat(dec(b, "$.data.rows[3].reqQty")).isEqualByComparingTo("10.5");
        assertThat(dec(b, "$.data.rows[4].reqQty")).isEqualByComparingTo("31.5");
        assertThat(dec(b, "$.data.rows[5].reqQty")).isEqualByComparingTo("1");
        assertThat(dec(b, "$.data.rows[6].reqQty")).isEqualByComparingTo("73.5");
        assertThat(dec(b, "$.data.rows[7].reqQty")).isEqualByComparingTo("31.5");
        assertThat(dec(b, "$.data.rows[8].reqQty")).isEqualByComparingTo("2.1");
        assertThat(dec(b, "$.data.rows[9].reqQty")).isEqualByComparingTo("10");
        assertThat(dec(b, "$.data.rows[10].reqQty")).isEqualByComparingTo("20.4");
        // 같은 SA 두 경로 — 경로 표기는 행마다 그 경로
        assertThat((String) JsonPath.read(b, "$.data.rows[10].path")).isEqualTo("FG > SA > RM1");
        assertThat((String) JsonPath.read(b, "$.data.rows[4].path")).isEqualTo("FG > PH > RM1");
    }

    @Test
    void 섞인_소요량_합산_같은_품목_여러_경로가_한_줄로_출처는_중복없이() throws Exception {
        mixed();
        String b = body(explode(admin, "itemCd", "FG", "qty", "10", "view", "SUM").andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[*].itemCd")).containsExactly("BOLTA", "BOLTB", "JIG", "RM1",
                "CHIP");
        Map<String, Object> rm1 = sumRow(b, "RM1", "NORMAL");
        assertThat(new BigDecimal(rm1.get("reqQty").toString())).isEqualByComparingTo("96.78");
        assertThat(rm1.get("parentCd")).isEqualTo("SA, PH");
        // 고정 수량도 경로마다 더해진다 (3 + 1 + 3) — 관찰 O3
        assertThat(new BigDecimal(sumRow(b, "JIG", "NORMAL").get("reqQty").toString())).isEqualByComparingTo("7");
        assertThat(new BigDecimal(sumRow(b, "CHIP", "BYPRODUCT").get("reqQty").toString())).isEqualByComparingTo("2.1");
        // 가상(PH)·중간 조립(SA)은 합산에 없다
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[*].itemCd")).doesNotContain("PH", "SA");
    }

    @Test
    void 같은_품목이_투입과_부산물로_동시에_나오면_합산은_두_줄() throws Exception {
        items("FG:EA", "REGRIND:KG", "RESIN:KG");
        bom("FG", "A0", "1", today.minusDays(1), null, new String[] {"RESIN", "1", "KG"},
                new String[] {"REGRIND", "0.2", "KG"}, new String[] {"REGRIND", "0.3", "KG", "0", "BYPRODUCT"});
        String b = body(explode(admin, "itemCd", "FG", "qty", "10", "view", "SUM").andExpect(status().isOk()).andReturn());
        assertThat(new BigDecimal(sumRow(b, "REGRIND", "NORMAL").get("reqQty").toString())).isEqualByComparingTo("2");
        assertThat(new BigDecimal(sumRow(b, "REGRIND", "BYPRODUCT").get("reqQty").toString())).isEqualByComparingTo("3");
    }

    @Test
    void 관찰_BOM_없는_가상조립은_합산에서_구매_자재처럼_나온다() throws Exception {
        items("FG:EA", "GHOST:EA", "RM1:KG");
        bom("FG", "A0", "1", today.minusDays(1), null, new String[] {"GHOST", "2", "EA", "0", "PHANTOM"},
                new String[] {"RM1", "1", "KG"});
        String b = body(explode(admin, "itemCd", "FG", "view", "SUM").andExpect(status().isOk()).andReturn());
        // 가상 조립인데 아래 구조가 없다는 표시 없이 NORMAL 말단 2 EA 로 합산된다 — 관찰 O4
        Map<String, Object> g = sumRow(b, "GHOST", "NORMAL");
        assertThat(new BigDecimal(g.get("reqQty").toString())).isEqualByComparingTo("2");
    }

    private void chain(String prefix, int levels, String base) {
        String[] it = new String[levels + 1];
        for (int i = 0; i <= levels; i++) {
            it[i] = prefix + i + ":EA";
        }
        items(it);
        for (int i = 0; i < levels; i++) {
            bom(prefix + i, "A0", base, today.minusDays(1), null, new String[] {prefix + (i + 1), "1", "EA"});
        }
    }

    @Test
    void 기준수량_3_으로_10단_나누어도_필요량_값은_정확() throws Exception {
        chain("N", 10, "3");
        String b = body(explode(admin, "itemCd", "N0").andExpect(status().isOk()).andReturn());
        // (1/3)^10 = 0.0000169350878084302867…
        assertThat(dec(b, "$.data.rows[9].reqQty").subtract(new BigDecimal("0.0000169350878084302867")).abs())
                .isLessThan(new BigDecimal("1e-11"));      // 응답은 소수 12자리 (결함 #2 수정)
    }

    /**
     * 결함 #2(수정됨): 필요량 numeric 의 소수 자릿수가 레벨마다 더해져(나눗셈 20자리 + 곱셈 누적) 기준 수량 1·수량 1 사슬 10단만에
     * reqQty 가 수백 자리 숫자로 응답된다. 화면은 소수 3자리로 보이지만 JSON·계산 비용이 행 수 × 자릿수로 커진다.
     */
    @Test
    void 결함2_필요량은_정해진_자릿수로_반올림되어야_한다() throws Exception {
        chain("N", 10, "1");
        String b = body(explode(admin, "itemCd", "N0").andExpect(status().isOk()).andReturn());
        assertThat(JsonPath.<Object>read(b, "$.data.rows[9].reqQty").toString().length()).isLessThanOrEqualTo(30);
    }

    // =====================================================================
    // 3. 정책 상한 정확히·초과
    // =====================================================================

    @Test
    void 행_상한_정확히_1000행은_통과_1010행은_BOM_TOO_MANY_ROWS() throws Exception {
        policy("BOM_EXPLODE_MAX_ROWS", "1000");
        items("ROOT:EA", "SUB:EA", "ROOT2:EA", "SUB2:EA", "LEAF:EA");
        sameChildLines("SUB", "LEAF", 99, "1");
        sameChildLines("ROOT", "SUB", 10, "1");       // 10 + 10 × 99 = 1000
        sameChildLines("SUB2", "LEAF", 100, "1");
        sameChildLines("ROOT2", "SUB2", 10, "1");     // 10 + 10 × 100 = 1010
        explode(admin, "itemCd", "ROOT").andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(1000));
        // 합산: 여러 경로의 LEAF 990 이 한 줄
        explode(admin, "itemCd", "ROOT", "view", "SUM").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].reqQty").value(990));
        explode(admin, "itemCd", "ROOT2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOM_TOO_MANY_ROWS"));
        // 합산 보기도 같은 상한(합산 결과는 1줄이지만 계산 행이 넘으면 오류) / 단계별은 10행이라 통과
        explode(admin, "itemCd", "ROOT2", "view", "SUM").andExpect(status().isBadRequest());
        explode(admin, "itemCd", "ROOT2", "view", "SINGLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(10));
        // 역전개: LEAF 를 쓰는 행 99 + 100 + (SUB·SUB2 를 쓰는 행 10 × 199) = 2189 → 초과
        where(admin, "itemCd", "LEAF").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_TOO_MANY_ROWS"));
        // 엑셀도 같은 서비스 — 잘린 파일을 만들지 않는다
        mvc.perform(post(TREE + "/export").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCd\":\"ROOT2\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOM_TOO_MANY_ROWS"));
    }

    @Test
    void 레벨_상한_5_정확히는_통과_넘으면_오류_단계별은_깊어도_통과() throws Exception {
        policy("BOM_MAX_LEVEL", "5");
        String[] chain = new String[7];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = "K" + i + ":EA";
        }
        items(chain);
        for (int i = 0; i < 6; i++) {
            bom("K" + i, "A0", "1", today.minusDays(1), null, new String[] {"K" + (i + 1), "1", "EA"});
        }
        explode(admin, "itemCd", "K1").andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(5))
                .andExpect(jsonPath("$.data.maxLevel").value(5));
        explode(admin, "itemCd", "K0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_TOO_DEEP"));
        explode(admin, "itemCd", "K0", "view", "SUM").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOM_TOO_DEEP"));
        explode(admin, "itemCd", "K0", "view", "SINGLE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1));
        where(admin, "itemCd", "K5").andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(5));
        where(admin, "itemCd", "K6").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_TOO_DEEP"));
        // 최상위만 보기도 상한 검사는 같다
        where(admin, "itemCd", "K6", "topOnly", "true").andExpect(status().isBadRequest());
    }

    // =====================================================================
    // 4. 경로 폭주 — 같은 자품목을 두 행으로 쓰는 단계가 이어지면 경로가 2^n
    // =====================================================================

    /** D0 → D1 ×2 → D2 ×2 … → Dn. 전개 행 = 2^(n+1) - 2 */
    private void doubling(int n) {
        String[] it = new String[n + 1];
        for (int i = 0; i <= n; i++) {
            it[i] = "D" + i + ":EA";
        }
        items(it);
        for (int i = 0; i < n; i++) {
            sameChildLines("D" + i, "D" + (i + 1), 2, "1");
        }
    }

    @Test
    void 경로_폭주_상한_안쪽은_정확하고_넘으면_오류() throws Exception {
        doubling(13);                                   // 16,382행 < 20,000
        String b = body(explode(admin, "itemCd", "D0").andExpect(status().isOk()).andReturn());
        assertThat((Integer) JsonPath.read(b, "$.data.rows.length()")).isEqualTo(16382);
        explode(admin, "itemCd", "D0", "view", "SUM").andExpect(jsonPath("$.data.rows[0].itemCd").value("D13"))
                .andExpect(jsonPath("$.data.rows[0].reqQty").value(8192));
        // 저레벨 코드 계산은 간선만 보므로 폭주와 무관
        where(admin, "itemCd", "D1", "topOnly", "true").andExpect(status().isOk());
    }

    /**
     * 결함 #1(수정됨): 상한(20,000행)을 넘는 구조라도 재귀 CTE 가 **모든 경로를 끝까지 만든 뒤** 정렬·LIMIT 한다.
     * 같은 자품목 두 행 × 22단 = 경로 8,388,606개 — 저장 권한 사용자가 만든 구조 하나로 조회 권한 사용자 누구나 DB 를 붙잡는다.
     * 고치면(재귀를 행 상한에서 끊기) 즉시 BOM_TOO_MANY_ROWS 여야 한다.
     * 제한 시간 15초: 단독 2초 미만이지만 전체 check 부하에서 5초를 넘긴 적이 있다. 고치기 전은 문장 시간 제한(30초)까지 붙잡혀 여전히 구분된다.
     */
    @Test
    void 결함1_경로_폭주_구조는_상한에서_바로_끊겨야_한다() throws Exception {
        doubling(22);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            explode(admin, "itemCd", "D0").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BOM_TOO_MANY_ROWS"));
        });
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            where(admin, "itemCd", "D22").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BOM_TOO_MANY_ROWS"));
        });
    }

    /**
     * 결함 #6(수정됨): 역전개의 top(더 위에서 쓰지 않는 모품목) 이 행마다 재귀 결과 전체를 다시 훑는 상관 서브쿼리
     * (실행계획 SubPlan → CTE Scan on up u2, 행 수 추정 11) 라 행 수의 제곱으로 느려진다. **상한 안쪽** 16,382행 역전개가
     * 문장 시간 제한 30초에 걸려 500 DB_ERROR — 같은 구조 정전개는 1초 미만, top 을 뺀 같은 SQL 은 0.5초.
     */
    @Test
    void 결함6_상한_안쪽_역전개는_몇_초_안에_끝나야_한다() throws Exception {
        doubling(13);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            where(admin, "itemCd", "D13").andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(16382));
        });
    }

    // =====================================================================
    // 5. 회사·권한·엑셀
    // =====================================================================

    @Test
    void B사_사용자_A사_bomId_엑셀_역전개_엑셀_저레벨코드_모두_막힘() throws Exception {
        mixed();
        long fgBom = accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) where i.item_cd = 'FG'",
                Long.class);
        String other = accounts.newComp("QT");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-bom-tree", "SEARCH", "EXCEL");
        accounts.grant(other, bUser, "MASTER", "mst-bom-where", "SEARCH", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(post(TREE + "/export").with(csrf()).with(b).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bomId\":" + fgBom + "}")).andExpect(status().isNotFound());
        mvc.perform(post(WHERE + "/export").with(csrf()).with(b).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCd\":\"RM1\"}")).andExpect(status().isBadRequest());
        // B사에도 같은 코드 품목이 있으면 B사 것만 — A사 구조가 섞이지 않는다
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'RM1', 'B사 RM1', 'RM', 'BUY', 'KG', 'N', 't', 't')""", other);
        where(b, "itemCd", "RM1").andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(0))
                .andExpect(jsonPath("$.data.llc").doesNotExist());
        mvc.perform(get("/api/master/bom-llc").with(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 권한_조합_조회만은_엑셀_403_전개만은_역전개_저레벨_403() throws Exception {
        mixed();
        long only = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, only, "VIEW", "mst-bom-tree", "SEARCH");     // 관리자와 다른 권한 그룹
        RequestPostProcessor v = accounts.as(comp, only, "viewer");
        explode(v, "itemCd", "FG").andExpect(status().isOk());
        mvc.perform(post(TREE + "/export").with(csrf()).with(v).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCd\":\"FG\"}")).andExpect(status().isForbidden());
        where(v, "itemCd", "RM1").andExpect(status().isForbidden());
        mvc.perform(get("/api/master/bom-llc").with(v)).andExpect(status().isForbidden());
        // CSRF 없는 엑셀 요청
        mvc.perform(post(TREE + "/export").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"itemCd\":\"FG\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 입력_경계_수량_최대값_음수_소문자보기_빈품목() throws Exception {
        mixed();
        explode(admin, "itemCd", "FG", "qty", "999999999999.999999").andExpect(status().isOk());
        explode(admin, "itemCd", "FG", "qty", "-1").andExpect(status().isBadRequest());
        explode(admin, "itemCd", "FG", "qty", "1e3").andExpect(status().isBadRequest());
        explode(admin, "itemCd", "FG", "view", "sum").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("view"));
        explode(admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        explode(admin, "bomId", "abc").andExpect(status().isBadRequest());
        explode(admin, "bomId", "99999999999").andExpect(status().isNotFound());
        where(admin, "itemCd", "%").andExpect(status().isBadRequest());
    }
}
