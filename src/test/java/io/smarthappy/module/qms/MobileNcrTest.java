package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 모바일 부적합 사진 등록 (/m/ncr, docs/74 화면 4·M8) — 화면이 쓰는 기존 API 를 화면 순서대로:
 * ① 부적합 등록(멱등 키) ② 사진 올리기(대상 = qms-ncr, 부적합 id) ③ 결과 조회.
 * 이상 사용: 같은 누름 재전송(409 — 부적합 한 건), 저장 권한 없는 계정이 등록·사진 올리기(403), 그림이 아닌 파일(거부),
 * A사 계정으로 B사 LOT·B사 부적합(칸 오류·404, B사에서 사진이 보이지 않음).
 */
class MobileNcrTest extends QmsTestBase {

    static final String N = "/api/qms/ncrs";
    static final String F = "/api/platform/files";
    /** PNG 시그니처 + 몇 바이트(내용은 시그니처만 확인한다) */
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};

    long defect;

    @BeforeEach
    void mobileSetUp() {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE");
        defect = defect(comp);
    }

    long defect(String c) {
        return accounts.query(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-MOB', '찍힘', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, c);
    }

    long lot(String c, String no) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""",
                Long.class, c, no);
    }

    static String form(String lotNo, long defectId) {
        return "{\"lotNo\":" + q(lotNo) + ",\"source\":\"PROCESS\",\"defectId\":" + defectId + ",\"ncQty\":2,\"description\":\"모서리 찍힘\"}";
    }

    ResultActions create(RequestPostProcessor who, String key, String body) throws Exception {
        var req = json(post(N), body).with(who);
        return mvc.perform(key == null ? req : req.header("X-Idempotency-Key", key));
    }

    ResultActions photo(RequestPostProcessor who, String targetId, String name, byte[] content) throws Exception {
        return mvc.perform(multipart(F).file(new MockMultipartFile("file", name, "image/png", content))
                .param("targetType", "qms-ncr").param("targetId", targetId).with(who).with(csrf()));
    }

    long ncrCount(String c) {
        return accounts.query(c, "select count(*) from qms_ncr", Long.class);
    }

    long fileCount(String c, long ncrId) {
        return accounts.query(c, "select count(*) from sys_file where target_type = 'qms-ncr' and target_id = ? and use_yn = 'Y'",
                Long.class, String.valueOf(ncrId));
    }

    @Test
    void 화면_순서대로_선택지_등록_사진_두_장_결과() throws Exception {
        lot(comp, "L-MOB1");
        mvc.perform(get("/m/ncr").with(admin)).andExpect(status().isOk());
        mvc.perform(get(N + "/defects").with(admin)).andExpect(jsonPath("$.data[0].defectCd").value("D-MOB"));
        mvc.perform(get("/api/platform/codes").param("grp", "QMS_NCR_SOURCE").with(admin)).andExpect(status().isOk());

        long id = id(create(admin, "m-ncr-0001", form("L-MOB1", defect)));
        photo(admin, String.valueOf(id), "front.png", PNG).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("front.png"));
        photo(admin, String.valueOf(id), "side.png", PNG).andExpect(status().isOk());
        assertThat(fileCount(comp, id)).isEqualTo(2);
        mvc.perform(get(F).param("targetType", "qms-ncr").param("targetId", String.valueOf(id)).with(admin))
                .andExpect(jsonPath("$.list.length()").value(2));
        // 결과 — 번호·LOT·상태 OPEN, 정책 기본값(QMS_NCR_AUTO_HOLD = Y)이라 LOT 이 보류된다(화면이 등록 전에 알린다)
        mvc.perform(get(N + "/" + id).with(admin)).andExpect(jsonPath("$.data.ncr.status").value("OPEN"))
                .andExpect(jsonPath("$.data.ncr.lotNo").value("L-MOB1")).andExpect(jsonPath("$.data.ncr.holdYn").value("Y"))
                .andExpect(jsonPath("$.data.ncr.ncrNo").isNotEmpty());
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_no = 'L-MOB1'", String.class)).isEqualTo("Y");
    }

    @Test
    void 같은_누름을_다시_보내면_409_부적합은_한_건() throws Exception {
        lot(comp, "L-MOB2");
        long id = id(create(admin, "m-ncr-dup", form("L-MOB2", defect)));
        create(admin, "m-ncr-dup", form("L-MOB2", defect)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(ncrCount(comp)).isEqualTo(1);
        assertThat(id).isPositive();
        // 검증에 걸린 요청은 키를 쓰지 않는다 — 고쳐서 같은 키로 다시 보낼 수 있다
        create(admin, "m-ncr-fix", "{\"lotNo\":\"L-MOB2\",\"source\":\"PROCESS\",\"defectId\":" + defect + ",\"ncQty\":0,\"description\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ncQty"));
        assertThat(ncrCount(comp)).isEqualTo(1);
    }

    @Test
    void 빈_칸과_틀린_값은_칸_오류_아무것도_저장되지_않는다() throws Exception {
        create(admin, "key-0000-v1", "{\"lotNo\":\" \",\"source\":null,\"defectId\":null,\"ncQty\":null,\"description\":\"\\u200b\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(5));
        create(admin, "key-0000-v2", form("L-NOPE", defect)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lotNo")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        lot(comp, "L-MOB3");
        create(admin, "key-0000-v3", "{\"lotNo\":\"L-MOB3\",\"source\":\"NOPE\",\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("source"));
        assertThat(ncrCount(comp)).isZero();
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_no = 'L-MOB3'", String.class)).isEqualTo("N");
    }

    @Test
    void 조회_권한만_있는_계정은_등록도_사진_올리기도_403() throws Exception {
        lot(comp, "L-MOB4");
        long id = id(create(admin, "key-0000-p0", form("L-MOB4", defect)));
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "qms-ncr", "SEARCH");
        RequestPostProcessor asViewer = accounts.as(comp, viewer, "viewer");
        long none = accounts.newUser(comp, "none", PW);
        RequestPostProcessor asNone = accounts.as(comp, none, "none");

        mvc.perform(get("/m/ncr").with(asViewer)).andExpect(status().isForbidden());
        lot(comp, "L-MOB5");
        create(asViewer, "key-0000-p1", form("L-MOB5", defect)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        create(asNone, "key-0000-p2", form("L-MOB5", defect)).andExpect(status().isForbidden());
        photo(asViewer, String.valueOf(id), "a.png", PNG).andExpect(status().isForbidden());
        photo(asNone, String.valueOf(id), "a.png", PNG).andExpect(status().isForbidden());
        // CSRF 없는 등록
        mvc.perform(post(N).with(admin).contentType("application/json").content(form("L-MOB5", defect))).andExpect(status().isForbidden());
        assertThat(ncrCount(comp)).isEqualTo(1);
        assertThat(fileCount(comp, id)).isZero();
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_no = 'L-MOB5'", String.class)).isEqualTo("N");
    }

    @Test
    void 그림이_아닌_파일은_확장자를_속여도_거부() throws Exception {
        lot(comp, "L-MOB6");
        long id = id(create(admin, "key-0000-f0", form("L-MOB6", defect)));
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0};
        photo(admin, String.valueOf(id), "photo.png", exe).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        photo(admin, String.valueOf(id), "photo.exe", exe).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        photo(admin, String.valueOf(id), "empty.png", new byte[0]).andExpect(status().is4xxClientError());
        assertThat(fileCount(comp, id)).isZero();
    }

    /** 찾기 화면(/m/find)의 LOT 요약 — 부적합을 등록하면 그 LOT 이 보류로 보인다 + A사 계정으로 B사 LOT 번호·id */
    @Test
    void 찾기_LOT_요약에_등록한_부적합의_보류가_보이고_A사로_B사_LOT_은_404() throws Exception {
        String lots = "/api/production/lots";
        long lotId = lot(comp, "L-FIND1");
        mvc.perform(get(lots + "/by-no").param("lotNo", "L-FIND1").with(admin)).andExpect(status().isForbidden());   // prd-lot 권한 없음
        accounts.grant(comp, userId, "QA", "prd-lot", "SEARCH");
        mvc.perform(get("/m/find").with(admin)).andExpect(status().isOk());
        mvc.perform(get(lots + "/by-no").param("lotNo", "L-FIND1").with(admin)).andExpect(jsonPath("$.data.lotId").value((int) lotId))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
        id(create(admin, "find-0000", form("L-FIND1", defect)));
        mvc.perform(get(lots + "/" + lotId).with(admin)).andExpect(jsonPath("$.data.lot.state").value("HELD"))
                .andExpect(jsonPath("$.data.holds.length()").value(1)).andExpect(jsonPath("$.data.parents.length()").value(0));

        String other = accounts.newComp("QY");
        fixtures(other);
        long bLot = lot(other, "L-BFIND");
        mvc.perform(get(lots + "/by-no").param("lotNo", "L-BFIND").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(lots + "/" + bLot).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(lots + "/by-no").param("lotNo", "").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void A사_계정으로_B사_LOT_은_칸오류_B사_부적합은_404_사진도_B사에_안_보인다() throws Exception {
        String other = accounts.newComp("QZ");
        fixtures(other);
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-ncr", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long bDefect = defect(other);
        lot(other, "L-BONLY");
        long bNcr = id(create(b, "key-0000-b0", form("L-BONLY", bDefect)));

        // A사 계정: B사 LOT 번호 → 없는 번호와 같은 칸 오류(존재 노출 없음), B사 불량 유형 id → 칸 오류
        create(admin, "key-0000-a1", form("L-BONLY", defect)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lotNo")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        lot(comp, "L-AONLY");
        create(admin, "key-0000-a2", form("L-AONLY", bDefect)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("defectId"));
        assertThat(ncrCount(comp)).isZero();
        // B사 부적합 상세·첨부 목록
        mvc.perform(get(N + "/" + bNcr).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(F).param("targetType", "qms-ncr").param("targetId", String.valueOf(bNcr)).with(admin))
                .andExpect(jsonPath("$.list.length()").value(0));
        // A사 계정이 B사 부적합 id 로 사진을 올려도 B사 쪽에는 생기지 않는다(회사 범위) — B사 화면에서 보이지 않는다
        photo(b, String.valueOf(bNcr), "b.png", PNG).andExpect(status().isOk());
        photo(admin, String.valueOf(bNcr), "intruder.png", PNG);
        assertThat(fileCount(other, bNcr)).isEqualTo(1);
        mvc.perform(get(F).param("targetType", "qms-ncr").param("targetId", String.valueOf(bNcr)).with(b))
                .andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].name").value("b.png"));
        assertThat(accounts.query(other, "select hold_yn from lot_master where lot_no = 'L-BONLY'", String.class)).isEqualTo("Y");
        assertThat(accounts.query(other, "select status from qms_ncr where ncr_id = ?", String.class, bNcr)).isEqualTo("OPEN");
    }
}
