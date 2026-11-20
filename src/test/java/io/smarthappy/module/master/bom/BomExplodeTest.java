package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * BOM 전개·역전개 (P1 3b, docs/18 5.2·5.3) — 손으로 검산할 수 있는 구조로 필요량을 확인한다.
 *
 * <pre>
 * FG (기준 1)
 *  10 SA    2 EA                        SA (기준 10): 10 RM1 25 KG 손실 4%, 20 TIP 1 EA 고정
 *  20 PH    1 EA  가상 조립              PH (기준 1) : 10 NUT 4 EA, 20 RM1 500 G (환산 1 G = 0.001 KG)
 *  30 BOLTA 10 EA 대체 A1 우선 1 60%
 *  40 BOLTB 10 EA 대체 A1 우선 2 40%
 *  50 SCRAP 0.1 KG 부산물
 * FG 3개 → SA 6, RM1 = 25/10×6×1.04 = 15.6 (+ PH 경유 0.5×3 = 1.5) = 17.1 KG, TIP 1(고정), NUT 12, BOLTA 18, BOLTB 12, SCRAP 0.3
 * </pre>
 */
@AutoConfigureMockMvc
class BomExplodeTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

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
    LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("BX");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom-tree", "SEARCH", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-bom-where", "SEARCH", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    // ---------- 도우미 (직접 SQL — 전개는 조회만 보므로 저장 API 를 거치지 않는다) ----------

    private void items(String c, String... spec) {
        for (String s : spec) {
            String[] p = s.split(":");
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                          created_by, updated_by)
                    values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", c, p[0], p[0] + " 품목", p[1]);
        }
    }

    /** 확정 BOM: lines = {자품목, 수량, 단위, 손실률, 유형, 고정, 대체그룹, 순위, 비율} (뒤는 생략 가능) */
    private long bom(String c, String parent, String rev, String base, LocalDate from, boolean release, String[]... lines) {
        long id = accounts.query(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                select ?, item_id, ?, ?, ?::numeric, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, c, rev, from, base, parent);
        for (int i = 0; i < lines.length; i++) {
            String[] l = lines[i];
            String type = l.length > 4 ? l[4] : "NORMAL";
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, scrap_pct, line_type,
                                              fixed_yn, issue_method, alt_grp, alt_priority, alt_pct, created_by, updated_by)
                    select ?, ?, ?, item_id, ?::numeric, ?, ?::numeric, ?, ?, ?, ?, ?::smallint, ?::numeric, 't', 't'
                      from mst_item where item_cd = ?""", c, id, (i + 1) * 10, l[1], l[2], l.length > 3 ? l[3] : "0", type,
                    l.length > 5 ? l[5] : "N", "NORMAL".equals(type) ? "BACKFLUSH" : null, l.length > 6 ? l[6] : null,
                    l.length > 7 ? l[7] : null, l.length > 8 ? l[8] : null, l[0]);
        }
        if (release) {
            accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        }
        return id;
    }

    private void sample() {
        items(comp, "FG:EA", "SA:EA", "PH:EA", "RM1:KG", "TIP:EA", "NUT:EA", "BOLTA:EA", "BOLTB:EA", "SCRAP:KG");
        accounts.exec(comp, """
                insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by)
                select ?, item_id, 'G', 'KG', 0.001, 't', 't' from mst_item where item_cd = 'RM1'""", comp);
        bom(comp, "FG", "A0", "1", today, true, new String[] {"SA", "2", "EA"}, new String[] {"PH", "1", "EA", "0", "PHANTOM"},
                new String[] {"BOLTA", "10", "EA", "0", "NORMAL", "N", "A1", "1", "60"},
                new String[] {"BOLTB", "10", "EA", "0", "NORMAL", "N", "A1", "2", "40"},
                new String[] {"SCRAP", "0.1", "KG", "0", "BYPRODUCT"});
        bom(comp, "SA", "A0", "10", today, true, new String[] {"RM1", "25", "KG", "4"},
                new String[] {"TIP", "1", "EA", "0", "NORMAL", "Y"});
        bom(comp, "PH", "A0", "1", today, true, new String[] {"NUT", "4", "EA"}, new String[] {"RM1", "500", "G"});
    }

    private ResultActions explode(Map<String, String> params) throws Exception {
        var req = get("/api/master/bom-tree");
        params.forEach(req::param);
        return mvc.perform(req.with(admin));
    }

    private static BigDecimal dec(MvcResult r, String path) throws Exception {
        return new BigDecimal(JsonPath.read(r.getResponse().getContentAsString(), path).toString());
    }

    // ---------- 정전개 ----------

    @Test
    void 다단_전개_필요량_손실률_고정_환산_가상_부산물_대체비율() throws Exception {
        sample();
        MvcResult r = explode(Map.of("itemCd", "fg", "qty", "3", "view", "MULTI")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rev").value("A0"))
                .andExpect(jsonPath("$.data.rows.length()").value(9))
                .andReturn();
        // 경로 순서: SA(10) → SA 아래 RM1·TIP → PH(20) → PH 아래 NUT·RM1 → BOLTA → BOLTB → SCRAP
        List<String> codes = JsonPath.read(r.getResponse().getContentAsString(), "$.data.rows[*].itemCd");
        assertThat(codes).containsExactly("SA", "RM1", "TIP", "PH", "NUT", "RM1", "BOLTA", "BOLTB", "SCRAP");
        List<Integer> lvls = JsonPath.read(r.getResponse().getContentAsString(), "$.data.rows[*].lvl");
        assertThat(lvls).containsExactly(1, 2, 2, 1, 2, 2, 1, 1, 1);
        assertThat(dec(r, "$.data.rows[0].reqQty")).isEqualByComparingTo("6");          // SA = 2 × 3
        assertThat(dec(r, "$.data.rows[1].reqQty")).isEqualByComparingTo("15.6");       // 25/10 × 6 × 1.04
        assertThat(dec(r, "$.data.rows[2].reqQty")).isEqualByComparingTo("1");          // 고정
        assertThat(dec(r, "$.data.rows[4].reqQty")).isEqualByComparingTo("12");         // 가상 아래 NUT 4 × 3
        assertThat(dec(r, "$.data.rows[5].reqQty")).isEqualByComparingTo("1.5");        // 500 G × 0.001 × 3
        assertThat(dec(r, "$.data.rows[6].reqQty")).isEqualByComparingTo("18");         // 10 × 3 × 60%
        assertThat(dec(r, "$.data.rows[7].reqQty")).isEqualByComparingTo("12");
        assertThat(dec(r, "$.data.rows[8].reqQty")).isEqualByComparingTo("0.3");
        String body = r.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.data.rows[1].path")).isEqualTo("FG > SA > RM1");
        assertThat((String) JsonPath.read(body, "$.data.rows[1].lvlMark")).isEqualTo(". 2");
        assertThat((String) JsonPath.read(body, "$.data.rows[0].childRev")).isEqualTo("A0");
        assertThat((Object) JsonPath.read(body, "$.data.rows[1].childRev")).isNull();
    }

    @Test
    void 합산은_말단_자재별_합이고_가상_중간조립은_빠지고_부산물은_따로() throws Exception {
        sample();
        MvcResult r = explode(Map.of("itemCd", "FG", "qty", "3", "view", "SUM")).andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        List<String> codes = JsonPath.read(body, "$.data.rows[*].itemCd");
        assertThat(codes).containsExactly("BOLTA", "BOLTB", "NUT", "RM1", "TIP", "SCRAP");
        assertThat(dec(r, "$.data.rows[3].reqQty")).isEqualByComparingTo("17.1");       // 15.6 + 1.5
        assertThat((String) JsonPath.read(body, "$.data.rows[3].parentCd")).isEqualTo("SA, PH");
        assertThat((String) JsonPath.read(body, "$.data.rows[5].lineType")).isEqualTo("BYPRODUCT");
    }

    @Test
    void 단계별은_바로_아래만() throws Exception {
        sample();
        explode(Map.of("itemCd", "FG", "view", "SINGLE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(5))
                .andExpect(jsonPath("$.data.rows[0].reqQty").value(2));
    }

    @Test
    void 기준일마다_그날_유효한_리비전으로_전개하고_작성중은_지정해야_미리본다() throws Exception {
        sample();
        // SA A1: 10일 뒤부터 RM1 30 KG — 확정하면 A0 은 그날 마감
        accounts.exec(comp, "update mst_bom set valid_to = ?::date where rev = 'A0' and item_id = (select item_id from mst_item where item_cd = 'SA')",
                today.plusDays(10).toString());
        bom(comp, "SA", "A1", "10", today.plusDays(10), true, new String[] {"RM1", "30", "KG"});
        MvcResult now = explode(Map.of("itemCd", "FG", "qty", "1")).andReturn();
        MvcResult later = explode(Map.of("itemCd", "FG", "qty", "1", "date", today.plusDays(10).toString())).andReturn();
        assertThat(dec(now, "$.data.rows[1].reqQty")).isEqualByComparingTo("5.2");      // 25/10 × 2 × 1.04
        assertThat(dec(later, "$.data.rows[1].reqQty")).isEqualByComparingTo("6");      // 30/10 × 2
        assertThat((String) JsonPath.read(later.getResponse().getContentAsString(), "$.data.rows[0].childRev")).isEqualTo("A1");
        // 확정 BOM 이 없는 날
        explode(Map.of("itemCd", "FG", "date", "2000-01-01")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOM_NO_EFFECTIVE"));
        // 작성 중 BOM 미리보기(bomId 지정) — 환산 없는 단위는 noConv 로 표시하고 1 로 계산
        long draft = bom(comp, "FG", "A1", "1", today, false, new String[] {"RM1", "500", "G"}, new String[] {"NUT", "2", "BOX"});
        explode(Map.of("bomId", "" + draft)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rev").value("A1"))
                .andExpect(jsonPath("$.data.rows[0].noConv").value(false))
                .andExpect(jsonPath("$.data.rows[1].noConv").value(true));
    }

    @Test
    void 레벨_상한을_넘으면_오류_잘린_결과를_보여주지_않는다() throws Exception {
        String[] chain = new String[32];
        for (int i = 0; i < chain.length; i++) {
            chain[i] = "C" + i + ":EA";
        }
        items(comp, chain);
        for (int i = 0; i < 31; i++) {
            bom(comp, "C" + i, "A0", "1", today, true, new String[] {"C" + (i + 1), "1", "EA"});
        }
        explode(Map.of("itemCd", "C0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_TOO_DEEP"));
        explode(Map.of("itemCd", "C1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(30));
        mvc.perform(get("/api/master/bom-where").param("itemCd", "C31").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_TOO_DEEP"));
    }

    @Test
    void 입력_오류() throws Exception {
        sample();
        explode(Map.of("itemCd", "FG", "qty", "0", "date", "2026-02-30", "view", "TREE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        explode(Map.of("itemCd", "NOPE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    // ---------- 역전개 ----------

    @Test
    void 역전개_다단_최상위_전체리비전() throws Exception {
        sample();
        items(comp, "FG2:EA");
        bom(comp, "FG2", "A0", "1", today.plusDays(30), true, new String[] {"SA", "1", "EA"});   // 미래 확정본
        long draft = bom(comp, "TIP", "A0", "1", today, false, new String[] {"NUT", "1", "EA"});  // NUT 를 쓰는 작성 중
        MvcResult r = mvc.perform(get("/api/master/bom-where").param("itemCd", "RM1").with(admin))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        List<String> parents = JsonPath.read(body, "$.data.rows[*].parentCd");
        assertThat(parents).containsExactly("PH", "FG", "SA", "FG");            // RM1 ← PH ← FG, RM1 ← SA ← FG (오늘 기준, FG2 제외)
        assertThat((String) JsonPath.read(body, "$.data.rows[1].path")).isEqualTo("RM1 < PH < FG");
        List<Boolean> tops = JsonPath.read(body, "$.data.rows[*].top");
        assertThat(tops).containsExactly(false, true, false, true);
        mvc.perform(get("/api/master/bom-where").param("itemCd", "RM1").param("topOnly", "true").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].parentCd").value("FG"));
        // 전체 리비전: 미래 확정본 FG2 도
        mvc.perform(get("/api/master/bom-where").param("itemCd", "RM1").param("allRevs", "true").param("topOnly", "true")
                .with(admin)).andExpect(jsonPath("$.data.rows[*].parentCd").value(org.hamcrest.Matchers.contains("FG", "FG2")));
        // 작성 중은 전체 리비전에서만
        mvc.perform(get("/api/master/bom-where").param("itemCd", "NUT").with(admin))
                .andExpect(jsonPath("$.data.rows[*].parentCd").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("TIP"))));
        mvc.perform(get("/api/master/bom-where").param("itemCd", "NUT").param("allRevs", "true").with(admin))
                .andExpect(jsonPath("$.data.rows[*].parentCd").value(org.hamcrest.Matchers.hasItem("TIP")));
        assertThat(draft).isPositive();
    }

    // ---------- 엑셀·회사·권한 ----------

    @Test
    void 엑셀은_화면과_같은_결과() throws Exception {
        sample();
        byte[] x = mvc.perform(post("/api/master/bom-tree/export").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"itemCd\":\"FG\",\"qty\":\"3\",\"view\":\"MULTI\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            var s = wb.getSheetAt(0);
            // 레벨은 숫자 칸(정렬·필터 — 예전 ". 2" 글자, docs/60 D3), 경로는 끝 칸
            assertThat(s.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(2.0);
            assertThat(s.getRow(2).getCell(1).getStringCellValue()).isEqualTo("RM1");
            int last = s.getRow(0).getLastCellNum() - 1;
            assertThat(s.getRow(0).getCell(last).getStringCellValue()).isEqualTo("경로");
            assertThat(s.getRow(2).getCell(last).getStringCellValue()).isEqualTo("FG > SA > RM1");
            assertThat(s.getLastRowNum()).isEqualTo(9);
        }
        // 역전개: "최상위"(boolean) = 예/아니오 — true/false 글자로 나갔다(D3)
        byte[] w = mvc.perform(post("/api/master/bom-where/export").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"itemCd\":\"RM1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(w))) {
            var s = wb.getSheetAt(0);
            int top = -1;
            for (int c = 0; c < s.getRow(0).getLastCellNum(); c++) {
                if ("최상위".equals(s.getRow(0).getCell(c).getStringCellValue())) { top = c; }
            }
            assertThat(top).isPositive();
            assertThat(s.getRow(1).getCell(top).getStringCellValue()).isEqualTo("아니오");   // PH (위에 FG)
            assertThat(s.getRow(2).getCell(top).getStringCellValue()).isEqualTo("예");       // FG
            assertThat(s.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1.0);
        }
    }

    @Test
    void B사_사용자는_A사_품목_BOM_을_전개_역전개_못한다_권한없으면_403() throws Exception {
        sample();
        long fgBom = accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) where i.item_cd = 'FG'",
                Long.class);
        String other = accounts.newComp("BX");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-bom-tree", "SEARCH");
        accounts.grant(other, bUser, "MASTER", "mst-bom-where", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get("/api/master/bom-tree").param("itemCd", "FG").with(b)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get("/api/master/bom-tree").param("bomId", "" + fgBom).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/master/bom-where").param("itemCd", "RM1").with(b)).andExpect(status().isBadRequest());
        long noPerm = accounts.newUser(comp, "nobody", PW);
        RequestPostProcessor n = accounts.as(comp, noPerm, "nobody");
        mvc.perform(get("/api/master/bom-tree").param("itemCd", "FG").with(n)).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/bom-where").param("itemCd", "RM1").with(n)).andExpect(status().isForbidden());
    }

    // ---------- 실측 (평소 건너뜀: -Dhb.perf=true) ----------

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 실측_10레벨_1000행() throws Exception {
        // 루트 아래 100 갈래, 갈래마다 10레벨 사슬 → 전개 1,000행
        StringBuilder sb = new StringBuilder();
        runner.run(comp, () -> {
            jdbc.update("""
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    select ?, 'P' || g || '-' || l, 'perf', 'SFG', 'MAKE', 'EA', 'N', 't', 't'
                      from generate_series(1, 100) g, generate_series(1, 10) l""", comp);
            jdbc.update("""
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, 'PROOT', 'perf root', 'FG', 'MAKE', 'EA', 'N', 't', 't')""", comp);
        });
        long t0 = System.nanoTime();
        String[][] rootLines = new String[100][];
        for (int g = 1; g <= 100; g++) {
            rootLines[g - 1] = new String[] {"P" + g + "-1", "1", "EA"};
        }
        bom(comp, "PROOT", "A0", "1", today, true, rootLines);
        for (int g = 1; g <= 100; g++) {
            for (int l = 1; l < 10; l++) {
                bom(comp, "P" + g + "-" + l, "A0", "1", today, true, new String[] {"P" + g + "-" + (l + 1), "2", "EA"});
            }
        }
        long built = System.nanoTime();
        MvcResult r = null;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            r = explode(Map.of("itemCd", "PROOT", "qty", "1")).andExpect(status().isOk()).andReturn();
            best = Math.min(best, System.nanoTime() - s);
        }
        long w0 = System.nanoTime();
        mvc.perform(get("/api/master/bom-where").param("itemCd", "P1-10").with(admin)).andExpect(status().isOk());
        long w1 = System.nanoTime();
        int rows = JsonPath.read(r.getResponse().getContentAsString(), "$.data.rows.length()");
        assertThat(rows).isEqualTo(1000);
        assertThat(dec(r, "$.data.rows[9].reqQty")).isEqualByComparingTo("512");       // 2^9
        sb.append("구조 생성 ").append((built - t0) / 1_000_000).append("ms, 전개 1,000행 최선 ").append(best / 1_000_000)
                .append("ms, 역전개 10레벨 ").append((w1 - w0) / 1_000_000).append("ms");
        System.out.println("[perf] " + sb);
    }
}
