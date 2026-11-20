package io.smarthappy.module.srm.asn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * ASN grid 지연 조인(QA offset M5) — 쪽 경계: 작은 쪽으로 끝까지 읽은 순서 = 한 번에 읽은 순서(빠짐·겹침 없음), 정렬별(기본 도착 예정일·
 * 덜 받은 행 수·협력사 이름·상태), "늦은 ASN 만"·검색어 조건에서도. 같은 값이 많은 정렬(도착 예정일 몇 개, 행 수 1~3)이라 PK 동점 처리가 드러난다.
 */
@AutoConfigureMockMvc
class AsnGridPageTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    String from;
    String to;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PG");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "PUR", "srm-asn", "SEARCH");
        me = accounts.as(comp, u, "buyer1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        long cust1 = cust("V100", "가 상사");
        long cust2 = cust("V200", "나 상사");
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        long po = ((Number) JsonPath.read(mvc.perform(post("/api/srm/pos").with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\"" + due
                        + "\"}]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        long poLine = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
        long item = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT'", Long.class);
        // ASN 37건: 협력사 둘, 도착 예정일 4가지(지난 날 포함), 상태 작성 중·보냄, 행 1~3개(받은 것 없음 → 덜 받은 행 = 행 수)
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            s.execute(String.format("""
                    insert into srm_asn (comp_cd, asn_no, cust_id, plant_cd, ship_date, eta_date, status, sent_at, supplier_ref, created_by, updated_by)
                    select '%1$s', 'PG' || lpad(g::text, 3, '0'), case when g %% 3 = 0 then %2$d else %3$d end, 'P1',
                           current_date - 10, current_date - 5 + (g %% 4) * 3,
                           case when g %% 2 = 0 then 'SENT' else 'DRAFT' end, case when g %% 2 = 0 then now() end,
                           case when g %% 5 = 0 then 'DN-' || g end, 't', 't'
                      from generate_series(1, 37) g""", comp, cust2, cust1));
            s.execute(String.format("""
                    insert into srm_asn_line (comp_cd, asn_id, line_no, po_line_id, item_id, unit_cd, qty, supplier_lot_no, created_by, updated_by)
                    select a.comp_cd, a.asn_id, n, %d, %d, 'EA', 1, 'L' || n, 't', 't'
                      from srm_asn a cross join generate_series(1, 3) n
                     where a.comp_cd = '%s' and n <= 1 + (a.asn_id %% 3)""", poLine, item, comp));
            // 트리거를 끈 채 넣었으므로 덜 받은 행 수 칸(V185)을 같은 정의로 채운다
            s.execute(String.format("update srm_asn a set open_line_cnt = hb_srm_asn_open_cnt(a.asn_id) where a.comp_cd = '%s'", comp));
        }
        from = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - 30)::text", String.class);
        to = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 30)::text", String.class);
    }

    private long cust(String cd, String nm) {
        long id = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp, cd, nm);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, comp);
        return id;
    }

    private List<Integer> read(String sort, boolean late, String keyword, int size, int page) throws Exception {
        var b = get("/api/srm/asns/grid").param("from", from).param("to", to).param("size", String.valueOf(size)).param("page", String.valueOf(page))
                .param("total", "false").with(me);
        if (sort != null) {
            b = b.param("sort", sort.split(";"));
        }
        if (late) {
            b = b.param("late", "true");
        }
        if (keyword != null) {
            b = b.param("keyword", keyword);
        }
        return JsonPath.read(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*].asnId");
    }

    private void samePages(String sort, boolean late, String keyword) throws Exception {
        List<Integer> all = read(sort, late, keyword, 100, 1);
        List<Integer> paged = new ArrayList<>();
        for (int p = 1; ; p++) {
            List<Integer> one = read(sort, late, keyword, 5, p);
            paged.addAll(one);
            if (one.size() < 5) {
                break;
            }
        }
        assertThat(paged).as("sort=%s late=%s keyword=%s", sort, late, keyword).containsExactlyElementsOf(all);
        assertThat(paged).doesNotHaveDuplicates();
    }

    @Test
    void 작은_쪽으로_끝까지_읽은_순서가_한_번에_읽은_순서와_같다() throws Exception {
        assertThat(read(null, false, null, 100, 1)).hasSize(37);
        samePages(null, false, null);
        samePages("openLineCnt,desc", false, null);
        samePages("custNm,asc;etaDate,asc", false, null);
        samePages("status,asc;openLineCnt,asc", false, null);
        samePages(null, true, null);
        samePages("openLineCnt,asc", true, null);
        samePages(null, false, "DN-");
    }

    @Test
    void 기본_정렬은_목록_API_와_같고_행_수_칸은_바깥에서_계산된다() throws Exception {
        String list = mvc.perform(get("/api/srm/asns").param("from", from).param("to", to).with(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> listIds = JsonPath.read(list, "$.data[*].asnId");
        assertThat(read(null, false, null, 100, 1)).containsExactlyElementsOf(listIds);
        String g = mvc.perform(get("/api/srm/asns/grid").param("from", from).param("to", to).param("size", "100").param("total", "true").with(me))
                .andReturn().getResponse().getContentAsString();
        List<Integer> lines = JsonPath.read(g, "$.list[*].lineCnt");
        List<Integer> open = JsonPath.read(g, "$.list[*].openLineCnt");
        assertThat(lines).containsExactlyElementsOf(open).allSatisfy(n -> assertThat(n).isBetween(1, 3));
        assertThat(((Number) JsonPath.read(g, "$.total")).intValue()).isEqualTo(37);
        // 늦은 ASN = 보냄 + 덜 받은 행 + 도착 예정일 지남 — 목록 API 와 같은 집합
        List<Integer> lateList = JsonPath.read(mvc.perform(get("/api/srm/asns").param("from", from).param("to", to).param("late", "true").with(me))
                .andReturn().getResponse().getContentAsString(), "$.data[*].asnId");
        assertThat(read(null, true, null, 100, 1)).containsExactlyElementsOf(lateList).isNotEmpty();
    }
}
