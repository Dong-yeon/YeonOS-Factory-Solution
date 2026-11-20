package io.smarthappy.module.master.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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
 * 근무조·작업자·자격 (P2 1단계, docs/25 4.2) — 근무조 겹침(자정 넘김·사용 중지·직접 SQL), 공장 시간대·DST 입력,
 * 배지(해시만·응답·감사 가림·회사 안 유일), 사무 계정 연결, 자격 보유·갱신·취소, 게이트(정책 상한·공장별), A/B사, 권한.
 */
@AutoConfigureMockMvc
class WorkforceTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/master/shifts";
    private static final String Q = "/api/master/quals";
    private static final String W = "/api/master/workers";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WF");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-shift", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-qual", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-worker", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "US1", "디트로이트");
        accounts.exec(comp, "update sys_plant set time_zone = 'America/New_York' where plant_cd = 'US1'");
        proc(comp, "SPOT");
        proc(comp, "PRESS");
    }

    private static void noop() {
    }

    private void proc(String c, String cd) {
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, ?, ?, 'WELD', 't', 't')""", c, cd, cd + " 공정");
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
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long shift(String plant, String cd, String start, String end, int offset) throws Exception {
        return id(postJson(S, "{\"plantCd\":\"" + plant + "\",\"shiftCd\":\"" + cd + "\",\"shiftNm\":\"" + cd + "조\",\"startTime\":\""
                + start + "\",\"endTime\":\"" + end + "\",\"dayOffset\":" + offset + "}"));
    }

    // ---------- 근무조 ----------

    @Test
    void 근무조_겹침은_자정넘김까지_칸오류_사용중지는_빼고_다시사용때_다시검사() throws Exception {
        shift("P1", "d", "08:00", "20:00", 0);
        long n = shift("P1", "N", "20:00", "08:00", 0);
        // 새벽 06~08 이 야간과 겹친다
        postJson(S, "{\"plantCd\":\"P1\",\"shiftCd\":\"E\",\"shiftNm\":\"조출\",\"startTime\":\"06:00\",\"endTime\":\"10:00\"}")
                .andExpect(jsonPath("$.errors[0].code").value("SHIFT_OVERLAP")).andExpect(jsonPath("$.errors[0].field").value("startTime"));
        // 형식·같은 시각·휴게 과다·오프셋
        postJson(S, "{\"plantCd\":\"P1\",\"shiftCd\":\"1조!\",\"shiftNm\":\"\",\"startTime\":\"24:00\",\"endTime\":\"8:00\",\"dayOffset\":1}")
                .andExpect(jsonPath("$.errors.length()").value(5));
        postJson(S, "{\"plantCd\":\"US1\",\"shiftCd\":\"X\",\"shiftNm\":\"x\",\"startTime\":\"09:00\",\"endTime\":\"09:00\"}")
                .andExpect(jsonPath("$.errors[0].code").value("SAME_AS_START"));
        postJson(S, "{\"plantCd\":\"US1\",\"shiftCd\":\"X\",\"shiftNm\":\"x\",\"startTime\":\"09:00\",\"endTime\":\"10:00\",\"breakMin\":60}")
                .andExpect(jsonPath("$.errors[0].code").value("BREAK_TOO_LONG"));
        // 다른 공장은 같은 시간이어도 된다
        shift("US1", "D", "06:00", "14:00", 0);
        postJson(S, "{\"plantCd\":\"US1\",\"shiftCd\":\"d\",\"shiftNm\":\"x\",\"startTime\":\"20:00\",\"endTime\":\"22:00\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 야간 사용 중지 → 조출 들어감 → 야간 다시 사용은 겹침
        putJson(S + "/" + n, "{\"shiftNm\":\"야간\",\"startTime\":\"20:00\",\"endTime\":\"08:00\",\"useYn\":\"N\",\"rowVer\":0}")
                .andExpect(status().isOk());
        shift("P1", "E", "06:00", "08:00", 0);
        putJson(S + "/" + n, "{\"shiftNm\":\"야간\",\"startTime\":\"20:00\",\"endTime\":\"08:00\",\"useYn\":\"Y\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("SHIFT_OVERLAP"));
        // 직접 SQL(앱 계정)도 트리거가 막는다, 공장·코드는 바꾸지 않는다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'Z', 'z', '19:00', '21:00', 't', 't')""", comp)))).contains("ck_mst_shift_overlap");
        putJson(S + "/" + n, "{\"plantCd\":\"US1\",\"shiftCd\":\"N2\",\"shiftNm\":\"야간\",\"startTime\":\"20:00\",\"endTime\":\"06:00\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.contains("FIXED", "FIXED")));
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_shift where shift_id = ?", n))))
                .contains("42501");
    }

    @Test
    void 작업일_확인은_공장_시간대_DST_없는_시각은_칸오류() throws Exception {
        shift("P1", "D", "08:00", "20:00", 0);
        shift("P1", "N", "20:00", "08:00", 0);
        shift("US1", "D", "08:00", "20:00", 0);
        mvc.perform(get(S + "/work-day").param("plantCd", "P1").param("at", "2026-10-21T03:00").with(admin))
                .andExpect(jsonPath("$.data.workDay").value("2026-10-20")).andExpect(jsonPath("$.data.shiftCd").value("N"))
                .andExpect(jsonPath("$.data.zone").value("Asia/Seoul"));
        mvc.perform(get(S + "/work-day").param("plantCd", "us1").param("at", "2026-10-21T03:00").with(admin))
                .andExpect(jsonPath("$.data.zone").value("America/New_York"))
                .andExpect(jsonPath("$.data.shiftCd").doesNotExist())
                .andExpect(jsonPath("$.data.workDay").value("2026-10-21"));
        mvc.perform(get(S + "/work-day").param("plantCd", "US1").param("at", "2026-03-08T02:30").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DST_GAP"));
        mvc.perform(get(S + "/work-day").param("plantCd", "US1").param("at", "2026-13-01T00:00").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(get(S + "/work-day").param("plantCd", "NOPE").with(admin)).andExpect(status().isNotFound());
        // 잘못된 시간대 값이 들어 있어도 회사 시간대로
        accounts.exec(comp, "update sys_plant set time_zone = 'Mars/Base' where plant_cd = 'P1'");
        mvc.perform(get(S + "/work-day").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.data.zone").value("Asia/Seoul"));
    }

    // ---------- 작업자·배지 ----------

    @Test
    void 배지는_해시만_응답과_감사에_없고_회사안_유일_다른회사는_같은배지_가능() throws Exception {
        long a = id(postJson(W, "{\"workerCd\":\"e1001\",\"workerNm\":\"김용접\",\"plantCd\":\"p1\"}"));
        long b = id(postJson(W, "{\"workerCd\":\"E1002\",\"workerNm\":\"이프레스\"}"));
        postJson(W, "{\"workerCd\":\"E1001\",\"workerNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        putJson(W + "/" + a + "/badge", "{\"badge\":\" BADGE-0001 \",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(W + "/" + a).with(admin)).andExpect(jsonPath("$.data.badgeSet").value(true))
                .andExpect(jsonPath("$.data.workerCd").value("E1001"))
                .andExpect(jsonPath("$.data", not(hasKey("badgeHash"))));
        assertThat(accounts.query(comp, "select badge_hash from mst_worker where worker_id = ?", String.class, a))
                .isEqualTo(WorkerService.badgeHash(comp, "BADGE-0001")).doesNotContain("BADGE");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'mst_worker' and after_data::text like '%' || ? || '%'",
                Integer.class, WorkerService.badgeHash(comp, "BADGE-0001"))).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'mst_worker' and after_data ->> 'badge_hash' = '(changed)'",
                Integer.class)).isPositive();
        // 같은 배지 다른 사람 → DUPLICATE, 형식(짧음·한글·공백 포함)
        putJson(W + "/" + b + "/badge", "{\"badge\":\"BADGE-0001\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        for (String bad : new String[] {"ab", "배지1234", "AB CD12"}) {
            putJson(W + "/" + b + "/badge", "{\"badge\":\"" + bad + "\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        }
        // 지우기 → 다른 사람이 쓸 수 있다
        putJson(W + "/" + a + "/badge", "{\"badge\":\"\",\"rowVer\":1}").andExpect(status().isOk());
        putJson(W + "/" + b + "/badge", "{\"badge\":\"BADGE-0001\",\"rowVer\":0}").andExpect(status().isOk());
        // B사는 같은 배지 값을 써도 된다(해시에 회사가 들어간다)
        String other = accounts.newComp("WG");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "MASTER", "mst-worker", "SEARCH", "SAVE");
        RequestPostProcessor bAdmin = accounts.as(other, bu, "admin1");
        MvcResult r = mvc.perform(json(post(W), "{\"workerCd\":\"E1001\",\"workerNm\":\"B사\"}").with(bAdmin)).andReturn();
        long bw = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(json(put(W + "/" + bw + "/badge"), "{\"badge\":\"BADGE-0001\",\"rowVer\":0}").with(bAdmin)).andExpect(status().isOk());
        // B사로 A사 작업자: 404, 배지 지정 404, 목록 0
        mvc.perform(get(W + "/" + a).with(bAdmin)).andExpect(status().isNotFound());
        mvc.perform(json(put(W + "/" + a + "/badge"), "{\"badge\":\"STEAL-001\",\"rowVer\":1}").with(bAdmin)).andExpect(status().isNotFound());
        mvc.perform(get(W).with(bAdmin)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 사무계정_연결은_회사안_한명_B사_계정은_없는코드_공장은_사용중만() throws Exception {
        long u2 = accounts.newUser(comp, "kim.lead", PW);
        long a = id(postJson(W, "{\"workerCd\":\"E1\",\"workerNm\":\"반장\",\"loginId\":\"KIM.LEAD\"}"));
        mvc.perform(get(W + "/" + a).with(admin)).andExpect(jsonPath("$.data.userId").value(u2))
                .andExpect(jsonPath("$.data.loginId").value("kim.lead"));
        postJson(W, "{\"workerCd\":\"E2\",\"workerNm\":\"x\",\"loginId\":\"kim.lead\"}").andExpect(jsonPath("$.errors[0].field").value("loginId"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        String other = accounts.newComp("WH");
        long ou = accounts.newUser(other, "outsider", PW);
        postJson(W, "{\"workerCd\":\"E3\",\"workerNm\":\"x\",\"loginId\":\"outsider\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL 로 다른 회사 계정 연결 → 트리거
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update mst_worker set user_id = ? where worker_id = ?", ou, a))))
                .contains("fk_mst_worker_user_comp");
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where plant_cd = 'US1'");
        postJson(W, "{\"workerCd\":\"E4\",\"workerNm\":\"x\",\"plantCd\":\"US1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(W, "{\"workerCd\":\"E4\",\"workerNm\":\"x\",\"plantCd\":\"ZZ\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(W, "{\"workerCd\":\"E4\",\"workerNm\":\"앞\\u0000뒤\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 사번은 바꾸지 않는다(직접 SQL 도)
        putJson(W + "/" + a, "{\"workerCd\":\"E9\",\"workerNm\":\"반장\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update mst_worker set worker_cd = 'E9' where worker_id = ?", a))))
                .contains("ck_mst_worker_cd_fixed");
        noop();
    }

    // ---------- 자격·게이트 ----------

    @Test
    void 자격_보유는_유효개월로_만료_갱신은_새행_게이트는_정책상한과_공장별() throws Exception {
        long weld = id(postJson(Q, "{\"qualCd\":\"weld-a\",\"qualNm\":\"스폿 용접 A\",\"validMonths\":24}"));
        id(postJson(Q, "{\"qualCd\":\"SAFE\",\"qualNm\":\"안전교육\"}"));
        long rq = id(postJson(Q + "/reqs", "{\"procCd\":\"spot\",\"qualCd\":\"WELD-A\",\"gateMode\":\"BLOCK\"}"));
        id(postJson(Q + "/reqs", "{\"procCd\":\"SPOT\",\"qualCd\":\"SAFE\"}"));
        postJson(Q + "/reqs", "{\"procCd\":\"SPOT\",\"qualCd\":\"SAFE\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long w = id(postJson(W, "{\"workerCd\":\"E1\",\"workerNm\":\"김용접\",\"plantCd\":\"P1\"}"));
        // 아무 자격 없음: 기본 정책 WARN 이 상한 → BLOCK 요구도 WARN
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2026-10-20").with(admin))
                .andExpect(jsonPath("$.data.mode").value("WARN")).andExpect(jsonPath("$.data.missing.length()").value(2))
                .andExpect(jsonPath("$.data.missing[0].reason").value("NONE"));
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'QUAL_GATE_MODE', 'BLOCK', 't', 't')", comp);
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2026-10-20").with(admin))
                .andExpect(jsonPath("$.data.mode").value("BLOCK"));
        // 다른 공장(US1)은 회사 기본 WARN
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("plantCd", "US1").param("workDay", "2026-10-20").with(admin))
                .andExpect(jsonPath("$.data.mode").value("WARN"));
        // 2024-10-21 취득 + 24개월 → 2026-10-20 까지
        long h1 = id(postJson(W + "/" + w + "/quals", "{\"qualCd\":\"WELD-A\",\"acquiredOn\":\"2024-10-21\"}"));
        id(postJson(W + "/" + w + "/quals", "{\"qualCd\":\"SAFE\",\"acquiredOn\":\"2024-01-01\"}"));
        assertThat(accounts.query(comp, "select expires_on from mst_worker_qual where wq_id = ?", LocalDate.class, h1))
                .isEqualTo(LocalDate.parse("2026-10-20"));
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2026-10-20").with(admin))
                .andExpect(jsonPath("$.data.mode").value("OK"));
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2026-10-21").with(admin))
                .andExpect(jsonPath("$.data.mode").value("BLOCK")).andExpect(jsonPath("$.data.missing[0].reason").value("EXPIRED"));
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2024-10-20").with(admin))
                .andExpect(jsonPath("$.data.missing[0].reason").value("NOT_YET"));
        // 갱신 = 새 행(옛 행 그대로), 취소하면 REVOKED
        long h2 = id(postJson(W + "/" + w + "/quals", "{\"qualCd\":\"WELD-A\",\"acquiredOn\":\"2026-10-01\"}"));
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2026-10-21").with(admin))
                .andExpect(jsonPath("$.data.mode").value("OK"));
        putJson(W + "/" + w + "/quals/" + h2, "{\"revokedOn\":\"2026-10-15\",\"expiresOn\":\"2028-09-30\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2026-10-21").with(admin))
                .andExpect(jsonPath("$.data.missing[0].reason").value("REVOKED"));
        mvc.perform(get(W + "/" + w + "/quals").with(admin)).andExpect(jsonPath("$.data.length()").value(3));
        // 자격·취득일은 고정, 미래 취득·만료 < 취득 거부
        putJson(W + "/" + w + "/quals/" + h1, "{\"qualCd\":\"SAFE\",\"acquiredOn\":\"2024-10-22\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors.length()").value(2));
        postJson(W + "/" + w + "/quals", "{\"qualCd\":\"SAFE\",\"acquiredOn\":\"2999-01-01\"}").andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson(W + "/" + w + "/quals", "{\"qualCd\":\"SAFE\",\"acquiredOn\":\"2026-01-02\",\"expiresOn\":\"2026-01-01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_ACQUIRED"));
        // 요구 끄기 → 게이트 OK, 정책 OFF → 전부 OK
        putJson(Q + "/reqs/" + rq, "{\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        accounts.exec(comp, "update sys_policy set policy_value = 'OFF' where plant_cd = 'P1' and policy_key = 'QUAL_GATE_MODE'");
        mvc.perform(get(W + "/" + w + "/gate").param("procCd", "SPOT").param("workDay", "2030-01-01").with(admin))
                .andExpect(jsonPath("$.data.mode").value("OK"));
        // 사용 중지 자격은 새로 줄 수 없고, 그 자격 요구 다시 사용도 막힘
        putJson(Q + "/" + weld, "{\"qualNm\":\"스폿 용접 A\",\"validMonths\":24,\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        postJson(W + "/" + w + "/quals", "{\"qualCd\":\"WELD-A\",\"acquiredOn\":\"2026-10-01\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        putJson(Q + "/reqs/" + rq, "{\"useYn\":\"Y\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 보유 행 삭제는 앱 계정 권한 없음
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_worker_qual where wq_id = ?", h1))))
                .contains("42501");
    }

    @Test
    void B사로_A사_자격_요구_보유_근무조_모두_막힘_직접SQL_다른회사_참조_거부_권한() throws Exception {
        long q = id(postJson(Q, "{\"qualCd\":\"WELD\",\"qualNm\":\"용접\"}"));
        long sh = shift("P1", "D", "08:00", "20:00", 0);
        long w = id(postJson(W, "{\"workerCd\":\"E1\",\"workerNm\":\"김\"}"));
        long wq = id(postJson(W + "/" + w + "/quals", "{\"qualCd\":\"WELD\",\"acquiredOn\":\"2026-01-01\"}"));
        String other = accounts.newComp("WB");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "MASTER", "mst-shift", "SEARCH", "SAVE");
        accounts.grant(other, bu, "MASTER", "mst-qual", "SEARCH", "SAVE");
        accounts.grant(other, bu, "MASTER", "mst-worker", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        accounts.addPlant(other, "P1", "B 1공장");
        proc(other, "SPOT");
        long bw = ((Number) JsonPath.read(mvc.perform(json(post(W), "{\"workerCd\":\"E1\",\"workerNm\":\"B\"}").with(b)).andReturn()
                .getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(get(Q).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S).param("plantCd", "P1").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put(Q + "/" + q), "{\"qualNm\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(S + "/" + sh), "{\"shiftNm\":\"x\",\"startTime\":\"08:00\",\"endTime\":\"20:00\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(get(W + "/" + w + "/quals").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(W + "/" + w + "/quals/" + wq), "{\"revokedOn\":\"2026-02-01\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // 자기 작업자 경로에 A사 보유 id → 404
        mvc.perform(json(put(W + "/" + bw + "/quals/" + wq), "{\"revokedOn\":\"2026-02-01\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // A사 자격 코드로 B사 보유 추가 → 없는 코드
        mvc.perform(json(post(W + "/" + bw + "/quals"), "{\"qualCd\":\"WELD\",\"acquiredOn\":\"2026-01-01\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL: B사 행이 A사 작업자·자격을 가리키기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, created_by, updated_by)
                values (?, ?, ?, '2026-01-01', 't', 't')""", other, bw, q)))).contains("fk_mst_worker_qual_qual_comp");
        long bProc = accounts.query(other, "select proc_id from mst_proc where proc_cd = 'SPOT'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_proc_qual_req (comp_cd, proc_id, qual_id, created_by, updated_by)
                values (?, ?, ?, 't', 't')""", other, bProc, q)))).contains("fk_mst_proc_qual_req_qual_comp");
        // 권한: 작업자 조회만 → 저장·배지 403, 자격 선택지·공정 선택지 200, 근무조 화면 API 403
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-worker", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(json(put(W + "/" + w + "/badge"), "{\"badge\":\"BADGE-9\",\"rowVer\":0}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post(W + "/" + w + "/quals"), "{\"qualCd\":\"WELD\",\"acquiredOn\":\"2026-01-01\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(get(W + "/quals").with(r)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(W + "/procs").with(r)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(S).param("plantCd", "P1").with(r)).andExpect(status().isForbidden());
    }
}
