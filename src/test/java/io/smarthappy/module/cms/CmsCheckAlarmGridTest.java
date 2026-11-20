package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * UI 단계 3 ① 3묶음 (docs/33 15.1) — 점검표·점검 실행·알람 정의·알람 이력 grid API: 목록과 같은 조건·같은 기본 순서,
 * 페이지 경계·전체 건수, 정렬 허용 목록(계산 값 열 400), 열 정의, 권한(SEARCH), A사로 B사.
 */
@AutoConfigureMockMvc
class CmsCheckAlarmGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String T = "/api/cms/chk-tmpls";
    private static final String P = "/api/cms/pm-plans";
    private static final String R = "/api/cms/chk-runs";
    private static final String D = "/api/cms/alarm-defs";
    private static final String A = "/api/cms/alarms";
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
    String today;
    long prs1;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GC");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        for (String e : List.of("PRS-03", "PRS-02", "PRS-01")) {
            accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', ?, ?, ?, 't', 't')",
                    comp, e, "프레스 " + e, cls);
        }
        prs1 = accounts.query(comp, "select equip_id from mst_equip where equip_cd = 'PRS-01'", Long.class);
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        // 점검표 3 (항목 1)
        for (String t : List.of("TM-C", "TM-A", "TM-B")) {
            long id = id(save(T, "{\"tmplCd\":\"" + t + "\",\"tmplNm\":\"점검표 " + t + "\"}"));
            mvc.perform(json(put(T + "/" + id + "/items?rowVer=0"), "[{\"seq\":1,\"itemNm\":\"소음\",\"judgeType\":\"OKNG\"}]").with(admin))
                    .andExpect(status().isOk());
        }
        // 일상 계획 3 → 오늘 예정 점검 실행 3
        for (String e : List.of("PRS-03", "PRS-02", "PRS-01")) {
            save(P, "{\"planCd\":\"D-" + e + "\",\"planNm\":\"일상 " + e + "\",\"targetCd\":\"" + e + "\",\"tmplCd\":\"TM-A\",\"execMode\":\"CHECK\","
                    + "\"calUnit\":\"DAY\",\"calInterval\":1,\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"" + today + "\"}");
        }
        mvc.perform(json(post(P + "/generate"), "{}").with(admin)).andExpect(jsonPath("$.data.runs").value(3));
        // 알람 정의 3 + 이력 3
        for (String c : List.of("E-C", "E-A", "E-B")) {
            save(D, "{\"alarmCd\":\"" + c + "\",\"alarmNm\":\"알람 " + c + "\",\"severity\":\"WARN\",\"autoBmYn\":\"N\"}");
        }
        int m = 0;
        for (String c : List.of("E-C", "E-A", "E-B")) {
            Instant at = T0.plus(m++ * 10L, ChronoUnit.MINUTES);
            runner.call(comp, () -> {
                sink.raise(prs1, c, null, at, null, "ev-" + at.toEpochMilli() + "-" + c);
                return null;
            });
        }
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-run", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-alarm", "SEARCH");
        accounts.grant(c, u, "CMS", "cms-alarm-def", "SEARCH", "SAVE");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions save(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk());
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    private ResultActions grid(RequestPostProcessor who, String url, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(url + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<String> read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    /** 기본 순서 = 목록 API 순서 */
    private void sameAsList(String url, String field, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(url);
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        List<String> listed = JsonPath.read(mvc.perform(b.with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*]." + field);
        assertThat(listed).isNotEmpty();
        String[] gp = java.util.Arrays.copyOf(params, params.length + 2);
        gp[params.length] = "size";
        gp[params.length + 1] = "50";
        assertThat(read(grid(admin, url, gp), "$.list[*]." + field)).containsExactlyElementsOf(listed);
    }

    @Test
    void 점검표_grid_페이지경계_정렬_목록과_같은_조건() throws Exception {
        grid(admin, T, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, T, "size", "2"), "$.list[*].tmplCd")).containsExactly("TM-A", "TM-B");
        assertThat(read(grid(admin, T, "size", "2", "page", "2"), "$.list[*].tmplCd")).containsExactly("TM-C");
        assertThat(read(grid(admin, T, "size", "3", "sort", "tmplNm,desc"), "$.list[*].tmplCd")).containsExactly("TM-C", "TM-B", "TM-A");
        sameAsList(T, "tmplCd");
        grid(admin, T, "keyword", "tm-b").andExpect(jsonPath("$.total").value(1));
        grid(admin, T, "useYn", "X").andExpect(status().isBadRequest());
        grid(admin, T, "sort", "itemCnt,desc").andExpect(status().isBadRequest());
        mvc.perform(get(T + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("tmplCd", "itemCnt", "planCnt")));
    }

    @Test
    void 점검실행_grid_기간필수_상태는_계산값_정렬밖() throws Exception {
        grid(admin, R, "from", today, "to", today, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, R, "from", today, "to", today, "size", "2", "sort", "targetCd,asc"), "$.list[*].targetCd"))
                .containsExactly("PRS-01", "PRS-02");
        assertThat(read(grid(admin, R, "from", today, "to", today, "size", "2", "page", "2", "sort", "targetCd,asc"), "$.list[*].targetCd"))
                .containsExactly("PRS-03");
        sameAsList(R, "runId", "from", today, "to", today);
        assertThat(read(grid(admin, R, "from", today, "to", today), "$.list[*].state")).doesNotContainNull();   // 목록과 같은 decorate
        grid(admin, R, "from", today, "to", today, "status", "DONE").andExpect(jsonPath("$.total").value(0));
        grid(admin, R).andExpect(status().isBadRequest());                                                       // 기간 필수
        grid(admin, R, "from", today, "to", today, "status", "NOPE").andExpect(status().isBadRequest());
        grid(admin, R, "from", today, "to", today, "sort", "state,asc").andExpect(status().isBadRequest());
        mvc.perform(get(R + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("dueDate", "status", "state")));
    }

    @Test
    void 알람정의_grid_페이지경계_정렬_목록과_같은_조건() throws Exception {
        grid(admin, D, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, D, "size", "2"), "$.list[*].alarmCd")).containsExactly("E-A", "E-B");
        assertThat(read(grid(admin, D, "size", "2", "page", "2"), "$.list[*].alarmCd")).containsExactly("E-C");
        sameAsList(D, "alarmCd");
        grid(admin, D, "classCd", "NOPE").andExpect(jsonPath("$.total").value(0));          // 없는 클래스 = 0건(목록과 같음)
        grid(admin, D, "useYn", "X").andExpect(status().isBadRequest());
        grid(admin, D, "sort", "symptomCd,asc").andExpect(status().isBadRequest());
        mvc.perform(get(D + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("alarmCd", "severity", "autoBmYn")));
    }

    @Test
    void 알람이력_grid_공장기간필수_기본은_발생최신순_분반복은_정렬밖() throws Exception {
        grid(admin, A, "plantCd", "P1", "from", DAY, "to", DAY, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(read(grid(admin, A, "plantCd", "P1", "from", DAY, "to", DAY, "size", "2"), "$.list[*].alarmCd")).containsExactly("E-B", "E-A");
        assertThat(read(grid(admin, A, "plantCd", "P1", "from", DAY, "to", DAY, "size", "2", "page", "2"), "$.list[*].alarmCd"))
                .containsExactly("E-C");
        sameAsList(A, "alarmId", "plantCd", "P1", "from", DAY, "to", DAY);
        grid(admin, A, "plantCd", "P1", "from", DAY, "to", DAY, "size", "1")
                .andExpect(jsonPath("$.list[0].raisedAt").value(org.hamcrest.Matchers.startsWith(DAY + "T09:20:00+09:00")));   // 공장 현지
        grid(admin, A, "plantCd", "P1", "from", DAY, "to", DAY, "keyword", "e-a").andExpect(jsonPath("$.total").value(1));
        grid(admin, A, "from", DAY, "to", DAY).andExpect(status().isBadRequest());                                     // 공장 필수
        grid(admin, A, "plantCd", "P1", "from", DAY, "to", DAY, "sort", "minutes,desc").andExpect(status().isBadRequest());
        mvc.perform(get(A + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("raisedAt", "severity", "repeat")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("GD");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(b, T).andExpect(jsonPath("$.total").value(0));
        grid(b, R, "from", today, "to", today).andExpect(jsonPath("$.total").value(0));
        grid(b, D).andExpect(jsonPath("$.total").value(0));
        grid(b, A, "plantCd", "P1", "from", DAY, "to", DAY).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "cms-mwo", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(n, T).andExpect(status().isForbidden());
        grid(n, R, "from", today, "to", today).andExpect(status().isForbidden());
        grid(n, D).andExpect(status().isForbidden());
        grid(n, A, "plantCd", "P1", "from", DAY, "to", DAY).andExpect(status().isForbidden());
        for (String url : List.of(T, R, D, A)) {
            mvc.perform(get(url + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
