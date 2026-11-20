package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 포트폴리오 연도·유형 차트 (docs/32 4.20, 사용자 결정 P-11) — 연도 = 계획 시작 해(착수, 작성 중 포함)·종료일 해(종료, CLOSED 만),
 * 유형 = 건수·진행 중, 작업 없는 프로젝트 수, 목록과 같은 조건(상태·유형·키워드·내 PM), 입력 검사·권한, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsTrendTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String TR = "/api/pms/portfolio/trend";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long otherId;
    RequestPostProcessor pm;
    RequestPostProcessor other;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PX");
        pmId = accounts.newUser(comp, "pm1", PW);
        otherId = accounts.newUser(comp, "pm2", PW);
        for (long u : List.of(pmId, otherId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-project", "SEARCH", "SAVE");
            accounts.grant(comp, u, "PMS" + u, "pms-home", "SEARCH");
        }
        pm = accounts.as(comp, pmId, "pm1");
        other = accounts.as(comp, otherId, "pm2");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        // ① 일반·작년 착수·진행 ② APQP·올해 착수·작성 중 ③ 일반·올해 착수·종료(오늘) ④ 일반·작업 없음(pm2 가 PM)
        long p1 = project("ZT 작년 진행", "GENERAL", pm);
        task(p1, today.minusYears(1).withDayOfYear(10), today.minusYears(1).withDayOfYear(20), pm);
        move(p1, "ACTIVE", pm);
        long p2 = project("ZT 올해 신규", "APQP", pm);
        task(p2, today.withDayOfYear(1), today.withDayOfYear(2), pm);
        long p3 = project("ZT 올해 종료", "GENERAL", pm);
        long t3 = task(p3, today.withDayOfYear(1), today.withDayOfYear(2), pm);
        move(p3, "ACTIVE", pm);
        int tv = accounts.query(comp, "select row_ver from pms_task where task_id = ?", Integer.class, t3);
        send(put("/api/pms/tasks/" + t3), "{\"taskNm\":\"t\",\"planStart\":\"" + today.withDayOfYear(1) + "\",\"planEnd\":\"" + today.withDayOfYear(2)
                + "\",\"actStart\":\"" + today.withDayOfYear(1) + "\",\"actEnd\":\"" + today.withDayOfYear(2) + "\",\"rowVer\":" + tv + "}", pm)
                .andExpect(status().isOk());
        move(p3, "CLOSED", pm);
        project("ZT 빈 프로젝트", "GENERAL", other);
    }

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        String s = m.getResponse().getContentAsString();
        assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private long project(String nm, String type, RequestPostProcessor who) throws Exception {
        return id(send(post(P), "{\"projNm\":\"" + nm + "\",\"projType\":\"" + type + "\"}", who));
    }

    private long task(long p, LocalDate s, LocalDate e, RequestPostProcessor who) throws Exception {
        return id(send(post(P + "/" + p + "/tasks"), "{\"taskNm\":\"t\",\"planStart\":\"" + s + "\",\"planEnd\":\"" + e + "\"}", who));
    }

    private void move(long p, String to, RequestPostProcessor who) throws Exception {
        int ver = JsonPath.read(mvc.perform(get(P + "/" + p).with(who)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
        send(post(P + "/" + p + "/status"), "{\"status\":\"" + to + "\",\"rowVer\":" + ver + "}", who).andExpect(status().isOk());
    }

    private Map<String, Object> trend(String query, RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(get(TR + (query == null ? "" : "?" + query)).with(who)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, String> years(Map<String, Object> d) {
        return ((List<Map<String, Object>>) d.get("years")).stream()
                .collect(Collectors.toMap(r -> (Integer) r.get("year"), r -> r.get("startCnt") + "/" + r.get("closeCnt")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> types(Map<String, Object> d) {
        return ((List<Map<String, Object>>) d.get("types")).stream()
                .collect(Collectors.toMap(r -> (String) r.get("projType"), r -> r.get("projCnt") + "/" + r.get("activeCnt")));
    }

    @Test
    void 전체_연도는_계획시작_종료일_유형은_건수_진행_작업없는_프로젝트_따로() throws Exception {
        Map<String, Object> d = trend("status=ALL", pm);
        int y = today.getYear();
        // 작년: 착수 1 / 올해: 착수 2(작성 중 포함)·종료 1 — 작업 없는 ④는 연도에서 빠지고 noPlanCnt
        assertThat(years(d)).containsExactlyInAnyOrderEntriesOf(Map.of(y - 1, "1/0", y, "2/1"));
        assertThat(d.get("noPlanCnt")).isEqualTo(1);
        assertThat(types(d)).containsExactlyInAnyOrderEntriesOf(Map.of("GENERAL", "3/1", "APQP", "1/0"));
        // 연도 순서 = 오래된 순
        assertThat(JsonPath.<List<Integer>>read(d, "$.years[*].year")).containsExactly(y - 1, y);
    }

    @Test
    void 목록과_같은_조건_진행중이면_종료_빠짐_유형_키워드_내PM() throws Exception {
        int y = today.getYear();
        // 기본(진행 중 = 작성·진행·보류) → 종료 프로젝트 빠짐: 올해 착수 1·종료 0
        assertThat(years(trend(null, pm))).containsExactlyInAnyOrderEntriesOf(Map.of(y - 1, "1/0", y, "1/0"));
        assertThat(years(trend("status=CLOSED", pm))).containsExactlyInAnyOrderEntriesOf(Map.of(y, "1/1"));
        assertThat(types(trend("status=ALL&projType=APQP", pm))).containsExactlyInAnyOrderEntriesOf(Map.of("APQP", "1/0"));
        Map<String, Object> kw = JsonPath.read(mvc.perform(get(TR).param("status", "ALL").param("keyword", "빈 프로젝트").with(pm))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data");
        assertThat(years(kw)).isEmpty();
        assertThat(kw.get("noPlanCnt")).isEqualTo(1);
        // 내가 PM: pm2 는 빈 프로젝트 하나뿐
        Map<String, Object> mine = trend("status=ALL&mine=true", other);
        assertThat(years(mine)).isEmpty();
        assertThat(types(mine)).containsExactlyInAnyOrderEntriesOf(Map.of("GENERAL", "1/0"));
    }

    @Test
    void 입력검사_권한_A사로_B사() throws Exception {
        mvc.perform(get(TR).param("status", "NOPE").with(pm)).andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(get(TR).param("projType", "bad type!").with(pm)).andExpect(jsonPath("$.errors[0].field").value("projType"));
        long noHome = accounts.newUser(comp, "proj1", PW);
        accounts.grant(comp, noHome, "PROJ", "pms-project", "SEARCH");
        mvc.perform(get(TR).with(accounts.as(comp, noHome, "proj1"))).andExpect(status().isForbidden());
        // B사: A사 프로젝트 하나도 안 보임
        String compB = accounts.newComp("PY");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PMS", "pms-home", "SEARCH");
        Map<String, Object> b = trend("status=ALL", accounts.as(compB, bId, "pm1"));
        assertThat(years(b)).isEmpty();
        assertThat(types(b)).isEmpty();
        assertThat(b.get("noPlanCnt")).isEqualTo(0);
    }
}
