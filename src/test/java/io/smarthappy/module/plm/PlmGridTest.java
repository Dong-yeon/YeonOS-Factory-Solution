package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 주 목록 grid API (UI 단계 3, docs/39 21.2) — 지시·요청 /grid·/columns: 쪽 경계·전체 건수·정렬 허용 목록(밖·4개 → 400)·
 * 목록과 같은 조건·기본 순서, 권한 403, A사로 B사.
 */
@AutoConfigureMockMvc
class PlmGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String ECR = "/api/plm/ecrs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor me;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PGR");
        userId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, userId, "ENG", "plm-eco", "SEARCH");
        accounts.grant(comp, userId, "ENG2", "plm-ecr", "SEARCH");
        me = accounts.as(comp, userId, "eng1");
        seed(comp, userId);
    }

    /** 지시 5(제목 가~마, 하나는 취소) · 요청 5 — 번호는 넣은 순서대로 */
    private void seed(String c, long owner) {
        String[] t = {"다", "가", "마", "나", "라"};
        for (int i = 0; i < t.length; i++) {
            accounts.exec(c, """
                    insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                    values (?, ?, ?, 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't')""", c, "ECO-" + (i + 1), "지시 " + t[i], owner);
            accounts.exec(c, """
                    insert into plm_ecr (comp_cd, ecr_no, title, reason_cd, requester_id, created_by, updated_by)
                    values (?, ?, ?, 'QUALITY', ?, 't', 't')""", c, "ECR-" + (i + 1), "요청 " + t[i], owner);
        }
        accounts.exec(c, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_no = 'ECO-3'");
    }

    private ResultActions grid(String base, RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(base + "/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private List<String> col(ResultActions r, String field) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*]." + field);
    }

    @Test
    void 지시_grid_쪽_경계_전체_건수_정렬_허용_목록_목록과_같은_조건() throws Exception {
        grid(ECO, me, "size", "2", "sort", "title,asc").andExpect(jsonPath("$.total").value(5));
        assertThat(col(grid(ECO, me, "size", "2", "page", "1", "sort", "title,asc"), "title")).containsExactly("지시 가", "지시 나");
        assertThat(col(grid(ECO, me, "size", "2", "page", "2", "sort", "title,asc"), "title")).containsExactly("지시 다", "지시 라");
        assertThat(col(grid(ECO, me, "size", "2", "page", "3", "sort", "title,asc"), "title")).containsExactly("지시 마");
        assertThat(col(grid(ECO, me, "size", "2", "page", "4", "sort", "title,asc"), "title")).isEmpty();
        // 기본 = 번호 내림차순 — 목록 API(id 내림차순)와 같은 순서
        List<String> listed = JsonPath.read(mvc.perform(get(ECO).with(me)).andReturn().getResponse().getContentAsString(), "$.data.rows[*].ecoNo");
        assertThat(col(grid(ECO, me, "size", "50"), "ecoNo")).containsExactlyElementsOf(listed);
        // 같은 조건: 상태·검색어
        grid(ECO, me, "status", "CANCELLED").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].ecoNo").value("ECO-3"));
        grid(ECO, me, "keyword", "가").andExpect(jsonPath("$.total").value(1));
        grid(ECO, me, "itemCd", "NOPE").andExpect(jsonPath("$.total").value(0));
        // 여러 정렬(상태 → 번호)
        assertThat(col(grid(ECO, me, "sort", "status,asc", "sort", "ecoNo,asc"), "ecoNo")).containsExactly("ECO-3", "ECO-1", "ECO-2", "ECO-4", "ECO-5");
        // 허용 밖·4개·쪽 크기·쪽 번호·상태 코드 → 400
        grid(ECO, me, "sort", "targetCnt,asc").andExpect(status().isBadRequest());
        grid(ECO, me, "sort", "comp_cd;drop table x,asc").andExpect(status().isBadRequest());
        grid(ECO, me, "sort", "title,asc", "sort", "status,asc", "sort", "ecoNo,asc", "sort", "ownerNm,asc").andExpect(status().isBadRequest());
        grid(ECO, me, "size", "100000").andExpect(status().isBadRequest());
        grid(ECO, me, "page", "0").andExpect(status().isBadRequest());
        grid(ECO, me, "status", "NOPE").andExpect(status().isBadRequest());
        // 열 정의
        mvc.perform(get(ECO + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("ecoNo", "title", "status", "targetCnt")));
    }

    @Test
    void 요청_grid_쪽_경계_정렬_목록과_같은_순서() throws Exception {
        grid(ECR, me, "size", "2").andExpect(jsonPath("$.total").value(5));
        assertThat(col(grid(ECR, me, "size", "3", "sort", "title,desc"), "title")).containsExactly("요청 마", "요청 라", "요청 다");
        List<String> listed = JsonPath.read(mvc.perform(get(ECR).with(me)).andReturn().getResponse().getContentAsString(), "$.data.rows[*].ecrNo");
        assertThat(col(grid(ECR, me, "size", "50"), "ecrNo")).containsExactlyElementsOf(listed);
        grid(ECR, me, "sort", "requesterNm,asc").andExpect(status().isBadRequest());
        // 빈 값 있는 키(품목 — left join)는 오름·내림 모두 빈 값이 뒤(QA 정렬 가드 뒤에도 예전 nulls last 순서 그대로)
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'IA', 'IA', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'IB', 'IB', 'FG', 'MAKE', 'EA', 't', 't')""", comp, comp);
        accounts.exec(comp, "update plm_ecr set item_id = (select item_id from mst_item where item_cd = 'IA') where ecr_no = 'ECR-4'");
        accounts.exec(comp, "update plm_ecr set item_id = (select item_id from mst_item where item_cd = 'IB') where ecr_no = 'ECR-2'");
        assertThat(col(grid(ECR, me, "sort", "itemCd,asc"), "ecrNo")).startsWith("ECR-4", "ECR-2").hasSize(5);
        assertThat(col(grid(ECR, me, "sort", "itemCd,desc"), "ecrNo")).startsWith("ECR-2", "ECR-4").hasSize(5);
        mvc.perform(get(ECR + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("ecrNo", "status", "ecoNo")));
    }

    @Test
    void 권한_없으면_403_B사는_A사_행을_못_본다() throws Exception {
        String other = accounts.newComp("PGRB");
        long bu = accounts.newUser(other, "engB", PW);
        accounts.grant(other, bu, "ENG", "plm-eco", "SEARCH");
        accounts.grant(other, bu, "ENG2", "plm-ecr", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "engB");
        grid(ECO, b).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        grid(ECR, b).andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "plm-ecr", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        grid(ECO, n).andExpect(status().isForbidden());
        mvc.perform(get(ECO + "/columns").with(n)).andExpect(status().isForbidden());
        // 요청 화면 권한만 있으면 요청 grid 는 보인다
        grid(ECR, n).andExpect(jsonPath("$.total").value(5));
    }
}
