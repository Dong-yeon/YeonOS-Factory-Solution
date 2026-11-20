package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.cms.CmsKpiMapper.CountRow;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C9 수집 기준 (docs/33 14장, D34~D37) — 수집 COUNT 순합: 역분개 포함(원 행 발생 시각으로), 공장 현지 날짜 경계 [00:00, 다음날 00:00), 다른 회사 0.
 * KPI basis=COUNT: 성능 = 개당 이론 초 × (양품+불량) ÷ 가동, 품질 = 양품 ÷ (양품+불량), OEE = 이론 초 × 양품 ÷ 계획. 이론 초 없으면 빈칸, 기본(RESULT) 불변.
 */
@AutoConfigureMockMvc
class CmsKpiCountTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    /** 2026-03-02 공장 현지(+09:00) 하루 */
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-03-02T00:00:00+09:00");
    private static final OffsetDateTime UNTIL = OffsetDateTime.parse("2026-03-03T00:00:00+09:00");

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    CmsKpiMapper mapper;

    String comp;
    long prs1;
    long prs2;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("KC");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-kpi", "SEARCH");
        accounts.grant(comp, u, "CMS", "cms-equip-profile", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = equip(comp, "PRS-01", cls);
        prs2 = equip(comp, "PRS-02", cls);
    }

    private long equip(String c, String cd, long cls) {
        return accounts.query(c, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id",
                Long.class, c, cd, cd, cls);
    }

    /** 수집 행(AUTO) — 시각은 공장 현지 글자 */
    private long count(String c, long equip, String metric, String qty, String at) {
        return accounts.query(c, """
                insert into cms_equip_count (comp_cd, equip_id, metric_cd, qty, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?::numeric, ?::timestamptz, 'AUTO', 'col') returning count_id""", Long.class, c, equip, metric, qty, at);
    }

    /** 역분개 행 — 정정 시각은 원 행과 다르게(다음 날) 넣어도 원 행 발생 시각으로 센다 */
    private void reverse(String c, long orig, long equip, String metric, String qty, String at) {
        accounts.query(c, """
                insert into cms_equip_count (comp_cd, equip_id, metric_cd, qty, occurred_at, entry_method, recorded_by, reversal_of_id, reason)
                values (?, ?, ?, ?::numeric, ?::timestamptz, 'CORRECT', 'admin1', ?, '중복 수집') returning count_id""", Long.class, c, equip, metric,
                qty, at, orig);
    }

    private List<CountRow> sums(String c) {
        return runner.call(c, () -> mapper.countSums("P1", FROM, UNTIL));
    }

    private static CountRow of(List<CountRow> rows, long equip) {
        return rows.stream().filter(r -> r.getEquipId() == equip).findFirst().orElse(null);
    }

    @Test
    void 역분개는_원행_시각으로_빼고_공장현지일_경계는_시작포함_끝제외() {
        count(comp, prs1, "GOOD", "10", "2026-03-02T00:00:00+09:00");                 // 하루 시작 = 포함
        long dup = count(comp, prs1, "GOOD", "5", "2026-03-02T09:00:00+09:00");
        reverse(comp, dup, prs1, "GOOD", "-5", "2026-03-03T10:00:00+09:00");         // 다음 날 정정 → 원 행 날(이 날)에서 빠짐
        count(comp, prs1, "SCRAP", "2.5", "2026-03-02T23:59:59.999+09:00");           // 하루 끝 직전 = 포함
        count(comp, prs1, "GOOD", "7", "2026-03-03T00:00:00+09:00");                  // 다음 날 0시 = 제외
        count(comp, prs1, "GOOD", "3", "2026-03-01T23:59:59+09:00");                  // 전날 = 제외
        count(comp, prs2, "GOOD", "4", "2026-03-02T12:00:00+09:00");
        List<CountRow> rows = sums(comp);
        assertThat(of(rows, prs1).getGoodQty()).isEqualByComparingTo("10");
        assertThat(of(rows, prs1).getScrapQty()).isEqualByComparingTo("2.5");
        assertThat(of(rows, prs2).getGoodQty()).isEqualByComparingTo("4");
        assertThat(of(rows, prs2).getScrapQty()).isEqualByComparingTo(BigDecimal.ZERO);
        // 정정한 날(다음 날)에는 원 행도 역분개도 잡히지 않는다 — 다음 날 합 = 7 만
        List<CountRow> next = runner.call(comp, () -> mapper.countSums("P1", UNTIL, UNTIL.plusDays(1)));
        assertThat(of(next, prs1).getGoodQty()).isEqualByComparingTo("7");
    }

    @Test
    void 다른_회사_수집은_0() {
        count(comp, prs1, "GOOD", "10", "2026-03-02T09:00:00+09:00");
        String other = accounts.newComp("KD");
        accounts.addPlant(other, "P1", "1공장");
        long cls = accounts.query(other, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, other);
        long b1 = equip(other, "PRS-01", cls);
        count(other, b1, "GOOD", "99", "2026-03-02T09:00:00+09:00");
        assertThat(sums(other)).hasSize(1);
        assertThat(of(sums(other), b1).getGoodQty()).isEqualByComparingTo("99");                // B사 = 자기 것만
        assertThat(of(sums(comp), prs1).getGoodQty()).isEqualByComparingTo("10");               // A사도 자기 것만
        assertThat(of(sums(comp), b1)).isNull();
    }

    private void prd(long equip, String start, String end) {
        accounts.query(comp, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, 'PRD', ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col') returning state_log_id""", Long.class,
                comp, equip, start, end, start);
    }

    private ResultActions profile(long equip, String body) throws Exception {
        return mvc.perform(put("/api/cms/equip-profiles/" + equip).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    private ResultActions kpi(String... basis) throws Exception {
        var b = get("/api/cms/kpi").param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-02");
        if (basis.length > 0) {
            b = b.param("basis", basis[0]);
        }
        return mvc.perform(b.with(admin));
    }

    @Test
    void 수집기준_성능_품질_OEE_손검산_이론초_없으면_빈칸_기본은_실적() throws Exception {
        profile(prs1, "{\"criticality\":\"C\",\"idealCtSec\":\"0\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("idealCtSec"));
        profile(prs1, "{\"criticality\":\"C\",\"idealCtSec\":\"1.2345\"}").andExpect(status().isBadRequest());   // 소수 3자리까지
        profile(prs1, "{\"criticality\":\"C\",\"idealCtSec\":\"2\"}").andExpect(status().isOk());
        mvc.perform(get("/api/cms/equip-profiles/" + prs1).with(admin)).andExpect(jsonPath("$.data.idealCtSec").value(2));
        // PRS-01: 가동 09:00~10:00(3600초), 양품 1000 + 불량 200(역분개로 300 → 200)
        prd(prs1, "2026-03-02T09:00:00+09:00", "2026-03-02T10:00:00+09:00");
        count(comp, prs1, "GOOD", "1000", "2026-03-02T09:30:00+09:00");
        count(comp, prs1, "SCRAP", "200", "2026-03-02T09:40:00+09:00");
        long wrong = count(comp, prs1, "SCRAP", "100", "2026-03-02T09:50:00+09:00");
        reverse(comp, wrong, prs1, "SCRAP", "-100", "2026-03-02T11:00:00+09:00");
        kpi("COUNT").andExpect(status().isOk()).andExpect(jsonPath("$.data.basis").value("COUNT"))
                .andExpect(jsonPath("$.data.equips[?(@.code == 'PRS-01')].kpi.performance").value(0.6667))   // 2 × 1200 ÷ 3600
                .andExpect(jsonPath("$.data.equips[?(@.code == 'PRS-01')].kpi.quality").value(0.8333))       // 1000 ÷ 1200
                .andExpect(jsonPath("$.data.equips[?(@.code == 'PRS-01')].kpi.oee").value(0.5556))           // 2 × 1000 ÷ 3600
                .andExpect(jsonPath("$.data.plant.kpi.performance").value(0.6667));
        // PRS-02: 이론 초 없음 + 수집 수량 있음 → 그 설비·공장 합계 성능·OEE 빈칸(품질은 낸다)
        prd(prs2, "2026-03-02T09:00:00+09:00", "2026-03-02T10:00:00+09:00");
        count(comp, prs2, "GOOD", "50", "2026-03-02T09:30:00+09:00");
        kpi("COUNT").andExpect(jsonPath("$.data.equips[?(@.code == 'PRS-02')].kpi.performance").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.data.equips[?(@.code == 'PRS-02')].kpi.quality").value(1.0))
                .andExpect(jsonPath("$.data.plant.kpi.performance").doesNotExist());
        // 기본(RESULT) = 지금 동작 — 확정 실적이 없으니 품질 빈칸, 수집 카운트를 쓰지 않는다
        kpi().andExpect(jsonPath("$.data.basis").value("RESULT")).andExpect(jsonPath("$.data.plant.kpi.quality").doesNotExist());
        kpi("NOPE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("basis"));
    }
}
