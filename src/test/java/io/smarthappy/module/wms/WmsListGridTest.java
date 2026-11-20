package io.smarthappy.module.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * WMS 주 목록 그리드 API (UI 단계 3 ①, 결정 docs/29 #45) — 출하 `/api/wms/ships/grid`·실사 `/api/wms/counts/grid` + `/columns`.
 * 기존 목록 API 는 그대로(응답 모양·건수), 새 API 는 페이징 경계·정렬 허용 열·조건·건수, 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class WmsListGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String SHIPS = "/api/wms/ships";
    private static final String COUNTS = "/api/wms/counts";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("GR");
        admin = user(comp, "admin1", true);
        seed(comp, 7, 5);
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "WMS", "wms-ship", "SEARCH");
            accounts.grant(c, u, "WMS", "wms-count", "SEARCH");
            accounts.grant(c, u, "WMS", "wms-stock", "SEARCH");
            accounts.grant(c, u, "WMS", "wms-trx", "SEARCH");
            accounts.grant(c, u, "WMS", "wms-print-job", "SEARCH");
            accounts.grant(c, u, "WMS", "wms-label", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /** 출하 n 건(예정일 n 일 → 1 일, 마지막 하나 취소)·실사 m 건(DRAFT) */
    private void seed(String c, int ships, int counts) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C-001', '고객', 'SALES', 't', 't')", c);
        long wh = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'WH', 'WH', 'WH', 't', 't') returning node_id""", Long.class, c);
        long stg = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', 'STG', 'STG', 't', 't') returning node_id""", Long.class, c, wh);
        accounts.exec(c, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, c);
        for (int i = 1; i <= ships; i++) {
            accounts.exec(c, """
                    insert into wms_ship (comp_cd, plant_cd, ship_no, cust_id, stage_node_id, plan_date, created_by, updated_by)
                    select ?, 'P1', ?, cust_id, ?, date '2026-11-01' + ?, 't', 't' from mst_cust where cust_cd = 'C-001'""",
                    c, "SHP-" + i, stg, i);
        }
        accounts.exec(c, "update wms_ship set status = 'CANCELLED', cancel_reason = '시험' where ship_no = ?", "SHP-" + ships);
        // 라벨 템플릿 3(LOC 2·ITEM 1, 하나 미사용)·프린터 2
        accounts.exec(c, """
                insert into wms_label_tpl (comp_cd, label_type, tpl_cd, tpl_nm, width_mm, height_mm, body, default_yn, use_yn, created_by, updated_by)
                values (?, 'LOC', 'T-LOC-1', '위치 1', 50, 30, '^XA^XZ', 'Y', 'Y', 't', 't'), (?, 'LOC', 'T-LOC-2', '위치 2', 50, 30, '^XA^XZ', 'N', 'N', 't', 't'),
                       (?, 'ITEM', 'T-ITEM-1', '품목 1', 50, 30, '^XA^XZ', 'Y', 'Y', 't', 't')""", c, c, c);
        accounts.exec(c, """
                insert into wms_printer (comp_cd, plant_cd, printer_cd, printer_nm, host, usage, created_by, updated_by)
                values (?, 'P1', 'PR-B', '프린터 B', '10.0.0.8', 'LOC', 't', 't'), (?, 'P1', 'PR-A', '프린터 A', '10.0.0.9', 'ANY', 't', 't')""", c, c);
        for (int i = 1; i <= counts; i++) {
            accounts.exec(c, """
                    insert into wms_count (comp_cd, plant_cd, count_no, count_type, scope_node_id, tol_pct, created_by, updated_by)
                    values (?, 'P1', ?, 'CYCLE', ?, 0, 't', 't')""", c, "CNT-" + i, wh);
        }
    }

    /** 조회 — 쿼리 문자열은 param 으로(정렬 값 "필드,방향" 의 쉼표가 목록 구분으로 나뉘지 않게, hb.grid 가 보내는 모양과 같음) */
    private MockHttpServletRequestBuilder req(String url) {
        int q = url.indexOf('?');
        MockHttpServletRequestBuilder b = get(q < 0 ? url : url.substring(0, q));
        if (q >= 0) {
            for (String kv : url.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                b = b.param(kv.substring(0, e), kv.substring(e + 1));
            }
        }
        return b;
    }

    private MvcResult ok(RequestPostProcessor who, String url) throws Exception {
        MvcResult r = mvc.perform(req(url).with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url + " → " + r.getResponse().getContentAsString()).isEqualTo(200);
        return r;
    }

    private static List<String> field(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 출하_그리드는_예정일_내림차순_페이지와_전체_건수를_주고_페이지_경계에서_빠지거나_겹치지_않는다() throws Exception {
        MvcResult p1 = ok(admin, SHIPS + "/grid?plantCd=P1&page=1&size=3");
        assertThat(field(p1, "$.list[*].shipNo")).containsExactly("SHP-7", "SHP-6", "SHP-5");
        assertThat(((Number) JsonPath.read(p1.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(7);
        assertThat(field(ok(admin, SHIPS + "/grid?plantCd=P1&page=2&size=3"), "$.list[*].shipNo")).containsExactly("SHP-4", "SHP-3", "SHP-2");
        assertThat(field(ok(admin, SHIPS + "/grid?plantCd=P1&page=3&size=3"), "$.list[*].shipNo")).containsExactly("SHP-1");
        assertThat(field(ok(admin, SHIPS + "/grid?plantCd=P1&page=4&size=3"), "$.list[*].shipNo")).isEmpty();
        // 정렬 허용 열·여러 키, 상태 조건, total=false
        assertThat(field(ok(admin, SHIPS + "/grid?plantCd=P1&sort=shipNo,asc&size=2"), "$.list[*].shipNo")).containsExactly("SHP-1", "SHP-2");
        assertThat(field(ok(admin, SHIPS + "/grid?plantCd=P1&sort=status,asc&sort=planDate,asc&size=1"), "$.list[*].shipNo"))
                .containsExactly("SHP-7");                                                        // CANCELLED 가 DRAFT 보다 앞
        assertThat(field(ok(admin, SHIPS + "/grid?plantCd=P1&status=CANCELLED"), "$.list[*].shipNo")).containsExactly("SHP-7");
        mvc.perform(req(SHIPS + "/grid?plantCd=P1&total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
        // 기존 목록 API 는 그대로(전체 목록, 예정일 내림차순)
        assertThat(field(ok(admin, SHIPS + "?plantCd=P1"), "$.list[*].shipNo")).hasSize(7).startsWith("SHP-7");
    }

    @Test
    void 실사_그리드는_만든_순_내림차순_페이지와_건수_정렬_허용_열() throws Exception {
        MvcResult p = ok(admin, COUNTS + "/grid?plantCd=P1&page=1&size=2");
        assertThat(field(p, "$.list[*].countNo")).containsExactly("CNT-5", "CNT-4");
        assertThat(((Number) JsonPath.read(p.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(5);
        assertThat(field(ok(admin, COUNTS + "/grid?plantCd=P1&page=3&size=2"), "$.list[*].countNo")).containsExactly("CNT-1");
        assertThat(field(ok(admin, COUNTS + "/grid?plantCd=P1&sort=countNo,asc&size=2"), "$.list[*].countNo")).containsExactly("CNT-1", "CNT-2");
        assertThat(field(ok(admin, COUNTS + "/grid?plantCd=P1&status=POSTED"), "$.list[*].countNo")).isEmpty();
        assertThat(field(ok(admin, COUNTS + "?plantCd=P1"), "$.list[*].countNo")).hasSize(5);
    }

    @Test
    void 열_정의는_정렬_표시가_정렬_허용_열과_같고_문구는_로캘을_따른다() throws Exception {
        MvcResult s = ok(admin, SHIPS + "/columns");
        assertThat(field(s, "$.list[?(@.sortable == true)].field")).containsExactlyInAnyOrder("shipNo", "custCd", "planDate", "status", "createdAt");
        assertThat(field(s, "$.list[?(@.field == 'shipNo')].title")).containsExactly("출하 번호");
        MvcResult c = ok(admin, COUNTS + "/columns");
        assertThat(field(c, "$.list[?(@.sortable == true)].field")).containsExactlyInAnyOrder("countNo", "countType", "status", "createdAt");
    }

    @Test
    void 기존_그리드도_정렬_키_하나는_쉼표째_받는다_재고_원장_인쇄_이력() throws Exception {
        // "필드,방향" 하나만 보내면 List<String> 바인딩이 쉼표로 쪼개 400 이던 회귀(그리드 머리 한 번 클릭 = 정렬 키 하나)
        ok(admin, "/api/wms/stock?plantCd=P1&sort=itemCd,desc");
        ok(admin, "/api/wms/trx?plantCd=P1&from=2026-10-01&to=2026-10-22&sort=occurredAt,asc");
        ok(admin, "/api/wms/print-jobs?from=2026-10-01&to=2026-10-22&sort=requestedAt,asc");
        mvc.perform(req("/api/wms/stock?plantCd=P1&sort=spec,asc").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 라벨_템플릿_프린터_그리드는_조건_정렬_페이지와_건수_기본_정렬은_유형_코드() throws Exception {
        MvcResult t = ok(admin, "/api/wms/labels/grid?size=2");
        assertThat(field(t, "$.list[*].tplCd")).containsExactly("T-ITEM-1", "T-LOC-1");
        assertThat(((Number) JsonPath.read(t.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(3);
        assertThat(field(ok(admin, "/api/wms/labels/grid?size=2&page=2"), "$.list[*].tplCd")).containsExactly("T-LOC-2");
        assertThat(field(ok(admin, "/api/wms/labels/grid?labelType=LOC&useYn=Y"), "$.list[*].tplCd")).containsExactly("T-LOC-1");
        assertThat(field(ok(admin, "/api/wms/labels/grid?sort=tplNm,desc&size=1"), "$.list[*].tplCd")).containsExactly("T-ITEM-1");
        assertThat(field(ok(admin, "/api/wms/printers/grid"), "$.list[*].printerCd")).containsExactly("PR-A", "PR-B");
        assertThat(field(ok(admin, "/api/wms/printers/grid?sort=usage,desc"), "$.list[*].printerCd")).containsExactly("PR-B", "PR-A");
        assertThat(field(ok(admin, "/api/wms/printers/grid?plantCd=P2"), "$.list[*].printerCd")).isEmpty();
        assertThat(field(ok(admin, "/api/wms/labels/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("labelType", "tplCd", "tplNm", "updatedAt");
        assertThat(field(ok(admin, "/api/wms/printers/columns"), "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("plantCd", "printerCd", "printerNm", "usage", "updatedAt");
        // 기존 목록 API 는 그대로(전체)
        assertThat(field(ok(admin, "/api/wms/labels"), "$.data[*].tplCd")).hasSize(3);
        // 거부: 허용 밖 정렬·없는 유형·사용 여부 값
        mvc.perform(req("/api/wms/labels/grid?sort=body,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req("/api/wms/labels/grid?labelType=NOPE").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req("/api/wms/printers/grid?useYn=X").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(req("/api/wms/printers/grid?sort=host,asc").with(admin)).andExpect(status().isBadRequest());
    }

    // =====================================================================
    // 거부 · A사로 B사 · 권한
    // =====================================================================

    @Test
    void B_정렬_허용_밖_열_네_개_이상_정렬_페이지_0_크기_초과는_400() throws Exception {
        for (String q : List.of("sort=custNm,asc", "sort=ship_no,asc", "sort=shipNo,up", "sort=shipNo&sort=planDate&sort=status&sort=custCd",
                "page=0", "size=0", "size=100000", "sort=shipNo,asc&sort=shipNo,desc")) {
            mvc.perform(req(SHIPS + "/grid?plantCd=P1&" + q).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(req(COUNTS + "/grid?plantCd=P1&sort=lineCount,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(SHIPS + "/grid?plantCd=NOPE").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get(SHIPS + "/grid").with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("GB");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, 2, 1);
        MvcResult r = ok(bAdmin, SHIPS + "/grid?plantCd=P1");
        assertThat(field(r, "$.list[*].shipNo")).containsExactly("SHP-2", "SHP-1");
        assertThat(((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(2);
        assertThat(field(ok(bAdmin, COUNTS + "/grid?plantCd=P1"), "$.list[*].countNo")).containsExactly("CNT-1");
        assertThat(((Number) JsonPath.read(ok(admin, SHIPS + "/grid?plantCd=P1").getResponse().getContentAsString(), "$.total")).intValue())
                .isEqualTo(7);
        assertThat(field(ok(bAdmin, "/api/wms/labels/grid"), "$.list[*].tplCd")).hasSize(3);           // B사 자기 템플릿 3개만
        assertThat(((Number) JsonPath.read(ok(bAdmin, "/api/wms/printers/grid").getResponse().getContentAsString(), "$.total")).intValue())
                .isEqualTo(2);
        RequestPostProcessor noAuth = user(comp, "viewer", false);
        mvc.perform(get(SHIPS + "/grid?plantCd=P1").with(noAuth)).andExpect(status().isForbidden());
        mvc.perform(get(COUNTS + "/columns").with(noAuth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/labels/grid").with(noAuth)).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/printers/columns").with(noAuth)).andExpect(status().isForbidden());
    }
}
