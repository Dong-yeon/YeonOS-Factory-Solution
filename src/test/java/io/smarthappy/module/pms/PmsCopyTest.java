package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * WBS 복사 + 산출물 빈 서식 (docs/32 4.17, 사용자 결정 P-6·P-7·P-8) — 프로젝트 → 새 프로젝트 복사(작업 트리·종류·계획 공수·선후행·
 * 산출물 정의·게이트 점검 문구, 일정 평행 이동, 담당·실적·기준선·제출물 비움), 빈 서식 = QMS 관리문서 번호(템플릿 칸·산출물, 복사 때
 * 따라감), 입력 오류·권한·직접 SQL, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsCopyTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String T = "/api/pms/templates";
    private static final String D = "/api/pms/deliverables";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    RequestPostProcessor pm;
    String formNo;
    long src;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PY");
        pmId = accounts.newUser(comp, "pm1", PW);
        for (String prog : List.of("pms-project", "pms-template", "pms-deliverable", "qms-doc")) {
            accounts.grant(comp, pmId, "PMS", prog, "SEARCH", "SAVE");
        }
        pm = accounts.as(comp, pmId, "pm1");
        long docId = id(send(post("/api/qms/docs"), "{\"docType\":\"CP\",\"docNm\":\"ZT 시험성적서 서식\",\"targets\":[]}", pm));
        formNo = read(mvc.perform(get("/api/qms/docs/" + docId).with(pm)), "$.data.doc.docNo");
        src = source();
    }

    // ------------------------------------------------------------------ 도우미

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
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

    /**
     * 원본: 템플릿(단계 > 설계(표준 16h, 산출물 칸 + 서식) · 게이트(점검 문구 2)) + 프로젝트에서 더한 작업·선후행·산출물, 그리고 담당·실적·기준선
     */
    private long source() throws Exception {
        long t = id(send(post(T), "{\"tmplCd\":\"ZT1\",\"tmplNm\":\"ZT 표준\",\"projType\":\"GENERAL\"}", pm));
        long ph = id(send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"설계 단계\",\"durationDays\":1}", pm));
        long a = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"설계\",\"durationDays\":5,\"stdHours\":16}", pm));
        long g = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"설계 게이트\",\"taskType\":\"GATE\",\"offsetDays\":8}", pm));
        send(post(T + "/tasks/" + a + "/items"), "{\"kind\":\"DELIV\",\"itemNm\":\"설계 검토서\",\"formDocNo\":\"" + formNo.toLowerCase() + "\"}", pm)
                .andExpect(status().isOk());
        send(post(T + "/tasks/" + g + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"도면 승인\"}", pm).andExpect(status().isOk());
        send(post(T + "/tasks/" + g + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"원가 확인\"}", pm).andExpect(status().isOk());
        long p = id(send(post(P), "{\"projNm\":\"ZT 원본\",\"projType\":\"GENERAL\",\"tmplCd\":\"ZT1\",\"startDate\":\"2026-03-02\"}", pm));
        long b = id(send(post(P + "/" + p + "/tasks"), "{\"taskNm\":\"시작품\",\"planStart\":\"2026-03-16\",\"planEnd\":\"2026-03-18\","
                + "\"planHours\":8,\"ownerLoginId\":\"pm1\"}", pm));
        long design = taskId(p, "설계");
        id(send(post(P + "/" + p + "/deps"), "{\"predTaskId\":" + design + ",\"succTaskId\":" + b + ",\"lagDays\":2}", pm));
        id(send(post(P + "/" + p + "/deliverables"), "{\"taskId\":" + b + ",\"delivNm\":\"시작품 성적서\",\"requiredYn\":\"N\",\"ownerLoginId\":\"pm1\","
                + "\"dueDate\":\"2026-03-20\"}", pm));
        // 실적·담당·기준선 — 복사되지 않아야 한다
        Map<String, Object> d = task(p, "설계");
        send(put("/api/pms/tasks/" + design), "{\"taskNm\":\"설계\",\"planStart\":\"" + d.get("planStart") + "\",\"planEnd\":\"" + d.get("planEnd")
                + "\",\"ownerLoginId\":\"pm1\",\"actStart\":\"2026-03-03\",\"progressPct\":40,\"planHours\":16,\"rowVer\":" + d.get("rowVer") + "}", pm)
                .andExpect(status().isOk());
        id(send(post(P + "/" + p + "/baselines"), "{\"reason\":\"착수\"}", pm));
        return p;
    }

    private List<Map<String, Object>> tasks(long proj) throws Exception {
        return read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks");
    }

    private Map<String, Object> task(long proj, String nm) throws Exception {
        return tasks(proj).stream().filter(t -> nm.equals(t.get("taskNm"))).findFirst().orElseThrow();
    }

    private long taskId(long proj, String nm) throws Exception {
        return ((Number) task(proj, nm).get("taskId")).longValue();
    }

    private List<Map<String, Object>> delivs(long proj) throws Exception {
        return read(mvc.perform(get(P + "/" + proj + "/deliverables").with(pm)), "$.data.rows");
    }

    private ResultActions copy(long from, String body, RequestPostProcessor who) throws Exception {
        return send(post(P + "/" + from + "/copy"), body, who);
    }

    // ------------------------------------------------------------------ 복사

    @Test
    void 복사_작업_선후행_산출물_점검문구_일정이동_담당실적기준선_비움() throws Exception {
        long n = id(copy(src, "{\"projNm\":\"ZT 복사\",\"projType\":\"GENERAL\",\"startDate\":\"2026-04-06\"}", pm));
        Map<String, Object> head = read(mvc.perform(get(P + "/" + n).with(pm)), "$.data");
        assertThat(head).containsEntry("status", "DRAFT").containsEntry("projNm", "ZT 복사").containsEntry("planStart", "2026-04-06");
        assertThat(accounts.query(comp, "select tmpl_id from pms_project where proj_id = ?", Long.class, n)).isNull();
        // 같은 트리·이름·종류, 일정 +35일(원본 첫 시작 3/2 → 4/6), 계획 공수(잎), 담당·실적·진척 비움
        List<Map<String, Object>> a = tasks(src);
        List<Map<String, Object>> b = tasks(n);
        assertThat(b).extracting(t -> t.get("wbsNo") + " " + t.get("taskNm") + " " + t.get("taskType"))
                .containsExactlyElementsOf(a.stream().map(t -> t.get("wbsNo") + " " + t.get("taskNm") + " " + t.get("taskType")).toList());
        for (int i = 0; i < a.size(); i++) {
            assertThat(java.time.LocalDate.parse((String) b.get(i).get("planStart")))
                    .isEqualTo(java.time.LocalDate.parse((String) a.get(i).get("planStart")).plusDays(35));
            assertThat(java.time.LocalDate.parse((String) b.get(i).get("planEnd")))
                    .isEqualTo(java.time.LocalDate.parse((String) a.get(i).get("planEnd")).plusDays(35));
            assertThat(b.get(i)).containsEntry("status", "NOT_STARTED").containsEntry("progressPct", 0)
                    .containsEntry("ownerLoginId", null).containsEntry("actStart", null);
        }
        assertThat(task(n, "설계").get("planHours")).isEqualTo(16.0);
        assertThat(task(n, "시작품").get("planHours")).isEqualTo(8.0);
        // 선후행(lag 그대로, 새 작업끼리)
        List<Map<String, Object>> deps = read(mvc.perform(get(P + "/" + n + "/tasks").with(pm)), "$.data.deps");
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0)).containsEntry("predTaskId", (int) taskId(n, "설계")).containsEntry("succTaskId", (int) taskId(n, "시작품"))
                .containsEntry("lagDays", 2);
        // 산출물 정의: 이름·필수·서식, 담당·기한·상태 비움
        Map<String, Map<String, Object>> dv = delivs(n).stream().collect(Collectors.toMap(x -> (String) x.get("delivNm"), x -> x));
        assertThat(dv.keySet()).containsExactlyInAnyOrder("설계 검토서", "시작품 성적서");
        assertThat(dv.get("설계 검토서")).containsEntry("formDocNo", formNo).containsEntry("requiredYn", "Y").containsEntry("status", "NONE");
        assertThat(dv.get("시작품 성적서")).containsEntry("requiredYn", "N").containsEntry("ownerLoginId", null).containsEntry("dueDate", null)
                .containsEntry("formDocNo", null);
        // 게이트 점검 문구, 기준선 없음
        assertThat(accounts.query(comp, "select string_agg(check_nm, ',' order by seq) from pms_task_check where proj_id = ?", String.class, n))
                .isEqualTo("도면 승인,원가 확인");
        assertThat(accounts.query(comp, "select count(*) from pms_baseline where proj_id = ?", Long.class, n)).isZero();
        // 원본 그대로
        assertThat(task(src, "설계")).containsEntry("ownerLoginId", "pm1").containsEntry("progressPct", 40);
        assertThat(accounts.query(comp, "select count(*) from pms_baseline where proj_id = ?", Long.class, src)).isEqualTo(1L);
    }

    @Test
    void 종료_취소된_원본도_복사_입력오류() throws Exception {
        int ver = ((Number) read(mvc.perform(get(P + "/" + src).with(pm)), "$.data.rowVer")).intValue();
        send(post(P + "/" + src + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"수주 취소\",\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
        id(copy(src, "{\"projNm\":\"재수주\",\"projType\":\"GENERAL\",\"startDate\":\"2027-01-04\"}", pm));
        // 시작일 없음 / 템플릿과 같이 / 이름 없음 / 범위 밖으로 밀림 / 없는 원본
        copy(src, "{\"projNm\":\"x\",\"projType\":\"GENERAL\"}", pm).andExpect(jsonPath("$.errors[0].field").value("startDate"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        copy(src, "{\"projNm\":\"x\",\"projType\":\"GENERAL\",\"tmplCd\":\"ZT1\",\"startDate\":\"2027-01-04\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_COPY_NO_TEMPLATE"));
        copy(src, "{\"projType\":\"GENERAL\",\"startDate\":\"2027-01-04\"}", pm).andExpect(jsonPath("$.errors[0].field").value("projNm"));
        copy(src, "{\"projNm\":\"x\",\"projType\":\"GENERAL\",\"startDate\":\"2999-12-30\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        copy(99999999L, "{\"projNm\":\"x\",\"projType\":\"GENERAL\",\"startDate\":\"2027-01-04\"}", pm).andExpect(status().isNotFound());
        // 실패한 복사는 아무것도 남기지 않는다(원본 + 재수주 = 2)
        assertThat(accounts.query(comp, "select count(*) from pms_project", Long.class)).isEqualTo(2L);
    }

    @Test
    void 권한_조회만이면_403() throws Exception {
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "VIEW", "pms-project", "SEARCH");
        copy(src, "{\"projNm\":\"x\",\"projType\":\"GENERAL\",\"startDate\":\"2027-01-04\"}", accounts.as(comp, viewer, "view1"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 빈 서식

    @Test
    void 빈서식_입력검사_대문자로_저장_점검문구엔_불가_직접SQL() throws Exception {
        // 템플릿 칸: 소문자로 넣어도 QMS 표기로(원본 setUp), 없는 번호, 점검 문구에 서식
        assertThat(accounts.query(comp, "select form_doc_no from pms_template_item where item_nm = '설계 검토서'", String.class)).isEqualTo(formNo);
        long t = accounts.query(comp, "select tmpl_id from pms_template where tmpl_cd = 'ZT1'", Long.class);
        long g = accounts.query(comp, "select tt_id from pms_template_task where tmpl_id = ? and task_type = 'GATE'", Long.class, t);
        long a = accounts.query(comp, "select tt_id from pms_template_task where tmpl_id = ? and task_nm = '설계'", Long.class, t);
        send(post(T + "/tasks/" + a + "/items"), "{\"kind\":\"DELIV\",\"itemNm\":\"x\",\"formDocNo\":\"NO-SUCH-DOC\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_NOT_FOUND"));
        send(post(T + "/tasks/" + g + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"x\",\"formDocNo\":\"" + formNo + "\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_FORM_DELIV_ONLY"));
        // 프로젝트 산출물: 붙이기 → 바꾸기(빈 값 = 떼기), 없는 번호
        Map<String, Object> d = delivs(src).stream().filter(x -> "시작품 성적서".equals(x.get("delivNm"))).findFirst().orElseThrow();
        long did = ((Number) d.get("delivId")).longValue();
        send(put(D + "/" + did), "{\"delivNm\":\"시작품 성적서\",\"requiredYn\":\"N\",\"formDocNo\":\"" + formNo.toLowerCase() + "\",\"rowVer\":"
                + d.get("rowVer") + "}", pm).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select form_doc_no from pms_deliverable where deliv_id = ?", String.class, did)).isEqualTo(formNo);
        send(put(D + "/" + did), "{\"delivNm\":\"시작품 성적서\",\"requiredYn\":\"N\",\"formDocNo\":\"NO-SUCH-DOC\",\"rowVer\":"
                + (((Number) d.get("rowVer")).intValue() + 1) + "}", pm).andExpect(jsonPath("$.errors[0].field").value("formDocNo"));
        send(put(D + "/" + did), "{\"delivNm\":\"시작품 성적서\",\"requiredYn\":\"N\",\"formDocNo\":\" \",\"rowVer\":"
                + (((Number) d.get("rowVer")).intValue() + 1) + "}", pm).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select form_doc_no from pms_deliverable where deliv_id = ?", String.class, did)).isNull();
        // 직접 SQL: 형식 틀림(소문자·공백), 점검 문구에 서식
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_deliverable set form_doc_no = 'fm 1' where deliv_id = ?", did)))
                .hasMessageContaining("ck_pms_deliverable_form");
        assertThat(catchThrowable(() -> accounts.exec(comp, "insert into pms_template_item (comp_cd, tmpl_id, tt_id, kind, seq, item_nm, form_doc_no, "
                + "created_by) values (?, ?, ?, 'CHECK', 90, 'x', ?, 't')", comp, t, g, formNo))).hasMessageContaining("ck_pms_template_item_form");
    }

    @Test
    void A사로_B사_원본복사_404_B사에_없는_서식번호() throws Exception {
        String compB = accounts.newComp("PZ");
        long bId = accounts.newUser(compB, "pm1", PW);
        for (String prog : List.of("pms-project", "pms-deliverable", "qms-doc")) {
            accounts.grant(compB, bId, "PMS", prog, "SEARCH", "SAVE");
        }
        RequestPostProcessor b = accounts.as(compB, bId, "pm1");
        copy(src, "{\"projNm\":\"훔친 WBS\",\"projType\":\"GENERAL\",\"startDate\":\"2027-01-04\"}", b).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from pms_project", Long.class)).isZero();
        // A사 서식 번호는 B사에서 없는 문서
        long bp = id(send(post(P), "{\"projNm\":\"B사\",\"projType\":\"GENERAL\"}", b));
        long bt = id(send(post(P + "/" + bp + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-02\",\"planEnd\":\"2026-03-03\"}", b));
        send(post(P + "/" + bp + "/deliverables"), "{\"taskId\":" + bt + ",\"delivNm\":\"x\",\"formDocNo\":\"" + formNo + "\"}", b)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DOC_NOT_FOUND"));
    }
}
