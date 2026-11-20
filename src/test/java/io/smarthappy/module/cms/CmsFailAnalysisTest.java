package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 고장 분석 (docs/33 16.4 G1 · 16.5 G2, cms-dashboard) — 손 검산 시나리오(기간 2026-03-02 ~ 03-04, 공장 시간대 +09:00):
 * <pre>
 * #1 PRS-01 WEAR  03-02 09:00 정지 60분 · 대기 10 · 수리 80 · 공수 60+30 · 담당 admin1
 * #2 PRS-01 WEAR  03-03 09:00 정지 30분 · 대기 20 · 수리 20 · 공수 20     · 담당 admin1
 * #3 PRS-02 SEAL  03-03 13:00 정지 없음 · 대기 30 · 수리 30
 * #4 WLD-01 WEAR  03-04 08:00 정지 20분 · 대기 5  · 수리 25
 * #5 PRS-01 BOLT  03-04 23:30 정지 없음 · 대기 10 · 수리 10 (원인 분류 ELEC)
 * 기간 밖: 03-05 00:00(자정 경계), 앞 기간 PRS-02 SEAL 03-01 20:00 · WLD-01 WEAR 03-01 07:59(반복 판정의 앞 고장만)
 * 빠짐: 취소·진행 중 고장, 끝난 개선(CM)
 * </pre>
 * 파레토 합 = 고장 5, 드릴다운 건수 = 막대 값, 반복 N 경계, 설비 클래스, A사로 B사, 권한·입력 검사(API 직접).
 */
@AutoConfigureMockMvc
class CmsFailAnalysisTest extends PostgresTestBase {

