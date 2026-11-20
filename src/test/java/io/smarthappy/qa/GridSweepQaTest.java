package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-10-22 ⑥ — UI 단계 3 의 `/grid` 끝점이 모듈마다 계속 늘어서(2회차 기준 57개) 표를 손으로 두지 않고
 * **실행 시점에 스프링 매핑에서 `GET …/grid` 를 모두 찾아** 같은 이상 사용을 돌린다. 정렬 키는 짝 `/columns` 의 sortable 열에서 고른다.
 * 새 끝점이 생기면 이 시험이 저절로 덮는다(빠지는 끝점이 없게). 끝점별 고정 데이터가 필요한 A사로 B사는 GridApiQaTest(프린터) 몫.
 * 분석 docs/qa/2026-10-22_ui-stage3.md 2회차.
 */
@AutoConfigureMockMvc
class GridSweepQaTest extends PostgresTestBase {

    static final String PW = "Cobalt-river-64";
    /** 끝점이 필수로 받는 조건(없으면 400 이 설계) — 화면이 항상 보내는 값 */
    static final Map<String, String[]> REQUIRED = Map.of(
            "/api/admin/codes/grid", new String[] {"grpCd", "PAY_TERMS"},                 // 회사 확장 그룹만(시스템 그룹은 404 — 설계)
            "/api/master/class-props/classes/grid", new String[] {"classType", "ITEM"},
            "/api/cms/equip-card/history/grid", new String[] {"equipCd", "QS-E1"},       // 설비 이력 카드 = 설비 한 대(기간은 아래 range)
            "/api/master/work-cal/grid", new String[] {"month", "2026-12"},              // 휴일 달력 예외 목록 = 그 달
            "/api/sales/forecasts/revs/grid", new String[] {"custCd", "QS-C1"},
            "/api/production/serial-trace/grid", new String[] {"itemCd", "QS-PART", "partSerialNo", "QS-SN-NONE"});   // 부품 역추적 = 부품 LOT 또는 (품목 + 부품 시리얼) 하나가 기준(docs/77) — 장착 이력이 없으면 빈 목록

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    String comp;
    RequestPostProcessor admin;
    RequestPostProcessor nobody;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QS");
        accounts.addPlant(comp, "P1", "1공장");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "QADM", "sys-user", "SEARCH");
        accounts.exec(comp, "update sys_auth_group set admin_yn = 'Y' where auth_grp_cd = 'QADM'");   // 회사 관리자 = 모든 화면(행 없이)
        admin = accounts.as(comp, u, "admin1");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) "
                + "values (?, 'QS-C1', 'QS 고객', 'SALES', 't', 't')", comp);
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'QS-CL', 'QS', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'QS-E1', 'QS 설비', ?, 't', 't')",
                comp, cls);
        accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) "
                + "values (?, 'QS-PART', 'QS 부품', 'RM', 'BUY', 'EA', 't', 't')", comp);   // 시리얼 역추적 기준 품목
        long n = accounts.newUser(comp, "none1", PW);
        nobody = accounts.as(comp, n, "none1");
    }

    /** 매핑에서 찾은 GET …/grid (경로 변수 없는 것) */
    List<String> gridPaths() {
        TreeSet<String> out = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, hm) -> {
            if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
                return;
            }
            for (String p : info.getPatternValues()) {
                if (p.endsWith("/grid") && !p.contains("{") && !p.startsWith("/dev/")) {
                    out.add(p);
                }
            }
        });
        return new ArrayList<>(out);
    }

    @Test
    void 매핑에서_찾은_grid_끝점이_50개_이상_빠짐없이_돈다() {
        assertThat(gridPaths()).hasSizeGreaterThanOrEqualTo(50).contains("/api/admin/codes/grid", "/api/cms/mwos/grid", "/api/srm/pos/grid");
    }

    private MockHttpServletRequestBuilder req(String path, boolean range, String... params) {
        MockHttpServletRequestBuilder b = get(path).param("plantCd", "P1");
        String[] need = REQUIRED.get(path);
        if (need != null) {
            for (int i = 0; i + 1 < need.length; i += 2) {   // 이름·값 짝 여럿
                b = b.param(need[i], need[i + 1]);
            }
        }
        if (range) {
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            b = b.param("from", today.minusDays(7).toString()).param("to", today.toString());
        }
        for (int i = 0; i + 1 < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return b;
    }

    private MvcResult call(String path, boolean range, RequestPostProcessor who, String... params) throws Exception {
        return mvc.perform(req(path, range, params).with(who)).andReturn();
    }

    @TestFactory
    Stream<DynamicTest> 모든_grid_끝점_정렬_경계_권한() {
        return gridPaths().stream().map(path -> DynamicTest.dynamicTest(path, () -> {
            List<String> bad = new ArrayList<>();
            // 기준: 200. 기간 필수(INVALID_RANGE)면 회사 날짜 7일 범위로
            MvcResult base = call(path, false, admin);
            String b0 = base.getResponse().getContentAsString();
            // 기간 필수 — 끝점에 따라 INVALID_RANGE 또는 from/to REQUIRED(관찰: 같은 상황의 코드가 둘)
            boolean range = base.getResponse().getStatus() == 400 && (b0.contains("INVALID_RANGE") || b0.contains("\"field\":\"from\""));
            if (range) {
                base = call(path, true, admin);
            }
            assertThat(base.getResponse().getStatus()).as(path + " 기준 응답 " + base.getResponse().getContentAsString()).isEqualTo(200);
            // 정렬 가능한 열(짝 /columns)
            List<String> sortable = List.of();
            MvcResult cols = mvc.perform(get(path.substring(0, path.length() - "/grid".length()) + "/columns").with(admin)).andReturn();
            if (cols.getResponse().getStatus() == 200) {
                String body = cols.getResponse().getContentAsString();
                List<String> s = JsonPath.read(body, "$..[?(@.sortable == true)].field");
                sortable = s;
            } else {
                bad.add("/columns " + cols.getResponse().getStatus());
            }
            if (!sortable.isEmpty()) {
                String f = sortable.get(0);
                expect(bad, "정렬 키 하나(쉼표)", call(path, range, admin, "sort", f + ",desc"), 200);
                expect(bad, "정렬 방향 없음", call(path, range, admin, "sort", f), 200);
                if (sortable.size() > 1) {
                    expect(bad, "두 키", call(path, range, admin, "sort", f + ",asc", "sort", sortable.get(1) + ",desc"), 200);
                }
                expect(bad, "같은 키 두 번", call(path, range, admin, "sort", f + ",asc", "sort", f + ",desc"), 400);
                expect(bad, "방향 형식", call(path, range, admin, "sort", f + ",sideways"), 400);
            }
            expect(bad, "허용 밖 정렬", call(path, range, admin, "sort", "pwdHash,asc"), 400);
            expect(bad, "정렬 4개", call(path, range, admin, "sort", "a", "sort", "b", "sort", "c", "sort", "d"), 400);
            expect(bad, "page=0", call(path, range, admin, "page", "0"), 400);
            expect(bad, "size=501", call(path, range, admin, "size", "501"), 400);
            expect(bad, "page=abc", call(path, range, admin, "page", "abc"), 400);
            expect(bad, "size=500", call(path, range, admin, "size", "500"), 200);
            expect(bad, "권한 없음", call(path, range, nobody), 403);
            assertThat(bad).as(path).isEmpty();
        }));
    }

    private static void expect(List<String> bad, String name, MvcResult r, int want) throws Exception {
        int got = r.getResponse().getStatus();
        if (got != want) {
            String body = r.getResponse().getContentAsString();
            bad.add(name + ": 기대 " + want + " 실제 " + got + " " + (body.length() > 160 ? body.substring(0, 160) : body));
        }
    }
}
