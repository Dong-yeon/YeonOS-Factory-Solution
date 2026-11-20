package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * MES 확인(WMS F1 #634, docs/53 — 상위 배정 2026-10-28): 실제 POP 실적 투입에서, 그 LOT 의 가용 재고가 출하 대기(STAGING) 칸에만 있으면
 * 409 INSUFFICIENT_STOCK + "출하 대기 칸 재고는 먼저 일반 칸으로 옮기세요" 안내(응답 details)가 POP 까지 그대로 오고, 실적·계보는 남지 않는다.
 * 일반 칸으로 옮기면(여기선 칸 용도를 STORAGE 로) 같은 요청 id 로 다시 보내 투입된다. QA 고정물(QaPopFixture)을 쓰려고 qa 패키지에 둔다(MES 소유 시험)
 */
@AutoConfigureMockMvc
class MesStagingInputQaTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    QaPopFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new QaPopFixture(mvc, accounts, "QS");
    }

    @Test
    void 출하_대기_칸에만_있는_LOT_투입은_409와_옮기라는_안내_옮기면_같은_요청으로_투입() throws Exception {
        String rm = f.receive("RM", "10");
        accounts.exec(f.comp, "update wms_loc_attr set loc_usage = 'STAGING' where node_id = ?", f.a1);   // 그 LOT 재고 = 출하 대기 칸에만
        f.start(f.pop1, f.spot);
        String req = QaPopFixture.req();
        String body = "\"goodQty\":\"3\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"3\"}]";
        f.result(f.pop1, f.spot, req, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                // 시험 요청 로케일에 따라 ko/en — 어느 쪽이든 출하 대기 칸 수량·옮기라는 안내
                .andExpect(jsonPath("$.details[0]").value(Matchers.containsString("(10)")))
                .andExpect(jsonPath("$.details[0]").value(Matchers.anyOf(Matchers.containsString("먼저 일반 칸으로"),
                        Matchers.containsString("to a regular location first"))));
        assertThat(f.count("select count(*) from prd_result where wo_proc_id = ?", f.spot)).isZero();
        assertThat(f.count("select count(*) from lot_genealogy where parent_lot_id = ?", f.lotId(rm))).isZero();
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("10");

        // 일반 칸으로(칸 용도 STORAGE) → 같은 요청 id 로 다시 → 투입·차감
        accounts.exec(f.comp, "update wms_loc_attr set loc_usage = 'STORAGE' where node_id = ?", f.a1);
        f.result(f.pop1, f.spot, req, body).andExpect(status().isOk());
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("7");
    }
}
