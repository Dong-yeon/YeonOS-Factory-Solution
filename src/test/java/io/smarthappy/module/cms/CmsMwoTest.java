package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

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
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 1부 C1 (docs/33) — 고장 코드·설비 프로필·고장 신고·보전 작업지시.
 * 정상 흐름(신고 → 접수 → 착수 → 완료 → 확인) + 이상 사용: 닫을 때 필수, 완료 뒤 불변(직접 SQL), 전이 건너뛰기, 공장 시간대·DST·미래,
 * 게이지·사용 중지 대상, 클래스 한정 코드, 공수, 동시 접수, 멱등키, 기간 상한, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsMwoTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/cms/fail-codes";
    private static final String P = "/api/cms/equip-profiles";
    private static final String B = "/api/cms/breakdowns";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    long e1;
    long e2;
    long weldClass;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CM");
        userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "US1", "디트로이트");
        accounts.exec(comp, "update sys_plant set time_zone = 'America/New_York' where plant_cd = 'US1'");
        weldClass = equipClass(comp, "WELD");
        long press = equipClass(comp, "PRESS");
        e1 = equip(comp, "P1", "SPOT-01", weldClass);
        e2 = equip(comp, "US1", "PRS-01", press);
        asset(comp, "M-100", "MOLD");
        asset(comp, "G-100", "GAUGE");
        codes();
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-fail-code", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-equip-profile", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    private long equipClass(String c, String cd) {
        return accounts.query(c, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, ?, ?, 't', 't') returning class_id""", Long.class, c, cd, cd + " 클래스");
    }

    private long equip(String c, String plant, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, plant, cd, cd + " 설비", classId);
    }

    private long asset(String c, String cd, String type) {
        return accounts.query(c, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, created_by, updated_by)
                values (?, ?, ?, ?, 't', 't') returning asset_id""", Long.class, c, cd, type, cd + " 자산");
    }

    /** 현상 LEAK(모든 설비)·TIP(용접기 전용), 원인 WEAR, 조치 REPL */
    private void codes() throws Exception {
        id(postJson(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"leak\",\"failNm\":\"누유\"}"));
        id(postJson(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"TIP\",\"failNm\":\"팁 마모\",\"equipClassCd\":\"weld\"}"));
        id(postJson(F, "{\"codeType\":\"CAUSE\",\"failCd\":\"WEAR\",\"failNm\":\"마모\",\"failCat\":\"MECH\"}"));
        id(postJson(F, "{\"codeType\":\"ACTION\",\"failCd\":\"REPL\",\"failNm\":\"부품 교체\"}"));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private int rowVer(long mwoId) throws Exception {
        return JsonPath.read(mvc.perform(get(M + "/" + mwoId).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
    }

    private String stateOf(long mwoId) {
        return accounts.query(comp, "select status from cms_mwo where mwo_id = ?", String.class, mwoId);
    }

    private long report(String equipCd) throws Exception {
        return id(postJson(B, "{\"targetCd\":\"" + equipCd + "\",\"symptomCd\":\"LEAK\",\"downYn\":\"Y\"}"));
    }

    // ---------- 고장 코드 ----------

    @Test
    void 고장코드_유형별_유일_원인은_분류필수_유형코드고정_사용중지클래스_직접SQL() throws Exception {
        postJson(F, "{\"codeType\":\"SYMPTOM\",\"failCd\":\"LEAK\",\"failNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 다른 유형은 같은 코드 가능
        id(postJson(F, "{\"codeType\":\"CAUSE\",\"failCd\":\"LEAK\",\"failNm\":\"씰 누설\",\"failCat\":\"FLUID\"}"));
        postJson(F, "{\"codeType\":\"CAUSE\",\"failCd\":\"X1\",\"failNm\":\"x\"}").andExpect(jsonPath("$.errors[0].field").value("failCat"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(F, "{\"codeType\":\"WHAT\",\"failCd\":\"한글\",\"failNm\":\"\",\"failCat\":\"NOPE\",\"sortNo\":-1}")
                .andExpect(jsonPath("$.errors.length()").value(5));
        postJson(F, "{\"codeType\":\"ACTION\",\"failCd\":\"A1\",\"failNm\":\"x\",\"equipClassCd\":\"NOPE\"}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        accounts.exec(comp, "update mst_equip_class set use_yn = 'N' where class_cd = 'PRESS'");
        postJson(F, "{\"codeType\":\"ACTION\",\"failCd\":\"A1\",\"failNm\":\"x\",\"equipClassCd\":\"PRESS\"}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        long tip = accounts.query(comp, "select fail_id from cms_fail_code where fail_cd = 'TIP'", Long.class);
        putJson(F + "/" + tip, "{\"codeType\":\"CAUSE\",\"failCd\":\"TIP2\",\"failNm\":\"팁\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        putJson(F + "/" + tip, "{\"failNm\":\"팁 마모\",\"equipClassCd\":\"WELD\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(F + "/" + tip, "{\"failNm\":\"팁 마모\",\"equipClassCd\":\"WELD\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isConflict());
        mvc.perform(get(F).param("codeType", "SYMPTOM").param("useYn", "Y").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(F).param("keyword", "lea").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        // 직접 SQL: 코드 바꾸기·삭제·원인 분류 비우기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_fail_code set fail_cd = 'Z' where fail_id = ?", tip))))
                .contains("ck_cms_fail_code_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_fail_code where fail_id = ?", tip))))
                .contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_fail_code set fail_cat = null where code_type = 'CAUSE' and fail_cd = 'WEAR'")))).contains("ck_cms_fail_code_cause_cat");
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 신고_접수_착수_완료_확인_번호는_결번없이_닫을때만_필수_기본담당() throws Exception {
        long owner = accounts.newUser(comp, "tech1", PW);
        putJson(P + "/" + e1, "{\"criticality\":\"A\",\"keyEquipYn\":\"Y\",\"ownerLoginId\":\"TECH1\"}").andExpect(status().isOk());
        long m = report("spot-01");
        long m2 = report("SPOT-01");
        String no1 = accounts.query(comp, "select mwo_no from cms_mwo where mwo_id = ?", String.class, m);
        String no2 = accounts.query(comp, "select mwo_no from cms_mwo where mwo_id = ?", String.class, m2);
        assertThat(no1).matches("^MWO-\\d{4}-0001$");
        assertThat(no2).isEqualTo(no1.substring(0, 9) + "0002");
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.status").value("REQ"))
                .andExpect(jsonPath("$.data.title").value("누유"))                       // 제목을 비우면 현상 이름
                .andExpect(jsonPath("$.data.downStartAt").exists())                     // "멈춤" 만 누르면 지금부터
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.requestedLoginId").value("admin1"));
        mvc.perform(get(P).with(admin)).andExpect(jsonPath("$.data[?(@.equipCd == 'SPOT-01')].openMwoCnt").value(2));
        // 접수: 담당을 안 주면 프로필 기본 담당
        postJson(M + "/" + m + "/accept", "{\"rowVer\":0}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select assignee_id from cms_mwo where mwo_id = ?", Long.class, m)).isEqualTo(owner);
        // 착수 전 완료 → 상태 오류, 착수
        postJson(M + "/" + m + "/finish", "{\"rowVer\":1}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        postJson(M + "/" + m + "/start", "{\"rowVer\":1}").andExpect(status().isOk());
        // 공수
        id(postJson(M + "/" + m + "/labor", "{\"loginId\":\"tech1\",\"workDate\":\"" + LocalDate.now(ZoneId.of("Asia/Seoul")) + "\",\"minutes\":45}"));
        // 완료: 원인·조치·작업 내용·정지 끝 빠짐 → 칸 오류 4개, 상태 그대로
        postJson(M + "/" + m + "/finish", "{\"symptomCd\":\"LEAK\",\"downYn\":\"Y\",\"downStartAt\":\"2026-01-01T08:00\",\"rowVer\":2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("causeCd", "actionCd", "workText", "downEndAt")));
        assertThat(stateOf(m)).isEqualTo("WIP");
        String started = JsonPath.read(mvc.perform(get(M + "/" + m).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.startedAt");
        postJson(M + "/" + m + "/finish", "{\"symptomCd\":\"LEAK\",\"causeCd\":\"wear\",\"actionCd\":\"REPL\",\"workText\":\"씰 교체\","
                + "\"downYn\":\"Y\",\"downStartAt\":\"2026-01-01T08:00\",\"downEndAt\":\"2026-01-01T09:30\",\"startedAt\":\""
                + started.substring(0, 16) + "\",\"rowVer\":2}").andExpect(status().isOk());
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.laborMin").value(45)).andExpect(jsonPath("$.data.causeCd").value("WEAR"));
        // 완료 뒤: 수정·공수·취소 막힘, 확인은 신고자(신고 화면) → CLOSED
        putJson(M + "/" + m, "{\"title\":\"x\",\"symptomCd\":\"LEAK\",\"rowVer\":3}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        postJson(M + "/" + m + "/labor", "{\"workDate\":\"2026-01-01\",\"minutes\":5}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        postJson(M + "/" + m + "/cancel", "{\"reason\":\"x\",\"rowVer\":3}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        postJson(B + "/" + m + "/confirm", "{\"rowVer\":3}").andExpect(status().isOk());
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedLoginId").value("admin1"));
        // 두 번째 신고는 취소(사유 필수)
        postJson(M + "/" + m2 + "/cancel", "{\"reason\":\" \",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(M + "/" + m2 + "/cancel", "{\"reason\":\"중복 신고\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(P + "/" + e1).with(admin)).andExpect(jsonPath("$.data.openMwoCnt").value(0))
                .andExpect(jsonPath("$.data.criticality").value("A"));
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        mvc.perform(get(P + "/" + e1 + "/mwos").param("from", "2026-01-01").param("to", today).with(admin))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void 개선_작업지시는_바로_접수_PM은_직접못만듬_취소는_신고접수만_재작업은_후속() throws Exception {
        long cm = id(postJson(M, "{\"mwoType\":\"CM\",\"targetCd\":\"SPOT-01\",\"title\":\"안전 커버 추가\",\"dueDate\":\"2026-01-01\"}"));
        mvc.perform(get(M + "/" + cm).with(admin)).andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.overdue").value(true));
        postJson(M, "{\"mwoType\":\"PM\",\"targetCd\":\"SPOT-01\",\"title\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(M, "{\"mwoType\":\"CM\",\"targetCd\":\"SPOT-01\"}").andExpect(jsonPath("$.errors[0].field").value("title"));
        postJson(M + "/" + cm + "/start", "{\"rowVer\":0}").andExpect(status().isOk());
        // 담당 없이 착수 → 착수한 사람
        assertThat(accounts.query(comp, "select assignee_id from cms_mwo where mwo_id = ?", Long.class, cm)).isEqualTo(userId);
        postJson(M + "/" + cm + "/cancel", "{\"reason\":\"x\",\"rowVer\":1}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        // CM 완료는 작업 내용만 필수(코드 없음)
        postJson(M + "/" + cm + "/finish", "{\"title\":\"안전 커버 추가\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].field").value("workText"));
        postJson(M + "/" + cm + "/finish", "{\"title\":\"안전 커버 추가\",\"workText\":\"커버 설치\",\"rowVer\":1}").andExpect(status().isOk());
        // 재작업 = 후속 작업지시(앞 작업지시 표시)
        long next = id(postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"SPOT-01\",\"symptomText\":\"커버 흔들림\",\"parentMwoId\":" + cm + "}"));
        mvc.perform(get(M + "/" + next).with(admin)).andExpect(jsonPath("$.data.parentMwoNo").exists())
                .andExpect(jsonPath("$.data.title").value("커버 흔들림"));
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"SPOT-01\",\"symptomText\":\"x\",\"parentMwoId\":999999999}")
                .andExpect(jsonPath("$.errors[0].field").value("parentMwoId"));
    }

    // ---------- 이상 사용: 직접 SQL ----------

    @Test
    void 직접SQL_완료뒤_수정_전이건너뛰기_닫을때필수_삭제_공수수정_모두_거부() throws Exception {
        long m = report("SPOT-01");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_mwo set status = 'DONE', started_at = now(), finished_at = now() where mwo_id = ?", m))))
                .contains("ck_cms_mwo_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set equip_id = ? where mwo_id = ?", e2, m))))
                .contains("ck_cms_mwo_fixed");
        accounts.exec(comp, "update cms_mwo set status = 'OPEN' where mwo_id = ?", m);
        accounts.exec(comp, "update cms_mwo set status = 'WIP', started_at = reported_at where mwo_id = ?", m);
        // 닫을 때 필수: 원인 없이 DONE → CHECK
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_mwo set status = 'DONE', finished_at = started_at, work_text = 'x', down_end_at = down_start_at where mwo_id = ?", m))))
                .contains("ck_cms_mwo_done");
        long cause = accounts.query(comp, "select fail_id from cms_fail_code where fail_cd = 'WEAR'", Long.class);
        long action = accounts.query(comp, "select fail_id from cms_fail_code where fail_cd = 'REPL'", Long.class);
        // 원인 칸에 조치 코드 → 유형 불일치
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set cause_id = ? where mwo_id = ?", action, m))))
                .contains("fk_cms_mwo_cause_comp");
        // 작업 내용 NULL 로 완료(코드 3종은 다 있음) → CHECK. 전에는 length(btrim(NULL)) = NULL 이라 통과했다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                update cms_mwo set status = 'DONE', finished_at = started_at + interval '1 hour', work_text = null, cause_id = ?, action_id = ?,
                       down_end_at = down_start_at + interval '1 hour'
                 where mwo_id = ?""", cause, action, m)))).contains("ck_cms_mwo_done");
        // 현상 코드·현상 글 둘 다 NULL 인 고장 신고 → CHECK (전에는 통과)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_mwo set symptom_id = null, symptom_text = null where mwo_id = ?", m)))).contains("ck_cms_mwo_symptom_any");
        accounts.exec(comp, """
                update cms_mwo set status = 'DONE', finished_at = started_at + interval '1 hour', work_text = 'x', cause_id = ?, action_id = ?,
                       down_end_at = down_start_at + interval '1 hour'
                 where mwo_id = ?""", cause, action, m);
        // 완료 상태 그대로 고치기 = 전이 표 밖, 확인(CLOSED)하면서 슬쩍 고치기 = 완료 뒤 불변
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set work_text = '나중에 고침' where mwo_id = ?", m))))
                .contains("ck_cms_mwo_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_mwo set status = 'CLOSED', closed_at = now(), work_text = '나중에 고침' where mwo_id = ?", m))))
                .contains("ck_cms_mwo_done_fixed");
        accounts.exec(comp, "update cms_mwo set status = 'CLOSED', closed_at = now() where mwo_id = ?", m);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo set status = 'WIP' where mwo_id = ?", m))))
                .contains("ck_cms_mwo_transition");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_mwo where mwo_id = ?", m)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_mwo_labor (comp_cd, mwo_id, user_id, work_date, minutes, created_by, updated_by)
                values (?, ?, ?, (now() at time zone 'Asia/Seoul')::date, 10, 't', 't')""", comp, m, userId)))).contains("ck_cms_mwo_labor_status");
        // 신고(REQ)는 고장만, PM 은 계획 없이 못 만든다, 새 행은 REQ·OPEN 으로만
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, created_by, updated_by)
                values (?, 'X-1', 'CM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), 't', 't')""", comp, e1)))).contains("ck_cms_mwo_req");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, created_by, updated_by)
                values (?, 'X-2', 'CM', 'P1', 'EQUIP', ?, 'x', 'DONE', now(), 't', 't')""", comp, e1)))).contains("ck_cms_mwo_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, created_by, updated_by)
                values (?, 'X-3', 'CM', 'US1', 'EQUIP', ?, 'x', 'OPEN', now(), 't', 't')""", comp, e1)))).contains("ck_cms_mwo_plant");
    }

    // ---------- 이상 사용: 시각·대상·코드 ----------

    @Test
    void 시각은_대상공장_시간대_DST없는시각_미래_순서_오류() throws Exception {
        // 디트로이트 설비: 현지 2026-03-07 23:00(EST) = UTC 03-08 04:00
        long m = id(postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"소음\",\"reportedAt\":\"2026-03-07T23:00\"}"));
        assertThat(accounts.query(comp, "select reported_at from cms_mwo where mwo_id = ?", OffsetDateTime.class, m).toInstant())
                .isEqualTo(Instant.parse("2026-03-08T04:00:00Z"));
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.data.reportedAt").value(matchesPattern("^2026-03-07T23:00.*")));
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"x\",\"reportedAt\":\"2026-03-08T02:30\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DST_GAP"));
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"x\",\"reportedAt\":\"2999-01-01T00:00\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"x\",\"reportedAt\":\"2026-13-01T00:00\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"x\",\"downYn\":\"Y\",\"downStartAt\":\"2026-03-07T10:00\","
                + "\"downEndAt\":\"2026-03-07T09:00\"}").andExpect(jsonPath("$.errors[0].code").value("BEFORE_START"));
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"x\",\"downYn\":\"Y\"}")
                .andExpect(jsonPath("$.errors[0].field").value("downStartAt"));          // 작업지시 화면은 정지 시작 필수(신고 화면만 지금)
        // 착수 시각이 신고보다 앞
        postJson(M + "/" + m + "/start", "{\"startedAt\":\"2026-03-07T22:00\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_REPORTED"));
        postJson(M + "/" + m + "/start", "{\"startedAt\":\"2026-03-08T01:00\",\"rowVer\":0}").andExpect(status().isOk());
        postJson(M + "/" + m + "/finish", "{\"symptomText\":\"소음\",\"finishedAt\":\"2026-03-08T00:30\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[?(@.field == 'finishedAt')].code").value("BEFORE_START"));
        // 글자 길이·제어문자·우선순위
        postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"앞\\u0007뒤\",\"priority\":9,\"title\":\""
                + "가".repeat(201) + "\"}").andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void 대상은_사용중_설비와_금형지그만_게이지_없음_클래스한정코드_사용중지코드() throws Exception {
        id(postJson(B, "{\"targetType\":\"ASSET\",\"targetCd\":\"m-100\",\"symptomText\":\"버 발생\"}"));
        postJson(B, "{\"targetType\":\"ASSET\",\"targetCd\":\"G-100\",\"symptomText\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(B, "{\"targetCd\":\"NOPE\",\"symptomText\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(B, "{\"targetType\":\"ROBOT\",\"targetCd\":\"SPOT-01\",\"symptomText\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(B, "{\"targetCd\":\"SPOT-01\"}").andExpect(jsonPath("$.errors[0].field").value("symptomCd"));   // 현상 하나는 있어야
        // 용접기 전용 현상은 프레스·금형에 못 쓴다
        id(postJson(B, "{\"targetCd\":\"SPOT-01\",\"symptomCd\":\"TIP\"}"));
        postJson(B, "{\"targetCd\":\"PRS-01\",\"symptomCd\":\"TIP\"}").andExpect(jsonPath("$.errors[0].code").value("CLASS_MISMATCH"));
        postJson(B, "{\"targetType\":\"ASSET\",\"targetCd\":\"M-100\",\"symptomCd\":\"TIP\"}").andExpect(jsonPath("$.errors[0].code").value("CLASS_MISMATCH"));
        // 사용 중지 설비·코드는 새로 못 쓴다, 이미 쓴 작업지시는 그대로 고칠 수 있다
        long m = report("SPOT-01");
        accounts.exec(comp, "update cms_fail_code set use_yn = 'N' where fail_cd = 'LEAK'");
        accounts.exec(comp, "update mst_equip set use_yn = 'N' where equip_cd = 'SPOT-01'");
        postJson(B, "{\"targetCd\":\"SPOT-01\",\"symptomText\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(B, "{\"targetCd\":\"PRS-01\",\"symptomCd\":\"LEAK\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        putJson(M + "/" + m, "{\"symptomCd\":\"LEAK\",\"title\":\"누유 심함\",\"downYn\":\"Y\",\"downStartAt\":\"2026-01-01T08:00\",\"rowVer\":0}")
                .andExpect(status().isOk());
        putJson(M + "/" + m, "{\"targetCd\":\"PRS-01\",\"mwoType\":\"CM\",\"symptomCd\":\"LEAK\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        // 대상 찾기에는 사용 중 설비와 금형·지그만(게이지 없음)
        mvc.perform(get(B + "/targets").with(admin)).andExpect(jsonPath("$.data[*].code").value(containsInAnyOrder("PRS-01", "M-100")));
    }

    // ---------- 이상 사용: 공수·동시성·멱등·조회 ----------

    @Test
    void 공수는_진행중에만_미래날짜_범위_사용중지사용자_거부_삭제후_다시() throws Exception {
        long m = id(postJson(M, "{\"mwoType\":\"CM\",\"targetCd\":\"SPOT-01\",\"title\":\"정리\"}"));
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        postJson(M + "/" + m + "/labor", "{\"workDate\":\"2999-01-01\",\"minutes\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FUTURE", "RANGE")));
        postJson(M + "/" + m + "/labor", "{\"minutes\":10}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long gone = accounts.newUser(comp, "gone1", PW);
        accounts.setUserUse(comp, gone, "N");
        postJson(M + "/" + m + "/labor", "{\"loginId\":\"gone1\",\"workDate\":\"" + today + "\",\"minutes\":10}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        long l = id(postJson(M + "/" + m + "/labor", "{\"workDate\":\"" + today + "\",\"minutes\":30,\"remark\":\"정리\"}"));
        mvc.perform(get(M + "/" + m + "/labor").with(admin)).andExpect(jsonPath("$.data[0].loginId").value("admin1"));
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update cms_mwo_labor set minutes = 600 where labor_id = ?", l))))
                .contains("42501");
        mvc.perform(json(post(M + "/" + m + "/labor/" + l + "/delete"), "{}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(M + "/" + m + "/labor/" + l + "/delete"), "{}").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.laborMin").value(0));
    }

    @Test
    void 같은_rowVer_두번_접수는_두번째_409_멱등키_중복은_409_기간없음_상한초과_400() throws Exception {
        long m = report("SPOT-01");
        postJson(M + "/" + m + "/accept", "{\"rowVer\":0}").andExpect(status().isOk());
        postJson(M + "/" + m + "/accept", "{\"rowVer\":0}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        postJson(M + "/" + m + "/accept", "{\"rowVer\":1}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        postJson(M + "/" + m + "/accept", "{}").andExpect(status().isBadRequest());
        String body = "{\"targetCd\":\"SPOT-01\",\"symptomCd\":\"LEAK\"}";
        mvc.perform(json(post(B), body).header("X-Idempotency-Key", "cms-bd-0001").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(B), body).header("X-Idempotency-Key", "cms-bd-0001").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from cms_mwo", Integer.class)).isEqualTo(2);
        mvc.perform(get(M).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(M).param("from", "2024-01-01").param("to", "2026-01-01").with(admin))
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        mvc.perform(get(M).param("from", "2026-01-01").param("to", "2026-01-31").param("status", "REQ,BOGUS").with(admin))
                .andExpect(status().isBadRequest());
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        mvc.perform(get(M).param("from", today).param("to", today).param("status", "REQ,OPEN").with(admin))
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(M).param("from", today).param("to", today).param("scope", "MINE").with(admin))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(M).param("from", today).param("to", today).param("keyword", "spot").with(admin))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ---------- 설비 프로필 ----------

    @Test
    void 프로필은_처음저장때_생성_동시생성_409_담당은_사용중_같은회사만() throws Exception {
        mvc.perform(get(P + "/" + e2).with(admin)).andExpect(jsonPath("$.data.criticality").value("C"))
                .andExpect(jsonPath("$.data.rowVer").doesNotExist());
        putJson(P + "/" + e2, "{\"criticality\":\"B\"}").andExpect(status().isOk());
        putJson(P + "/" + e2, "{\"criticality\":\"A\"}").andExpect(status().isConflict());          // 이미 있는데 "새로"
        putJson(P + "/" + e2, "{\"criticality\":\"Z\",\"keyEquipYn\":\"X\",\"rowVer\":0}").andExpect(jsonPath("$.errors.length()").value(2));
        long gone = accounts.newUser(comp, "gone2", PW);
        accounts.setUserUse(comp, gone, "N");
        putJson(P + "/" + e2, "{\"ownerLoginId\":\"gone2\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        String other = accounts.newComp("CN");
        long ou = accounts.newUser(other, "outsider", PW);
        putJson(P + "/" + e2, "{\"ownerLoginId\":\"outsider\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_equip_profile set owner_user_id = ? where equip_id = ?", ou, e2)))).contains("fk_cms_equip_profile_owner_comp");
        mvc.perform(get(P).param("criticality", "B").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(P).param("keyword", "prs").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(P + "/999999999").with(admin)).andExpect(status().isNotFound());
    }

    // ---------- A사로 B사 · 권한 ----------

    @Test
    void B사로_A사_작업지시_코드_프로필_공수_모두_막힘_직접SQL_다른회사_참조_거부() throws Exception {
        long m = report("SPOT-01");
        String other = accounts.newComp("CB");
        long bu = accounts.newUser(other, "admin1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        accounts.addPlant(other, "P1", "B 1공장");
        long bClass = equipClass(other, "WELD");
        long be = equip(other, "P1", "B-01", bClass);
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        mvc.perform(get(M + "/" + m).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(M).param("from", today).param("to", today).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(F).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put(M + "/" + m), "{\"symptomText\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + m + "/accept"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + m + "/cancel"), "{\"reason\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + m + "/labor"), "{\"workDate\":\"" + today + "\",\"minutes\":5}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(B + "/" + m).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(B + "/" + m + "/confirm"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + e1).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(P + "/" + e1), "{\"criticality\":\"A\"}").with(b)).andExpect(status().isNotFound());
        // A사 설비·코드로 B사 신고 → 없는 코드
        mvc.perform(json(post(B), "{\"targetCd\":\"SPOT-01\",\"symptomText\":\"x\"}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(post(B), "{\"targetCd\":\"B-01\",\"symptomCd\":\"LEAK\"}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL: B사 행이 A사 설비·코드·사용자·작업지시를 가리키기
        long aLeak = accounts.query(comp, "select fail_id from cms_fail_code where fail_cd = 'LEAK'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_text,
                                     created_by, updated_by)
                values (?, 'X-1', 'BM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), 'x', 't', 't')""", other, e1)))).contains("fk_cms_mwo_equip_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_id,
                                     created_by, updated_by)
                values (?, 'X-2', 'BM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), ?, 't', 't')""", other, be, aLeak)))).contains("fk_cms_mwo_symptom_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_text,
                                     assignee_id, created_by, updated_by)
                values (?, 'X-3', 'BM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), 'x', ?, 't', 't')""", other, be, userId)))).contains("fk_cms_mwo_assignee_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_text,
                                     parent_mwo_id, created_by, updated_by)
                values (?, 'X-4', 'BM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), 'x', ?, 't', 't')""", other, be, m)))).contains("fk_cms_mwo_parent_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_equip_profile (equip_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')""", e1, other))))
                .contains("fk_cms_equip_profile_equip_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, equip_class_id, created_by, updated_by)
                values (?, 'SYMPTOM', 'X', 'x', ?, 't', 't')""", other, weldClass)))).contains("fk_cms_fail_code_class_comp");
        // B사가 A사 회사 코드로 행 넣기 → RLS
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(other, """
                insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, created_by, updated_by)
                values (?, 'SYMPTOM', 'X', 'x', 't', 't')""", comp)))).contains("42501");
        // 같은 번호 체계라도 회사마다 따로
        long bm = ((Number) JsonPath.read(mvc.perform(json(post(B), "{\"targetCd\":\"B-01\",\"symptomText\":\"x\"}").with(b))
                .andReturn().getResponse().getContentAsString(), "$.data.mwoId")).longValue();
        assertThat(accounts.query(other, "select mwo_no from cms_mwo where mwo_id = ?", String.class, bm)).endsWith("-0001");
    }

    @Test
    void 권한_신고만_가진_작업자는_작업지시_못보고_남의신고_확인못함_확인은_APPROVE_취소는_DELETE() throws Exception {
        long m = report("SPOT-01");
        long op = accounts.newUser(comp, "op1", PW);
        accounts.grant(comp, op, "OPERATOR", "cms-breakdown", "SEARCH", "SAVE");
        RequestPostProcessor o = accounts.as(comp, op, "op1");
        mvc.perform(get(M + "/" + m).with(o)).andExpect(status().isForbidden());
        mvc.perform(get(F).with(o)).andExpect(status().isForbidden());
        mvc.perform(get(B + "/symptoms").with(o)).andExpect(jsonPath("$.data.length()").value(2));
        long mine = ((Number) JsonPath.read(mvc.perform(json(post(B), "{\"targetCd\":\"SPOT-01\",\"symptomCd\":\"LEAK\"}").with(o))
                .andReturn().getResponse().getContentAsString(), "$.data.mwoId")).longValue();
        mvc.perform(get(B + "/" + mine).with(o)).andExpect(jsonPath("$.data.requestedLoginId").value("op1"));
        mvc.perform(get(B + "/" + m).with(o)).andExpect(status().isNotFound());                   // 남의 신고
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        mvc.perform(get(B).param("from", today).param("to", today).with(o)).andExpect(jsonPath("$.data.length()").value(1));
        // 남의 완료 건 확인 → 404, 내 것은 DONE 이 아니면 상태 오류
        mvc.perform(json(post(B + "/" + m + "/confirm"), "{\"rowVer\":0}").with(o)).andExpect(status().isNotFound());
        mvc.perform(json(post(B + "/" + mine + "/confirm"), "{\"rowVer\":0}").with(o)).andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        // 보전 기사: 저장만 → 확인(APPROVE)·취소(DELETE) 403
        long tech = accounts.newUser(comp, "tech9", PW);
        accounts.grant(comp, tech, "TECH", "cms-mwo", "SEARCH", "SAVE");
        RequestPostProcessor t = accounts.as(comp, tech, "tech9");
        mvc.perform(json(post(M + "/" + m + "/cancel"), "{\"reason\":\"x\",\"rowVer\":0}").with(t)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/" + m + "/close"), "{\"rowVer\":0}").with(t)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/" + m + "/accept"), "{\"rowVer\":0}").with(t)).andExpect(status().isOk());
    }
}
