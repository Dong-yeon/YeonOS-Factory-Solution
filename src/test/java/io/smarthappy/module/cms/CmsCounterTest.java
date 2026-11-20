package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * CMS 1부 C3 (docs/33 4.4) — 카운터 읽기(기준·증가량·리셋·직접 입력·순서·낡은 화면), 무효(마지막부터·사슬 다시 계산),
 * 사용량 주기 계획(도달 때 같은 트랜잭션 예정·완료 누적·다음 예정·같은 날 여러 번), 계획 검증, 정기 작업, 동시 입력, 직접 SQL, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsCounterTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/cms/counters";
    private static final String P = "/api/cms/pm-plans";
    private static final String R = "/api/cms/chk-runs";
    private static final String M = "/api/cms/mwos";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    CmsJobs jobs;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CT");
        long userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long press = equipClass(comp, "PRESS");
        equip(comp, "P1", "PRS-01", press);
        asset(comp, "M-100", "MOLD", 100000L);
        asset(comp, "G-100", "GAUGE", null);
        today = LocalDate.now(SEOUL).toString();
        // 금형 점검표(필수 1) · 프레스 일상 점검표(필수 1)
        long mold = id(postJson("/api/cms/chk-tmpls", "{\"tmplCd\":\"MOLD-PM\",\"tmplNm\":\"금형 정기\",\"targetKind\":\"ASSET\"}"));
        putJson("/api/cms/chk-tmpls/" + mold + "/items?rowVer=0", "[{\"seq\":1,\"itemNm\":\"파팅면 손상\",\"judgeType\":\"OKNG\"}]")
                .andExpect(status().isOk());
        long prs = id(postJson("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 일상\"}"));
        putJson("/api/cms/chk-tmpls/" + prs + "/items?rowVer=0", "[{\"seq\":1,\"itemNm\":\"클러치 소음\",\"judgeType\":\"OKNG\"}]")
                .andExpect(status().isOk());
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-counter", "SEARCH", "SAVE", "DELETE");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-run", "SEARCH", "SAVE", "DELETE");
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    private long equipClass(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String plant, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, plant, cd, cd + " 설비", classId);
    }

    private long asset(String c, String cd, String type, Long lifeShots) {
        return accounts.query(c, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, life_shots, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning asset_id""", Long.class, c, cd, type, cd, lifeShots);
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

    private <V> V read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private long counter(String cd, String type, String target, String kind, int scale) throws Exception {
        return id(postJson(C, "{\"counterCd\":\"" + cd + "\",\"counterNm\":\"" + cd + "\",\"targetType\":\"" + type + "\",\"targetCd\":\""
                + target + "\",\"counterKind\":\"" + kind + "\",\"scale\":" + scale + "}"));
    }

    private ResultActions reading(long counterId, String body) throws Exception {
        return postJson(C + "/" + counterId + "/readings", body);
    }

    private ResultActions gauge(long counterId, String v) throws Exception {
        return reading(counterId, "{\"gaugeValue\":\"" + v + "\"}");
    }

    private long lastReading(long counterId) {
        return accounts.query(comp, "select max(reading_id) from cms_counter_reading where counter_id = ?", Long.class, counterId);
    }

    private BigDecimal total(long counterId) {
        return accounts.query(comp, "select coalesce(sum(delta_qty), 0) from cms_counter_reading where counter_id = ? and void_yn = 'N'",
                BigDecimal.class, counterId);
    }

    // ---------- 읽기 ----------

    @Test
    void 읽기_첫값은_기준_증가량_직접입력_리셋_검증_시각순서_낡은화면은_409() throws Exception {
        // 등록 검증: 코드 형식·이름 없음·게이지는 대상 아님·종류·자릿수
        postJson(C, "{\"counterCd\":\"bad cd\",\"counterNm\":\" \",\"targetType\":\"ASSET\",\"targetCd\":\"G-100\",\"counterKind\":\"PULSE\",\"scale\":7}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FORMAT", "REQUIRED", "NO_SUCH_CODE", "CODE", "RANGE")));
        long c = counter("m100-shot", "ASSET", "M-100", "SHOTS", 0);
        postJson(C, "{\"counterCd\":\"M100-SHOT\",\"counterNm\":\"x\",\"targetType\":\"ASSET\",\"targetCd\":\"M-100\",\"counterKind\":\"SHOTS\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        reading(c, "{\"gaugeValue\":\"12000\",\"lastReadingId\":0}").andExpect(jsonPath("$.data.deltaText").value("0"))
                .andExpect(jsonPath("$.data.totalText").value("0"));                                  // 첫 계기 값 = 기준
        long first = lastReading(c);
        gauge(c, "12500").andExpect(jsonPath("$.data.deltaText").value("500"));
        reading(c, "{\"deltaQty\":\"300\",\"remark\":\"시스템 전 누적\"}").andExpect(jsonPath("$.data.totalText").value("800"));
        mvc.perform(get(C + "/" + c).with(admin)).andExpect(jsonPath("$.data.totalText").value("800"))
                .andExpect(jsonPath("$.data.lastGaugeText").value("12500")).andExpect(jsonPath("$.data.readingCnt").value(3))
                .andExpect(jsonPath("$.data.life").value("OK")).andExpect(jsonPath("$.data.lifeShots").value(100000));
        // 계기 값이 줄면 리셋 확인 없이는 거부, 리셋이면 0 부터
        gauge(c, "12000").andExpect(jsonPath("$.errors[0].field").value("gaugeValue"))
                .andExpect(jsonPath("$.errors[0].code").value("GAUGE_DECREASE"));
        reading(c, "{\"gaugeValue\":\"100\",\"resetYn\":\"Y\"}").andExpect(jsonPath("$.data.deltaText").value("100"))
                .andExpect(jsonPath("$.data.totalText").value("900"));
        // 입력 검증: 둘 다·리셋인데 증가량·0·자릿수·빈 값
        reading(c, "{\"gaugeValue\":\"1\",\"deltaQty\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("GAUGE_OR_DELTA"));
        reading(c, "{\"deltaQty\":\"5\",\"resetYn\":\"Y\"}").andExpect(jsonPath("$.errors[0].field").value("resetYn"));
        reading(c, "{\"deltaQty\":\"0\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        gauge(c, "100.5").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        gauge(c, "-1").andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE"));
        reading(c, "{}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 시각: 마지막 읽기보다 앞 / 미래 / 화면을 연 뒤 다른 사람이 넣음
        reading(c, "{\"gaugeValue\":\"200\",\"readAt\":\"2020-01-01T00:00\"}").andExpect(jsonPath("$.errors[0].code").value("BEFORE_LAST"));
        String future = LocalDateTime.now(SEOUL).plusHours(2).truncatedTo(ChronoUnit.MINUTES).toString();
        reading(c, "{\"gaugeValue\":\"200\",\"readAt\":\"" + future + "\"}").andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        reading(c, "{\"gaugeValue\":\"200\",\"lastReadingId\":" + first + "}").andExpect(status().isConflict());
        // 지금 분으로 보낸 시각(초가 잘림)은 같은 분의 마지막 읽기보다 앞이어도 받는다
        String nowMinute = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.MINUTES).toString();
        reading(c, "{\"gaugeValue\":\"150\",\"readAt\":\"" + nowMinute + "\"}").andExpect(jsonPath("$.data.deltaText").value("50"));
        mvc.perform(get(C + "/" + c + "/readings").with(admin)).andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].cumulativeText").value("950")).andExpect(jsonPath("$.data[1].resetYn").value("Y"))
                .andExpect(jsonPath("$.data[2].gaugeText").doesNotExist()).andExpect(jsonPath("$.data[4].deltaText").value("0"));
        // 고정 칸·수정
        putJson(C + "/" + c, "{\"counterNm\":\"금형 타수\",\"counterKind\":\"CYCLES\",\"scale\":1,\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        putJson(C + "/" + c, "{\"counterNm\":\"금형 타수\",\"unitNm\":\"타\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(C + "/" + c, "{\"counterNm\":\"x\",\"rowVer\":0}").andExpect(status().isConflict());
        // 사용 중지 카운터에는 못 넣는다
        putJson(C + "/" + c, "{\"counterNm\":\"금형 타수\",\"useYn\":\"N\",\"rowVer\":1}").andExpect(status().isOk());
        gauge(c, "160").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    @Test
    void 무효는_마지막부터_사유필수_누적과_계기사슬을_다시_계산_권한은_DELETE() throws Exception {
        long c = counter("PRS-HR", "EQUIP", "PRS-01", "RUN_HOURS", 1);
        gauge(c, "100.0").andExpect(status().isOk());
        gauge(c, "110.5").andExpect(jsonPath("$.data.deltaText").value("10.5"));
        long r2 = lastReading(c);
        gauge(c, "120.0").andExpect(jsonPath("$.data.totalText").value("20.0"));
        long r3 = lastReading(c);
        postJson(C + "/" + c + "/readings/" + r2 + "/void", "{\"reason\":\"오입력\"}").andExpect(jsonPath("$.errors[0].code").value("VOID_ORDER"));
        postJson(C + "/" + c + "/readings/" + r3 + "/void", "{\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-counter", "SEARCH", "SAVE");
        mvc.perform(json(post(C + "/" + c + "/readings/" + r3 + "/void"), "{\"reason\":\"x\"}").with(accounts.as(comp, viewer, "viewer1")))
                .andExpect(status().isForbidden());
        postJson(C + "/" + c + "/readings/" + r3 + "/void", "{\"reason\":\"오입력\"}").andExpect(status().isOk());
        mvc.perform(get(C + "/" + c).with(admin)).andExpect(jsonPath("$.data.totalText").value("10.5"))
                .andExpect(jsonPath("$.data.lastGaugeText").value("110.5")).andExpect(jsonPath("$.data.lastReadingId").value(r2));
        // 다음 계기 값은 무효 앞 계기 값(110.5)에서
        gauge(c, "115.0").andExpect(jsonPath("$.data.deltaText").value("4.5")).andExpect(jsonPath("$.data.totalText").value("15.0"));
        postJson(C + "/" + c + "/readings/" + r3 + "/void", "{\"reason\":\"또\"}").andExpect(status().isConflict());   // 이미 무효
        mvc.perform(get(C + "/" + c + "/readings").with(admin)).andExpect(jsonPath("$.data[1].voidYn").value("Y"))
                .andExpect(jsonPath("$.data[1].voidReason").value("오입력")).andExpect(jsonPath("$.data[1].cumulativeText").doesNotExist())
                .andExpect(jsonPath("$.data[0].cumulativeText").value("15.0"));
        // 무효로 지운 시각보다 앞인 새 읽기는 여전히 마지막 유효 읽기 기준
        assertThat(total(c)).isEqualByComparingTo("15.0");
    }

    // ---------- 사용량 주기 ----------

    private long usagePlan(String cd, String target, String type, String tmpl, String mode, String counterCd, String interval, String lead,
            String anchor, String basis) throws Exception {
        return id(postJson(P, "{\"planCd\":\"" + cd + "\",\"planNm\":\"" + cd + " 점검\",\"targetType\":\"" + type + "\",\"targetCd\":\"" + target
                + "\",\"tmplCd\":\"" + tmpl + "\",\"execMode\":\"" + mode + "\",\"cycleType\":\"USAGE\",\"counterCd\":\"" + counterCd
                + "\",\"usageInterval\":\"" + interval + "\"" + (lead == null ? "" : ",\"usageLead\":\"" + lead + "\"")
                + (anchor == null ? "" : ",\"anchorUsage\":\"" + anchor + "\"") + ",\"scheduleBasis\":\"" + basis + "\"}"));
    }

    @Test
    void 사용량계획_도달하면_같은트랜잭션에서_예정_완료누적_다음예정_같은날_여러번_늦은완료() throws Exception {
        long c = counter("M100-SHOT", "ASSET", "M-100", "SHOTS", 0);
        gauge(c, "1000").andExpect(status().isOk());                                          // 기준
        long pl = usagePlan("PM-M100", "M-100", "ASSET", "MOLD-PM", "WO", "M100-SHOT", "50000", "2000", null, "FIXED");
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.cycleType").value("USAGE"))
                .andExpect(jsonPath("$.data.nextDueUsageText").value("50000"))                 // 비우면 지금 누적(0) + 간격
                .andExpect(jsonPath("$.data.state").value("UPCOMING")).andExpect(jsonPath("$.data.calInterval").doesNotExist())
                .andExpect(jsonPath("$.data.nextDueDate").doesNotExist());
        gauge(c, "48999").andExpect(jsonPath("$.data.runs").value(0));                          // 47999 < 50000 − 2000
        gauge(c, "49000").andExpect(jsonPath("$.data.runs").value(1)).andExpect(jsonPath("$.data.workOrders").value(1));   // 48000 = 경계
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.state").value("DUE"))
                .andExpect(jsonPath("$.data.openRunId").exists());
        gauge(c, "51000").andExpect(jsonPath("$.data.runs").value(0));                          // 열린 예정이 있으면 더 안 만든다
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(0));
        long run = accounts.query(comp, "select run_id from cms_chk_run where plan_id = ? and status = 'OPEN'", Long.class, pl);
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ?", Long.class, pl);
        assertThat(accounts.query(comp, "select due_ref_usage from cms_mwo where mwo_id = ?", BigDecimal.class, mwo)).isEqualByComparingTo("50000");
        mvc.perform(get(R + "/" + run).with(admin)).andExpect(jsonPath("$.data.dueUsageText").value("50000"))
                .andExpect(jsonPath("$.data.dueDate").value(today)).andExpect(jsonPath("$.data.counterTotalText").value("50000"))
                .andExpect(jsonPath("$.data.state").value("DUE"));
        // 작업지시 완료 → 점검 완료(완료 누적 50000 ≤ 예정 = 준수) → 다음 예정 100000
        finishPm(mwo, run);
        mvc.perform(get(R + "/" + run).with(admin)).andExpect(jsonPath("$.data.doneUsageText").value("50000"))
                .andExpect(jsonPath("$.data.onTime").value(true));
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueUsageText").value("100000"))
                .andExpect(jsonPath("$.data.lastDoneUsageText").value("50000")).andExpect(jsonPath("$.data.lastDoneDate").value(today));
        // 같은 날 다시 도달(큰 입력) → 같은 예정일·다른 예정 사용량으로 또 만든다(멱등 키에 사용량)
        gauge(c, "151000").andExpect(jsonPath("$.data.runs").value(1));
        long mwo2 = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ? and status = 'OPEN'", Long.class, pl);
        // 취소 = 건너뛰기 → 다음 = 100000 뒤 첫 고정 150000 → "지금 생성" 이 바로 만든다(누적 150000)
        postJson(M + "/" + mwo2 + "/cancel", "{\"rowVer\":0,\"reason\":\"금형 교체\"}").andExpect(status().isOk());
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(1));
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run where plan_id = ? and due_date = ?::date", Integer.class, pl, today))
                .isEqualTo(3);
        // 늦은 완료: 151000 에 끝냄 → 준수 아님, 다음 = 151000 뒤 첫 고정 200000
        gauge(c, "152000").andExpect(jsonPath("$.data.runs").value(0));
        long run3 = accounts.query(comp, "select run_id from cms_chk_run where plan_id = ? and status = 'OPEN'", Long.class, pl);
        long mwo3 = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ? and status = 'OPEN'", Long.class, pl);
        finishPm(mwo3, run3);
        mvc.perform(get(R + "/" + run3).with(admin)).andExpect(jsonPath("$.data.dueUsageText").value("150000"))
                .andExpect(jsonPath("$.data.doneUsageText").value("151000")).andExpect(jsonPath("$.data.onTime").value(false));
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueUsageText").value("200000"));
        // 점검 목록: 기간 조회에 예정 사용량
        mvc.perform(get(R).param("from", today).param("to", today).with(admin)).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].dueUsageText").value(containsInAnyOrder("50000", "100000", "150000")));
    }

    /** 점검 결과(OK) → 착수 → 완료 */
    private void finishPm(long mwo, long run) throws Exception {
        int rv = accounts.query(comp, "select row_ver from cms_chk_run where run_id = ?", Integer.class, run);
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":" + rv + ",\"results\":[{\"seq\":1,\"judge\":\"OK\"}]}").andExpect(status().isOk());
        int v = read(mvc.perform(get(M + "/" + mwo).with(admin)), "$.data.rowVer");
        postJson(M + "/" + mwo + "/start", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        String title = read(mvc.perform(get(M + "/" + mwo).with(admin)), "$.data.title");
        postJson(M + "/" + mwo + "/finish", "{\"title\":\"" + title + "\",\"rowVer\":" + (v + 1) + "}").andExpect(status().isOk());
    }

    @Test
    void 사용량계획_검증_카운터대상_선행_고정칸_규칙변경_미리보기_정기작업_완료기준() throws Exception {
        long prs = counter("PRS-CYC", "EQUIP", "PRS-01", "CYCLES", 0);
        counter("M100-SHOT", "ASSET", "M-100", "SHOTS", 0);
        String base = "{\"planCd\":\"U1\",\"planNm\":\"u\",\"targetCd\":\"PRS-01\",\"tmplCd\":\"PRS-D\",\"cycleType\":\"USAGE\",";
        postJson(P, base + "\"counterCd\":\"M100-SHOT\",\"usageInterval\":\"500\"}").andExpect(jsonPath("$.errors[0].code").value("COUNTER_TARGET"));
        postJson(P, base + "\"counterCd\":\"NOPE\"}").andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("NO_SUCH_CODE", "REQUIRED")));
        postJson(P, base + "\"counterCd\":\"PRS-CYC\",\"usageInterval\":\"500\",\"usageLead\":\"500\"}")
                .andExpect(jsonPath("$.errors[0].code").value("LEAD_GE_INTERVAL"));
        postJson(P, base + "\"counterCd\":\"PRS-CYC\",\"usageInterval\":\"0\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        postJson(P, base + "\"counterCd\":\"PRS-CYC\",\"usageInterval\":\"1.5\",\"anchorUsage\":\"-1\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("SCALE", "NOT_NEGATIVE")));
        postJson(P, base.replace("USAGE", "WEEKLY") + "\"calUnit\":\"DAY\",\"calInterval\":1,\"anchorDate\":\"" + today + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        long pl = usagePlan("U1", "PRS-01", "EQUIP", "PRS-D", "CHECK", "PRS-CYC", "500", null, "1000", "FLOAT");
        // 종류·카운터 고정, 수정 때 첫 예정 필수, 규칙 바꾸면(열린 예정 없음) 다시 계산
        String upd = "{\"planNm\":\"u\",\"tmplCd\":\"PRS-D\",\"execMode\":\"CHECK\",\"scheduleBasis\":\"FLOAT\",\"usageInterval\":\"400\",";
        putJson(P + "/" + pl, upd + "\"cycleType\":\"CAL\",\"counterCd\":\"M100-SHOT\",\"anchorUsage\":\"800\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        putJson(P + "/" + pl, upd + "\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        putJson(P + "/" + pl, upd + "\"anchorUsage\":\"800\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueUsageText").value("800"))
                .andExpect(jsonPath("$.data.usageIntervalText").value("400"));
        // 미리보기(사용량, 카운터 자릿수)
        postJson(P + "/preview", "{\"cycleType\":\"USAGE\",\"scheduleBasis\":\"FIXED\",\"usageInterval\":\"500\",\"anchorUsage\":\"1000\","
                + "\"counterCd\":\"PRS-CYC\"}").andExpect(jsonPath("$.data[0]").value("1000")).andExpect(jsonPath("$.data[1]").value("1500"))
                .andExpect(jsonPath("$.data.length()").value(12));
        postJson(P + "/preview", "{\"cycleType\":\"USAGE\",\"usageInterval\":\"x\"}").andExpect(status().isBadRequest());
        // 읽기가 먼저 쌓인 뒤 만든 계획은 정기 작업이 만든다
        gauge(prs, "0").andExpect(status().isOk());
        reading(prs, "{\"deltaQty\":\"900\"}").andExpect(jsonPath("$.data.runs").value(1));   // U1(예정 800) 도달
        long late = usagePlan("U2", "PRS-01", "EQUIP", "PRS-D", "CHECK", "PRS-CYC", "300", null, "600", "FIXED");
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run where plan_id = ?", Integer.class, late)).isZero();
        jobs.pmGenerate();
        jobs.pmGenerate();                                                                       // 두 번 돌려도 하나
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run where plan_id = ?", Integer.class, late)).isEqualTo(1);
        // 완료 기준(FLOAT) CHECK 완료 → 다음 = 완료 누적(900) + 400
        long run = accounts.query(comp, "select run_id from cms_chk_run where plan_id = ? and status = 'OPEN'", Long.class, pl);
        putJson(R + "/" + run + "/results", "{\"rowVer\":0,\"results\":[{\"seq\":1,\"judge\":\"OK\"}]}").andExpect(status().isOk());
        postJson(R + "/" + run + "/complete", "{\"rowVer\":1}").andExpect(status().isOk());
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueUsageText").value("1300"))
                .andExpect(jsonPath("$.data.lastDoneUsageText").value("900")).andExpect(jsonPath("$.data.state").value("UPCOMING"));
        // 계획 화면의 카운터 고르기: 대상의 사용 중 카운터만
        mvc.perform(get(P + "/counters").param("targetType", "EQUIP").param("targetCd", "PRS-01").with(admin))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].counterCd").value("PRS-CYC"));
        // 클래스 일괄은 달력만 — 기존 C2 흐름 그대로
        postJson(P + "/bulk", "{\"classCd\":\"PRESS\",\"tmplCd\":\"PRS-D\",\"calUnit\":\"MONTH\",\"calInterval\":1,\"anchorDate\":\"" + today + "\"}")
                .andExpect(jsonPath("$.data.created").value(1));
        assertThat(accounts.query(comp, "select cycle_type from cms_pm_plan where plan_cd = 'PRS-D-PRS-01'", String.class)).isEqualTo("CAL");
    }

    // ---------- 동시 입력 ----------

    @Test
    void 같은_카운터_동시_계기입력은_줄을_서서_사슬이_끊기지_않는다() throws Exception {
        long c = counter("PRS-CYC", "EQUIP", "PRS-01", "CYCLES", 0);
        gauge(c, "0").andExpect(status().isOk());
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String v = String.valueOf(i * 10);
            fs.add(pool.submit(() -> {
                go.await();
                return gauge(c, v).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Integer> f : fs) {
            int st = f.get();
            assertThat(st).isIn(200, 400);   // 늦게 도착한 작은 값은 GAUGE_DECREASE — 500·교착 없음
            ok += st == 200 ? 1 : 0;
        }
        pool.shutdown();
        assertThat(ok).isPositive();
        // 사슬: 누적 = 마지막 유효 계기 값 − 기준(0), 각 행 증가량 ≥ 0
        BigDecimal last = accounts.query(comp, """
                select gauge_value from cms_counter_reading where counter_id = ? and void_yn = 'N'
                 order by read_at desc, reading_id desc limit 1""", BigDecimal.class, c);
        assertThat(total(c)).isEqualByComparingTo(last);
        assertThat(accounts.query(comp, "select count(*) from cms_counter_reading where counter_id = ?", Integer.class, c)).isEqualTo(ok + 1);
    }

    // ---------- 직접 SQL ----------

    @Test
    void 직접SQL_읽기는_추가와_마지막부터_무효만_사슬_순서_고정칸_계획_칸_규칙() throws Exception {
        long c = counter("M100-SHOT", "ASSET", "M-100", "SHOTS", 0);
        gauge(c, "100").andExpect(status().isOk());
        long r1 = lastReading(c);
        gauge(c, "150").andExpect(status().isOk());
        String ins = "insert into cms_counter_reading (comp_cd, counter_id, read_at, gauge_value, delta_qty, reset_yn, recorded_by) values (?, ?, ";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_counter_reading set delta_qty = 999 where reading_id = ?", r1))))
                .contains("ck_cms_counter_reading_ledger");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_counter_reading where reading_id = ?", r1)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins + "now(), 170, 5, 'N', 't')", comp, c))))
                .contains("ck_cms_counter_reading_chain");                                       // 150 → 170 인데 증가량 5
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins + "now(), 120, 0, 'N', 't')", comp, c))))
                .contains("ck_cms_counter_reading_decrease");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins + "now() - interval '1 day', 170, 20, 'N', 't')", comp, c))))
                .contains("ck_cms_counter_reading_order");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins + "now(), null, -1, 'N', 't')", comp, c))))
                .contains("ck_cms_counter_reading_delta");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_counter_reading set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now() where reading_id = ?", r1))))
                .contains("ck_cms_counter_reading_void_order");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_counter set scale = 2 where counter_id = ?", c))))
                .contains("ck_cms_counter_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_counter where counter_id = ?", c)))).contains("42501");
        // 계획: 사용량인데 카운터 없음 / 종류 바꾸기
        long tmpl = accounts.query(comp, "select tmpl_id from cms_chk_tmpl where tmpl_cd = 'MOLD-PM'", Long.class);
        long asset = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'M-100'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, asset_id, tmpl_id, cycle_type, usage_interval, anchor_usage,
                                         next_due_usage, created_by, updated_by)
                values (?, 'X', 'x', 'ASSET', ?, ?, 'USAGE', 10, 10, 10, 't', 't')""", comp, asset, tmpl)))).contains("ck_cms_pm_plan_cycle");
        long pl = usagePlan("PM-M", "M-100", "ASSET", "MOLD-PM", "CHECK", "M100-SHOT", "1000", null, null, "FIXED");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_pm_plan set cycle_type = 'CAL' where plan_id = ?", pl))))
                .contains("ck_cms_pm_plan_fixed");
        // 사용량 계획의 실행에 예정 사용량이 없으면 거부(멱등 키)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, asset_id, tmpl_id, due_date, created_by, updated_by)
                values (?, ?, 'ASSET', ?, ?, (now() at time zone 'Asia/Seoul')::date, 't', 't')""", comp, pl, asset, tmpl)))).contains("fk_cms_chk_run_plan_comp");
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사로_A사_카운터_읽기_무효_계획참조_막힘_직접SQL_다른회사_참조_거부() throws Exception {
        long c = counter("M100-SHOT", "ASSET", "M-100", "SHOTS", 0);
        gauge(c, "100").andExpect(status().isOk());
        long r1 = lastReading(c);
        String other = accounts.newComp("CB");
        long bu = accounts.newUser(other, "admin1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        accounts.addPlant(other, "P1", "B");
        long bEquip = equip(other, "P1", "B-01", equipClass(other, "PRESS"));
        mvc.perform(get(C).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(C + "/" + c).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/" + c + "/readings").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/" + c + "/readings"), "{\"gaugeValue\":\"999\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/" + c + "/readings/" + r1 + "/void"), "{\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(C + "/" + c), "{\"counterNm\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // B사 화면에서 A사 금형으로 카운터·A사 카운터로 계획
        mvc.perform(json(post(C), "{\"counterCd\":\"X\",\"counterNm\":\"x\",\"targetType\":\"ASSET\",\"targetCd\":\"M-100\",\"counterKind\":\"SHOTS\"}")
                .with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(post("/api/cms/chk-tmpls"), "{\"tmplCd\":\"B-T\",\"tmplNm\":\"b\"}").with(b)).andExpect(status().isOk());
        mvc.perform(json(post(P), "{\"planCd\":\"B1\",\"planNm\":\"b\",\"targetCd\":\"B-01\",\"tmplCd\":\"B-T\",\"cycleType\":\"USAGE\","
                + "\"counterCd\":\"M100-SHOT\",\"usageInterval\":\"10\"}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        assertThat(total(c)).isEqualByComparingTo("0");                                          // A사 누적 그대로
        // 직접 SQL(B사 범위): A사 카운터에 읽기 / A사 금형에 카운터 / A사 카운터로 계획
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_counter_reading (comp_cd, counter_id, read_at, delta_qty, recorded_by) values (?, ?, now(), 5, 't')""",
                other, c)))).contains("fk_cms_counter_reading_counter_comp");
        long aAsset = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'M-100'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, asset_id, counter_kind, created_by, updated_by)
                values (?, 'X', 'x', 'ASSET', ?, 'SHOTS', 't', 't')""", other, aAsset)))).contains("fk_cms_counter_asset_comp");
        long bTmpl = accounts.query(other, "select tmpl_id from cms_chk_tmpl where tmpl_cd = 'B-T'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, cycle_type, counter_id, usage_interval,
                                         anchor_usage, next_due_usage, created_by, updated_by)
                values (?, 'X', 'x', 'EQUIP', ?, ?, 'USAGE', ?, 10, 10, 10, 't', 't')""", other, bEquip, bTmpl, c))))
                .contains("fk_cms_pm_plan_counter_comp");
        // B사 직접 SQL 로 A사 읽기 무효 → 0행(RLS)
        assertThat(accounts.exec(other, "update cms_counter_reading set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now()"))
                .isZero();
    }
}
