package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/**
 * QMS UI 단계 3 2차 (docs/29_P3 15.2) — 일반 표 주 목록의 grid API(부적합·봉쇄·검사 실행·4M 변경·CSR, SPC 단위는 SpcApiTest): 목록과 같은 조건, 서버 페이징 경계·전체 건수, 정렬 허용 목록
 * (모르는 열·4개 이상·쪽 크기·쪽 번호 400), 열 정의, 권한(SEARCH 없으면 403), A사로 B사. 기존 목록 API 는 그대로.
 */
class QmsGridTest extends QmsTestBase {

    static final String N = "/api/qms/ncrs";
    static final String C = "/api/qms/contains";
    static final String I = "/api/qms/insps";
    static final String CH = "/api/qms/changes";
    static final String CSR = "/api/qms/csrs";

    long defect;

    @BeforeEach
    void gridSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-contain", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE");
        defect = accounts.query(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-GR', '찍힘', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, comp);
        for (String no : List.of("L-G3", "L-G1", "L-G5", "L-G2", "L-G4")) {
            accounts.exec(comp, """
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                    select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no);
            postJson(N, "{\"lotNo\":\"" + no + "\",\"source\":\"PROCESS\",\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"찍힘\"}")
                    .andExpect(status().isOk());
        }
    }

    long lotId(String no) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, no);
    }

    ResultActions grid(String url, RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(url + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    static List<String> col(ResultActions r, String field) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*]." + field);
    }

    /** 쪽 경계·정렬 허용 목록·열 정의 — 화면마다 같은 규칙 */
    void pagingAndGuards(String url, String sortField, List<String> asc, String... extraColumns) throws Exception {
        grid(url, admin, "size", "2", "page", "1", "sort", sortField + ",asc").andExpect(jsonPath("$.total").value(asc.size()));
        assertThat(col(grid(url, admin, "size", "2", "page", "1", "sort", sortField + ",asc"), sortField)).containsExactlyElementsOf(asc.subList(0, 2));
        assertThat(col(grid(url, admin, "size", "2", "page", "2", "sort", sortField + ",asc"), sortField)).containsExactlyElementsOf(asc.subList(2, 4));
        assertThat(col(grid(url, admin, "size", "2", "page", "3", "sort", sortField + ",asc"), sortField)).containsExactly(asc.get(4));
        assertThat(col(grid(url, admin, "size", "2", "page", "4", "sort", sortField + ",asc"), sortField)).isEmpty();
        assertThat(col(grid(url, admin, "size", "5", "sort", sortField + ",desc"), sortField)).containsExactlyElementsOf(asc.reversed());
        grid(url, admin, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(url, admin, "sort", "comp_cd;drop table x,asc").andExpect(status().isBadRequest());
        grid(url, admin, "sort", sortField + ",asc", "sort", "status,asc", "sort", sortField + ",desc", "sort", "status,desc")
                .andExpect(status().isBadRequest());
        grid(url, admin, "size", "100000").andExpect(status().isBadRequest());
        grid(url, admin, "page", "0").andExpect(status().isBadRequest());
        mvc.perform(get(url + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems(extraColumns)));
    }

    // ---------- 부적합 ----------

    @Test
    void 부적합_grid_쪽경계_정렬허용_목록과_같은_조건과_순서() throws Exception {
        pagingAndGuards(N, "lotNo", List.of("L-G1", "L-G2", "L-G3", "L-G4", "L-G5"), "ncrNo", "status", "lotNo", "createdAt");
        // 기본 정렬 = 등록 최근(같으면 id 내림차순) — 목록 API 와 같은 순서
        List<String> listed = JsonPath.read(mvc.perform(get(N).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].ncrNo");
        assertThat(col(grid(N, admin, "size", "50"), "ncrNo")).containsExactlyElementsOf(listed);
        // 같은 조건: 상태 묶음 OPEN_ALL·검색어·출처
        grid(N, admin, "status", "OPEN_ALL").andExpect(jsonPath("$.total").value(5));
        grid(N, admin, "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
        grid(N, admin, "keyword", "l-g3").andExpect(jsonPath("$.total").value(1));
        grid(N, admin, "source", "CLAIM").andExpect(jsonPath("$.total").value(0));
        // total=false 면 건수 계산 생략
        grid(N, admin, "total", "false").andExpect(jsonPath("$.total").doesNotExist());
    }

    // ---------- 봉쇄 ----------

    @Test
    void 봉쇄_grid_쪽경계_정렬허용_목록과_같은_조건과_순서() throws Exception {
        // LOT 하나씩 봉쇄 5건(만든 순서 L-G3, L-G1, …) — 봉쇄 번호 오름차순 = 만든 순서
        List<String> made = new java.util.ArrayList<>();
        for (String no : List.of("L-G3", "L-G1", "L-G5", "L-G2", "L-G4")) {
            long id = id(postJson(C, "{\"itemCd\":\"FG1\",\"fromDate\":\"" + today + "\",\"toDate\":\"" + today + "\",\"reason\":\"의심 " + no
                    + "\",\"lotIds\":[" + lotId(no) + "]}"));
            made.add(accounts.query(comp, "select contain_no from qms_contain where contain_id = ?", String.class, id));
        }
        pagingAndGuards(C, "containNo", made, "containNo", "status", "heldCnt", "createdAt");
        List<String> listed = JsonPath.read(mvc.perform(get(C).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].containNo");
        assertThat(col(grid(C, admin, "size", "50"), "containNo")).containsExactlyElementsOf(listed);
        grid(C, admin, "status", "CLOSED").andExpect(jsonPath("$.total").value(0));
        grid(C, admin, "keyword", "의심 l-g5").andExpect(jsonPath("$.total").value(1));
        // B사·권한 없음
        String other = accounts.newComp("QC");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "QA", "qms-contain", "SEARCH");
        grid(C, accounts.as(other, bu, "admin1")).andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none2", PW);
        accounts.grant(comp, none, "OTHER", "qms-ncr", "SEARCH");
        grid(C, accounts.as(comp, none, "none2")).andExpect(status().isForbidden());
        mvc.perform(get(C + "/columns").with(accounts.as(comp, none, "none2"))).andExpect(status().isForbidden());
    }

    // ---------- 검사 실행 ----------

    @Test
    void 검사_grid_쪽경계_정렬허용_목록과_같은_조건과_검증() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("b", null, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        for (String no : List.of("L-G3", "L-G1", "L-G5", "L-G2", "L-G4")) {
            postJson(I, "{\"lotNo\":\"" + no + "\",\"inspType\":\"FINAL\"}").andExpect(status().isOk());
        }
        pagingAndGuards(I, "lotNo", List.of("L-G1", "L-G2", "L-G3", "L-G4", "L-G5"), "inspNo", "status", "result", "occurredAt");
        List<String> listed = JsonPath.read(mvc.perform(get(I).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].inspNo");
        assertThat(col(grid(I, admin, "size", "50"), "inspNo")).containsExactlyElementsOf(listed);
        grid(I, admin, "inspType", "FINAL", "status", "OPEN").andExpect(jsonPath("$.total").value(5));
        grid(I, admin, "result", "NG").andExpect(jsonPath("$.total").value(0));
        // 목록과 같은 검증 — 모르는 유형·상태 400
        grid(I, admin, "inspType", "XX").andExpect(status().isBadRequest());
        grid(I, admin, "status", "DONE").andExpect(status().isBadRequest());
        String other = accounts.newComp("QI");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "QA", "qms-insp", "SEARCH");
        grid(I, accounts.as(other, bu, "admin1")).andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none3", PW);
        accounts.grant(comp, none, "OTHER", "qms-ncr", "SEARCH");
        grid(I, accounts.as(comp, none, "none3")).andExpect(status().isForbidden());
    }

    // ---------- 4M 변경 ----------

    @Test
    void 사엠_변경_grid_쪽경계_정렬허용_ECO_머리_채움() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-change", "SEARCH");
        for (String no : List.of("ECO-G3", "ECO-G1", "ECO-G5", "ECO-G2", "ECO-G4")) {
            long eco = accounts.query(comp, """
                    insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                    values (?, ?, ?, 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""",
                    Long.class, comp, no, "제목 " + no, userId);
            accounts.exec(comp, "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                    + "values (?, ?, '-', array['MAN', 'METHOD']::varchar(10)[], '전', '후 " + no + "', 't', 't')", eco, comp);
        }
        pagingAndGuards(CH, "ecoNo", List.of("ECO-G1", "ECO-G2", "ECO-G3", "ECO-G4", "ECO-G5"), "ecoNo", "ecoStatus", "m4Text", "updatedAt");
        List<String> listed = JsonPath.read(mvc.perform(get(CH).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].ecoNo");
        assertThat(col(grid(CH, admin, "size", "50"), "ecoNo")).containsExactlyElementsOf(listed);
        // ECO 제목·상태는 PLM 계약으로 채운다(목록과 같다)
        assertThat(col(grid(CH, admin, "sort", "ecoNo,asc", "size", "1"), "ecoTitle")).containsExactly("제목 ECO-G1");
        assertThat(col(grid(CH, admin, "sort", "ecoNo,asc", "size", "1"), "ecoStatus")).containsExactly("DRAFT");
        grid(CH, admin, "m4Type", "METHOD").andExpect(jsonPath("$.total").value(5));
        grid(CH, admin, "m4Type", "MACHINE").andExpect(jsonPath("$.total").value(0));
        grid(CH, admin, "m4Type", "XX").andExpect(status().isBadRequest());
        grid(CH, admin, "keyword", "후 eco-g4").andExpect(jsonPath("$.total").value(1));
        String other = accounts.newComp("QE");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "QA", "qms-change", "SEARCH");
        grid(CH, accounts.as(other, bu, "admin1")).andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none4", PW);
        accounts.grant(comp, none, "OTHER", "qms-ncr", "SEARCH");
        grid(CH, accounts.as(comp, none, "none4")).andExpect(status().isForbidden());
    }

    // ---------- CSR ----------

    @Test
    void CSR_grid_쪽경계_정렬허용_사용중지_포함_조건() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-csr", "SEARCH");
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'CU1', '고객 일', 'SALES', 't', 't') returning cust_id""", Long.class, comp);
        for (String no : List.of("C3", "C1", "C5", "C2", "C4")) {
            accounts.exec(comp, "insert into qms_cust_csr (comp_cd, cust_id, csr_no, csr_rev, title, effective_from, use_yn, created_by, updated_by) "
                    + "values (?, ?, ?, 'A', ?, (now() at time zone 'Asia/Seoul')::date, ?, 't', 't')", comp, cust, no, "요구 " + no, "C5".equals(no) ? "N" : "Y");
        }
        // 기본(사용 중만) 4건, 사용 중지 포함 5건으로 쪽 경계
        grid(CSR, admin).andExpect(jsonPath("$.total").value(4));
        List<String> listed = JsonPath.read(mvc.perform(get(CSR).with(admin)).andReturn().getResponse().getContentAsString(), "$.data[*].csrNo");
        assertThat(col(grid(CSR, admin, "size", "50"), "csrNo")).containsExactlyElementsOf(listed);
        assertThat(col(grid(CSR, admin, "withUnused", "true", "size", "2", "page", "3", "sort", "csrNo,asc"), "csrNo")).containsExactly("C5");
        assertThat(col(grid(CSR, admin, "withUnused", "true", "size", "2", "page", "1", "sort", "csrNo,asc"), "csrNo")).containsExactly("C1", "C2");
        grid(CSR, admin, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(CSR, admin, "page", "0").andExpect(status().isBadRequest());
        grid(CSR, admin, "keyword", "요구 c3").andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(CSR + "/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(hasItems("custCd", "csrNo", "useYn")));
        String other = accounts.newComp("QS");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "QA", "qms-csr", "SEARCH");
        grid(CSR, accounts.as(other, bu, "admin1"), "withUnused", "true").andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none5", PW);
        accounts.grant(comp, none, "OTHER", "qms-ncr", "SEARCH");
        grid(CSR, accounts.as(comp, none, "none5")).andExpect(status().isForbidden());
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        String other = accounts.newComp("QG");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "QA", "qms-ncr", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        grid(N, b).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(N, b, "keyword", "L-G1").andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "qms-spc", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(N, n).andExpect(status().isForbidden());
        mvc.perform(get(N + "/columns").with(n)).andExpect(status().isForbidden());
    }
}
