package io.smarthappy.module.cms;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C13 (docs/33 13장) — 보전 작업지시 첨부(cms-mwo)·고장 신고 사진(cms-breakdown) 서버 잠금: 없는 작업지시·숫자 아닌 id·다른 회사·취소·확인은 잠김,
 * 완료(DONE)는 열림, 신고 사진은 고장(BM)만. 화면이 버튼을 숨기는 것과 별개로 API 를 직접 불러도 막히는지 본다.
 */
@AutoConfigureMockMvc
class CmsMwoFileTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";
    private static final String B = "/api/cms/breakdowns";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MF");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, cls);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    private ResultActions upload(RequestPostProcessor who, String type, String targetId) throws Exception {
        return mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "보고서.pdf", "application/pdf", PDF))
                .param("targetType", type).param("targetId", targetId).with(who).with(csrf()));
    }

    private long cm() throws Exception {
        return id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비\"}"));
    }

    private long fileOf(long mwo) {
        return accounts.query(comp, "select min(file_id) from sys_file where target_type = 'cms-mwo' and target_id = ?", Long.class, String.valueOf(mwo));
    }

    @Test
    void 완료까지는_올리고_확인되면_올리기_삭제_잠김_취소도_잠김() throws Exception {
        long m = cm();
        upload(admin, "cms-mwo", String.valueOf(m)).andExpect(status().isOk());
        postJson(M + "/" + m + "/start", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        postJson(M + "/" + m + "/finish", "{\"title\":\"정비\",\"workText\":\"베어링 교체\",\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        upload(admin, "cms-mwo", String.valueOf(m)).andExpect(status().isOk());            // 완료 뒤 보고서 — 열림
        postJson(M + "/" + m + "/close", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        upload(admin, "cms-mwo", String.valueOf(m)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(post("/api/platform/files/" + fileOf(m) + "/delete").with(admin).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(get("/api/platform/files").param("targetType", "cms-mwo").param("targetId", String.valueOf(m)).with(admin))
                .andExpect(jsonPath("$.total").value(2));                                       // 읽기는 그대로
        // 취소
        long c = cm();
        postJson(M + "/" + c + "/cancel", "{\"reason\":\"중복\",\"rowVer\":" + ver(c) + "}").andExpect(status().isOk());
        upload(admin, "cms-mwo", String.valueOf(c)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
    }

    @Test
    void 신고_사진은_고장작업지시에만_없는번호_숫자아님은_잠김() throws Exception {
        long bm = id(postJson(B, "{\"targetCd\":\"PRS-01\",\"symptomText\":\"유압 누유\"}"));
        upload(admin, "cms-breakdown", String.valueOf(bm)).andExpect(status().isOk());
        upload(admin, "cms-mwo", String.valueOf(bm)).andExpect(status().isOk());
        long m = cm();
        upload(admin, "cms-breakdown", String.valueOf(m)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(admin, "cms-mwo", String.valueOf(m + 999)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(admin, "cms-breakdown", "x1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        // 조회만 권한은 공통 첨부 권한 규칙대로 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-mwo", "SEARCH");
        upload(accounts.as(comp, viewer, "viewer1"), "cms-mwo", String.valueOf(m)).andExpect(status().isForbidden());
    }

    @Test
    void B사로_A사_작업지시에_올리지_못하고_목록도_0건() throws Exception {
        long m = cm();
        upload(admin, "cms-mwo", String.valueOf(m)).andExpect(status().isOk());
        String other = accounts.newComp("MG");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-mwo", "SEARCH", "SAVE");
        accounts.grant(other, bu, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        upload(b, "cms-mwo", String.valueOf(m)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        upload(b, "cms-breakdown", String.valueOf(m)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        mvc.perform(get("/api/platform/files").param("targetType", "cms-mwo").param("targetId", String.valueOf(m)).with(b))
                .andExpect(jsonPath("$.total").value(0));
    }
}
