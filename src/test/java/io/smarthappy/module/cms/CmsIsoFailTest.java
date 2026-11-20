package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
 * ISO 14224 고장 기록 칸 (docs/33 16.6 G3) — 메커니즘·탐지 방법·영향: 선택 입력·고장 수리만(BM_ONLY), 시스템 코드 + 회사 코드(메커니즘·탐지만),
 * 바꾸지 않은 사용 중지 코드는 유지·새로 넣기는 거부, 직접 SQL(트리거·CHECK·완료 뒤 불변), 알람 자동 신고 = CBM,
 * 고장 분석 축(MECH·DETECT·IMPACT, 값 없음 '-' 드릴다운)·예방 활동으로 찾은 수, A사로 B사(회사 코드).
 */
@AutoConfigureMockMvc
class CmsIsoFailTest extends PostgresTestBase {

    private static final String PW = "Oak-harbor-38";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    MwoService mwos;

    String comp;
    RequestPostProcessor admin;
    long eq;
    long cls;
    long sym;
    long cause;
    long act;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("IS");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(comp, userId, "CMS", "cms-dashboard", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        eq = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't') returning equip_id""", Long.class, comp, cls);
        sym = code("SYMPTOM", "NOISE", null);
        cause = code("CAUSE", "WEAR", "MECH");
        act = code("ACTION", "REPL", null);
    }

    private long code(String type, String cd, String cat) {
        return accounts.query(comp, """
                insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, fail_cat, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning fail_id""", Long.class, comp, type, cd, cd, cat);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    private String bm(String mech, String detect, String impact) {
        return "{\"mwoType\":\"BM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"소음\",\"symptomCd\":\"NOISE\""
                + (mech == null ? "" : ",\"failMechCd\":\"" + mech + "\"") + (detect == null ? "" : ",\"failDetectCd\":\"" + detect + "\"")
                + (impact == null ? "" : ",\"failImpactCd\":\"" + impact + "\"") + "}";
    }

    private String upd(long m, String mech, String detect, String impact) {
        return bm(mech, detect, impact).replace("}", ",\"rowVer\":" + ver(m) + "}");
    }

    @Test
    void 선택입력_저장과_조회_대문자정리_빈값은_지움_엑셀열() throws Exception {
        long m = id(postJson(M, bm("mat_wear", "INSPECTION", "STOP")));
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.failMechCd").value("MAT_WEAR"))
                .andExpect(jsonPath("$.data.failDetectCd").value("INSPECTION")).andExpect(jsonPath("$.data.failImpactCd").value("STOP"));
        // 비우면 지운다(PUT 의미 — 다른 칸과 같다)
        mvc.perform(json(put(M + "/" + m), upd(m, null, "INSPECTION", null)).with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select coalesce(fail_mech_cd, '-') || '/' || coalesce(fail_impact_cd, '-') from cms_mwo where mwo_id = ?",
                String.class, m)).isEqualTo("-/-");
        // 넣지 않아도 저장된다(선택 입력)
        id(postJson(M, bm(null, null, null)));
        assertThat(MwoService.COLUMNS).extracting(c -> c.field()).contains("failMechCd", "failDetectCd", "failImpactCd");
    }

    @Test
    void 없는코드는_CODE_고장수리가_아니면_BM_ONLY() throws Exception {
        postJson(M, bm("NOPE", "ALARM_X", "BROKEN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("failMechCd", "failDetectCd", "failImpactCd")))
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("CODE"))));
        // 다른 그룹 코드(영향 칸에 메커니즘 코드)도 CODE
        postJson(M, bm(null, null, "MAT_WEAR")).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"개선\",\"failMechCd\":\"MAT_WEAR\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("failMechCd"))
                .andExpect(jsonPath("$.errors[0].code").value("BM_ONLY"));
    }

    @Test
    void 회사코드는_메커니즘_탐지만_사용중지코드는_바꾸지않으면_유지_A사로_B사() throws Exception {
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'CMS_FAIL_MECH', 'GLUE_FAIL', '접착 불량', 't', 't')", comp);
        // 영향은 회사가 늘릴 수 없다(그룹 comp_ext_yn = N — 트리거)
        assertThat(catchThrowable(() -> accounts.exec(comp,
                "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'CMS_FAIL_IMPACT', 'MINOR', '경미', 't', 't')", comp)))
                .isNotNull();
        long m = id(postJson(M, bm("GLUE_FAIL", null, null)));
        // 회사 코드를 끈 뒤: 그대로 두는 저장은 통과, 새로 넣기는 거부
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'CMS_FAIL_MECH' and code = 'GLUE_FAIL'");
        mvc.perform(json(put(M + "/" + m), upd(m, "GLUE_FAIL", "CASUAL", null)).with(admin)).andExpect(status().isOk());
        postJson(M, bm("GLUE_FAIL", null, null)).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        // B사는 A사 회사 코드를 못 쓴다(API·직접 SQL)
        String b = accounts.newComp("IT");
        long bUser = accounts.newUser(b, "badmin", PW);
        accounts.grant(b, bUser, "CMS", "cms-mwo", "SEARCH", "SAVE");
        accounts.addPlant(b, "P1", "B");
        long bCls = accounts.query(b, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, b);
        accounts.exec(b, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', 'p', ?, 't', 't')", b, bCls);
        accounts.exec(b, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, created_by, updated_by) values (?, 'SYMPTOM', 'NOISE', 'n', 't', 't')", b);
        accounts.exec(comp, "update sys_comp_code set use_yn = 'Y' where grp_cd = 'CMS_FAIL_MECH' and code = 'GLUE_FAIL'");
        mvc.perform(json(post(M), bm("GLUE_FAIL", null, null)).with(accounts.as(b, bUser, "badmin"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("failMechCd"));
        assertThat(accounts.query(b, "select count(*) from cms_mwo", Integer.class)).isZero();   // 아무것도 저장되지 않았다
    }

    @Test
    void 직접SQL_트리거와_CHECK_완료뒤_불변() throws Exception {
        long m = id(postJson(M, bm("MAT_WEAR", null, null)));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set fail_mech_cd = 'NOPE' where mwo_id = ?", m))))
                .contains("fk_cms_mwo_fail_mech_code");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set fail_detect_cd = 'STOP' where mwo_id = ?", m))))
                .contains("fk_cms_mwo_fail_detect_code");
        long cm = id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"개선\"}"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set fail_impact_cd = 'STOP' where mwo_id = ?", cm))))
                .contains("ck_cms_mwo_iso_bm");
        // 완료 뒤에는 ISO 칸도 바뀌지 않는다(트리거 — 전이·행 전체 비교)
        accounts.exec(comp, "update cms_mwo set status = 'WIP', started_at = now() where mwo_id = ?", m);
        accounts.exec(comp, "update cms_mwo set status = 'DONE', finished_at = now(), cause_id = ?, action_id = ?, work_text = '교체' where mwo_id = ?", cause, act, m);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set fail_mech_cd = 'MAT_CORR' where mwo_id = ?", m))))
                .hasValueSatisfying(c -> assertThat(c).isIn("ck_cms_mwo_transition", "ck_cms_mwo_done_fixed"));   // 완료(DONE→DONE) 수정은 전이 검사가 먼저 막는다
    }

    @Test
    void 알람_자동신고는_탐지방법_CBM() {
        long id = runner.call(comp, () -> mwos.createFromAlarm(eq, "P1", cls, "과열 알람", sym, "모터 온도 높음", OffsetDateTime.now().minusMinutes(5), "dev:edge"));
        assertThat(accounts.query(comp, "select fail_detect_cd from cms_mwo where mwo_id = ?", String.class, id)).isEqualTo("CBM");
    }

    @Test
    void 고장분석_ISO축과_값없음_드릴다운_예방활동으로_찾은수() throws Exception {
        done("MAT_WEAR", "INSPECTION", "STOP", "2026-03-02T09:00");
        done("MAT_WEAR", "CASUAL", "DEGRADED", "2026-03-02T11:00");
        done(null, "CBM", null, "2026-03-02T13:00");
        done(null, null, null, "2026-03-02T15:00");
        String f = "/api/cms/dashboard/failures";
        String b = mvc.perform(get(f).param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-02").param("axis", "MECH").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(b, "$.data.bars[*].keyCd")).containsExactly("MAT_WEAR", null);
        assertThat((String) JsonPath.read(b, "$.data.bars[0].keyNm")).isEqualTo("마모");
        // 탐지 기록 3건 중 예방 활동(점검·상태 감시) 2건 — 우연 발견은 아님, 빈 칸은 분모에서 뺀다
        assertThat((Integer) JsonPath.read(b, "$.data.detectRecorded")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(b, "$.data.detectPreventive")).isEqualTo(2);
        // 값 없음 막대('-') 드릴다운 = 2건
        mvc.perform(get(f + "/mwos").param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-02").param("axis", "MECH").param("key", "-")
                .with(admin)).andExpect(jsonPath("$.data.list.length()").value(2));
        mvc.perform(get(f).param("plantCd", "P1").param("from", "2026-03-02").param("to", "2026-03-02").param("axis", "IMPACT").with(admin))
                .andExpect(jsonPath("$.data.bars.length()").value(3));
    }

    private void done(String mech, String detect, String impact, String at) {
        long id = accounts.query(comp, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_id, down_yn,
                                     fail_mech_cd, fail_detect_cd, fail_impact_cd, created_by, updated_by)
                values (?, 'T-' || nextval(pg_get_serial_sequence('cms_mwo', 'mwo_id')), 'BM', 'P1', 'EQUIP', ?, '고장', 'OPEN', ?::timestamptz, ?, 'N',
                        ?, ?, ?, 't', 't') returning mwo_id""", Long.class, comp, eq, at + ":00+09:00", sym, mech, detect, impact);
        accounts.exec(comp, "update cms_mwo set status = 'WIP', started_at = reported_at + interval '5 minutes' where mwo_id = ?", id);
        accounts.exec(comp, "update cms_mwo set status = 'DONE', finished_at = reported_at + interval '30 minutes', cause_id = ?, action_id = ?, work_text = 'w' where mwo_id = ?",
                cause, act, id);
    }
}
