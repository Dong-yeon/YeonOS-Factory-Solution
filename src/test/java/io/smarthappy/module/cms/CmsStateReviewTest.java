package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * CMS 2부 C7 (docs/33 11.2) — 상태 사유 관리(MES 표, 코드·분류 고정), 사후 사유(닫힌 구간·같은 분류·UDT ↔ SDT 재분류·구간당 유효 하나·
 * 무효 뒤 다시), 고장 작업지시 연결(실효 UDT·같은 설비 BM·연결 중 재분류 금지), MES 원장 불변, 직접 SQL, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsStateReviewTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-48";
    private static final String R = "/api/cms/state-reasons";
    private static final String V = "/api/cms/state-review";
    private static final String M = "/api/cms/mwos";
    private static final String DAY = "2026-03-02";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long eq1;
    long eq2;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SR");
        long userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        eq1 = equip(comp, "PRS-01");
        eq2 = equip(comp, "PRS-02");
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-state-reason", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS2", "cms-state-review", "SEARCH", "SAVE", "DELETE");
        accounts.grant(c, u, "CMS3", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    private long equip(String c, String cd) {
        Long cls = accounts.query(c, "select min(class_id) from mst_equip_class", Long.class);
        long classId = cls != null ? cls : accounts.query(c,
                "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'PRESS', 't', 't') returning class_id",
                Long.class, c);
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", classId);
    }

    /** 원장 구간(수집 대신 직접) — 시각은 "HH:mm"(2026-03-02, +09:00), end 가 null 이면 열린 구간 */
    private long interval(String c, long equip, String cat, Long reasonId, String start, String end) {
        return accounts.query(c, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, reason_id, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col')
                returning state_log_id""", Long.class, c, equip, cat, reasonId, DAY + "T" + start + ":00+09:00",
                end == null ? null : DAY + "T" + end + ":00+09:00", DAY + "T" + start + ":00+09:00");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private long reason(String cd, String cat, String planned) throws Exception {
        return id(postJson(R, "{\"reasonCd\":\"" + cd + "\",\"reasonNm\":\"" + cd + " 사유\",\"stateCat\":\"" + cat + "\",\"plannedYn\":\"" + planned + "\"}"));
    }

    private long bm(String equipCd, String reportedAt) throws Exception {
        return id(postJson(M, "{\"mwoType\":\"BM\",\"targetCd\":\"" + equipCd + "\",\"symptomText\":\"소음\",\"reportedAt\":\"" + reportedAt + "\"}"));
    }

    private ResultActions note(long iv, String reasonCd) throws Exception {
        return postJson(V + "/" + iv + "/notes", "{\"reasonCd\":\"" + reasonCd + "\",\"noteText\":\"확인함\"}");
    }

    private ResultActions voidIt(String url, String reason) throws Exception {
        return postJson(url, reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}");
    }

    // ---------- 사유 관리 ----------

    @Test
    void 사유_관리_검증_코드분류_고정_이름계획_수정_낙관락() throws Exception {
        long r1 = reason("brk-motor", "UDT", "N");
        mvc.perform(get(R + "/" + r1).with(admin)).andExpect(jsonPath("$.data.reasonCd").value("BRK-MOTOR"))
                .andExpect(jsonPath("$.data.stateCat").value("UDT"));
        postJson(R, "{\"reasonCd\":\"BRK-MOTOR\",\"reasonNm\":\"x\",\"stateCat\":\"UDT\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(R, "{\"reasonCd\":\"한글\",\"reasonNm\":\"x\",\"stateCat\":\"UDT\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 통신 끊김(UNKNOWN)은 사유가 없다
        postJson(R, "{\"reasonCd\":\"NET\",\"reasonNm\":\"x\",\"stateCat\":\"UNKNOWN\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(R, "{\"reasonCd\":\"A1\",\"stateCat\":\"SDT\",\"sortNo\":10000}")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder("REQUIRED", "RANGE")));
        // 코드·분류 고정, 이름·계획 여부는 수정
        mvc.perform(json(put(R + "/" + r1), "{\"reasonCd\":\"OTHER\",\"stateCat\":\"SDT\",\"reasonNm\":\"모터 고장\",\"rowVer\":0}").with(admin))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("reasonCd", "stateCat")));
        mvc.perform(json(put(R + "/" + r1), "{\"reasonNm\":\"모터 고장\",\"plannedYn\":\"N\",\"sortNo\":5,\"useYn\":\"Y\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(put(R + "/" + r1), "{\"reasonNm\":\"늦은 수정\",\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        mvc.perform(get(R).param("stateCat", "UDT").with(admin)).andExpect(jsonPath("$.data[*].reasonNm").value(contains("모터 고장")));
        // MES 수집 경로(코드로 사유 찾기)와 같은 표 — 원장 트리거가 분류를 맞춰 본다
        assertThat(PgErrors.constraint(catchThrowable(() -> interval(comp, eq1, "SDT", r1, "01:00", "02:00"))))
                .contains("fk_cms_equip_state_log_ctx_comp");
    }

    // ---------- 사후 사유 ----------

    @Test
    void 사후사유_닫힌구간_같은분류_재분류_유효하나_무효뒤다시_원장불변() throws Exception {
        reason("BRK", "UDT", "N");
        reason("PM", "SDT", "Y");
        reason("RUN", "PRD", "N");
        long off = reason("OLD", "UDT", "N");
        mvc.perform(json(put(R + "/" + off), "{\"reasonNm\":\"옛 사유\",\"useYn\":\"N\",\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        long udt = interval(comp, eq1, "UDT", null, "01:00", "02:30");
        long open = interval(comp, eq1, "UDT", null, "03:00", null);
        long unk = interval(comp, eq2, "UNKNOWN", null, "01:00", "01:10");
        long prd = interval(comp, eq2, "PRD", null, "02:00", "05:00");

        // 사유 없음만: 정지·대기 구간(PRD·UNKNOWN 제외)
        mvc.perform(get(V).param("plantCd", "P1").param("from", DAY).param("to", DAY).param("pending", "Y").with(admin))
                .andExpect(jsonPath("$.data[*].stateLogId").value(contains((int) open, (int) udt)));

        long n1 = id(note(udt, "brk"));
        note(udt, "BRK").andExpect(jsonPath("$.errors[0].code").value("STATE_NOTED"));
        note(open, "BRK").andExpect(jsonPath("$.errors[0].code").value("STATE_OPEN"));
        note(prd, "BRK").andExpect(jsonPath("$.errors[0].code").value("STATE_CAT"));
        note(unk, "BRK").andExpect(jsonPath("$.errors[0].code").value("STATE_CAT"));
        note(prd, "OLD").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        note(prd, "NOPE").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        id(note(prd, "RUN"));
        mvc.perform(get(V + "/" + udt).with(admin)).andExpect(jsonPath("$.data.row.noteReasonCd").value("BRK"))
                .andExpect(jsonPath("$.data.row.effCat").value("UDT")).andExpect(jsonPath("$.data.row.minutes").value(90))
                .andExpect(jsonPath("$.data.row.startAt").value(org.hamcrest.Matchers.startsWith(DAY + "T01:00:00+09:00")))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"));

        // 무효(사유 필수) 뒤 계획 정지로 재분류
        voidIt(V + "/" + udt + "/notes/" + n1 + "/void", null).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        voidIt(V + "/" + udt + "/notes/" + n1 + "/void", "정비 계획이었음").andExpect(status().isOk());
        voidIt(V + "/" + udt + "/notes/" + n1 + "/void", "두 번").andExpect(status().isConflict());
        id(note(udt, "PM"));
        mvc.perform(get(V).param("plantCd", "P1").param("from", DAY).param("to", DAY).param("effCat", "SDT").with(admin))
                .andExpect(jsonPath("$.data[*].stateLogId").value(contains((int) udt)))
                .andExpect(jsonPath("$.data[0].stateCat").value("UDT"));
        mvc.perform(get(V + "/" + udt).with(admin)).andExpect(jsonPath("$.data.notes.length()").value(2))
                .andExpect(jsonPath("$.data.notes[1].voidYn").value("Y"));

        // MES 원장은 그대로(사유 칸 비어 있음)
        assertThat(accounts.query(comp, "select count(*) from cms_equip_state_log where reason_id is not null", Integer.class)).isZero();
    }

    // ---------- 작업지시 연결 ----------

    @Test
    void 연결_실효UDT_같은설비_BM만_구간당하나_연결중_재분류금지_후보() throws Exception {
        reason("PM", "SDT", "Y");
        reason("BRK", "UDT", "N");
        long udt = interval(comp, eq1, "UDT", null, "01:00", "02:30");
        long sdt = interval(comp, eq1, "SDT", null, "04:00", "05:00");
        long open = interval(comp, eq1, "UDT", null, "06:00", null);
        long bm1 = bm("PRS-01", DAY + "T01:05");
        long bm2 = bm("PRS-02", DAY + "T01:05");
        long cm = id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"개선\"}"));
        bm("PRS-01", "2026-02-20T09:00");   // 하루 넘게 앞 — 후보 아님

        mvc.perform(get(V + "/" + udt + "/mwo-options").with(admin)).andExpect(jsonPath("$.data[*].mwoId").value(contains((int) bm1)));
        postJson(V + "/" + udt + "/links", "{}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(V + "/" + udt + "/links", "{\"mwoId\":" + cm + "}").andExpect(jsonPath("$.errors[0].code").value("MWO_NOT_BREAKDOWN"));
        postJson(V + "/" + udt + "/links", "{\"mwoId\":" + bm2 + "}").andExpect(jsonPath("$.errors[0].code").value("MWO_NOT_BREAKDOWN"));
        postJson(V + "/" + sdt + "/links", "{\"mwoId\":" + bm1 + "}").andExpect(jsonPath("$.errors[0].code").value("STATE_NOT_UDT"));
        long link = id(postJson(V + "/" + udt + "/links", "{\"mwoId\":" + bm1 + "}"));
        postJson(V + "/" + udt + "/links", "{\"mwoId\":" + bm1 + "}").andExpect(jsonPath("$.errors[0].code").value("STATE_LINKED"));
        // 열린 비계획 정지도 연결은 된다(수리 중), 작업지시 하나에 구간 여럿
        id(postJson(V + "/" + open + "/links", "{\"mwoId\":" + bm1 + "}"));
        mvc.perform(get(V + "/" + udt).with(admin)).andExpect(jsonPath("$.data.row.mwoId").value((int) bm1))
                .andExpect(jsonPath("$.data.links[0].mwoNo").exists());

        // 연결된 구간을 계획 정지로 재분류할 수 없다 → 연결 무효 뒤 가능
        note(udt, "PM").andExpect(jsonPath("$.errors[0].code").value("STATE_UNLINK_FIRST"));
        voidIt(V + "/" + udt + "/links/" + link + "/void", "다른 고장").andExpect(status().isOk());
        long n = id(note(udt, "PM"));
        // 계획 정지로 바뀐 구간은 연결 불가, SDT 구간을 UDT 로 재분류하면 연결 가능
        postJson(V + "/" + udt + "/links", "{\"mwoId\":" + bm1 + "}").andExpect(jsonPath("$.errors[0].code").value("STATE_NOT_UDT"));
        id(note(sdt, "BRK"));
        id(postJson(V + "/" + sdt + "/links", "{\"mwoId\":" + bm1 + "}"));
        // 그 재분류를 무효로 하면 실효 분류가 SDT 로 돌아가 연결이 어긋난다 → 연결 먼저
        long sdtNote = accounts.query(comp, "select note_id from cms_state_note where state_log_id = ? and void_yn = 'N'", Long.class, sdt);
        voidIt(V + "/" + sdt + "/notes/" + sdtNote + "/void", "착오").andExpect(jsonPath("$.errors[0].code").value("STATE_UNLINK_FIRST"));
        voidIt(V + "/" + udt + "/notes/" + n + "/void", "착오").andExpect(status().isOk());

        // C11 작업지시 상세: 유효 연결만(udt 연결은 무효), 열린 구간은 분 없음, 닫힌 것만 합계(SDT→UDT 재분류 구간 60분)
        mvc.perform(get(M + "/" + bm1 + "/downtime").with(admin))
                .andExpect(jsonPath("$.data.intervals[*].stateLogId").value(org.hamcrest.Matchers.contains((int) sdt, (int) open)))
                .andExpect(jsonPath("$.data.intervals[0].effCat").value("UDT"))
                .andExpect(jsonPath("$.data.intervals[0].reasonCd").value("BRK"))
                .andExpect(jsonPath("$.data.collectedMinutes").value(60))
                .andExpect(jsonPath("$.data.alarms.length()").value(0))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"));
        // 다른 설비 작업지시는 연결 없음, 없는 작업지시 404
        mvc.perform(get(M + "/" + bm2 + "/downtime").with(admin)).andExpect(jsonPath("$.data.intervals.length()").value(0))
                .andExpect(jsonPath("$.data.collectedMinutes").doesNotExist());
        mvc.perform(get(M + "/999999999/downtime").with(admin)).andExpect(status().isNotFound());
    }

    // ---------- 직접 SQL ----------

    @Test
    void 직접SQL_무효만_삭제금지_새행은유효_원장행은_그대로() throws Exception {
        long brk = reason("BRK", "UDT", "N");
        long udt = interval(comp, eq1, "UDT", null, "01:00", "02:00");
        long n = id(note(udt, "BRK"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_state_note set note_text = '바꿈' where note_id = ?", n))))
                .contains("ck_cms_state_note_ledger");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_state_note where note_id = ?", n)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_state_note (comp_cd, state_log_id, reason_id, recorded_by, void_yn, void_reason, voided_by, voided_at)
                values (?, ?, ?, 't', 'Y', 'x', 't', now())""", comp, udt, brk)))).contains("ck_cms_state_note_ledger");
        // 유효 두 개는 부분 unique 가 막는다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_state_note (comp_cd, state_log_id, reason_id, recorded_by) values (?, ?, ?, 't')", comp, udt, brk))))
                .contains("uq_cms_state_note_live");
        long bm = bm("PRS-01", DAY + "T01:05");
        long link = id(postJson(V + "/" + udt + "/links", "{\"mwoId\":" + bm + "}"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo_down set recorded_by = 'y' where link_id = ?", link))))
                .contains("ck_cms_mwo_down_ledger");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_mwo_down where link_id = ?", link)))).contains("42501");
        // 사후 사유·연결은 MES 원장을 바꾸지 않는다
        assertThat(accounts.query(comp, "select reason_id is null and end_at is not null from cms_equip_state_log where state_log_id = ?",
                Boolean.class, udt)).isTrue();
    }

    // ---------- 권한 ----------

    @Test
    void 권한_조회만이면_지정연결무효_403_화면권한없으면_403() throws Exception {
        reason("BRK", "UDT", "N");
        long udt = interval(comp, eq1, "UDT", null, "01:00", "02:00");
        long n = id(note(udt, "BRK"));
        long viewerId = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewerId, "VIEW", "cms-state-review", "SEARCH");
        RequestPostProcessor viewer = accounts.as(comp, viewerId, "viewer");
        mvc.perform(get(V).param("plantCd", "P1").param("from", DAY).param("to", DAY).with(viewer)).andExpect(status().isOk());
        mvc.perform(json(post(V + "/" + udt + "/notes"), "{\"reasonCd\":\"BRK\"}").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(json(post(V + "/" + udt + "/notes/" + n + "/void"), "{\"reason\":\"x\"}").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get(R).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(json(post(R), "{\"reasonCd\":\"X\"}").with(viewer)).andExpect(status().isForbidden());
        // 기간 검사: 31일 넘음, 공장 필수
        mvc.perform(get(V).param("plantCd", "P1").param("from", "2026-01-01").param("to", DAY).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(V).param("from", DAY).param("to", DAY).with(admin)).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
    }

    // ---------- A사로 B사 ----------

    @Test
    void A사로_B사_구간_사유_작업지시_보이지않음_직접SQL_거부() throws Exception {
        String b = accounts.newComp("SB");
        long bUser = accounts.newUser(b, "admin1", PW);
        grantAll(b, bUser);
        accounts.addPlant(b, "P1", "B 1공장");
        long bEq = equip(b, "PRS-01");
        long bReason = accounts.query(b, """
                insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, created_by, updated_by)
                values (?, 'BONLY', 'B 사유', 'UDT', 't', 't') returning reason_id""", Long.class, b);
        long bIv = interval(b, bEq, "UDT", null, "01:00", "02:00");
        RequestPostProcessor bAdmin = accounts.as(b, bUser, "admin1");
        long bBm = id(mvc.perform(json(post(M), "{\"mwoType\":\"BM\",\"targetCd\":\"PRS-01\",\"symptomText\":\"소음\",\"reportedAt\":\"" + DAY
                + "T01:05\"}").with(bAdmin)));

        reason("BRK", "UDT", "N");
        long aIv = interval(comp, eq1, "UDT", null, "01:00", "02:00");
        // A사 화면으로 B사 구간·사유·작업지시
        mvc.perform(get(V + "/" + bIv).with(admin)).andExpect(status().isNotFound());
        note(bIv, "BRK").andExpect(status().isNotFound());
        note(aIv, "BONLY").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(V + "/" + aIv + "/links", "{\"mwoId\":" + bBm + "}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(R + "/" + bReason).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(V).param("plantCd", "P1").param("from", DAY).param("to", DAY).with(admin))
                .andExpect(jsonPath("$.data[*].stateLogId").value(contains((int) aIv)));
        // 직접 SQL(A사 범위): B사 구간·B사 사유·B사 작업지시
        long aReason = accounts.query(comp, "select reason_id from cms_state_reason where reason_cd = 'BRK'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_state_note (comp_cd, state_log_id, reason_id, recorded_by) values (?, ?, ?, 't')", comp, bIv, aReason))))
                .contains("fk_cms_state_note_ctx_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_state_note (comp_cd, state_log_id, reason_id, recorded_by) values (?, ?, ?, 't')", comp, aIv, bReason))))
                .contains("fk_cms_state_note_ctx_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_mwo_down (comp_cd, mwo_id, state_log_id, recorded_by) values (?, ?, ?, 't')", comp, bBm, aIv))))
                .contains("fk_cms_mwo_down_ctx_comp");
        // 회사 코드를 B사로 쓴 행 — 트리거(A사 범위에서 B사 구간이 안 보임)가 RLS 검사보다 먼저 막는다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_state_note (comp_cd, state_log_id, reason_id, recorded_by) values (?, ?, ?, 't')", b, bIv, bReason))))
                .contains("fk_cms_state_note_ctx_comp");
        assertThat(accounts.query(b, "select count(*) from cms_state_note", Integer.class)).isZero();
    }
}
