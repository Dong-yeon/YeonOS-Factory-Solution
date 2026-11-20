package io.smarthappy.module.wms.asrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.messaging.InboundProcessor;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 자동창고 E4 조각 2 (docs/65 25장, 사용자 결정 Q-E4-1~4) — 출고(인계 때 원장·SHORT·OVER_PICK)·긴급(APPROVE·우선순위는 긴급만)·
 * 대사(조각 멱등·움직이는 용기 건너뜀·격리)·바로 조정(StockService.adjust 경유·한 번만·APPROVE). 정상 + 이상 사용 + A사로 B사 + 직접 SQL.
 */
@AutoConfigureMockMvc
class AsrsOutReconTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    OutboxRelay relay;

    @Autowired
    InboundProcessor processor;

    static final class Site {
        String comp;
        RequestPostProcessor admin;
        RequestPostProcessor clerk;
        long item;
        long inNode;
        long outNode;
        long storeNode;
        long deviceId;
        String key;
        long asrsId;
        long typeId;
    }

    Site a;
    HttpServer wes;
    final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        wes = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        wes.createContext("/wes/requests", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        wes.start();
        a = site("AO", "DIRECT");
    }

    @AfterEach
    void tearDown() {
        wes.stop(0);
    }

    Site site(String prefix, String conn) throws Exception {
        Site s = new Site();
        s.comp = accounts.newComp(prefix);
        long admin = accounts.newUser(s.comp, "admin1", PW);
        accounts.grant(s.comp, admin, "WMS", "wms-asrs", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(s.comp, admin, "ADM", "sys-terminal", "SEARCH", "SAVE");
        s.admin = accounts.as(s.comp, admin, "admin1");
        long clerk = accounts.newUser(s.comp, "clerk1", PW);
        accounts.grant(s.comp, clerk, "CLERK", "wms-asrs", "SEARCH", "SAVE");             // 저장은 되지만 승인(긴급·조정)은 없음
        s.clerk = accounts.as(s.comp, clerk, "clerk1");
        accounts.addPlant(s.comp, "P1", "1공장");
        s.item = accounts.query(s.comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'ZT-A', 'ZT 부품', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, s.comp);
        long wh = node(s, null, "WH", "ZT-WH", "ZT 창고");
        s.inNode = node(s, wh, "LOC", "ZT-IN", "투입구");
        s.outNode = node(s, wh, "LOC", "ZT-OUT", "출구");
        s.storeNode = node(s, wh, "LOC", "ZT-AS", "자동창고");
        accounts.exec(s.comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'AUTO_STORE', 't', 't')",
                s.storeNode, s.comp);
        accounts.exec(s.comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 50, 'EA', 'MANUAL', now(), 'KEY', 't')""", s.comp, s.item, s.inNode);
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"WES-ADP\",\"terminalNm\":\"WES\",\"plantCd\":\"P1\","
                + "\"terminalType\":\"COLLECTOR\"}").with(s.admin)).andExpect(status().isOk()).andReturn();
        s.deviceId = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.terminalId")).longValue();
        s.key = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        String url = "DIRECT".equals(conn) ? ",\"wesUrl\":\"http://127.0.0.1:" + wes.getAddress().getPort() + "/wes\"" : "";
        s.asrsId = id(s, post("/api/wms/asrs"), "{\"plantCd\":\"P1\",\"asrsCd\":\"AS1\",\"asrsNm\":\"1동 자동창고\",\"storeNodeId\":" + s.storeNode
                + ",\"inNodeId\":" + s.inNode + ",\"outNodeId\":" + s.outNode + ",\"terminalId\":" + s.deviceId + ",\"connMode\":\"" + conn + "\"" + url + "}");
        s.typeId = id(s, post("/api/wms/asrs/ctr-types"), "{\"typeCd\":\"BIN4\",\"typeNm\":\"4칸 빈\",\"lengthMm\":600,\"widthMm\":400,\"heightMm\":300,"
                + "\"slotCnt\":4}");
        return s;
    }

    // =====================================================================
    // 출고
    // =====================================================================

    @Test
    void 출고_인계_때_원장_칸_순으로_사본_차감_완료_뒤_COMPLETED_는_STALE() throws Exception {
        long ctr = storeIn(a, "BIN-1", "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":10},{\"slotNo\":2,\"itemCd\":\"ZT-A\",\"qty\":5}]");
        long req = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"zt-a\",\"qty\":12}]}");
        send(a, req).andExpect(jsonPath("$.data").value("SENT"));
        relay.relay(a.comp);
        String body = lastBody();
        assertThat(body).contains("\"type\":\"OUT\"").contains("\"itemCd\":\"ZT-A\"").doesNotContain("priority");   // 일반 = 칸 없음
        String key = key(a, req);
        cb(a, key, 1, "ACCEPTED", "-", null);
        cb(a, key, 1, "PICKED", "BIN-1", null);
        processor.drain(a.comp);
        assertThat(stock(a, a.storeNode)).isEqualTo("15");                              // 집음 = 원장 없음
        cb(a, key, 1, "HANDED_OVER", "BIN-1", "\"qty\":12");
        processor.drain(a.comp);
        assertThat(stock(a, a.storeNode)).isEqualTo("3");
        assertThat(stock(a, a.outNode)).isEqualTo("12");
        assertThat(str(a, "select string_agg(slot_no || ':' || trim_scale(qty), ',' order by slot_no) from wms_ctr_content where ctr_id = ?", ctr))
                .isEqualTo("2:3");
        assertThat(str(a, "select status || ':' || trim_scale(done_qty) from wms_asrs_req_line where req_id = ?", req)).isEqualTo("DONE:12");
        assertThat(str(a, "select string_agg(trx_type || ':' || trim_scale(qty), ',' order by trx_type) from inv_trx where src_type = 'ASRS_OUT'")).isEqualTo("MOVE_IN:12,MOVE_OUT:-12");
        cb(a, key, 1, "COMPLETED", "-", null);
        processor.drain(a.comp);
        assertThat(str(a, "select count(*)::text from wms_asrs_event where req_id = ? and event_cd = 'STALE'", req)).isEqualTo("1");
        // 라인 단위 상태에 용기 · 용기 단위 상태에 "-" = 거부
        long r2 = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":1}]}");
        send(a, r2);
        cb(a, key(a, r2), 1, "ACCEPTED", "BIN-1", null);
        cb(a, key(a, r2), 1, "HANDED_OVER", "-", "\"qty\":1");
        processor.drain(a.comp);
        assertThat(str(a, "select string_agg(result_cd, ',' order by inbound_id) from sys_inbound_msg where status = 'REJECTED'"))
                .isEqualTo("CTR_LEVEL,CTR_LEVEL");
    }

    @Test
    void Q_E4_2_덜_집고_완료는_SHORT_로_닫고_사건만_OVER_PICK_은_격리() throws Exception {
        long ctr = storeIn(a, "BIN-S", "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":20}]");
        long req = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":10}]}");
        send(a, req);
        String key = key(a, req);
        cb(a, key, 1, "HANDED_OVER", "BIN-S", "\"qty\":4,\"slot\":1");
        cb(a, key, 1, "COMPLETED", "-", null);
        processor.drain(a.comp);
        assertThat(str(a, "select status || ':' || trim_scale(done_qty) || ':' || last_reason from wms_asrs_req_line where req_id = ?", req))
                .isEqualTo("SHORT:4:SHORT");
        assertThat(progress(a, req)).isEqualTo("PARTIAL");
        assertThat(str(a, "select detail from wms_asrs_event where req_id = ? and event_cd = 'SHORT'", req)).isEqualTo("4/10");
        assertThat(stock(a, a.outNode)).isEqualTo("4");                                 // 남은 6 은 자동 재요청하지 않는다
        assertThat(str(a, "select count(*)::text from wms_asrs_req where req_type = 'OUT'")).isEqualTo("1");
        assertThat(sqlError(a.comp, "update wms_asrs_req_line set status = 'IN_PROGRESS' where req_id = ?", req))
                .isEqualTo("55000 ck_wms_asrs_req_line_final");
        // 라인보다 많이 인계 = 원장 없음 + 용기 격리
        long r2 = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":2}]}");
        send(a, r2);
        cb(a, key(a, r2), 1, "HANDED_OVER", "BIN-S", "\"qty\":5");
        processor.drain(a.comp);
        assertThat(str(a, "select status || ':' || exc_reason from wms_ctr where ctr_id = ?", ctr)).isEqualTo("QUARANTINE:OVER_PICK");
        assertThat(stock(a, a.outNode)).isEqualTo("4");
        // 격리 용기 내용(16)은 출고에 쓸 수 없다 — 열린 라인 2 도 잡혀 있음
        postJson(a, "/api/wms/asrs/reqs/out", "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":1}]}").andExpect(status().isConflict());
        // 입력: 없는 품번 · 수량 0 · 같은 품목 두 줄 · 없는 LOT
        postJson(a, "/api/wms/asrs/reqs/out", "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"NOPE\",\"qty\":1},{\"itemCd\":\"ZT-A\",\"qty\":0},"
                + "{\"itemCd\":\"ZT-A\",\"lotNo\":\"L-X\",\"qty\":1}]}")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItems("NOT_FOUND", "RANGE")));
    }

    // =====================================================================
    // 긴급 (Q-E4-4)
    // =====================================================================

    @Test
    void Q_E4_4_긴급만_우선순위를_보내고_APPROVE_만_붙인다_남용은_건수로() throws Exception {
        storeIn(a, "BIN-U", "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":10}]");
        long normal = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":1}]}");
        long urgent = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":2}]}");
        // 저장 권한만 있는 사람은 긴급을 못 붙인다(API 직접)
        mvc.perform(json(post("/api/wms/asrs/reqs/" + urgent + "/urgent"), "{\"rowVer\":0,\"reason\":\"고객 라인 정지\"}").with(a.clerk))
                .andExpect(status().isForbidden());
        postJson(a, "/api/wms/asrs/reqs/" + urgent + "/urgent", "{\"rowVer\":0,\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson(a, "/api/wms/asrs/reqs/" + urgent + "/urgent", "{\"rowVer\":0,\"reason\":\"고객 라인 정지\"}").andExpect(status().isOk());
        postJson(a, "/api/wms/asrs/reqs/" + urgent + "/urgent", "{\"rowVer\":1,\"reason\":\"다시\"}").andExpect(status().isConflict());
        long inReq = a.asrsId;
        long c2 = ctr(a, "BIN-U2");
        contents(a, c2, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":1}]");
        long in2 = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + inReq + ",\"ctrCds\":[\"BIN-U2\"]}");
        postJson(a, "/api/wms/asrs/reqs/" + in2 + "/urgent", "{\"rowVer\":0,\"reason\":\"입고 급함\"}")
                .andExpect(jsonPath("$.errors[0].code").value("URGENT_OUT_ONLY"));
        // 표시는 되돌리지 않는다(직접 SQL)
        assertThat(sqlError(a.comp, "update wms_asrs_req set urgent_yn = 'N', urgent_reason = null where req_id = ?", urgent))
                .isEqualTo("55000 ck_wms_asrs_req_urgent_fixed");
        assertThat(sqlError(a.comp, "update wms_asrs_req set urgent_yn = 'Y', urgent_reason = 'x' where req_id = ?", in2))
                .isEqualTo("23514 ck_wms_asrs_req_urgent");
        // 섞인 큐 — 일반은 칸 없음, 긴급만 URGENT
        send(a, normal);
        send(a, urgent);
        relay.relay(a.comp);
        String b1 = received.poll(5, TimeUnit.SECONDS);
        String b2 = received.poll(5, TimeUnit.SECONDS);
        String nk = key(a, normal);
        String nb = b1.contains(nk) ? b1 : b2;
        String ub = b1.contains(nk) ? b2 : b1;
        assertThat(nb).doesNotContain("priority");
        assertThat(ub).contains("\"priority\":\"URGENT\"");
        // 보낸 뒤에는 긴급 표시 못 함
        postJson(a, "/api/wms/asrs/reqs/" + normal + "/urgent", "{\"rowVer\":1,\"reason\":\"늦게\"}").andExpect(status().isConflict());
        mvc.perform(get("/api/wms/asrs/urgent-stats?days=7").with(a.clerk)).andExpect(jsonPath("$.data.outCnt").value(2))
                .andExpect(jsonPath("$.data.urgentCnt").value(1)).andExpect(jsonPath("$.data.byUser[0].cnt").value(1));
        assertThat(str(a, "select detail from wms_asrs_event where event_cd = 'URGENT'")).isEqualTo("고객 라인 정지");
        // SaaS 엣지가 당겨 가는 본문도 같다(일반 = 칸 없음)
        Site e = site("AF", "EDGE");
        storeInEdge(e, "BIN-E", 5);
        long er = id(e, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + e.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":1}]}");
        send(e, er).andExpect(jsonPath("$.data").value("QUEUED"));
        MvcResult c = ext(e.key, "/requests/claim", "{}").andReturn();
        assertThat(c.getResponse().getContentAsString()).contains("\"type\":\"OUT\"").doesNotContain("priority");
    }

    // =====================================================================
    // 대사 + 바로 조정 (Q-E4-1)
    // =====================================================================

    @Test
    void Q_E4_1_대사_차이는_격리_APPROVE_가_재고_조정_서비스로_한_번만_조정() throws Exception {
        long ctr = storeIn(a, "BIN-R", "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":10}]");
        // 조각 1(마지막 아님) → 받음, 같은 조각 재전송 → DUP
        String p1 = "{\"snapKey\":\"S-20261110\",\"partNo\":1,\"lastPart\":false,\"containers\":[{\"ctrCd\":\"BIN-R\",\"contents\":[{\"slot\":1,"
                + "\"itemCd\":\"ZT-A\",\"qty\":7}]}]}";
        ext(a.key, "/snapshot", p1).andExpect(jsonPath("$.data.result").value("RECEIVED"));
        ext(a.key, "/snapshot", p1).andExpect(jsonPath("$.data.result").value("DUP"));
        // 조각 2(마지막) — WES 만 아는 용기
        MvcResult done = ext(a.key, "/snapshot", "{\"snapKey\":\"S-20261110\",\"partNo\":2,\"lastPart\":true,\"containers\":[{\"ctrCd\":\"BIN-ZZ\","
                + "\"contents\":[{\"slot\":1,\"itemCd\":\"ZT-A\",\"qty\":3}]}]}").andExpect(jsonPath("$.data.result").value("DONE"))
                .andExpect(jsonPath("$.data.diffCnt").value(2)).andReturn();
        long recon = ((Number) JsonPath.read(done.getResponse().getContentAsString(), "$.data.reconId")).longValue();
        ext(a.key, "/snapshot", p1).andExpect(jsonPath("$.data.result").value("DUP"));                              // 끝난 회차
        assertThat(str(a, "select status || ':' || exc_reason from wms_ctr where ctr_id = ?", ctr)).isEqualTo("QUARANTINE:RECON_DIFF");
        long qty = diffId(a, recon, "QTY");
        long unk = diffId(a, recon, "UNKNOWN");
        // 승인 권한 없는 사람(API 직접) · 사유 없음 · 회사 사유 아님
        mvc.perform(json(post("/api/wms/asrs/recon-diffs/" + qty + "/adjust"), "{\"reasonCd\":\"LOSS\",\"rowVer\":0}").with(a.clerk))
                .andExpect(status().isForbidden());
        postJson(a, "/api/wms/asrs/recon-diffs/" + qty + "/adjust", "{\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(a, "/api/wms/asrs/recon-diffs/" + qty + "/adjust", "{\"reasonCd\":\"NOT_A_CODE\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        // 조정 — 원장 ADJ_OUT -3(재고 조정 서비스, 요청 id = ASRS-RECON-{diff}), 사본 7, 격리 해제
        postJson(a, "/api/wms/asrs/recon-diffs/" + qty + "/adjust", "{\"reasonCd\":\"LOSS\",\"remark\":\"현장 확인\",\"rowVer\":0}")
                .andExpect(status().isOk());
        assertThat(stock(a, a.storeNode)).isEqualTo("7");
        assertThat(str(a, "select trx_type || ':' || trim_scale(qty) || ':' || reason_cd || ':' || src_type || ':' || recorded_by from inv_trx "
                + "where client_req_id = ?", "ASRS-RECON-" + qty)).isEqualTo("ADJ_OUT:-3:LOSS:MANUAL:admin1");
        assertThat(str(a, "select trim_scale(qty)::text from wms_ctr_content where ctr_id = ?", ctr)).isEqualTo("7");
        assertThat(str(a, "select status || ':' || resolved_by || ':' || (resolved_at is not null) from wms_asrs_recon_diff where diff_id = ?", qty))
                .isEqualTo("ADJUSTED:admin1:true");
        assertThat(str(a, "select status from wms_ctr where ctr_id = ?", ctr)).isEqualTo("IN_SYSTEM");
        // 같은 차이를 두 번 — 409, 원장 한 번
        postJson(a, "/api/wms/asrs/recon-diffs/" + qty + "/adjust", "{\"reasonCd\":\"LOSS\",\"rowVer\":1}").andExpect(status().isConflict());
        postJson(a, "/api/wms/asrs/recon-diffs/" + qty + "/adjust", "{\"reasonCd\":\"LOSS\",\"rowVer\":0}").andExpect(status().isConflict());
        assertThat(str(a, "select count(*)::text from inv_trx where client_req_id like 'ASRS-RECON-%'")).isEqualTo("1");
        // 모르는 용기 = 조정 불가, 확인만(비고 필수)
        postJson(a, "/api/wms/asrs/recon-diffs/" + unk + "/adjust", "{\"reasonCd\":\"LOSS\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("NOT_ADJUSTABLE"));
        postJson(a, "/api/wms/asrs/recon-diffs/" + unk + "/dismiss", "{\"remark\":\"\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].field").value("remark"));
        mvc.perform(json(post("/api/wms/asrs/recon-diffs/" + unk + "/dismiss"), "{\"remark\":\"WES 정정 요청\",\"rowVer\":0}").with(a.clerk))
                .andExpect(status().isForbidden());
        postJson(a, "/api/wms/asrs/recon-diffs/" + unk + "/dismiss", "{\"remark\":\"WES 정정 요청\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get("/api/wms/asrs/recons/" + recon).with(a.clerk)).andExpect(jsonPath("$.data.head.openCnt").value(0))
                .andExpect(jsonPath("$.data.diffs.length()").value(2));
        // 직접 SQL: 끝난 줄 되돌림 · 측정값 바꾸기 · 조각 원문 지우기 · 조정 id 없이 ADJUSTED
        assertThat(sqlError(a.comp, "update wms_asrs_recon_diff set status = 'OPEN' where diff_id = ?", qty))
                .isEqualTo("55000 ck_wms_asrs_recon_diff_final");
        assertThat(sqlError(a.comp, "delete from wms_asrs_recon_part where recon_id = ?", recon)).startsWith("42501");
        long r2 = snapshotDone(a, "S-2", "BIN-R", 5);
        long d2 = diffId(a, r2, "QTY");
        assertThat(sqlError(a.comp, "update wms_asrs_recon_diff set wes_qty = 6 where diff_id = ?", d2)).isEqualTo("55000 ck_wms_asrs_recon_diff_final");
        assertThat(sqlError(a.comp, "update wms_asrs_recon_diff set status = 'ADJUSTED', resolved_by = 'x', resolved_at = now(), reason_cd = 'LOSS' "
                + "where diff_id = ?", d2)).isEqualTo("23514 ck_wms_asrs_recon_diff_adj");
        // 대사 뒤 사본이 바뀜(그 사이 출고 등) → 다시 대사(409), 원장 그대로
        accounts.exec(a.comp, "update wms_ctr_content set qty = 6 where ctr_id = ?", ctr);
        postJson(a, "/api/wms/asrs/recon-diffs/" + d2 + "/adjust", "{\"reasonCd\":\"LOSS\",\"rowVer\":0}").andExpect(status().isConflict());
        assertThat(stock(a, a.storeNode)).isEqualTo("7");
    }

    @Test
    void 움직이는_중인_용기는_대사에서_건너뛴다_입력_검사() throws Exception {
        storeIn(a, "BIN-M", "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":5}]");
        long req = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":2}]}");
        send(a, req);
        cb(a, key(a, req), 1, "PICKED", "BIN-M", null);                                // 출고 중 — 사본이 곧 바뀐다
        processor.drain(a.comp);
        ext(a.key, "/snapshot", "{\"snapKey\":\"S-M\",\"partNo\":1,\"lastPart\":true,\"containers\":[{\"ctrCd\":\"BIN-M\",\"contents\":[{\"slot\":1,"
                + "\"itemCd\":\"ZT-A\",\"qty\":3}]}]}").andExpect(jsonPath("$.data.diffCnt").value(0)).andExpect(jsonPath("$.data.skippedCnt").value(1));
        ext(a.key, "/snapshot", "{\"snapKey\":\"bad key!\",\"partNo\":1}").andExpect(jsonPath("$.errors[0].field").value("snapKey"));
        ext(a.key, "/snapshot", "{\"snapKey\":\"S-X\",\"partNo\":0}").andExpect(jsonPath("$.errors[0].field").value("partNo"));
    }

    @Test
    void A사로_B사_출고_콜백_대사_조정() throws Exception {
        long ctr = storeIn(a, "BIN-AB", "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":4}]");
        long req = id(a, post("/api/wms/asrs/reqs/out"), "{\"asrsId\":" + a.asrsId + ",\"lines\":[{\"itemCd\":\"ZT-A\",\"qty\":1}]}");
        send(a, req);
        long recon = snapshotDone(a, "S-A", "BIN-AB", 1);
        long diff = diffId(a, recon, "QTY");
        Site b = site("AQ", "DIRECT");
        // B사 WES 가 A사 출고 콜백 → 거부, A사 원장 그대로
        ext(b.key, "/callback", cbBody(key(a, req), 1, "HANDED_OVER", "BIN-AB", "\"qty\":1"));
        processor.drain(b.comp);
        assertThat(accounts.query(b.comp, "select result_cd from sys_inbound_msg where route_cd = 'WES_CALLBACK'", String.class)).isEqualTo("UNKNOWN_REQ");
        assertThat(stock(a, a.outNode)).isEqualTo("0");
        // B사 스냅숏에 A사 용기 코드 → B사 쪽 모르는 용기(UNKNOWN), A사 용기 그대로
        ext(b.key, "/snapshot", "{\"snapKey\":\"S-B\",\"partNo\":1,\"lastPart\":true,\"containers\":[{\"ctrCd\":\"BIN-AB\",\"contents\":[{\"slot\":1,"
                + "\"itemCd\":\"ZT-A\",\"qty\":9}]}]}").andExpect(jsonPath("$.data.diffCnt").value(1));
        assertThat(accounts.query(b.comp, "select diff_kind || ':' || (ctr_id is null) from wms_asrs_recon_diff", String.class)).isEqualTo("UNKNOWN:true");
        assertThat(str(a, "select status from wms_ctr where ctr_id = ?", ctr)).isEqualTo("QUARANTINE");     // A사 자기 대사 때문(B사 아님)
        // B사 사람이 A사 차이·회차·요청
        postJson(b, "/api/wms/asrs/recon-diffs/" + diff + "/adjust", "{\"reasonCd\":\"LOSS\",\"rowVer\":0}").andExpect(status().isNotFound());
        postJson(b, "/api/wms/asrs/recon-diffs/" + diff + "/dismiss", "{\"remark\":\"남의 것\",\"rowVer\":0}").andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/asrs/recons/" + recon).with(b.admin)).andExpect(status().isNotFound());
        postJson(b, "/api/wms/asrs/reqs/" + req + "/urgent", "{\"rowVer\":1,\"reason\":\"남의 것\"}").andExpect(status().isNotFound());
        assertThat(str(a, "select status from wms_asrs_recon_diff where diff_id = ?", diff)).isEqualTo("OPEN");
        // 직접 SQL: B사 범위에서 A사 회차에 차이 줄 · A사 회차에 조각
        assertThat(sqlError(b.comp, "insert into wms_asrs_recon_diff (comp_cd, recon_id, asrs_id, ctr_cd, slot_no, item_cd, our_qty, wes_qty, diff_kind) "
                + "values (?, ?, ?, 'X', 1, 'ZT-A', 0, 1, 'UNKNOWN')", b.comp, recon, b.asrsId)).isEqualTo("23503 fk_wms_asrs_recon_diff_comp");
        assertThat(sqlError(b.comp, "insert into wms_asrs_recon_part (comp_cd, recon_id, part_no, body) values (?, ?, 9, '[]')", b.comp, recon))
                .isEqualTo("23503 fk_wms_asrs_recon_part_comp");
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    /** 용기 정의 → 입고 요청 → 보내기 → STORED(설치형) — 자동창고 안 용기 */
    long storeIn(Site s, String cd, String lines) throws Exception {
        long ctr = ctr(s, cd);
        contents(s, ctr, lines).andExpect(status().isOk());
        long req = id(s, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + s.asrsId + ",\"ctrCds\":[\"" + cd + "\"]}");
        send(s, req);
        relay.relay(s.comp);
        received.clear();
        cb(s, key(s, req), 1, "STORED", cd, null);
        processor.drain(s.comp);
        assertThat(str(s, "select status from wms_ctr where ctr_id = ?", ctr)).isEqualTo("IN_SYSTEM");
        return ctr;
    }

    void storeInEdge(Site s, String cd, int qty) throws Exception {
        long ctr = ctr(s, cd);
        contents(s, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":" + qty + "}]");
        long req = id(s, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + s.asrsId + ",\"ctrCds\":[\"" + cd + "\"]}");
        send(s, req);
        ext(s.key, "/requests/claim", "{}");
        cb(s, key(s, req), 1, "STORED", cd, null);
        processor.drain(s.comp);
    }

    long snapshotDone(Site s, String snapKey, String ctrCd, int qty) throws Exception {
        MvcResult m = ext(s.key, "/snapshot", "{\"snapKey\":\"" + snapKey + "\",\"partNo\":1,\"lastPart\":true,\"containers\":[{\"ctrCd\":\"" + ctrCd
                + "\",\"contents\":[{\"slot\":1,\"itemCd\":\"ZT-A\",\"qty\":" + qty + "}]}]}").andExpect(jsonPath("$.data.result").value("DONE")).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.reconId")).longValue();
    }

    long diffId(Site s, long recon, String kind) {
        return accounts.query(s.comp, "select diff_id from wms_asrs_recon_diff where recon_id = ? and diff_kind = ? order by diff_id limit 1", Long.class,
                recon, kind);
    }

    String lastBody() throws InterruptedException {
        String b = received.poll(5, TimeUnit.SECONDS);
        assertThat(b).isNotNull();
        return b;
    }

    long node(Site s, Long parent, String type, String cd, String nm) {
        return accounts.query(s.comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, s.comp, parent, type, cd, nm);
    }

    String stock(Site s, long node) {
        return str(s, "select coalesce(trim_scale(sum(qty))::text, '0') from inv_stock where node_id = ? and item_id = ?", node, s.item);
    }

    String progress(Site s, long req) throws Exception {
        MvcResult m = mvc.perform(get("/api/wms/asrs/reqs/" + req).with(s.admin)).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.progress");
    }

    String key(Site s, long req) {
        return str(s, "select req_key::text from wms_asrs_req where req_id = ?", req);
    }

    long ctr(Site s, String cd) throws Exception {
        return id(s, post("/api/wms/asrs/ctrs"), "{\"asrsId\":" + s.asrsId + ",\"ctrCd\":\"" + cd + "\",\"typeId\":" + s.typeId + "}");
    }

    ResultActions contents(Site s, long ctr, String lines) throws Exception {
        String rv = str(s, "select row_ver::text from wms_ctr where ctr_id = ?", ctr);
        return mvc.perform(json(put("/api/wms/asrs/ctrs/" + ctr + "/contents"), "{\"lines\":" + lines + ",\"rowVer\":" + rv + "}").with(s.admin));
    }

    ResultActions send(Site s, long req) throws Exception {
        String rv = str(s, "select row_ver::text from wms_asrs_req where req_id = ?", req);
        return postJson(s, "/api/wms/asrs/reqs/" + req + "/send", "{\"rowVer\":" + rv + "}");
    }

    static String cbBody(String key, int lineNo, String status, String ctrCd, String extra) {
        return "{\"reqKey\":\"" + key + "\",\"lineNo\":" + lineNo + ",\"ctrCd\":\"" + ctrCd + "\",\"status\":\"" + status + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    ResultActions cb(Site s, String key, int lineNo, String status, String ctrCd, String extra) throws Exception {
        return ext(s.key, "/callback", cbBody(key, lineNo, status, ctrCd, extra)).andExpect(status().isOk());
    }

    ResultActions ext(String key, String path, String body) throws Exception {
        return mvc.perform(post(AsrsExtController.BASE + path).header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long id(Site s, MockHttpServletRequestBuilder b, String body) throws Exception {
        MvcResult m = mvc.perform(json(b, body).with(s.admin)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    ResultActions postJson(Site s, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(s.admin));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String str(Site s, String sql, Object... args) {
        return accounts.query(s.comp, sql, String.class, args);
    }

    String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }
}
