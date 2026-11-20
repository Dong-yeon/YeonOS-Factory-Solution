package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
import org.springframework.transaction.IllegalTransactionStateException;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 2부 C8 (docs/33 11.6) — 알람 정의(코드 정규화·클래스 우선·자동 신고는 CRITICAL 만), 원장(발생마다 한 행·열린 행 한 번만 해제·
 * 해제 시각 당김), 자동 고장 신고 멱등(D22), 반복 판정·상위 알람 손 검산, 수집 계약(MANDATORY·막지 않음), 직접 SQL, 권한, A사로 B사.
 * 수집 경로는 MES 호출 줄이 들어오기 전이라 계약 빈을 직접 부른다(MES 세션 몫).
 */
@AutoConfigureMockMvc
class CmsAlarmTest extends PostgresTestBase {

    private static final String PW = "Birch-signal-27";
    private static final String D = "/api/cms/alarm-defs";
    private static final String A = "/api/cms/alarms";
    private static final String M = "/api/cms/mwos";
    /** 2026-03-02 09:00 (Asia/Seoul) */
    private static final Instant T0 = Instant.parse("2026-03-02T00:00:00Z");
    private static final String DAY = "2026-03-02";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    AlarmSink sink;

    String comp;
    RequestPostProcessor admin;
    long press;
    long prs1;
    long prs2;
    long wld1;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AL");
        long userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        press = equipClass(comp, "PRESS");
        prs1 = equip(comp, "PRS-01", press);
        prs2 = equip(comp, "PRS-02", press);
        wld1 = equip(comp, "WLD-01", equipClass(comp, "WELD"));
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, created_by, updated_by) values (?, 'SYMPTOM', 'OVL', '과부하', 't', 't')", comp);
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-alarm", "SEARCH");
        accounts.grant(c, u, "CMS2", "cms-alarm-def", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS3", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    private long equipClass(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", classId);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private void raise(String c, long equip, String code, String text, Instant at) {
        runner.call(c, () -> {
            sink.raise(equip, code, text, at, null, "ev-" + at.toEpochMilli() + "-" + code);
            return null;
        });
    }

    private void clear(String c, long equip, String code, Instant at) {
        runner.call(c, () -> {
            sink.clear(equip, code, at, null, "evc-" + at.toEpochMilli() + "-" + code);
            return null;
        });
    }

    private int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    // ---------- 정의 ----------

    @Test
    void 정의_코드정규화_클래스별_중복_자동신고는_CRITICAL만_고정칸_낙관락() throws Exception {
        long d1 = id(postJson(D, "{\"alarmCd\":\"e-ovl\",\"classCd\":\"press\",\"alarmNm\":\"프레스 과부하\",\"severity\":\"CRITICAL\",\"symptomCd\":\"ovl\",\"autoBmYn\":\"Y\"}"));
        id(postJson(D, "{\"alarmCd\":\"E-OVL\",\"alarmNm\":\"과부하(공통)\",\"severity\":\"WARN\"}"));
        mvc.perform(get(D + "/" + d1).with(admin)).andExpect(jsonPath("$.data.alarmCd").value("E-OVL"))
                .andExpect(jsonPath("$.data.classCd").value("PRESS")).andExpect(jsonPath("$.data.symptomCd").value("OVL"));
        postJson(D, "{\"alarmCd\":\"E-OVL\",\"classCd\":\"PRESS\",\"alarmNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(D, "{\"alarmCd\":\"E-OVL\",\"alarmNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(D, "{\"alarmCd\":\"알람\",\"alarmNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(D, "{\"alarmCd\":\"W1\",\"alarmNm\":\"x\",\"severity\":\"WARN\",\"autoBmYn\":\"Y\"}")
                .andExpect(jsonPath("$.errors[0].code").value("ALARM_AUTO_CRITICAL"));
        postJson(D, "{\"alarmCd\":\"W1\",\"classCd\":\"NOPE\",\"severity\":\"HIGH\",\"symptomCd\":\"NOPE\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("NO_SUCH_CODE", "REQUIRED", "CODE", "NO_SUCH_CODE")));
        // 51자 코드
        postJson(D, "{\"alarmCd\":\"" + "A".repeat(51) + "\",\"alarmNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(put(D + "/" + d1), "{\"alarmCd\":\"E-OTHER\",\"classCd\":\"WELD\",\"alarmNm\":\"x\",\"severity\":\"CRITICAL\",\"rowVer\":0}").with(admin))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("alarmCd", "classCd")));
        mvc.perform(json(put(D + "/" + d1), "{\"alarmNm\":\"프레스 과부하 정지\",\"severity\":\"CRITICAL\",\"symptomCd\":\"OVL\",\"autoBmYn\":\"Y\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(put(D + "/" + d1), "{\"alarmNm\":\"늦음\",\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        mvc.perform(get(D).param("classCd", "PRESS").with(admin)).andExpect(jsonPath("$.data[*].alarmNm").value(contains("프레스 과부하 정지")));
    }

    // ---------- 원장·자동 신고 ----------

    @Test
    void 발생마다_한행_해제는_열린행_한번만_시각당김_정의없는코드_클래스우선_자동신고_멱등() throws Exception {
        id(postJson(D, "{\"alarmCd\":\"E-OVL\",\"classCd\":\"PRESS\",\"alarmNm\":\"프레스 과부하\",\"severity\":\"CRITICAL\",\"symptomCd\":\"OVL\",\"autoBmYn\":\"Y\"}"));
        id(postJson(D, "{\"alarmCd\":\"E-OVL\",\"alarmNm\":\"과부하(공통)\",\"severity\":\"WARN\"}"));

        raise(comp, prs1, "e-ovl", "  OVERLOAD 120%  ", T0);
        raise(comp, prs1, "E-OVL", null, T0.plus(10, ChronoUnit.MINUTES));   // 열린 신고가 있으니 새 신고 없음
        raise(comp, wld1, "E-OVL", null, T0);                               // 용접 = 공통 정의(WARN) → 신고 없음
        raise(comp, prs2, "X:99", "미정의", T0);
        assertThat(count("select count(*) from cms_alarm_log")).isEqualTo(4);
        assertThat(count("select count(*) from cms_mwo where mwo_type = 'BM' and status = 'REQ' and priority = 1 and equip_id = ?", prs1)).isEqualTo(1);
        assertThat(accounts.query(comp, "select f.fail_cd || '|' || m.symptom_text from cms_mwo m join cms_fail_code f on f.fail_id = m.symptom_id",
                String.class)).isEqualTo("OVL|E-OVL OVERLOAD 120%");
        assertThat(count("select count(*) from cms_alarm_log where mwo_id is not null")).isEqualTo(1);

        // 해제: 프레스 1의 열린 두 행 모두, 다시 해제·열린 행 없는 해제는 아무것도 안 함
        clear(comp, prs1, "E-OVL", T0.plus(20, ChronoUnit.MINUTES));
        clear(comp, prs1, "E-OVL", T0.plus(30, ChronoUnit.MINUTES));
        clear(comp, prs2, "NOPE", T0);
        assertThat(count("select count(*) from cms_alarm_log where equip_id = ? and cleared_at = ?::timestamptz", prs1,
                T0.plus(20, ChronoUnit.MINUTES).toString())).isEqualTo(2);
        // 해제가 발생보다 앞(장치 시계) → 발생 시각으로 당기고 표시
        clear(comp, wld1, "E-OVL", T0.minus(5, ChronoUnit.MINUTES));
        assertThat(accounts.query(comp, "select (cleared_at = raised_at) and clear_adjusted_yn = 'Y' from cms_alarm_log where equip_id = ?",
                Boolean.class, wld1)).isTrue();

        // 신고가 끝나지 않았으면 다음 발생도 새 신고 없음, 취소되면 다음 발생에 새 신고
        raise(comp, prs1, "E-OVL", null, T0.plus(40, ChronoUnit.MINUTES));
        assertThat(count("select count(*) from cms_mwo where equip_id = ?", prs1)).isEqualTo(1);
        long bm = accounts.query(comp, "select mwo_id from cms_mwo where equip_id = ?", Long.class, prs1);
        postJson(M + "/" + bm + "/cancel", "{\"reason\":\"오경보\",\"rowVer\":0}").andExpect(status().isOk());
        raise(comp, prs1, "E-OVL", null, T0.plus(50, ChronoUnit.MINUTES));
        assertThat(count("select count(*) from cms_mwo where equip_id = ? and status = 'REQ'", prs1)).isEqualTo(1);

        // 이력 화면: 정의 이름(클래스 우선)·미정의·신고 번호·분
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", DAY).param("keyword", "prs-02").with(admin))
                .andExpect(jsonPath("$.data[0].alarmCd").value("X:99")).andExpect(jsonPath("$.data[0].alarmNm").doesNotExist())
                .andExpect(jsonPath("$.data[0].raisedAt").value(org.hamcrest.Matchers.startsWith(DAY + "T09:00:00+09:00")));
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", DAY).param("keyword", "WLD").with(admin))
                .andExpect(jsonPath("$.data[0].alarmNm").value("과부하(공통)")).andExpect(jsonPath("$.data[0].clearAdjustedYn").value("Y"))
                .andExpect(jsonPath("$.data[0].minutes").value(0));
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", DAY).param("openOnly", "Y").with(admin))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void 반복판정과_상위알람_손검산() throws Exception {
        // 정책 기본 3회 / 24시간. PRS-01 W1: 0h·1h·2h·30h → 셋째만 반복(넷째는 창 안 1회... 2h·30h 사이 28h 라 창 안 = 1)
        String[] hrs = {"0", "1", "2", "30"};
        for (String h : hrs) {
            raise(comp, prs1, "W1", null, T0.plus(Long.parseLong(h), ChronoUnit.HOURS));
            clear(comp, prs1, "W1", T0.plus(Long.parseLong(h), ChronoUnit.HOURS).plus(15, ChronoUnit.MINUTES));
        }
        raise(comp, prs2, "W1", null, T0.plus(3, ChronoUnit.HOURS));   // 열린 채로
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", "2026-03-03").param("keyword", "PRS-01").with(admin))
                .andExpect(jsonPath("$.data[*].windowCount").value(contains(1, 3, 2, 1)))
                .andExpect(jsonPath("$.data[*].repeat").value(contains(false, true, false, false)));
        // 정책 2회로 바꾸면 둘째부터 반복
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_ALARM_REPEAT_COUNT', '2', 't', 't')", comp);
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", "2026-03-03").param("keyword", "PRS-01").with(admin))
                .andExpect(jsonPath("$.data[*].repeat").value(contains(false, true, true, false)));
        // 상위(3/2 하루): PRS-01 W1 3회·45분, PRS-02 W1 1회·(지금까지 → 하루 끝으로 잘림: 12:00 ~ 24:00 = 720분)
        mvc.perform(get(A + "/top").param("plantCd", "P1").param("from", DAY).param("to", DAY).with(admin))
                .andExpect(jsonPath("$.data[0].equipCd").value("PRS-01")).andExpect(jsonPath("$.data[0].raiseCount").value(3))
                .andExpect(jsonPath("$.data[0].activeMinutes").value(45))
                .andExpect(jsonPath("$.data[1].equipCd").value("PRS-02")).andExpect(jsonPath("$.data[1].activeMinutes").value(720))
                .andExpect(jsonPath("$.data[1].openCount").value(1));
    }

    // ---------- 계약·직접 SQL ----------

    @Test
    void 계약_트랜잭션밖_거부_틀린코드와_다른회사설비는_막지않고_무시_직접SQL() throws Exception {
        assertThatThrownBy(() -> sink.raise(prs1, "E1", null, T0, null, "x")).isInstanceOf(IllegalTransactionStateException.class);
        raise(comp, prs1, "bad code!", null, T0);
        raise(comp, prs1, " ", null, T0);
        assertThat(count("select count(*) from cms_alarm_log")).isZero();
        raise(comp, prs1, "E1", "x".repeat(250), T0);
        long a = accounts.query(comp, "select alarm_id from cms_alarm_log", Long.class);
        assertThat(count("select length(alarm_text) from cms_alarm_log")).isEqualTo(200);
        // 원장: 문구 바꾸기(열 권한 없음)·삭제·해제 두 번·해제된 채로 새 행
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update cms_alarm_log set alarm_text = 'y' where alarm_id = ?", a)))).contains("42501");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_alarm_log where alarm_id = ?", a)))).contains("42501");
        accounts.exec(comp, "update cms_alarm_log set cleared_at = raised_at, cleared_recorded_at = now() where alarm_id = ?", a);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_alarm_log set cleared_at = raised_at + interval '1 minute' where alarm_id = ?", a)))).contains("ck_cms_alarm_log_clear_once");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_alarm_log (comp_cd, equip_id, alarm_cd, raised_at, cleared_at, cleared_recorded_at, recorded_by)
                values (?, ?, 'E1', now(), now(), now(), 't')""", comp, prs1)))).contains("ck_cms_alarm_log_ledger");
        // 정의 삭제 없음(사용 중지)
        long d = id(postJson(D, "{\"alarmCd\":\"E1\",\"alarmNm\":\"x\"}"));
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_alarm_def where def_id = ?", d)))).contains("42501");
    }

    // ---------- 권한 ----------

    @Test
    void 권한_이력만이면_정의저장_403_기간검사() throws Exception {
        long viewerId = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewerId, "VIEW", "cms-alarm", "SEARCH");
        RequestPostProcessor viewer = accounts.as(comp, viewerId, "viewer");
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", DAY).with(viewer)).andExpect(status().isOk());
        mvc.perform(get(D).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(json(post(D), "{\"alarmCd\":\"E1\",\"alarmNm\":\"x\"}").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get(A).param("plantCd", "P1").param("from", "2025-01-01").param("to", DAY).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(A + "/top").param("from", DAY).param("to", DAY).with(admin)).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
    }

    // ---------- A사로 B사 ----------

    @Test
    void A사로_B사_설비_정의_현상_보이지않음_직접SQL_거부() throws Exception {
        String b = accounts.newComp("AB");
        long bUser = accounts.newUser(b, "admin1", PW);
        grantAll(b, bUser);
        accounts.addPlant(b, "P1", "B 1공장");
        long bEq = equip(b, "PRS-01", equipClass(b, "PRESS"));
        accounts.exec(b, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, created_by, updated_by) values (?, 'SYMPTOM', 'BONLY', 'b', 't', 't')", b);
        raise(b, bEq, "E1", null, T0);

        // A사 범위로 B사 설비 알람 → 기록 안 함(막지 않음), A사 목록에 B사 알람 없음
        raise(comp, bEq, "E1", null, T0);
        assertThat(count("select count(*) from cms_alarm_log")).isZero();
        assertThat(accounts.query(b, "select count(*) from cms_alarm_log", Integer.class)).isEqualTo(1);
        mvc.perform(get(A).param("plantCd", "P1").param("from", DAY).param("to", DAY).with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        postJson(D, "{\"alarmCd\":\"E1\",\"alarmNm\":\"x\",\"symptomCd\":\"BONLY\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long bDef = accounts.query(b, "insert into cms_alarm_def (comp_cd, alarm_cd, alarm_nm, created_by, updated_by) values (?, 'E1', 'b', 't', 't') returning def_id",
                Long.class, b);
        mvc.perform(get(D + "/" + bDef).with(admin)).andExpect(status().isNotFound());
        // 직접 SQL(A사 범위): B사 설비로 원장, B사 현상으로 정의
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_alarm_log (comp_cd, equip_id, alarm_cd, raised_at, recorded_by) values (?, ?, 'E1', now(), 't')", comp, bEq))))
                .contains("fk_cms_alarm_log_ctx_comp");
        long bSym = accounts.query(b, "select fail_id from cms_fail_code where fail_cd = 'BONLY'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into cms_alarm_def (comp_cd, alarm_cd, alarm_nm, symptom_id, created_by, updated_by) values (?, 'E2', 'x', ?, 't', 't')", comp, bSym))))
                .contains("fk_cms_alarm_def_symptom_comp");
    }

    // ---------- C12 중대 알람 알림 ----------

    @Test
    void 중대알람은_알람화면_볼수있는사람에게_한시간에_한번_경고는_안보냄() throws Exception {
        id(postJson(D, "{\"alarmCd\":\"E-OVL\",\"alarmNm\":\"과부하\",\"severity\":\"CRITICAL\"}"));
        id(postJson(D, "{\"alarmCd\":\"W1\",\"alarmNm\":\"경고\",\"severity\":\"WARN\"}"));
        long other = accounts.newUser(comp, "other1", PW);   // 알람 화면 권한 없음
        raise(comp, prs1, "E-OVL", null, T0);
        raise(comp, prs1, "E-OVL", null, T0.plus(20, ChronoUnit.MINUTES));   // 같은 시(UTC 00시) → 중복 키
        raise(comp, prs1, "E-OVL", null, T0.plus(70, ChronoUnit.MINUTES));   // 다음 시 → 새 알림
        raise(comp, prs2, "E-OVL", null, T0);                                // 다른 설비 → 따로
        raise(comp, prs1, "W1", null, T0);                                   // 경고 → 알림 없음
        assertThat(count("select count(*) from sys_notify where event_cd = 'CMS_ALARM_CRITICAL'")).isEqualTo(3);
        assertThat(count("select count(*) from sys_notify where user_id = ?", other)).isZero();
        assertThat(accounts.query(comp, "select link_prog_cd || '|' || (link_params->>'keyword') || '|' || (title_args->>0) from sys_notify order by notify_id limit 1",
                String.class)).isEqualTo("cms-alarm|PRS-01|PRS-01");
        // 구독을 끄면 안 받는다
        long admin1 = accounts.query(comp, "select user_id from sys_user where login_id = 'admin1'", Long.class);
        accounts.exec(comp, "insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, created_by, updated_by) values (?, ?, 'CMS_ALARM_CRITICAL', 'N', 't', 't')",
                comp, admin1);
        raise(comp, prs1, "E-OVL", null, T0.plus(200, ChronoUnit.MINUTES));
        assertThat(count("select count(*) from sys_notify where event_cd = 'CMS_ALARM_CRITICAL'")).isEqualTo(3);
    }
}
