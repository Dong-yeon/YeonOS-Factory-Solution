package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PMS 빠른 보완 (docs/32 4.15, docs/48 6장, 사용자 결정 P-2) — 이슈 유형(공통코드·회사 코드·필수·조회 조건), 기각(PM·PMO, 사유, 열린 이슈만,
 * 종료 방식), 첨부(플랫폼 첨부 + 닫힌 이슈 잠금), 포트폴리오 상태별 차트(목록 조건·지연·기준선 밀림), 직접 SQL, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsQuickTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String IS = "/api/pms/issues";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long memId;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    LocalDate today;
    long proj;
    long late;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PQ");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        for (long u : List.of(pmId, memId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-issue", "SEARCH", "SAVE", "EXCEL");
            accounts.grant(comp, u, "PMS" + u, "pms-home", "SEARCH");
        }
        accounts.grant(comp, pmId, "PMS" + pmId, "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = project(pm, "빠른 보완 시험");
        late = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"지연 작업\",\"ownerLoginId\":\"mem1\",\"planStart\":\""
                + today.minusDays(10) + "\",\"planEnd\":\"" + today.minusDays(2) + "\"}", pm));
        activate(proj, pm);
    }

    private long project(RequestPostProcessor who, String nm) throws Exception {
        return id(send(post(P), "{\"projNm\":\"" + nm + "\",\"projType\":\"GENERAL\"}", who));
    }

    private void activate(long p, RequestPostProcessor who) throws Exception {
        int ver = read(mvc.perform(get(P + "/" + p).with(who)), "$.data.rowVer");
        send(post(P + "/" + p + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":" + ver + "}", who).andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        String s = m.getResponse().getContentAsString();
        assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private long issue(String type, RequestPostProcessor who) throws Exception {
        return id(send(post(IS), "{\"issueType\":\"" + type + "\",\"projId\":" + proj + ",\"title\":\"ZT " + type + "\",\"ownerLoginId\":\"mem1\"}", who));
    }

    private Map<String, Object> issueRow(long id) throws Exception {
        return read(mvc.perform(get(IS + "/" + id).with(pm)), "$.data");
    }

    private ResultActions move(long id, String to, String resolution, RequestPostProcessor who) throws Exception {
        int ver = ((Number) issueRow(id).get("rowVer")).intValue();
        return send(post(IS + "/" + id + "/status"), "{\"status\":\"" + to + "\",\"resolution\":" + (resolution == null ? "null" : "\"" + resolution + "\"")
                + ",\"rowVer\":" + ver + "}", who);
    }

    private ResultActions upload(long issueId, RequestPostProcessor who) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "사진.pdf", "application/pdf", PDF))
                .param("targetType", "pms-issue").param("targetId", "I" + issueId).with(who).with(csrf()));
    }

    // ------------------------------------------------------------------ 유형

    @Test
    void 유형_필수_공통코드_회사코드_사용중지_조회조건_게이트는_위험_직접SQL() throws Exception {
        send(post(IS), "{\"projId\":" + proj + ",\"title\":\"x\"}", pm).andExpect(jsonPath("$.errors[0].field").value("issueType"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(post(IS), "{\"issueType\":\"URGENT\",\"projId\":" + proj + ",\"title\":\"x\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        long defect = issue("defect", pm);
        assertThat(issueRow(defect).get("issueType")).isEqualTo("DEFECT");
        // 회사 코드(그룹 comp_ext_yn = Y) — 더하면 쓰고, 사용 중지하면 못 쓴다(이미 붙은 이슈는 그대로 조회)
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by) "
                + "values (?, 'PMS_ISSUE_TYPE', 'CUSTOMER', '고객 요구', 9, 't', 't')", comp);
        long cust = issue("CUSTOMER", pm);
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where comp_cd = ? and grp_cd = 'PMS_ISSUE_TYPE' and code = 'CUSTOMER'", comp);
        send(post(IS), "{\"issueType\":\"CUSTOMER\",\"projId\":" + proj + ",\"title\":\"x\"}", pm).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        assertThat(issueRow(cust).get("issueType")).isEqualTo("CUSTOMER");
        // 고치기 — 유형 바꾸기
        int ver = ((Number) issueRow(defect).get("rowVer")).intValue();
        send(put(IS + "/" + defect), "{\"issueType\":\"QUESTION\",\"title\":\"ZT 질문\",\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
        // 조회 조건(목록 grid)
        List<Map<String, Object>> rows = read(mvc.perform(get(IS + "/grid").param("issueType", "question").with(pm)), "$.list");
        assertThat(rows).extracting(r -> ((Number) r.get("issueId")).longValue()).containsExactly(defect);
        mvc.perform(get(IS + "/grid").param("issueType", "a b").with(pm)).andExpect(jsonPath("$.errors[0].field").value("issueType"));
        // 직접 SQL — 없는 유형
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_issue set issue_type = 'NOPE' where issue_id = ?", defect)))
                .hasMessageContaining("is not usable");
    }

    // ------------------------------------------------------------------ 기각

    @Test
    void 기각은_PM_이_열린_이슈만_사유필수_종료방식_남음_해결은_종료() throws Exception {
        long a = issue("RISK", mem);
        // 담당·작성자(mem)는 고칠 수 있어도 기각은 못 한다
        move(a, "REJECTED", "중복", mem).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        move(a, "REJECTED", " ", pm).andExpect(jsonPath("$.errors[0].field").value("resolution"));
        move(a, "REJECTED", "다른 이슈와 중복", pm).andExpect(status().isOk());
        Map<String, Object> r = issueRow(a);
        assertThat(r.get("status")).isEqualTo("CLOSED");
        assertThat(r.get("closeKind")).isEqualTo("REJECTED");
        assertThat(r.get("resolution")).isEqualTo("다른 이슈와 중복");
        assertThat(r.get("resolvedOn")).isEqualTo(today.toString());
        // 닫힌 이슈 다시 기각·고치기 → 상태 오류
        move(a, "REJECTED", "x", pm).andExpect(jsonPath("$.code").value("PMS_ISSUE_STATE"));
        // 진행 중도 기각 가능, 해결된 이슈는 기각이 아니라 종료(DONE)
        long b = issue("REQUEST", pm);
        move(b, "IN_PROGRESS", null, pm).andExpect(status().isOk());
        move(b, "RESOLVED", "반영 완료", pm).andExpect(status().isOk());
        move(b, "REJECTED", "x", pm).andExpect(jsonPath("$.code").value("PMS_ISSUE_STATE"));
        move(b, "CLOSED", null, pm).andExpect(status().isOk());
        assertThat(issueRow(b).get("closeKind")).isEqualTo("DONE");
        long c = issue("QUESTION", pm);
        move(c, "IN_PROGRESS", null, pm).andExpect(status().isOk());
        move(c, "REJECTED", "질문 철회", pm).andExpect(status().isOk());
        // 직접 SQL: 열린 이슈를 DONE 으로 닫기 / 종료 방식 없이 닫기 / 열린 이슈에 종료 방식
        long d = issue("DEFECT", pm);
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                update pms_issue set status = 'CLOSED', close_kind = 'DONE', resolution = 'x', resolved_on = current_date where issue_id = ?""", d)))
                .hasMessageContaining("is not allowed");
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                update pms_issue set status = 'CLOSED', resolution = 'x', resolved_on = current_date where issue_id = ?""", d)))
                .hasMessageContaining("ck_pms_issue_close_kind");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_issue set close_kind = 'REJECTED' where issue_id = ?", d)))
                .hasMessageContaining("ck_pms_issue_close_kind");
        // 목록 칸 — 종료 방식은 닫힌 것만
        List<Map<String, Object>> rows = read(mvc.perform(get(IS + "/grid").param("status", "CLOSED").with(pm)), "$.list");
        assertThat(rows).extracting(x -> x.get("closeKind")).containsExactlyInAnyOrder("REJECTED", "DONE", "REJECTED");
    }

    // ------------------------------------------------------------------ 첨부

    @Test
    void 첨부_열린_이슈만_닫히면_잠금_없는_이슈_잠금_권한() throws Exception {
        long a = issue("DEFECT", mem);
        long fileId = ((Number) read(upload(a, mem).andExpect(status().isOk()), "$.data.fileId")).longValue();
        List<Map<String, Object>> files = read(mvc.perform(get("/api/platform/files").param("targetType", "pms-issue").param("targetId", "I" + a)
                .with(pm)), "$.list");
        assertThat(files).extracting(f -> f.get("name")).containsExactly("사진.pdf");
        // 이슈 화면 권한이 없는 사람
        long nobody = accounts.newUser(comp, "nob1", PW);
        accounts.grant(comp, nobody, "NOB", "pms-home", "SEARCH");
        upload(a, accounts.as(comp, nobody, "nob1")).andExpect(status().isForbidden());
        // 없는 이슈·형식 다른 id → 잠김
        upload(999_999_999L, pm).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF))
                .param("targetType", "pms-issue").param("targetId", String.valueOf(a)).with(pm).with(csrf()))
                .andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // 기각(닫힘) → 올리기·지우기 잠김, 목록·내려받기는 됨
        move(a, "REJECTED", "재현 안 됨", pm).andExpect(status().isOk());
        upload(a, pm).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        send(post("/api/platform/files/" + fileId + "/delete"), "{}", pm).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(get("/api/platform/files/" + fileId).with(pm)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 포트폴리오 차트

    @Test
    void 차트_상태별_수_지연_기준선밀림_목록조건과_같음() throws Exception {
        long draft = project(pm, "작성 중 프로젝트");
        Map<String, Map<String, Object>> by = chart(null);
        assertThat(by.keySet()).containsExactly("DRAFT", "ACTIVE", "ON_HOLD");
        assertThat(by.get("ACTIVE")).containsEntry("projCnt", 1).containsEntry("lateCnt", 1).containsEntry("slipCnt", 0);
        assertThat(by.get("DRAFT")).containsEntry("projCnt", 1).containsEntry("lateCnt", 0);
        assertThat(by.get("ON_HOLD")).containsEntry("projCnt", 0);
        // 기준선 뒤 작업 끝을 늦추면 밀림 1, 끝내면 지연 0
        id(send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"고객 일정 합의\"}", pm));
        Map<String, Object> t = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskId == " + late + ")]")
                instanceof List<?> l ? (Map<String, Object>) l.get(0) : Map.of();
        send(put("/api/pms/tasks/" + late), "{\"taskNm\":\"지연 작업\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(10)
                + "\",\"planEnd\":\"" + today.plusDays(3) + "\",\"planReason\":\"금형 수정\",\"rowVer\":" + t.get("rowVer") + "}", pm)
                .andExpect(status().isOk());
        assertThat(chart(null).get("ACTIVE")).containsEntry("slipCnt", 1).containsEntry("lateCnt", 0);
        // 조건: 상태 ALL → 종료·취소도 줄, 키워드 → 목록과 같은 수
        assertThat(chart("status=ALL").keySet()).containsExactly("DRAFT", "ACTIVE", "ON_HOLD", "CLOSED", "CANCELLED");
        assertThat(chart("keyword=작성").get("DRAFT")).containsEntry("projCnt", 1);
        assertThat(chart("keyword=작성").get("ACTIVE")).containsEntry("projCnt", 0);
        long total = ((Number) read(mvc.perform(get("/api/pms/portfolio/grid").param("total", "true").with(pm)), "$.total")).longValue();
        assertThat(chart(null).values().stream().mapToInt(r -> (Integer) r.get("projCnt")).sum()).isEqualTo((int) total);
        assertThat(draft).isPositive();
        mvc.perform(get("/api/pms/portfolio/chart").param("projType", "x y").with(pm)).andExpect(jsonPath("$.errors[0].field").value("projType"));   // 조회 조건은 형식만(docs/68)
    }

    private Map<String, Map<String, Object>> chart(String q) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get("/api/pms/portfolio/chart" + (q == null ? "" : "?" + q)).with(pm)), "$.data");
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        rows.forEach(r -> out.put((String) r.get("status"), r));
        return out;
    }

    // ------------------------------------------------------------------ A사로 B사

    @Test
    void A사로_B사_유형코드_첨부_차트_기각() throws Exception {
        long a = issue("DEFECT", pm);
        long fileId = ((Number) read(upload(a, pm).andExpect(status().isOk()), "$.data.fileId")).longValue();
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) "
                + "values (?, 'PMS_ISSUE_TYPE', 'A_ONLY', 'A사 유형', 't', 't')", comp);
        String compB = accounts.newComp("PR");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PMS", "pms-issue", "SEARCH", "SAVE");
        accounts.grant(compB, bId, "PMS", "pms-home", "SEARCH");
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, bId, "pm1");
        long bProj = project(b, "B사 프로젝트");
        // A사 회사 코드는 B사에 없다(API · 직접 SQL)
        send(post(IS), "{\"issueType\":\"A_ONLY\",\"projId\":" + bProj + ",\"title\":\"x\"}", b).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        long bIssue = id(send(post(IS), "{\"issueType\":\"RISK\",\"projId\":" + bProj + ",\"title\":\"B사 이슈\"}", b));
        assertThat(catchThrowable(() -> accounts.exec(compB, "update pms_issue set issue_type = 'A_ONLY' where issue_id = ?", bIssue)))
                .hasMessageContaining("is not usable");
        // A사 이슈 첨부: 올리기 잠김(없는 이슈), 목록 빈, 내려받기 없음 / A사 이슈 기각 → 없음
        upload(a, b).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        assertThat((List<?>) read(mvc.perform(get("/api/platform/files").param("targetType", "pms-issue").param("targetId", "I" + a).with(b)),
                "$.list")).isEmpty();
        mvc.perform(get("/api/platform/files/" + fileId).with(b)).andExpect(status().isNotFound());
        send(post(IS + "/" + a + "/status"), "{\"status\":\"REJECTED\",\"resolution\":\"x\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        // 차트: B 는 자기 프로젝트(작성 중 1)만
        List<Map<String, Object>> rows = read(mvc.perform(get("/api/pms/portfolio/chart").param("status", "ALL").with(b)), "$.data");
        assertThat(rows.stream().mapToInt(r -> (Integer) r.get("projCnt")).sum()).isEqualTo(1);
    }
}
