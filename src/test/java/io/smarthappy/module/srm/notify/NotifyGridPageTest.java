package io.smarthappy.module.srm.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 통지 grid 지연 조인(QA offset M4) — 쪽 경계: 작은 쪽으로 끝까지 읽은 순서 = 한 번에 읽은 순서(빠짐·겹침 없음), 정렬별(기본 만든 시각·
 * 협력사·번호·토픽·상태). 같은 값이 많은 정렬(만든 시각 몇 개, 협력사 셋, 클레임 통지는 번호 칸이 claimNo)이라 PK 동점 처리가 드러난다.
 * 다른 토픽·다른 회사 통지는 섞이지 않는다.
 */
@AutoConfigureMockMvc
class NotifyGridPageTest extends PostgresTestBase {

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
        comp = accounts.newComp("NG");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", NotifyService.PROG, "SEARCH");
        me = accounts.as(comp, u, "buyer1");
        String other = accounts.newComp("NH");
        // SRM 통지 37건(발행·취소·클레임, 협력사 셋, 만든 시각 4가지, 상태 보냄·실패) + 같은 회사 다른 토픽 5건 + 다른 회사 SRM 통지 5건
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            s.execute(String.format("""
                    insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, sent_at, last_error, created_at, created_by)
                    select '%s', gen_random_uuid(), t.topic, 'K:' || g,
                           case when t.topic = 'SRM_CLAIM_SENT'
                                then jsonb_build_object('claimId', g, 'claimNo', 'CL-' || lpad(g::text, 3, '0'), 'custNm', '협력사 ' || (g %% 3))
                                else jsonb_build_object('poId', g, 'poNo', 'PO-' || lpad((g %% 7)::text, 3, '0'), 'rev', 0, 'custNm', '협력사 ' || (g %% 3),
                                                        'to', 'v@example.com') end,
                           case when g %% 4 = 0 then 'FAIL' else 'SENT' end, case when g %% 4 = 0 then null else now() end,
                           case when g %% 4 = 0 then 'x' end, date_trunc('day', now()) - ((g %% 4) || ' days')::interval, 't'
                      from generate_series(1, 37) g
                      cross join lateral (select case g %% 5 when 0 then 'SRM_PO_CANCELLED' when 1 then 'SRM_CLAIM_SENT' else 'SRM_PO_ISSUED' end as topic) t""",
                    comp));
            s.execute(String.format("""
                    insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, created_at, created_by)
                    select '%s', gen_random_uuid(), 'WMS_STOCK_CHANGED', 'X:' || g, jsonb_build_object('custNm', '협력사 0'), 'NEW', now() - interval '1 day', 't'
                      from generate_series(1, 5) g""", comp));
            s.execute(String.format("""
                    insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, created_at, created_by)
                    select '%s', gen_random_uuid(), 'SRM_PO_ISSUED', 'O:' || g, jsonb_build_object('poNo', 'PO-000', 'custNm', '협력사 0'), 'SENT',
                           now() - interval '1 day', 't'
                      from generate_series(1, 5) g""", other));
        }
        from = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - 10)::text", String.class);
        to = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + ")::text", String.class);
    }

    private List<Integer> read(String sort, String status, int size, int page) throws Exception {
        var b = get("/api/srm/notices/grid").param("from", from).param("to", to).param("size", String.valueOf(size)).param("page", String.valueOf(page))
                .param("total", "false").with(me);
        if (sort != null) {
            b = b.param("sort", sort.split(";"));
        }
        if (status != null) {
            b = b.param("status", status);
        }
        return JsonPath.read(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*].outboxId");
    }

    private void samePages(String sort, String status) throws Exception {
        List<Integer> all = read(sort, status, 100, 1);
        List<Integer> paged = new ArrayList<>();
        for (int p = 1; ; p++) {
            List<Integer> one = read(sort, status, 5, p);
            paged.addAll(one);
            if (one.size() < 5) {
                break;
            }
        }
        assertThat(paged).as("sort=%s status=%s", sort, status).containsExactlyElementsOf(all);
        assertThat(paged).doesNotHaveDuplicates();
    }

    @Test
    void 작은_쪽으로_끝까지_읽은_순서가_한_번에_읽은_순서와_같다() throws Exception {
        assertThat(read(null, null, 100, 1)).hasSize(37);
        samePages(null, null);
        samePages("custNm,asc", null);
        samePages("poNo,desc;createdAt,asc", null);
        samePages("topic,asc;custNm,desc", null);
        samePages("status,asc", null);
        samePages(null, "FAIL");
    }

    @Test
    void 기본_정렬은_목록_API_와_같고_칸은_바깥에서_뽑힌다() throws Exception {
        List<Integer> listIds = JsonPath.read(mvc.perform(get("/api/srm/notices").param("from", from).param("to", to).with(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data[*].outboxId");
        assertThat(read(null, null, 100, 1)).containsExactlyElementsOf(listIds);
        String g = mvc.perform(get("/api/srm/notices/grid").param("from", from).param("to", to).param("size", "100").param("total", "true")
                .param("sort", "poNo,asc").with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(g, "$.total")).intValue()).isEqualTo(37);
        List<String> nos = JsonPath.read(g, "$.list[*].poNo");
        assertThat(nos).hasSize(37).isSorted();
        List<String> custs = JsonPath.read(g, "$.list[*].custNm");
        assertThat(custs).allSatisfy(n -> assertThat(n).startsWith("협력사 "));
        List<String> topics = JsonPath.read(g, "$.list[*].topic");
        assertThat(topics).allSatisfy(t -> assertThat(t).startsWith("SRM_"));
    }

    @Test
    void 빈_값_있는_정렬_키는_오름차순_내림차순_모두_빈_값이_끝() throws Exception {
        // nulls last 글자 대신 "키 is null, 키" 로 고른다(QA nulls last 가드) — 순서는 그대로여야 한다
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            s.execute(String.format("""
                    insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, created_at, created_by)
                    select '%s', gen_random_uuid(), 'SRM_PO_ISSUED', 'N:' || g, jsonb_build_object('poId', g), 'SENT', now() - interval '2 days', 't'
                      from generate_series(1, 3) g""", comp));
        }
        for (String sort : new String[] {"custNm,asc", "custNm,desc", "poNo,asc", "poNo,desc"}) {
            String g = mvc.perform(get("/api/srm/notices/grid").param("from", from).param("to", to).param("size", "100").param("total", "false")
                    .param("sort", sort).with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> vals = JsonPath.read(g, "$.list[*]." + sort.split(",")[0]);
            assertThat(vals).as(sort).hasSize(40);
            assertThat(vals.subList(0, 37)).as(sort).doesNotContainNull();
            assertThat(vals.subList(37, 40)).as(sort).containsOnlyNulls();
        }
    }
}
