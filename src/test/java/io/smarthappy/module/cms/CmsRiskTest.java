package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 1부 C6 (docs/33 4.7) — 위험성평가: 수준 계산(3X3·5X5·3단계), 확정 검사(대책·근로자 참여), 바로 확정(APPROVE)·결재 확정·반려,
 * 확정본 불변 + 개선 진행·주지, 복사(이전 확정본 폐기), 폐기, 엑셀, 직접 SQL, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsRiskTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String R = "/api/cms/risks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long adminId;
    long bossId;
    RequestPostProcessor admin;
    RequestPostProcessor boss;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RA");
        adminId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, adminId, "CMS", "cms-risk", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, adminId, "admin1");
        bossId = accounts.newUser(comp, "boss1", PW);
        accounts.grant(comp, bossId, "BOSS", "cms-risk", "SEARCH");
        boss = accounts.as(comp, bossId, "boss1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, cls);
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    private void approvalOff() {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_RA_APPROVAL', 'N', 't', 't')", comp);
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

    private long ra(String method) throws Exception {
        return id(postJson(R, "{\"raType\":\"REGULAR\",\"method\":\"" + method + "\",\"title\":\"프레스 라인 정기\",\"plantCd\":\"P1\",\"assessDate\":\""
                + today + "\"}"));
    }

    private long item(long ra, int seq, Integer l, Integer s, String extra) throws Exception {
        return id(postJson(R + "/" + ra + "/items", "{\"seq\":" + seq + ",\"taskText\":\"금형 교체\",\"hazardCat\":\"MECH\",\"hazardText\":\"끼임 " + seq
                + "\"" + (l == null ? "" : ",\"likelihood\":" + l + ",\"severity\":" + s) + (extra == null ? "" : "," + extra) + "}"));
    }

    private int ver(long ra) throws Exception {
        return read(mvc.perform(get(R + "/" + ra).with(admin)), "$.data.rowVer");
    }

    private void worker(long ra) throws Exception {
        postJson(R + "/" + ra + "/members", "{\"memberNm\":\"김근로\",\"roleCd\":\"WORKER\",\"participatedOn\":\"" + today + "\"}").andExpect(status().isOk());
    }

    @Test
    void 빈도강도_수준계산_확정검사_바로확정_확정본불변_개선진행_주지() throws Exception {
        approvalOff();
        long ra = ra("FREQ_SEV");
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.raNo").value(startsWith("RA-")))
                .andExpect(jsonPath("$.data.matrixCd").value("3X3")).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.problems[*].code").value(containsInAnyOrder("RA_NO_ITEMS", "RA_NO_WORKER")));
        // 3X3 손 검산: 3×3 높음 / 1×2 낮음 / 2×2 중간 / 3×1 중간
        item(ra, 1, 3, 3, null);
        item(ra, 2, 1, 2, null);
        item(ra, 3, 2, 2, "\"planMeasure\":\"방호 덮개\",\"ownerLoginId\":\"admin1\",\"dueDate\":\"" + LocalDate.parse(today).minusDays(1) + "\"");
        item(ra, 4, 3, 1, "\"equipCd\":\"prs-01\"");
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.items[*].riskLevel").value(containsInAnyOrder("HIGH", "LOW", "MEDIUM", "MEDIUM")))
                .andExpect(jsonPath("$.data.items[2].overdue").value(true)).andExpect(jsonPath("$.data.items[3].equipCd").value("PRS-01"));
        // 입력 검증: 3X3 에 4 / 하나만 / 순번 중복 / 분류 코드 / 없는 설비·담당
        postJson(R + "/" + ra + "/items", "{\"seq\":5,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":4,\"severity\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        postJson(R + "/" + ra + "/items", "{\"seq\":5,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":1}")
                .andExpect(jsonPath("$.errors[0].field").value("severity"));
        postJson(R + "/" + ra + "/items", "{\"seq\":1,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":1,\"severity\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(R + "/" + ra + "/items", "{\"seq\":5,\"taskText\":\" \",\"hazardCat\":\"NOISE\",\"hazardText\":\"y\",\"likelihood\":1,\"severity\":1,"
                + "\"equipCd\":\"NOPE\",\"ownerLoginId\":\"ghost\"}")
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("taskText", "hazardCat", "equipCd", "ownerLoginId")));
        // 확정 검사: 1·4(높음·중간)는 대책 없음, 근로자 참여 없음 → 칸 오류(행 키 = 순번)
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + "}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("RA_PLAN_REQUIRED", "RA_PLAN_REQUIRED", "RA_NO_WORKER")))
                .andExpect(jsonPath("$.errors[?(@.code == 'RA_PLAN_REQUIRED')].rowKey").value(containsInAnyOrder("1", "4")));
        long i1 = accounts.query(comp, "select item_id from cms_risk_item where ra_id = ? and seq = 1", Long.class, ra);
        long i4 = accounts.query(comp, "select item_id from cms_risk_item where ra_id = ? and seq = 4", Long.class, ra);
        postJson(R + "/" + ra + "/items", "{\"itemId\":" + i1 + ",\"rowVer\":0,\"seq\":1,\"taskText\":\"금형 교체\",\"hazardCat\":\"MECH\",\"hazardText\":\"끼임 1\","
                + "\"likelihood\":3,\"severity\":3,\"planMeasure\":\"양수 조작\",\"ownerLoginId\":\"admin1\",\"dueDate\":\"" + today + "\"}").andExpect(status().isOk());
        postJson(R + "/" + ra + "/items/" + i4 + "/delete", "").andExpect(status().isOk());
        worker(ra);
        // APPROVE 없는 사람은 바로 확정 못 함
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "cms-risk", "SEARCH", "SAVE");
        mvc.perform(json(post(R + "/" + ra + "/release"), "{\"rowVer\":" + ver(ra) + "}").with(accounts.as(comp, saver, "saver1")))
                .andExpect(status().isForbidden());
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + (ver(ra) - 1) + "}").andExpect(status().isConflict());
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + "}").andExpect(jsonPath("$.data").value(0));
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.releasedBy").value("admin1"))
                .andExpect(jsonPath("$.data.releasedAt").value(endsWith("+09:00")));   // 평가 공장 시간대(브라우저 확인에서 UTC 로 보임 — 고침)
        // 확정본: 내용 수정·항목 추가·삭제·참여자 추가 불가, 개선 진행·주지만
        putJson(R + "/" + ra, "{\"raType\":\"REGULAR\",\"title\":\"x\",\"assessDate\":\"" + today + "\",\"rowVer\":" + ver(ra) + "}")
                .andExpect(jsonPath("$.code").value("CMS_RA_STATE"));
        postJson(R + "/" + ra + "/items", "{\"seq\":9,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":1,\"severity\":1}")
                .andExpect(jsonPath("$.code").value("CMS_RA_STATE"));
        postJson(R + "/" + ra + "/items/" + i1 + "/delete", "").andExpect(jsonPath("$.code").value("CMS_RA_STATE"));
        postJson(R + "/" + ra + "/members", "{\"memberNm\":\"x\",\"roleCd\":\"WORKER\"}").andExpect(jsonPath("$.code").value("CMS_RA_STATE"));
        int iv = accounts.query(comp, "select row_ver from cms_risk_item where item_id = ?", Integer.class, i1);
        String tomorrow = LocalDate.parse(today).plusDays(1).toString();
        putJson(R + "/" + ra + "/items/" + i1 + "/progress", "{\"doneDate\":\"" + tomorrow + "\",\"rowVer\":" + iv + "}")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        putJson(R + "/" + ra + "/items/" + i1 + "/progress", "{\"residualL\":1,\"residualS\":1,\"rowVer\":" + iv + "}")
                .andExpect(jsonPath("$.errors[0].field").value("doneDate"));
        putJson(R + "/" + ra + "/items/" + i1 + "/progress", "{\"doneDate\":\"" + today + "\",\"residualL\":1,\"residualS\":2,\"rowVer\":" + iv + "}")
                .andExpect(status().isOk());
        putJson(R + "/" + ra + "/items/" + i1 + "/progress", "{\"doneDate\":\"" + today + "\",\"rowVer\":" + iv + "}").andExpect(status().isConflict());
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.items[0].residualLevel").value("LOW"))
                .andExpect(jsonPath("$.data.items[0].doneDate").value(today));
        long mem = accounts.query(comp, "select member_id from cms_risk_member where ra_id = ?", Long.class, ra);
        putJson(R + "/" + ra + "/members/" + mem + "/notify", "{\"notifiedOn\":\"" + today + "\",\"rowVer\":0}").andExpect(status().isOk());
        // 개선 미완(중간 이상 3번, 기한 어제)·지연 집계
        mvc.perform(get(R).param("from", today).param("to", today).with(admin)).andExpect(jsonPath("$.data[0].openCnt").value(1))
                .andExpect(jsonPath("$.data[0].overdueCnt").value(1)).andExpect(jsonPath("$.data[0].highCnt").value(1));
        // 직접 SQL: 확정본 내용·항목 고치기, 항목 지우기, 참여 고치기, 평가 지우기, 상태 건너뛰기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_risk_assess set title = 'x' where ra_id = ?", ra))))
                .contains("ck_cms_risk_assess_released_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_risk_item set hazard_text = 'x' where item_id = ?", i1))))
                .contains("ck_cms_risk_item_released_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "delete from cms_risk_item where item_id = ?", i1))))
                .contains("ck_cms_risk_item_released_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_risk_member set participated_on = null where member_id = ?", mem))))
                .contains("ck_cms_risk_member_released_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_risk_assess where ra_id = ?", ra)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_risk_assess set status = 'DRAFT' where ra_id = ?", ra))))
                .contains("ck_cms_risk_assess_transition");
        // 엑셀: 위험성평가표 3행
        byte[] x = mvc.perform(json(post(R + "/" + ra + "/export"), "{}").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(3);
            assertThat(wb.getSheetAt(0).getRow(1).getCell(8).getStringCellValue()).isEqualTo("높음");
        }
    }

    @Test
    void 결재확정과_반려_결재중_수정불가() throws Exception {
        long ra = ra("FREQ_SEV");
        item(ra, 1, 1, 1, null);
        worker(ra);
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + ",\"lines\":[]}").andExpect(status().is4xxClientError());   // 결재선 없음
        assertThat(accounts.query(comp, "select status from cms_risk_assess where ra_id = ?", String.class, ra)).isEqualTo("DRAFT");
        long apr = id(postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + ",\"lines\":[{\"userId\":" + bossId + "}]}"));
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.status").value("IN_APPROVAL")).andExpect(jsonPath("$.data.aprId").value(apr));
        postJson(R + "/" + ra + "/items", "{\"seq\":2,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":1,\"severity\":1}")
                .andExpect(jsonPath("$.code").value("CMS_RA_STATE"));
        postJson(R + "/" + ra + "/obsolete", "{\"rowVer\":" + ver(ra) + "}").andExpect(jsonPath("$.code").value("CMS_RA_STATE"));
        // 반려 → 작성 중, 다시 상신 → 승인 → 확정
        mvc.perform(json(post("/api/platform/approvals/" + apr + "/reject"), "{\"comment\":\"대책 보강\"}").with(boss)).andExpect(status().isOk());
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.status").value("DRAFT")).andExpect(jsonPath("$.data.aprId").doesNotExist());
        long apr2 = id(postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + ",\"lines\":[{\"userId\":" + bossId + "}]}"));
        mvc.perform(json(post("/api/platform/approvals/" + apr2 + "/approve"), "{\"comment\":\"OK\"}").with(boss)).andExpect(status().isOk());
        mvc.perform(get(R + "/" + ra).with(admin)).andExpect(jsonPath("$.data.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.releasedAt").exists());
    }

    @Test
    void 복사하면_이전확정본은_새확정때_폐기_끝낸대책은_현재조치로_행렬은_이어감_5X5와_3단계() throws Exception {
        approvalOff();
        long ra = ra("FREQ_SEV");
        long i1 = item(ra, 1, 3, 3, "\"currentMeasure\":\"안전 수칙\",\"planMeasure\":\"광전자 센서\",\"ownerLoginId\":\"admin1\",\"dueDate\":\"" + today + "\"");
        item(ra, 2, 2, 2, "\"planMeasure\":\"표지\",\"ownerLoginId\":\"admin1\",\"dueDate\":\"" + today + "\"");
        worker(ra);
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + "}").andExpect(status().isOk());
        putJson(R + "/" + ra + "/items/" + i1 + "/progress", "{\"doneDate\":\"" + today + "\",\"residualL\":1,\"residualS\":3,\"rowVer\":0}").andExpect(status().isOk());
        // 정책을 5X5 로 바꿔도 복사본은 원본 행렬(3X3)
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_RA_MATRIX', '5X5', 't', 't')", comp);
        postJson(R + "/" + (ra + 999) + "/copy", "{}").andExpect(status().isNotFound());
        long cp = id(postJson(R + "/" + ra + "/copy", "{\"raType\":\"REGULAR\"}"));
        mvc.perform(get(R + "/" + cp).with(admin)).andExpect(jsonPath("$.data.prevRaId").value(ra)).andExpect(jsonPath("$.data.matrixCd").value("3X3"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].currentMeasure").value("안전 수칙 / 광전자 센서"))
                .andExpect(jsonPath("$.data.items[0].riskLevel").value("MEDIUM"))          // 개선 후 1×3 = 3 → 중간이 새 위험성
                .andExpect(jsonPath("$.data.items[0].planMeasure").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].planMeasure").value("표지"))           // 못 끝낸 대책은 이어 간다
                .andExpect(jsonPath("$.data.members.length()").value(0));
        postJson(R + "/" + cp + "/copy", "{}").andExpect(jsonPath("$.code").value("CMS_RA_STATE"));   // 작성 중은 복사 원본 아님
        long c1 = accounts.query(comp, "select item_id from cms_risk_item where ra_id = ? and seq = 1", Long.class, cp);
        postJson(R + "/" + cp + "/items", "{\"itemId\":" + c1 + ",\"rowVer\":0,\"seq\":1,\"taskText\":\"금형 교체\",\"hazardCat\":\"MECH\",\"hazardText\":\"끼임 1\","
                + "\"likelihood\":1,\"severity\":2}").andExpect(status().isOk());
        worker(cp);
        postJson(R + "/" + cp + "/release", "{\"rowVer\":" + ver(cp) + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from cms_risk_assess where ra_id = ?", String.class, ra)).isEqualTo("OBSOLETE");
        // 새 평가는 5X5: 4×3 = 12 중간, 3×5 = 15 높음
        long r5 = ra("FREQ_SEV");
        item(r5, 1, 4, 3, null);
        item(r5, 2, 3, 5, null);
        mvc.perform(get(R + "/" + r5).with(admin)).andExpect(jsonPath("$.data.matrixCd").value("5X5"))
                .andExpect(jsonPath("$.data.items[*].riskLevel").value(containsInAnyOrder("MEDIUM", "HIGH")));
        // 3단계 판단법: 수준을 고른다, 없으면 필수, 가능성은 무시
        long r3 = ra("THREE_STEP");
        postJson(R + "/" + r3 + "/items", "{\"seq\":1,\"taskText\":\"x\",\"hazardCat\":\"ENV\",\"hazardText\":\"소음\"}").andExpect(jsonPath("$.errors[0].field").value("riskLevel"));
        item(r3, 1, null, null, "\"riskLevel\":\"LOW\"");
        mvc.perform(get(R + "/" + r3).with(admin)).andExpect(jsonPath("$.data.items[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.items[0].likelihood").doesNotExist());
        putJson(R + "/" + r3, "{\"raType\":\"ADHOC\",\"method\":\"FREQ_SEV\",\"title\":\"t\",\"assessDate\":\"" + today + "\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        // 작성 중 폐기(DELETE) — 권한 없으면 403
        long saver = accounts.newUser(comp, "saver2", PW);
        accounts.grant(comp, saver, "SAVER", "cms-risk", "SEARCH", "SAVE");
        mvc.perform(json(post(R + "/" + r3 + "/obsolete"), "{\"rowVer\":" + ver(r3) + "}").with(accounts.as(comp, saver, "saver2"))).andExpect(status().isForbidden());
        postJson(R + "/" + r3 + "/obsolete", "{\"rowVer\":" + ver(r3) + "}").andExpect(status().isOk());
        mvc.perform(get(R).param("from", today).param("to", today).param("status", "OBSOLETE").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void B사로_A사_평가_항목_참여_결재_막힘_직접SQL_다른회사_참조() throws Exception {
        long ra = ra("FREQ_SEV");
        long i1 = item(ra, 1, 1, 1, null);
        String other = accounts.newComp("RB");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-risk", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        mvc.perform(get(R).param("from", today).param("to", today).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(R + "/" + ra).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(R + "/" + ra), "{\"raType\":\"REGULAR\",\"title\":\"x\",\"assessDate\":\"" + today + "\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + ra + "/items"), "{\"seq\":2,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":1,\"severity\":1}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + ra + "/members"), "{\"memberNm\":\"x\",\"roleCd\":\"WORKER\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + ra + "/release"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + ra + "/obsolete"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + ra + "/export"), "{}").with(b)).andExpect(status().isNotFound());
        // B사 평가에 A사 공장·설비·사용자
        long bra = id(mvc.perform(json(post(R), "{\"raType\":\"REGULAR\",\"title\":\"b\",\"assessDate\":\"" + today + "\"}").with(b)));
        mvc.perform(json(post(R), "{\"raType\":\"REGULAR\",\"title\":\"b\",\"plantCd\":\"P1\",\"assessDate\":\"" + today + "\"}").with(b))
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(json(post(R + "/" + bra + "/items"), "{\"seq\":1,\"taskText\":\"x\",\"hazardCat\":\"MECH\",\"hazardText\":\"y\",\"likelihood\":1,"
                + "\"severity\":1,\"equipCd\":\"PRS-01\",\"ownerLoginId\":\"boss1\"}").with(b))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("equipCd", "ownerLoginId")));
        // 직접 SQL(B사): A사 평가에 항목·참여자, B사 참여자에 A사 사용자, B사 평가가 A사 확정본을 이전으로
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_risk_item (comp_cd, ra_id, seq, task_text, hazard_cat, hazard_text, risk_level, created_by, updated_by)
                values (?, ?, 9, 'x', 'MECH', 'y', 'LOW', 't', 't')""", other, ra)))).contains("fk_cms_risk_item_ra_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_risk_member (comp_cd, ra_id, member_nm, role_cd, created_by, updated_by) values (?, ?, 'x', 'WORKER', 't', 't')""",
                other, ra)))).contains("fk_cms_risk_member_ra_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_risk_member (comp_cd, ra_id, user_id, member_nm, role_cd, created_by, updated_by) values (?, ?, ?, 'x', 'WORKER', 't', 't')""",
                other, bra, bossId)))).contains("fk_cms_risk_member_user_comp");
        assertThat(accounts.exec(other, "update cms_risk_item set hazard_text = 'hacked'")).isZero();
        assertThat(accounts.query(comp, "select hazard_text from cms_risk_item where item_id = ?", String.class, i1)).isEqualTo("끼임 1");
    }

    private ResultActions upload(RequestPostProcessor who, String targetId) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "개선.pdf", "application/pdf",
                "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII)))
                .param("targetType", RiskService.PROG).param("targetId", targetId).with(who).with(csrf()));
    }

    /** C12 (docs/33 11.14) — 요인마다 개선 증빙: 작성·확정본은 올리기·삭제, 폐기·없는 요인·다른 회사 요인은 잠김, 조회만 권한은 못 올림 */
    @Test
    void 개선증빙은_요인마다_붙고_폐기되면_잠김_없는요인_다른회사요인도_잠김() throws Exception {
        approvalOff();
        long ra = ra("FREQ_SEV");
        long i1 = item(ra, 1, 1, 1, null);
        upload(admin, String.valueOf(i1)).andExpect(status().isOk());
        worker(ra);
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + "}").andExpect(status().isOk());
        upload(admin, String.valueOf(i1)).andExpect(status().isOk());   // 확정 뒤 개선 증빙(D17 — 확정본은 진행만 기록)
        mvc.perform(get("/api/platform/files").param("targetType", RiskService.PROG).param("targetId", String.valueOf(i1)).with(boss))
                .andExpect(jsonPath("$.total").value(2));
        upload(boss, String.valueOf(i1)).andExpect(status().isForbidden());
        // 없는 요인·숫자 아님 → 잠김(대상 없는 첨부를 만들지 않는다)
        upload(admin, String.valueOf(i1 + 999)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(admin, "x1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // B사로 A사 요인: 올리기 잠김, 목록 0건
        String other = accounts.newComp("RC");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-risk", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        upload(b, String.valueOf(i1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(get("/api/platform/files").param("targetType", RiskService.PROG).param("targetId", String.valueOf(i1)).with(b))
                .andExpect(jsonPath("$.total").value(0));
        // 폐기 → 올리기·삭제 잠김
        postJson(R + "/" + ra + "/obsolete", "{\"rowVer\":" + ver(ra) + "}").andExpect(status().isOk());
        upload(admin, String.valueOf(i1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        long fileId = accounts.query(comp, "select min(file_id) from sys_file where target_type = 'cms-risk' and target_id = ?", Long.class, String.valueOf(i1));
        mvc.perform(post("/api/platform/files/" + fileId + "/delete").with(admin).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_LOCKED"));
    }

    private int liveFiles(long itemId) {
        return accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-risk' and target_id = ? and use_yn = 'Y'", Integer.class,
                String.valueOf(itemId));
    }

    /** C24 (docs/33 13.11) — 작성 중 요인을 지우면 그 요인의 증빙 첨부도 같은 트랜잭션에서 지운다. 다른 평가 번호로는 404·첨부 그대로, 다른 요인 첨부 그대로 */
    @Test
    void 요인을_지우면_그요인_첨부도_지워지고_다른평가번호면_404_첨부그대로() throws Exception {
        approvalOff();
        long ra = ra("FREQ_SEV");
        long i1 = item(ra, 1, 1, 1, null);
        long i2 = item(ra, 2, 1, 1, null);
        upload(admin, String.valueOf(i1)).andExpect(status().isOk());
        upload(admin, String.valueOf(i1)).andExpect(status().isOk());
        upload(admin, String.valueOf(i2)).andExpect(status().isOk());
        // 다른 평가 번호로 i1 삭제 → 404, 첨부 그대로(먼저 지웠다가 롤백도 아니고 아예 건드리지 않음)
        long ra2 = ra("FREQ_SEV");
        postJson(R + "/" + ra2 + "/items/" + i1 + "/delete", "{}").andExpect(status().isNotFound());
        assertThat(liveFiles(i1)).isEqualTo(2);
        // 지우면 그 요인 첨부 2건만 사용 중지(이력은 남는다 — 공용 첨부 삭제와 같음), i2 첨부 그대로
        postJson(R + "/" + ra + "/items/" + i1 + "/delete", "{}").andExpect(status().isOk());
        assertThat(liveFiles(i1)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-risk' and target_id = ? and use_yn = 'N'", Integer.class,
                String.valueOf(i1))).isEqualTo(2);
        assertThat(liveFiles(i2)).isEqualTo(1);
        // 확정 뒤에는 요인 삭제 자체가 막힌다(첨부도 그대로)
        worker(ra);
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + "}").andExpect(status().isOk());
        postJson(R + "/" + ra + "/items/" + i2 + "/delete", "{}").andExpect(status().is4xxClientError());
        assertThat(liveFiles(i2)).isEqualTo(1);
    }
}
