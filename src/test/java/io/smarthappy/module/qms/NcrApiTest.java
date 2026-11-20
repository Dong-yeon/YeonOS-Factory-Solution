package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.tenant.TenantRunner;

/**
 * P3 QMS 2부 Q8a 부적합(NCR) — 등록 = LOT 보류(MES LotHoldPort), 처분 → 종결 = 해제, 특채 결재(승인·반려), 정책 N, 다른 근거가 남으면 보류 유지,
 * 같은 LOT 동시 등록(잠금 순서 LOT → qms_ncr) + 이상 사용(A~K: 입력·상태·권한·A사로 B사·직접 SQL).
 */
class NcrApiTest extends QmsTestBase {

    static final String N = "/api/qms/ncrs";
    static final String I = "/api/qms/insps";

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    long defect;
    long lot1;
    long lot2;
    String ngInsp;

    @BeforeEach
    void ncrSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-SCR', '스크래치', 'APPEARANCE', 't', 't'), (?, 'D-OLD', '안 쓰는 유형', 'OTHER', 't', 't')""", comp, comp);
        accounts.exec(comp, "update qms_defect set use_yn = 'N' where defect_cd = 'D-OLD'");
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-SCR'", Long.class);
        lot1 = lot(comp, "L-N1");
        lot2 = lot(comp, "L-N2");
        // 출하검사 확정본: FG1-2 계수(육안, 고정 5) → L-N1 을 NG 로 판정
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        long insp = id(postJson(I, "{\"lotNo\":\"L-N1\",\"inspType\":\"FINAL\"}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, insp);
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            a.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":\"").append(i == 1 ? "N" : "Y").append("\"}");
        }
        postJson(I + "/" + insp + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?",
                Integer.class, insp) + "}").andExpect(status().isOk());
        ngInsp = accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, insp);
    }

    long lot(String c, String no) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""",
                Long.class, c, no);
    }

    String create(String lotNo, String source, String inspNo, String qty) {
        return "{\"lotNo\":" + q(lotNo) + ",\"source\":" + q(source) + ",\"inspNo\":" + q(inspNo) + ",\"defectId\":" + defect
                + ",\"ncQty\":" + qty + ",\"description\":\"외관 스크래치\"}";
    }

    int ver(long ncr) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
    }

    String col(long ncr, String c) {
        return accounts.query(comp, "select " + c + "::text from qms_ncr where ncr_id = ?", String.class, ncr);
    }

    /** "hold_yn/살아 있는 근거(출처:참조)" */
    String lotState(long lotId) {
        return accounts.query(comp, """
                select l.hold_yn || '/' || coalesce((select string_agg(h.source || ':' || coalesce(h.ref_id::text, '-'), ',' order by h.hold_id)
                  from lot_hold h where h.lot_id = l.lot_id and h.released_at is null), '') from lot_master l where l.lot_id = ?""",
                String.class, lotId);
    }

    ResultActions save(long ncr, String qty, String disps) throws Exception {
        return putJson(N + "/" + ncr, "{\"rowVer\":" + ver(ncr) + ",\"defectId\":" + defect + ",\"ncQty\":" + qty
                + ",\"description\":\"외관 스크래치\",\"disps\":[" + disps + "]}");
    }

    static String disp(String key, String type, String qty, String rework) {
        return "{\"rowKey\":\"" + key + "\",\"dispType\":\"" + type + "\",\"qty\":" + qty + ",\"note\":\"MRB\",\"reworkLotNo\":" + q(rework) + "}";
    }

    ResultActions close(long ncr) throws Exception {
        return postJson(N + "/" + ncr + "/close", "{\"rowVer\":" + ver(ncr) + "}");
    }

    ResultActions apr(long aprId, String action, RequestPostProcessor who) throws Exception {
        return mvc.perform(post("/api/platform/approvals/" + aprId + "/" + action).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(action.equals("reject") ? "{\"comment\":\"고객 승인 없음\"}" : "{}"));
    }

    @Test
    void 등록하면_LOT_보류_처분_합이_맞으면_종결과_함께_이_근거만_해제() throws Exception {
        long id = id(postJson(N, create("L-N1", "INSP", ngInsp, "5")));
        assertThat(col(id, "status") + ":" + col(id, "hold_yn") + ":" + col(id, "unit_cd")).isEqualTo("OPEN:Y:EA");
        assertThat(lotState(lot1)).isEqualTo("Y/QMS_NCR:" + id);
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.ncr.inspNo").value(ngInsp))
                .andExpect(jsonPath("$.data.holds.length()").value(1)).andExpect(jsonPath("$.data.holds[0].refId").value(id))
                .andExpect(jsonPath("$.data.autoHold").value(true));
        // 처분 합 4 ≠ 5 → 종결 거부(보류 그대로)
        save(id, "5", disp("a", "SCRAP", "3", null) + "," + disp("b", "REWORK", "1", "R-1")).andExpect(status().isOk());
        close(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NCR_DISP_SUM"));
        assertThat(lotState(lot1)).isEqualTo("Y/QMS_NCR:" + id);
        // 통째 저장: 행 2개 → 3·2, 내용 고침
        save(id, "5", disp("a", "SCRAP", "3", null) + "," + disp("b", "REWORK", "2", "R-1")).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(disp_type || ':' || qty::numeric(9,0) || ':' || coalesce(rework_lot_no, '-'), ',' "
                + "order by line_no) from qms_ncr_disp where ncr_id = ?", String.class, id)).isEqualTo("SCRAP:3:-,REWORK:2:R-1");
        close(id).andExpect(status().isOk());
        assertThat(col(id, "status") + ":" + col(id, "closed_by")).isEqualTo("CLOSED:admin1");
        assertThat(lotState(lot1)).isEqualTo("N/");
        assertThat(accounts.query(comp, "select released_by || ':' || release_reason from lot_hold where ref_id = ? and source = 'QMS_NCR'",
                String.class, id)).startsWith("admin1:부적합 종결 NC");
        // 종결 뒤: 다시 종결·저장·취소 409
        close(id).andExpect(jsonPath("$.code").value("QMS_NCR_STATE"));
        save(id, "5", disp("a", "SCRAP", "5", null)).andExpect(jsonPath("$.code").value("QMS_NCR_STATE"));
        postJson(N + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\"x\"}").andExpect(jsonPath("$.code").value("QMS_NCR_STATE"));
        // 목록·검색
        mvc.perform(get(N).param("status", "CLOSED").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(N).param("keyword", "L-N1").with(admin)).andExpect(jsonPath("$.data[0].ncrId").value(id));
        mvc.perform(get(N + "/defects").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 특채가_있으면_결재로만_종결_승인은_해제_반려는_다시_처리중() throws Exception {
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, approver, "QA2", "qms-ncr", "SEARCH");
        RequestPostProcessor lee = accounts.as(comp, approver, "lee");
        String lines = "\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]";
        long id = id(postJson(N, create("L-N1", "CLAIM", null, "5")));
        save(id, "5", disp("a", "USE_AS_IS", "5", null)).andExpect(status().isOk());
        close(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QMS_NCR_APPROVAL_REQUIRED"));
        long apr1 = id(postJson(N + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        assertThat(col(id, "status")).isEqualTo("PENDING");
        // 목록 상태 묶음 OPEN_ALL = 처리 중 + 특채 결재 중 (홈 "열린 부적합" 숫자와 같은 범위)
        mvc.perform(get(N).param("status", "OPEN_ALL").with(admin)).andExpect(jsonPath("$.data[*].ncrId", org.hamcrest.Matchers.contains((int) id)));
        mvc.perform(get(N).param("status", "OPEN").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        save(id, "5", disp("a", "SCRAP", "5", null)).andExpect(jsonPath("$.code").value("QMS_NCR_STATE"));
        mvc.perform(get(N + "/" + id).with(lee)).andExpect(jsonPath("$.data.approval.myTurn").value(true));
        apr(apr1, "reject", lee).andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("OPEN");
        assertThat(lotState(lot1)).isEqualTo("Y/QMS_NCR:" + id);
        long apr2 = id(postJson(N + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        apr(apr2, "approve", lee).andExpect(status().isOk());
        assertThat(col(id, "status") + ":" + col(id, "closed_by") + ":" + col(id, "apr_id")).isEqualTo("CLOSED:lee:" + apr2);
        assertThat(lotState(lot1)).isEqualTo("N/");
        // 특채 없이 상신은 상태 오류
        long id2 = id(postJson(N, create("L-N2", "PROCESS", null, "2")));
        save(id2, "2", disp("a", "SCRAP", "2", null)).andExpect(status().isOk());
        mvc.perform(get(N).param("status", "OPEN_ALL").with(admin)).andExpect(jsonPath("$.data[*].ncrId", org.hamcrest.Matchers.contains((int) id2)));
        mvc.perform(get(N).param("status", "CLOSED").with(admin)).andExpect(jsonPath("$.data[*].ncrId", org.hamcrest.Matchers.contains((int) id)));
        postJson(N + "/" + id2 + "/submit", "{\"rowVer\":" + ver(id2) + "," + lines + "}").andExpect(jsonPath("$.code").value("QMS_NCR_STATE"));
    }

    static final String F = "/api/platform/files";
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    ResultActions upload(RequestPostProcessor who, String ncrId) throws Exception {
        return mvc.perform(multipart(F).file(new MockMultipartFile("file", "고객승인.pdf", "application/pdf", PDF))
                .param("targetType", "qms-ncr").param("targetId", ncrId).with(who).with(csrf()));
    }

    static long fileId(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.fileId")).longValue();
    }

    ResultActions files(RequestPostProcessor who, long ncr) throws Exception {
        return mvc.perform(get(F).param("targetType", "qms-ncr").param("targetId", String.valueOf(ncr)).with(who));
    }

    ResultActions deleteFile(RequestPostProcessor who, long fileId) throws Exception {
        return mvc.perform(post(F + "/" + fileId + "/delete").with(who).with(csrf()));
    }

    /** 첨부(docs/29_P3 24장) — 처리 중(OPEN)만 올리기·삭제, 특채 결재 중·종결·취소 = 409(읽기는 됨), 반려로 OPEN 이면 다시 풀림, 권한·A사로 B사 */
    @Test
    void 첨부는_처리중만_결재중_종결_취소_뒤_409_A사로_B사() throws Exception {
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, approver, "QA2", "qms-ncr", "SEARCH");
        RequestPostProcessor lee = accounts.as(comp, approver, "lee");
        String lines = "\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]";
        long id = id(postJson(N, create("L-N1", "CLAIM", null, "5")));
        long f1 = fileId(upload(admin, String.valueOf(id)).andExpect(jsonPath("$.data.name").value("고객승인.pdf")));
        // 특채 결재 중: 올리기·삭제 409, 목록·내려받기는 됨
        save(id, "5", disp("a", "USE_AS_IS", "5", null)).andExpect(status().isOk());
        long apr1 = id(postJson(N + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        upload(admin, String.valueOf(id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        deleteFile(admin, f1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        files(lee, id).andExpect(status().isOk()).andExpect(jsonPath("$.list[*].fileId", org.hamcrest.Matchers.contains((int) f1)));
        mvc.perform(get(F + "/" + f1).with(lee)).andExpect(status().isOk());
        // 반려 → OPEN: 다시 올리기·삭제
        apr(apr1, "reject", lee).andExpect(status().isOk());
        long f2 = fileId(upload(admin, String.valueOf(id)));
        deleteFile(admin, f2).andExpect(status().isOk());
        // 승인 → CLOSED: 409, 첨부는 남음
        long apr2 = id(postJson(N + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + "}"));
        apr(apr2, "approve", lee).andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("CLOSED");
        upload(admin, String.valueOf(id)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        deleteFile(admin, f1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        files(admin, id).andExpect(jsonPath("$.list[*].fileId", org.hamcrest.Matchers.contains((int) f1)));
        // 취소: 409
        long id2 = id(postJson(N, create("L-N2", "PROCESS", null, "1")));
        long f3 = fileId(upload(admin, String.valueOf(id2)));
        postJson(N + "/" + id2 + "/cancel", "{\"rowVer\":" + ver(id2) + ",\"reason\":\"중복 등록\"}").andExpect(status().isOk());
        upload(admin, String.valueOf(id2)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        deleteFile(admin, f3).andExpect(status().isConflict());
        // 없는 NCR·숫자 아닌 id = 잠김
        upload(admin, "999999999").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(admin, "abc").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // 권한: 조회만 = 목록은 되고 올리기·삭제 403, 권한 없음 = 목록도 403
        long id3 = id(postJson(N, create("L-N2", "PROCESS", null, "1")));
        long f4 = fileId(upload(admin, String.valueOf(id3)));
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-ncr", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        files(vw, id3).andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(1));
        upload(vw, String.valueOf(id3)).andExpect(status().isForbidden());
        deleteFile(vw, f4).andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none1", PW);
        files(accounts.as(comp, none, "none1"), id3).andExpect(status().isForbidden());
        // A사로 B사: B사 사용자(qms-ncr 전체 권한)가 A사 처리 중 NCR id 로 — 올리기 409(B사에선 없는 NCR), 목록 0, 내려받기·삭제 404
        String other = accounts.newComp("QF");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        upload(b, String.valueOf(id3)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        files(b, id3).andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(get(F + "/" + f4).with(b)).andExpect(status().isNotFound());
        deleteFile(b, f4).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'qms-ncr' and target_id = ? and use_yn = 'Y'",
                Long.class, String.valueOf(id3))).isEqualTo(1L);
        assertThat(accounts.query(other, "select count(*) from sys_file where target_type = 'qms-ncr'", Long.class)).isZero();
    }

    ResultActions closeS(MockHttpSession s, long ncr, int rowVer, String token) throws Exception {
        return postJson(s, N + "/" + ncr + "/close", "{\"rowVer\":" + rowVer + ",\"esignToken\":" + q(token) + "}");
    }

    @Test
    void 전자서명_정책_Y_종결은_승인_서명_특채_상신은_작성_서명_내용이_바뀌면_표시() throws Exception {
        policy("QMS_ESIGN_REQUIRED", "Y");
        MockHttpSession s = new MockHttpSession();
        long id = id(postJson(N, create("L-N1", "PROCESS", null, "5")));
        save(id, "5", disp("a", "SCRAP", "5", null)).andExpect(status().isOk());
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.esignRequired").value(true))
                .andExpect(jsonPath("$.data.signatures.length()").value(0));
        close(id).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QMS_ESIGN_REQUIRED"));
        // 다른 의미·다른 대상·다른 세션의 토큰은 403(구분 안 함)
        String author = esignToken(s, "AUTHOR", "qms_ncr", id);
        closeS(s, id, ver(id), author).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ESIGN_TOKEN"));
        long id2 = id(postJson(N, create("L-N2", "PROCESS", null, "2")));
        closeS(s, id, ver(id), esignToken(s, "APPROVE", "qms_ncr", id2)).andExpect(status().isForbidden());
        String t = esignToken(s, "APPROVE", "qms_ncr", id);
        closeS(new MockHttpSession(), id, ver(id), t).andExpect(status().isForbidden());
        // 낡은 rowVer → 409, 서명 행 없음(토큰은 서명 전 검증에서 막혀 그대로)
        closeS(s, id, ver(id) - 1, t).andExpect(status().isConflict());
        assertThat(esignCount("qms_ncr", id)).isZero();
        closeS(s, id, ver(id), t).andExpect(status().isOk());
        assertThat(col(id, "status")).isEqualTo("CLOSED");
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.signatures.length()").value(1))
                .andExpect(jsonPath("$.data.signatures[0].meaningCd").value("APPROVE"))
                .andExpect(jsonPath("$.data.signatures[0].signerNm").isNotEmpty())
                .andExpect(jsonPath("$.data.signatures[0].authMethod").value("PWD"))
                .andExpect(jsonPath("$.data.signatures[0].current").value(true));
        // 특채 상신 = 작성 서명. 한 번 쓴(거부된) 토큰은 다시 못 씀
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, approver, "QA2", "qms-ncr", "SEARCH");
        String lines = "\"lines\":[{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]";
        save(id2, "2", disp("a", "USE_AS_IS", "2", null)).andExpect(status().isOk());
        postJson(s, N + "/" + id2 + "/submit", "{\"rowVer\":" + ver(id2) + "," + lines + "}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QMS_ESIGN_REQUIRED"));
        postJson(s, N + "/" + id2 + "/submit", "{\"rowVer\":" + ver(id2) + "," + lines + ",\"esignToken\":" + q(author) + "}")
                .andExpect(status().isForbidden());
        long apr = id(postJson(s, N + "/" + id2 + "/submit", "{\"rowVer\":" + ver(id2) + "," + lines + ",\"esignToken\":"
                + q(esignToken(s, "AUTHOR", "qms_ncr", id2)) + "}"));
        assertThat(col(id2, "status")).isEqualTo("PENDING");
        apr(apr, "reject", accounts.as(comp, approver, "lee")).andExpect(status().isOk());
        // 반려 뒤 처분을 바꾸면 서명은 남고 "서명 뒤 바뀜"(지금 내용 해시와 다름)
        mvc.perform(get(N + "/" + id2).with(admin)).andExpect(jsonPath("$.data.signatures[0].current").value(true));
        save(id2, "2", disp("a", "USE_AS_IS", "1", null) + "," + disp("b", "SCRAP", "1", null)).andExpect(status().isOk());
        mvc.perform(get(N + "/" + id2).with(admin)).andExpect(jsonPath("$.data.signatures.length()").value(1))
                .andExpect(jsonPath("$.data.signatures[0].meaningCd").value("AUTHOR"))
                .andExpect(jsonPath("$.data.signatures[0].current").value(false));
        // 직접 SQL: 서명 원장은 고치거나 지울 수 없다
        sqlState("42501", () -> accounts.exec(comp, "update sys_esign set content_hash = repeat('0', 64) where target_id = ?", id));
        sqlState("42501", () -> accounts.exec(comp, "delete from sys_esign where target_id = ?", id));
    }

    /** 결재자(user)의 서명 토큰 — 그 사람 세션에서 재인증 */
    String userToken(RequestPostProcessor who, MockHttpSession s, String meaning, long ncr) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/platform/esign/token").with(who).with(csrf()).session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"meaningCd\":\"" + meaning + "\",\"targetType\":\"qms_ncr\",\"targetId\":" + ncr + ",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.token");
    }

    ResultActions aprSigned(long aprId, RequestPostProcessor who, MockHttpSession s, String token) throws Exception {
        return mvc.perform(post("/api/platform/approvals/" + aprId + "/approve").with(who).with(csrf()).session(s)
                .contentType(MediaType.APPLICATION_JSON).content("{\"esignToken\":" + q(token) + "}"));
    }

    /**
     * E-2 결재자 서명(정책 Y): 특채 결재의 합의 줄 = REVIEW, 승인 줄 = APPROVE 서명을 결재 처리에 실어야 한다(beforeAct 가 검증·소비).
     * 없음·다른 의미·다른 대상·남의 세션 토큰 = 403, 처리 전체 롤백(줄 그대로·NCR PENDING). 반려는 서명 없이. 서명 원장에 작성·검토·승인 3줄
     */
    @Test
    void 전자서명_정책_Y_특채_결재자_합의는_검토_승인은_승인_서명() throws Exception {
        policy("QMS_ESIGN_REQUIRED", "Y");
        MockHttpSession s = new MockHttpSession();
        long agreeUser = accounts.newUser(comp, "kim", PW);
        long approver = accounts.newUser(comp, "lee", PW);
        accounts.grant(comp, agreeUser, "QA2", "qms-ncr", "SEARCH");
        accounts.grant(comp, approver, "QA2", "qms-ncr", "SEARCH");
        RequestPostProcessor kim = accounts.as(comp, agreeUser, "kim");
        RequestPostProcessor lee = accounts.as(comp, approver, "lee");
        MockHttpSession ks = new MockHttpSession();
        MockHttpSession ls = new MockHttpSession();
        String lines = "\"lines\":[{\"userId\":" + agreeUser + ",\"lineType\":\"AGREE\"},{\"userId\":" + approver + ",\"lineType\":\"APPROVE\"}]";
        long id = id(postJson(N, create("L-N1", "PROCESS", null, "5")));
        long other = id(postJson(N, create("L-N2", "PROCESS", null, "2")));
        save(id, "5", disp("a", "USE_AS_IS", "5", null)).andExpect(status().isOk());
        long apr = id(postJson(s, N + "/" + id + "/submit", "{\"rowVer\":" + ver(id) + "," + lines + ",\"esignToken\":"
                + q(esignToken(s, "AUTHOR", "qms_ncr", id)) + "}"));
        // 합의 줄: 토큰 없음 → 403 QMS_ESIGN_REQUIRED, APPROVE 의미·다른 NCR·남의 세션 토큰 → 403, 줄·상태 그대로
        // 공통 결재함 안내(esignFor): 합의 줄 = REVIEW·이 NCR, 반려는 서명 없음
        mvc.perform(get("/api/platform/approvals/" + apr).with(kim)).andExpect(jsonPath("$.data.esignApprove.meaningCd").value("REVIEW"))
                .andExpect(jsonPath("$.data.esignApprove.targetType").value("qms_ncr"))
                .andExpect(jsonPath("$.data.esignApprove.targetId").value((int) id))
                .andExpect(jsonPath("$.data.esignReject").doesNotExist());
        aprSigned(apr, kim, ks, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("QMS_ESIGN_REQUIRED"));
        aprSigned(apr, kim, ks, userToken(kim, ks, "APPROVE", id)).andExpect(status().isForbidden());
        aprSigned(apr, kim, ks, userToken(kim, ks, "REVIEW", other)).andExpect(status().isForbidden());
        aprSigned(apr, kim, new MockHttpSession(), userToken(kim, ks, "REVIEW", id)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select status from apr_line where apr_id = ? and approver_id = ?", String.class, apr, agreeUser))
                .isEqualTo("CURRENT");
        assertThat(col(id, "status")).isEqualTo("PENDING");
        assertThat(esignCount("qms_ncr", id)).isEqualTo(1);
        aprSigned(apr, kim, ks, userToken(kim, ks, "REVIEW", id)).andExpect(status().isOk());
        // 승인 줄: REVIEW 토큰 → 403, APPROVE 토큰 → 종결·보류 해제
        mvc.perform(get("/api/platform/approvals/" + apr).with(lee)).andExpect(jsonPath("$.data.esignApprove.meaningCd").value("APPROVE"));
        aprSigned(apr, lee, ls, userToken(lee, ls, "REVIEW", id)).andExpect(status().isForbidden());
        assertThat(col(id, "status")).isEqualTo("PENDING");
        aprSigned(apr, lee, ls, userToken(lee, ls, "APPROVE", id)).andExpect(status().isOk());
        assertThat(col(id, "status") + ":" + col(id, "closed_by")).isEqualTo("CLOSED:lee");
        assertThat(lotState(lot1)).isEqualTo("N/");
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.signatures.length()").value(3))
                .andExpect(jsonPath("$.data.signatures[*].meaningCd", org.hamcrest.Matchers.contains("AUTHOR", "REVIEW", "APPROVE")))
                .andExpect(jsonPath("$.data.signatures[*].current", org.hamcrest.Matchers.contains(true, true, true)));
        // 반려는 서명 없이(두 번째 특채)
        save(other, "2", disp("a", "USE_AS_IS", "2", null)).andExpect(status().isOk());
        long apr2 = id(postJson(s, N + "/" + other + "/submit", "{\"rowVer\":" + ver(other) + ",\"lines\":[{\"userId\":" + approver
                + ",\"lineType\":\"APPROVE\"}],\"esignToken\":" + q(esignToken(s, "AUTHOR", "qms_ncr", other)) + "}"));
        apr(apr2, "reject", lee).andExpect(status().isOk());
        assertThat(col(other, "status")).isEqualTo("OPEN");
    }

    @Test
    void 전자서명_정책_N_은_토큰_없이_종결_토큰을_주면_서명_B사는_서명_못함() throws Exception {
        MockHttpSession s = new MockHttpSession();
        long id = id(postJson(N, create("L-N1", "PROCESS", null, "5")));
        save(id, "5", disp("a", "SCRAP", "5", null)).andExpect(status().isOk());
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.esignRequired").value(false));
        long id2 = id(postJson(N, create("L-N2", "PROCESS", null, "2")));
        save(id2, "2", disp("a", "SCRAP", "2", null)).andExpect(status().isOk());
        // B사 사용자: A사 NCR 은 보이지 않아(404) 서명 전에 막힌다, A사 서명 행도 안 보임
        String other = accounts.newComp("QX");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-ncr", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        MockHttpSession bs = new MockHttpSession();
        String bt = JsonPath.read(mvc.perform(json(post("/api/platform/esign/token"), "{\"meaningCd\":\"APPROVE\",\"targetType\":\"qms_ncr\","
                + "\"targetId\":" + id + ",\"password\":\"" + PW + "\"}").with(b).session(bs)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(), "$.data.token");
        mvc.perform(json(post(N + "/" + id + "/close"), "{\"rowVer\":" + ver(id) + ",\"esignToken\":" + q(bt) + "}").with(b).session(bs))
                .andExpect(status().isNotFound());
        assertThat(esignCount("qms_ncr", id)).isZero();
        // A사 토큰을 B사 세션에서(토큰은 세션에만 — 없는 토큰과 같다)는 쓰지 못한다: A 토큰으로 A 가 종결
        closeS(s, id, ver(id), esignToken(s, "APPROVE", "qms_ncr", id)).andExpect(status().isOk());
        assertThat(esignCount("qms_ncr", id)).isEqualTo(1);
        assertThat(accounts.query(other, "select count(*) from sys_esign", Long.class)).isZero();
        close(id2).andExpect(status().isOk());
        assertThat(esignCount("qms_ncr", id2)).isZero();
    }

    @Test
    void 정책_N_이면_보류_안함_다른_근거가_남으면_종결_뒤에도_보류() throws Exception {
        // 수동 보류(MES 근거) 먼저 → NCR 두 건 → 하나 종결해도 보류, 둘 다 끝나도 MANUAL 이 남아 보류
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '외관 의심', 't')", comp, lot1);
        long a = id(postJson(N, create("L-N1", "PROCESS", null, "1")));
        long b = id(postJson(N, create("L-N1", "PROCESS", null, "1")));
        assertThat(lotState(lot1)).isEqualTo("Y/MANUAL:-,QMS_NCR:" + a + ",QMS_NCR:" + b);
        save(a, "1", disp("a", "SCRAP", "1", null)).andExpect(status().isOk());
        close(a).andExpect(status().isOk());
        assertThat(lotState(lot1)).isEqualTo("Y/MANUAL:-,QMS_NCR:" + b);
        postJson(N + "/" + b + "/cancel", "{\"rowVer\":" + ver(b) + ",\"reason\":\"중복 등록\"}").andExpect(status().isOk());
        assertThat(col(b, "status") + ":" + col(b, "cancel_reason")).isEqualTo("CANCELLED:중복 등록");
        assertThat(lotState(lot1)).isEqualTo("Y/MANUAL:-");
        // MES 화면(수동 해제)으로 QMS 근거는 못 푼다 — 계약(MES 시험이 본다)을 여기서도 한 번
        long c = id(postJson(N, create("L-N2", "PROCESS", null, "1")));
        accounts.grant(comp, userId, "QA", "prd-lot", "SEARCH", "SAVE");
        long hold = accounts.query(comp, "select hold_id from lot_hold where source = 'QMS_NCR' and ref_id = ?", Long.class, c);
        mvc.perform(json(post("/api/production/lots/" + lot2 + "/holds/" + hold + "/release"), "{\"reason\":\"그냥\",\"rowVer\":"
                + accounts.query(comp, "select row_ver from lot_master where lot_id = ?", Integer.class, lot2) + "}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("HOLD_SOURCE"));
        // 정책 N: 보류하지 않음, 종결도 그대로
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_NCR_AUTO_HOLD', 'N', 't', 't')", comp);
        long lot3 = lot(comp, "L-N3");
        long d = id(postJson(N, create("L-N3", "OTHER", null, "1")));
        assertThat(col(d, "hold_yn")).isEqualTo("N");
        assertThat(lotState(lot3)).isEqualTo("N/");
        mvc.perform(get(N + "/" + d).with(admin)).andExpect(jsonPath("$.data.autoHold").value(false));
        save(d, "1", disp("a", "SORT", "1", null)).andExpect(status().isOk());
        close(d).andExpect(status().isOk());
    }

    @Test
    void D_같은_LOT_에_동시에_두_건_등록해도_교착_없이_근거_두_행() throws Exception {
        // 다른 트랜잭션이 LOT 행을 쥔 동안 두 등록이 모두 LOT 잠금(포트)에서 기다린다 — INSERT(FK KEY SHARE)를 먼저 했다면
        // 풀린 뒤 서로 FOR UPDATE 로 올리려다 교착(40P01)
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, lot2);
                locked.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> mvc.perform(json(post(N), create("L-N2", "PROCESS", null, "1")).with(admin)).andReturn()));
            }
            for (Future<MvcResult> f : calls) {
                assertThat(f.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            blocker.get(20, TimeUnit.SECONDS);
            assertThat(waited).as("LOT 잠금을 기다렸다").isGreaterThanOrEqualTo(400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from lot_hold where lot_id = ? and source = 'QMS_NCR' and released_at is null",
                Integer.class, lot2)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where lot_id = ?", Integer.class, lot2)).isEqualTo(2);
    }

    @Test
    void D_근거_검사_무효와_동시에_등록해도_교착_없이_무효_검사_근거_NCR_은_안_남음() throws Exception {
        // 무효 쪽 순서 = 검사 머리(FOR UPDATE) → LOT(B3 원장 트리거 FOR SHARE · SV-6 보류 FOR UPDATE). 등록이 LOT 을 먼저 잡고
        // 검사를 나중에(FK KEY SHARE) 보면 교착, 검사를 잠그지 않고 JUDGED 를 믿으면 무효로 커밋된 검사에 NCR 이 붙는다
        long insp = accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, ngInsp);
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> voider = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForObject("select insp_id from qms_insp where insp_id = ? for update", Long.class, insp);
                locked.countDown();
                try {
                    Thread.sleep(700);   // 그동안 등록이 들어온다
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, lot1);
                jdbc.update("update qms_insp set status = 'VOID', void_reason = '동시 시험', voided_at = now(), voided_by = 't', "
                        + "row_ver = row_ver + 1, updated_at = now(), updated_by = 't' where insp_id = ?", insp);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> call = pool.submit(() -> mvc.perform(json(post(N), create("L-N1", "INSP", ngInsp, "1")).with(admin)).andReturn());
            voider.get(30, TimeUnit.SECONDS);   // 교착이면 여기서 40P01
            MvcResult r = call.get(30, TimeUnit.SECONDS);
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(r.getResponse().getContentAsString()).contains("NCR_INSP");
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from qms_ncr n join qms_insp i on i.insp_id = n.insp_id where i.status = 'VOID'",
                Integer.class)).isZero();
        assertThat(lotState(lot1)).isEqualTo("N/");
    }

    @Test
    void 이상_입력과_상태() throws Exception {
        postJson(N, "{}").andExpect(status().isBadRequest());
        postJson(N, create("NO-LOT", "INSP", null, "1")).andExpect(jsonPath("$.errors[0].field").value("lotNo"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        postJson(N, create("L-N2", "INSP", ngInsp, "1")).andExpect(jsonPath("$.errors[0].code").value("NCR_INSP"));
        postJson(N, create("L-N1", "XX", "NO-INSP", "0")).andExpect(jsonPath("$.errors.length()").value(3));
        postJson(N, create("L-N1", "INSP", null, "1.2345")).andExpect(jsonPath("$.errors[0].field").value("ncQty"));
        postJson(N, create("L-N1", "INSP", null, "1").replace("\"defectId\":" + defect, "\"defectId\":" + accounts.query(comp,
                "select defect_id from qms_defect where defect_cd = 'D-OLD'", Long.class))).andExpect(jsonPath("$.errors[0].field").value("defectId"));
        postJson(N, create("L-N1", "INSP", null, "1").replace("외관 스크래치", " ")).andExpect(jsonPath("$.errors[0].field").value("description"));
        assertThat(lotState(lot1)).isEqualTo("N/");
        long id = id(postJson(N, create("L-N1", "INSP", null, "3")));
        save(id, "3", disp("a", "XX", "1", null) + "," + disp("b", "SCRAP", "0", null) + "," + disp("c", "SCRAP", "1", "R-9"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rowKey").value("a"))
                .andExpect(jsonPath("$.errors[1].rowKey").value("b")).andExpect(jsonPath("$.errors[2].code").value("NCR_REWORK_ONLY"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            many.append(i == 0 ? "" : ",").append(disp("k" + i, "SCRAP", "1", null));
        }
        save(id, "3", many.toString()).andExpect(jsonPath("$.errors[0].code").value("TOO_MANY"));
        putJson(N + "/" + id, "{\"rowVer\":99,\"defectId\":" + defect + ",\"ncQty\":3,\"description\":\"x\",\"disps\":[]}")
                .andExpect(status().isConflict());
        close(id).andExpect(jsonPath("$.errors[0].code").value("NCR_DISP_SUM"));
        postJson(N + "/" + id + "/close", "{\"rowVer\":99}").andExpect(status().isConflict());
        postJson(N + "/" + id + "/close", "{}").andExpect(status().isBadRequest());
        postJson(N + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\" \"}").andExpect(status().isBadRequest());
        postJson(N + "/" + id + "/cancel", "{\"rowVer\":" + ver(id) + ",\"reason\":\"잘못 등록\"}").andExpect(status().isOk());
        assertThat(lotState(lot1)).isEqualTo("N/");
        mvc.perform(get(N + "/999999999").with(admin)).andExpect(status().isNotFound());
        putJson(N + "/999999999", "{\"rowVer\":0,\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"x\",\"disps\":[]}")
                .andExpect(status().isNotFound());
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        // 수량 3: 아래 직접 SQL 처분 2 + 재작업 칸 시험 1 이 처분 합 ≤ 부적합 수량(V236 ck_qms_ncr_disp_over)을 넘지 않게
        long id = id(postJson(N, create("L-N1", "INSP", ngInsp, "3")));
        // 권한
        long reader = accounts.newUser(comp, "reader1", PW);
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(N).with(rd)).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-ncr", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(N + "/" + id).with(vw)).andExpect(status().isOk());
        mvc.perform(json(post(N), create("L-N2", "PROCESS", null, "1")).with(vw)).andExpect(status().isForbidden());
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "QS", "qms-ncr", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        mvc.perform(json(post(N + "/" + id + "/cancel"), "{\"rowVer\":" + ver(id) + ",\"reason\":\"x\"}").with(sv)).andExpect(status().isForbidden());
        // A사로 B사
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(N).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(N + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(N + "/" + id), "{\"rowVer\":0,\"defectId\":" + defect + ",\"ncQty\":2,\"description\":\"x\",\"disps\":[]}").with(b))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(N + "/" + id + "/close"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(N + "/" + id + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(N), create("L-N1", "PROCESS", null, "1")).with(b)).andExpect(jsonPath("$.errors[*].field",
                org.hamcrest.Matchers.hasItems("lotNo", "defectId")));
        assertThat(lotState(lot1)).isEqualTo("Y/QMS_NCR:" + id);
        assertThat(col(id, "status")).isEqualTo("OPEN");
        // 직접 SQL
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_ncr where ncr_id = ?", id));
        String ins = "insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, insp_id, defect_id, nc_qty, description, occurred_at, "
                + "status, hold_yn, created_by, updated_by) select ?, ?, 'OTHER', ?, item_id, 'EA', ?, ?, 1, 'x', now(), ?, 'N', 't', 't' "
                + "from mst_item where item_cd = 'FG1'";
        long insp = accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, ngInsp);
        constraint(comp, "ck_qms_ncr_open", () -> accounts.exec(comp, ins, comp, "X1", lot1, null, defect, "CLOSED"));
        constraint(comp, "ck_qms_ncr_insp", () -> accounts.exec(comp, ins, comp, "X2", lot2, insp, defect, "OPEN"));
        constraint(comp, "ck_qms_ncr_lot_copy", () -> accounts.exec(comp, ins.replace("'EA'", "'KG'"), comp, "X3", lot2, null, defect, "OPEN"));
        constraint(other, "fk_qms_ncr_lot_comp", () -> accounts.exec(other, ins.replace("from mst_item where item_cd = 'FG1'", "")
                .replace("select ?, ?, 'OTHER', ?, item_id,", "select ?, ?, 'OTHER', ?, " + accounts.query(comp,
                        "select item_id from mst_item where item_cd = 'FG1'", Long.class) + ","), other, "X4", lot1, null, defect, "OPEN"));
        constraint(comp, "ck_qms_ncr_immutable", () -> accounts.exec(comp, "update qms_ncr set lot_id = ? where ncr_id = ?", lot2, id));
        constraint(comp, "ck_qms_ncr_immutable", () -> accounts.exec(comp, "update qms_ncr set hold_yn = 'N' where ncr_id = ?", id));
        constraint(comp, "ck_qms_ncr_disp_sum", () -> accounts.exec(comp,
                "update qms_ncr set status = 'CLOSED', closed_at = now(), closed_by = 't' where ncr_id = ?", id));
        constraint(comp, "ck_qms_ncr_disp_no_update", () -> {
            accounts.exec(comp, "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, 1, 'SCRAP', 2, 't')",
                    comp, id);
            accounts.exec(comp, "update qms_ncr_disp set qty = 1 where ncr_id = ?", id);
        });
        constraint(comp, "ck_qms_ncr_disp_rework", () -> accounts.exec(comp,
                "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, rework_lot_no, created_by) values (?, ?, 2, 'SCRAP', 1, 'R', 't')",
                comp, id));
        accounts.exec(comp, "update qms_ncr set status = 'CANCELLED', cancel_reason = 'SQL' where ncr_id = ?", id);
        constraint(comp, "ck_qms_ncr_transition", () -> accounts.exec(comp,
                "update qms_ncr set status = 'OPEN', cancel_reason = null where ncr_id = ?", id));
        constraint(comp, "ck_qms_ncr_transition", () -> accounts.exec(comp, "update qms_ncr set description = 'y' where ncr_id = ?", id));
        constraint(comp, "ck_qms_ncr_disp_open", () -> accounts.exec(comp,
                "insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, 3, 'SCRAP', 1, 't')", comp, id));
        constraint(comp, "ck_qms_ncr_disp_open", () -> accounts.exec(comp, "delete from qms_ncr_disp where ncr_id = ?", id));
        assertThat(accounts.query(other, "select count(*) from qms_ncr", Long.class)).isZero();
        assertThat(accounts.exec(other, "update qms_ncr set description = 'B'")).isZero();
        // 8D 연결: B사 8D 에 A사 NCR
        constraint(other, "fk_qms_capa_ncr_comp", () -> accounts.exec(other, """
                insert into qms_capa (comp_cd, capa_no, title, source, owner_user_id, occurred_on, contain_due, close_due, ncr_id, created_by, updated_by)
                values (?, 'X1', 'x', 'INTERNAL', ?, (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date,
                        (now() at time zone 'Asia/Seoul')::date, ?, 't', 't')""", other, bUser, id));
    }
}
