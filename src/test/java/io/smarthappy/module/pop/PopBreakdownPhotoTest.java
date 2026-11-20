package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * POP 고장 신고 사진 (docs/25 4.44, CMS 계약 C25 docs/33 11.17) — 신고 응답의 사진 한도(CMS photoLimits), 사진 1·2·3 → 4번째 409 PHOTO_LIMIT,
 * 같은 바이트 재전송 = 같은 파일(duplicate), 공용 첨부 대상 cms-breakdown × 작업지시·행위자 = 단말·접속 로그 POP.
 * 이상: 작업자 없음 428, PDA 403, 파일 없음 400, 다른 단말 400 reqNo, 다른 신고 요청 id 400, 보전 착수 뒤 409, 형식(png 이름에 pdf)·exe 400,
 * A사 단말로 B사 작업지시 번호 400. 판정 세부(크기·정책·잠김)는 CMS 시험(CmsPopPhotoTest)이 덮는다
 */
@AutoConfigureMockMvc
class PopBreakdownPhotoTest extends PostgresTestBase {

    private static final String PW = "Maple-signal-61";
    private static final String P = "/api/pop";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String ip;
    long line1;
    long equip;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PH");
        ip = "10.85." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        line1 = line(comp);
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스', 't', 't') returning class_id",
                Long.class, comp);
        equip = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                values (?, 'P1', 'E-L1', 'E-L1 설비', ?, ?, 'Y', 't', 't') returning equip_id""", Long.class, comp, cls, line1);
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        cookie = terminal(comp, admin, "TAB-01", line1, "POP");
        s = new MockHttpSession();
        badge(cookie, s).andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    /** 구역 A1 아래 라인 L1 */
    private long line(String c) {
        long area = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'AREA', 'A1', 'A1 구역', 't', 't') returning node_id""", Long.class, c);
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'L1', 'L1 라인', 't', 't') returning node_id""", Long.class, c, area);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String terminal(String c, RequestPostProcessor who, String cd, Long nodeId, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"" + (nodeId == null ? "" : ",\"nodeId\":" + nodeId) + "}").with(who))
                .andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + c + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions badge(String c, MockHttpSession session) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, c)).session(session));
    }

    /** 신고 → 응답 JSON */
    private String report(String reqId) throws Exception {
        return mvc.perform(json(post(P + "/breakdown"), "{\"equipId\":" + equip + ",\"symptomText\":\"누유\",\"clientReqId\":\"" + reqId + "\"}")
                .cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static byte[] png(int seed) {
        byte[] b = Arrays.copyOf(PNG, PNG.length + 4);
        b[PNG.length] = (byte) seed;
        return b;
    }

    private ResultActions photo(String c, MockHttpSession session, String reqNo, String reqId, String name, byte[] body) throws Exception {
        MockMultipartHttpServletRequestBuilder b = multipart(P + "/breakdown/photos");
        if (body != null) {
            b.file(new MockMultipartFile("file", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, body));
        }
        if (reqNo != null) {
            b.param("reqNo", reqNo);
        }
        if (reqId != null) {
            b.param("clientReqId", reqId);
        }
        return mvc.perform(b.with(csrf()).header("X-HB-Client", "POP").cookie(new Cookie(PopAuthService.COOKIE, c)).session(session));
    }

    private ResultActions photo(String reqNo, String reqId, String name, byte[] body) throws Exception {
        return photo(cookie, s, reqNo, reqId, name, body);
    }

    // ---------- 시험 ----------

    @Test
    void 신고_응답에_사진_한도_사진_셋까지_넷째는_409_같은_바이트는_처음_파일() throws Exception {
        String reqId = "req-" + UUID.randomUUID();
        String r = report(reqId);
        String no = JsonPath.read(r, "$.data.reqNo");
        assertThat((Integer) JsonPath.read(r, "$.data.photoMax")).isEqualTo(3);
        assertThat(((Number) JsonPath.read(r, "$.data.photoBytes")).longValue()).isPositive().isLessThanOrEqualTo(5L * 1024 * 1024);
        assertThat(JsonPath.<java.util.List<String>>read(r, "$.data.photoExts")).contains("png", "jpg");

        MvcResult first = photo(no, reqId, "photo-1.png", png(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(false)).andExpect(jsonPath("$.data.count").value(1)).andReturn();
        long fileId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.data.fileId")).longValue();
        // 응답을 못 받아 같은 바이트를 다시 — 처음 파일, 수 그대로
        photo(no, reqId, "photo-1.png", png(1)).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.fileId").value(fileId)).andExpect(jsonPath("$.data.count").value(1));
        photo(no, reqId, "photo-2.png", png(2)).andExpect(jsonPath("$.data.count").value(2));
        photo(no, reqId, "photo-3.png", png(3)).andExpect(jsonPath("$.data.count").value(3));
        photo(no, reqId, "photo-4.png", png(4)).andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].code").value("PHOTO_LIMIT"));
        // 공용 첨부 cms-breakdown × 작업지시 — 사무 "신고 사진"에 그대로, 행위자 = 단말, 접속 로그는 POP
        long mwoId = accounts.query(comp, "select mwo_id from cms_mwo where mwo_no = ?", Long.class, no);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-breakdown' and target_id = ? and created_by like 't%:TAB-01'",
                Integer.class, String.valueOf(mwoId))).isEqualTo(3);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'FILE_UPLOAD' and client_type = 'POP'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void 작업자_없음_PDA_파일_없음_다른_단말_다른_요청_id_착수_뒤_형식() throws Exception {
        String reqId = "req-" + UUID.randomUUID();
        String no = JsonPath.read(report(reqId), "$.data.reqNo");
        // 작업자 없음(새 세션) → 428
        photo(cookie, new MockHttpSession(), no, reqId, "a.png", png(1)).andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        // PDA 단말 → 403(POP 경로)
        String pda = terminal(comp, admin, "PDA-01", null, "PDA");
        MockHttpSession ps = new MockHttpSession();
        photo(pda, ps, no, reqId, "a.png", png(1)).andExpect(status().isForbidden());
        // 파일 없음 → 400 file
        photo(no, reqId, null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("file"));
        // 같은 회사 다른 POP 단말(신고하지 않은 단말) → 400 reqNo
        String other = terminal(comp, admin, "TAB-02", line1, "POP");
        MockHttpSession os = new MockHttpSession();
        badge(other, os).andExpect(status().isOk());
        photo(other, os, no, reqId, "a.png", png(1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reqNo"));
        // 다른 신고의 요청 id → 400 clientReqId
        photo(no, "req-" + UUID.randomUUID(), "a.png", png(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        // 형식: png 이름에 pdf 내용·exe → 400(공용 거부)
        photo(no, reqId, "a.png", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)).andExpect(status().isBadRequest());
        photo(no, reqId, "a.exe", png(1)).andExpect(status().isBadRequest());
        // 보전 착수 뒤 → 409
        accounts.exec(comp, "update cms_mwo set status = 'OPEN' where mwo_no = ?", no);
        photo(no, reqId, "ok.png", png(5)).andExpect(status().isOk());
        accounts.exec(comp, "update cms_mwo set status = 'WIP', started_at = now() where mwo_no = ?", no);
        photo(no, reqId, "late.png", png(6)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-breakdown'", Integer.class)).isEqualTo(1);
    }

    @Test
    void A사_단말로_B사_작업지시_번호는_없음() throws Exception {
        // B사도 같은 모양으로 신고 하나
        String other = accounts.newComp("PI");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.addPlant(other, "P1", "1공장");
        long bLine = line(other);
        long bCls = accounts.query(other, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스', 't', 't') returning class_id",
                Long.class, other);
        long bEquip = accounts.query(other, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                values (?, 'P1', 'E-L1', 'E-L1 설비', ?, ?, 'Y', 't', 't') returning equip_id""", Long.class, other, bCls, bLine);
        accounts.exec(other, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", other, WorkerService.badgeHash(other, "BADGE-0001"));
        String bCookie = terminal(other, accounts.as(other, bu, "admin1"), "TAB-01", bLine, "POP");
        MockHttpSession bs = new MockHttpSession();
        badge(bCookie, bs).andExpect(status().isOk());
        String bReq = "req-" + UUID.randomUUID();
        String bNo = JsonPath.read(mvc.perform(json(post(P + "/breakdown"), "{\"equipId\":" + bEquip + ",\"symptomText\":\"소음\",\"clientReqId\":\"" + bReq + "\"}")
                .cookie(new Cookie(PopAuthService.COOKIE, bCookie)).session(bs)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.reqNo");
        // A사 단말이 B사 번호·요청 id 로 → 400 reqNo, B사 사진 0
        photo(bNo, bReq, "a.png", png(1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reqNo"));
        assertThat(accounts.query(other, "select count(*) from sys_file where target_type = 'cms-breakdown'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-breakdown'", Integer.class)).isZero();
    }
}