    private static final String PW = "Birch-lantern-52";
    private static final String F = "/api/cms/dashboard/failures";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("FA");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "cms-dashboard", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        seed(comp, userId);
    }

    /** 회사 하나에 위 시나리오 — 반환 없음(코드로 찾는다) */
    private void seed(String c, long user) {
        accounts.addPlant(c, "P1", "1공장");
        long press = cls(c, "PRESS");
        long weld = cls(c, "WELD");
        long prs1 = equip(c, "PRS-01", press);
        long prs2 = equip(c, "PRS-02", press);
        long wld1 = equip(c, "WLD-01", weld);
        long noise = code(c, "SYMPTOM", "NOISE", null);
        long leak = code(c, "SYMPTOM", "LEAK", null);
        long wear = code(c, "CAUSE", "WEAR", "MECH");
        long seal = code(c, "CAUSE", "SEAL", "FLUID");
        long bolt = code(c, "CAUSE", "BOLT", "ELEC");
        long repl = code(c, "ACTION", "REPL", null);
        bm(c, prs1, noise, wear, repl, "2026-03-02T09:00", "2026-03-02T09:10", "2026-03-02T10:30", "2026-03-02T09:00", "2026-03-02T10:00", user, 60, 30);
        bm(c, prs1, noise, wear, repl, "2026-03-03T09:00", "2026-03-03T09:20", "2026-03-03T09:40", "2026-03-03T09:00", "2026-03-03T09:30", user, 20);
        bm(c, prs2, leak, seal, repl, "2026-03-03T13:00", "2026-03-03T13:30", "2026-03-03T14:00", null, null, null);
        bm(c, wld1, noise, wear, repl, "2026-03-04T08:00", "2026-03-04T08:05", "2026-03-04T08:30", "2026-03-04T08:00", "2026-03-04T08:20", null);
        bm(c, prs1, noise, bolt, repl, "2026-03-04T23:30", "2026-03-04T23:40", "2026-03-04T23:50", null, null, null);
        // 기간 밖 — 자정 경계(공장 현지 03-05 00:00 = UTC 03-04 15:00), 반복 판정의 앞 고장 둘
        bm(c, prs1, noise, wear, repl, "2026-03-05T00:00", "2026-03-05T00:10", "2026-03-05T00:20", null, null, null);
        bm(c, prs2, leak, seal, repl, "2026-03-01T20:00", "2026-03-01T20:10", "2026-03-01T20:30", null, null, null);
        bm(c, wld1, noise, wear, repl, "2026-03-01T07:59", "2026-03-01T08:10", "2026-03-01T08:30", null, null, null);
        // 빠질 것: 취소 고장·진행 중 고장·끝난 개선(CM)
        long cancelled = open(c, prs1, "BM", noise, "2026-03-02T11:00");
        accounts.exec(c, "update cms_mwo set status = 'CANCELLED', cancel_reason = '중복' where mwo_id = ?", cancelled);
        open(c, prs1, "BM", noise, "2026-03-02T12:00");
        long cm = open(c, prs1, "CM", null, "2026-03-02T13:00");
        accounts.exec(c, "update cms_mwo set status = 'WIP', started_at = '2026-03-02T13:10:00+09:00' where mwo_id = ?", cm);
        accounts.exec(c, "update cms_mwo set status = 'DONE', finished_at = '2026-03-02T14:00:00+09:00', work_text = '개선' where mwo_id = ?", cm);
    }

    private long cls(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", classId);
    }

    private long code(String c, String type, String cd, String cat) {
        return accounts.query(c, """
                insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, fail_cat, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning fail_id""", Long.class, c, type, cd, cd + " 이름", cat);
    }

    private long open(String c, long equip, String type, Long sym, String reported) {
        return accounts.query(c, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_id, symptom_text,
                                     down_yn, created_by, updated_by)
                values (?, 'T-' || nextval(pg_get_serial_sequence('cms_mwo', 'mwo_id')), ?, 'P1', 'EQUIP', ?, '작업', 'OPEN', ?::timestamptz, ?, ?,
                        'N', 't', 't') returning mwo_id""", Long.class, c, type, equip, reported + ":00+09:00", sym, sym == null ? "글" : null);
    }

    /** 끝난 고장 수리 — 공수는 착수(WIP) 상태에서 넣는다(트리거) */
    private long bm(String c, long equip, long sym, long cause, long act, String reported, String started, String finished, String downStart,
            String downEnd, Long assignee, int... labor) {
        long id = accounts.query(c, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_id,
                                     down_yn, down_start_at, down_end_at, assignee_id, created_by, updated_by)
                values (?, 'T-' || nextval(pg_get_serial_sequence('cms_mwo', 'mwo_id')), 'BM', 'P1', 'EQUIP', ?, '고장', 'OPEN', ?::timestamptz, ?,
                        ?, ?::timestamptz, ?::timestamptz, ?, 't', 't') returning mwo_id""", Long.class, c, equip, CmsWave3FixTest.ts(reported), sym,
                downStart == null ? "N" : "Y", CmsWave3FixTest.ts(downStart), CmsWave3FixTest.ts(downEnd), assignee);
        accounts.exec(c, "update cms_mwo set status = 'WIP', started_at = ?::timestamptz where mwo_id = ?", CmsWave3FixTest.ts(started), id);
        for (int m : labor) {
            accounts.exec(c, """
                    insert into cms_mwo_labor (comp_cd, mwo_id, user_id, work_date, minutes, created_by, updated_by)
                    values (?, ?, ?, ?::date, ?, 't', 't')""", c, id, assignee, reported.substring(0, 10), m);
        }
        accounts.exec(c, "update cms_mwo set status = 'DONE', finished_at = ?::timestamptz, cause_id = ?, action_id = ?, work_text = '교체' where mwo_id = ?",
                CmsWave3FixTest.ts(finished), cause, act, id);
        return id;
    }

    private ResultActions fail(RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(F).param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-04");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private ResultActions drill(String axis, String key, String... more) throws Exception {
        MockHttpServletRequestBuilder b = get(F + "/mwos").param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-04")
                .param("axis", axis).param("key", key);
        for (int i = 0; i < more.length; i += 2) {
            b = b.param(more[i], more[i + 1]);
        }
        return mvc.perform(b.with(admin));
    }

    private static String body(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String dec(Object v) {
        return v == null ? null : new java.math.BigDecimal(v.toString()).setScale(1).toPlainString();
    }

    @Test
    void 원인_파레토_손검산_합은_기간고장수_취소진행중개선_기간밖_제외() throws Exception {
        String b = body(fail(admin));
        assertThat((Integer) JsonPath.read(b, "$.data.failures")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(b, "$.data.downSec")).isEqualTo(110 * 60);
        assertThat((String) JsonPath.read(b, "$.data.axis")).isEqualTo("CAUSE");
        // WEAR 3건(정지 110분) → 같은 1건이면 정지 시간 → 코드 순(BOLT, SEAL)
        assertThat(JsonPath.<List<String>>read(b, "$.data.bars[*].keyCd")).containsExactly("WEAR", "BOLT", "SEAL");
        assertThat(JsonPath.<List<Integer>>read(b, "$.data.bars[*].failures")).containsExactly(3, 1, 1);
        assertThat((Integer) JsonPath.read(b, "$.data.bars[0].downSec")).isEqualTo(6600);
        assertThat((String) JsonPath.read(b, "$.data.bars[0].keyNm")).isEqualTo("WEAR 이름");
        List<Integer> all = JsonPath.read(b, "$.data.bars[*].failures");
        assertThat(all.stream().mapToInt(Integer::intValue).sum()).as("파레토 합 = 고장 수").isEqualTo(5);
    }

    @Test
    void 축_현상_분류_설비_설비클래스_거르기() throws Exception {
        String cat = body(fail(admin, "axis", "cat"));
        // MECH = WEAR 3, ELEC = BOLT 1, FLUID = SEAL 1(같으면 코드 순). 이름은 공통코드 이름(원인은 분류 필수라 키 없는 줄은 없다)
        assertThat(JsonPath.<List<String>>read(cat, "$.data.bars[*].keyCd")).containsExactly("MECH", "ELEC", "FLUID");
        assertThat((String) JsonPath.read(cat, "$.data.bars[0].keyNm")).isNotBlank().isNotEqualTo("MECH");
        String eq = body(fail(admin, "axis", "EQUIP"));
        assertThat(JsonPath.<List<String>>read(eq, "$.data.bars[*].keyCd")).containsExactly("PRS-01", "WLD-01", "PRS-02");   // 같은 1건이면 정지 시간 큰 순(WLD-01 20분)
        assertThat(JsonPath.<List<Integer>>read(eq, "$.data.bars[*].failures")).containsExactly(3, 1, 1);
        String sym = body(fail(admin, "axis", "SYMPTOM"));
        assertThat(JsonPath.<List<String>>read(sym, "$.data.bars[*].keyCd")).containsExactly("NOISE", "LEAK");
        // 설비 클래스 WELD → #4 만, 설비 글자 prs-02 → #3 만
        assertThat((Integer) JsonPath.read(body(fail(admin, "classCd", "weld")), "$.data.failures")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body(fail(admin, "keyword", "prs-02")), "$.data.failures")).isEqualTo(1);
    }

    @Test
    void 드릴다운_건수는_막대값_같은조건() throws Exception {
        String b = body(fail(admin, "axis", "CAT"));
        List<Map<String, Object>> bars = JsonPath.read(b, "$.data.bars");
        for (Map<String, Object> bar : bars) {
            String key = bar.get("keyCd") == null ? "-" : (String) bar.get("keyCd");
            String d = body(drill("CAT", key));
            assertThat(JsonPath.<List<Object>>read(d, "$.data.list")).as("막대 %s", key).hasSize((Integer) bar.get("failures"));
        }
        String wear = body(drill("CAUSE", "WEAR"));
        assertThat(JsonPath.<List<String>>read(wear, "$.data.list[*].equipCd")).containsExactly("WLD-01", "PRS-01", "PRS-01");   // 고장 시각 ↓
        assertThat((Integer) JsonPath.read(wear, "$.data.list[2].downSec")).isEqualTo(3600);
        assertThat((Integer) JsonPath.read(wear, "$.data.list[2].waitSec")).isEqualTo(600);
        // 같은 조건(설비 클래스) — WELD 의 WEAR 는 1건
        assertThat(JsonPath.<List<Object>>read(body(drill("CAUSE", "WEAR", "classCd", "WELD")), "$.data.list")).hasSize(1);
        // 따옴표 섞인 키는 그냥 0건(파라미터 바인딩)
        assertThat(JsonPath.<List<Object>>read(body(drill("CAUSE", "WEAR' or '1'='1")), "$.data.list")).isEmpty();
    }

    @Test
    void 수리시간_분해_설비별_담당별_손검산_MTBF는_이름을_나눠() throws Exception {
        String b = body(fail(admin));
        Map<String, Object> p1 = JsonPath.<List<Map<String, Object>>>read(b, "$.data.byEquip[?(@.keyCd == 'PRS-01')]").get(0);
        // PRS-01: 고장 3(#1 #2 #5), 대기 (10+20+10)/3 = 13.3, 수리 (80+20+10)/3 = 36.7, 정지 (60+30)/2 = 45.0, 공수 (90+20)/2 = 55.0
        assertThat(p1).containsEntry("failures", 3).containsEntry("downCount", 2).containsEntry("waitCount", 3).containsEntry("laborCount", 2);
        assertThat(dec(p1.get("avgWaitMin"))).isEqualTo("13.3");
        assertThat(dec(p1.get("avgRepairMin"))).isEqualTo("36.7");
        assertThat(dec(p1.get("avgDownMin"))).isEqualTo("45.0");
        assertThat(dec(p1.get("avgLaborMin"))).isEqualTo("55.0");
        // 수집 없음 → 상태 원장 MTBF·MTTR 빈칸, 작업지시 기준: MTTR = 5400/2/60 = 45.0, MTBF = (3일 259200 − 5400)/3/60 = 1410.0
        assertThat(p1).containsEntry("collected", false);
        assertThat(p1.get("kpiMtbfMin")).isNull();
        assertThat(dec(p1.get("woMttrMin"))).isEqualTo("45.0");
        assertThat(dec(p1.get("woMtbfMin"))).isEqualTo("1410.0");
        Map<String, Object> p2 = JsonPath.<List<Map<String, Object>>>read(b, "$.data.byEquip[?(@.keyCd == 'PRS-02')]").get(0);
        assertThat(p2.get("avgDownMin")).isNull();   // 정지 기록 없음 = 평균 없음(0 이 아님)
        // 담당별: admin1 2건(#1 #2), 담당 없음 3건
        List<Map<String, Object>> by = JsonPath.read(b, "$.data.byAssignee");
        assertThat(by).hasSize(2);
        assertThat(by.get(0)).containsEntry("keyCd", null).containsEntry("failures", 3);
        assertThat(by.get(1)).containsEntry("keyCd", "admin1").containsEntry("failures", 2);
        assertThat(dec(by.get(1).get("avgWaitMin"))).isEqualTo("15.0");
    }

    @Test
    void 반복고장_기간앞_고장도_앞고장으로_N일_경계() throws Exception {
        String b = body(fail(admin));
        // 기본 30일: PRS-01×WEAR(1일), PRS-02×SEAL(앞 기간 03-01 20:00 → 1일 17시간), WLD-01×WEAR(03-01 07:59 → 3일 1분)
        assertThat((Integer) JsonPath.read(b, "$.data.repeatDays")).isEqualTo(30);
        assertThat(JsonPath.<List<String>>read(b, "$.data.repeats[*].equipCd")).containsExactlyInAnyOrder("PRS-01", "PRS-02", "WLD-01");
        Map<String, Object> p1 = JsonPath.<List<Map<String, Object>>>read(b, "$.data.repeats[?(@.equipCd == 'PRS-01')]").get(0);
        assertThat(p1).containsEntry("causeCd", "WEAR").containsEntry("repeats", 1);
        assertThat(dec(p1.get("minGapDays"))).isEqualTo("1.0");
        // 정책 3일: 3일 1분인 WLD-01 은 반복 아님, 1일·1일 17시간은 반복
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'CMS_REPEAT_FAIL_DAYS', '3', 't', 't')", comp);
        String n3 = body(fail(admin));
        assertThat(JsonPath.<List<String>>read(n3, "$.data.repeats[*].equipCd")).containsExactlyInAnyOrder("PRS-01", "PRS-02");
        // 정책 1일: 정확히 24시간(PRS-01) 은 경계 안(≤ N일), 1일 17시간(PRS-02) 은 밖
        accounts.exec(comp, "update sys_policy set policy_value = '1' where policy_key = 'CMS_REPEAT_FAIL_DAYS'");
        assertThat(JsonPath.<List<String>>read(body(fail(admin)), "$.data.repeats[*].equipCd")).containsExactly("PRS-01");
    }

    @Test
    void 자정경계_기간을_하루씩_나누면_합이_같다() throws Exception {
        int sum = 0;
        for (String d : List.of("2026-03-02", "2026-03-03", "2026-03-04")) {
            sum += (Integer) JsonPath.read(body(mvc.perform(get(F).param("plantCd", "P1").param("from", d).param("to", d).with(admin))), "$.data.failures");
        }
        assertThat(sum).isEqualTo(5);
        // 03-05 하루 = 자정 고장 1건(03-04 23:30 은 04일 쪽)
        assertThat((Integer) JsonPath.read(body(mvc.perform(get(F).param("plantCd", "P1").param("from", "2026-03-05").param("to", "2026-03-05")
                .with(admin))), "$.data.failures")).isEqualTo(1);
    }

    @Test
    void 권한과_입력검사_API_직접호출() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        fail(v).andExpect(status().isForbidden());
        accounts.grant(comp, viewer, "KPI", "cms-kpi", "SEARCH");    // 설비 KPI 권한만으로는 안 됨
        fail(accounts.as(comp, viewer, "viewer")).andExpect(status().isForbidden());
        mvc.perform(get(F + "/mwos").param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-04").param("key", "WEAR")
                .with(accounts.as(comp, viewer, "viewer"))).andExpect(status().isForbidden());
        fail(admin, "axis", "SECRET").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("axis"));
        fail(admin, "classCd", "NOPE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("classCd"));
        mvc.perform(get(F).param("plantCd", "P1").param("from", "2026-01-01").param("to", "2026-04-30").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(F).param("from", "2026-03-02").param("to", "2026-03-04").with(admin)).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(get(F + "/mwos").param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-04").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("key"));
        // 다른 공장 = 0건(공장 코드는 회사 안에서만)
        assertThat((Integer) JsonPath.read(body(mvc.perform(get(F).param("plantCd", "P9").param("from", "2026-03-02").param("to", "2026-03-04")
                .with(admin))), "$.data.failures")).isZero();
    }

    @Test
    void A사로_B사_고장이_섞이지_않는다() throws Exception {
        String b = accounts.newComp("FB");
        long bUser = accounts.newUser(b, "badmin", PW);
        accounts.grant(b, bUser, "ADM", "cms-dashboard", "SEARCH");
        seed(b, bUser);
        // B사에 고장 하나 더 — A사 값은 그대로
        long bEq = accounts.query(b, "select equip_id from mst_equip where equip_cd = 'PRS-01'", Long.class);
        long bSym = accounts.query(b, "select fail_id from cms_fail_code where fail_cd = 'NOISE'", Long.class);
        long bCause = accounts.query(b, "select fail_id from cms_fail_code where fail_cd = 'WEAR'", Long.class);
        long bAct = accounts.query(b, "select fail_id from cms_fail_code where fail_cd = 'REPL'", Long.class);
        bm(b, bEq, bSym, bCause, bAct, "2026-03-03T15:00", "2026-03-03T15:10", "2026-03-03T15:30", null, null, null);
        assertThat((Integer) JsonPath.read(body(fail(admin)), "$.data.failures")).isEqualTo(5);
        assertThat(JsonPath.<List<Object>>read(body(drill("CAUSE", "WEAR")), "$.data.list")).hasSize(3);
        assertThat((Integer) JsonPath.read(body(fail(accounts.as(b, bUser, "badmin"))), "$.data.failures")).isEqualTo(6);
    }
}
