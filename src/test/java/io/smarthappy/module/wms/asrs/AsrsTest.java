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
 * 자동창고 E4 조각 1 (docs/65 23장) — 설치형(DIRECT: 서버 → 가짜 WES HTTP, WES → /ext/v1/wes/callback)·SaaS(EDGE: 엣지 claim,
 * /ext/v1/edge/messages) 두 길. 정상 + 이상 사용(B 입력·C 같은 콜백 297회·D 늦은 상태·F 흐름·G 오류 동반 완료·재고 사라짐) + A사로 B사 + 직접 SQL.
 */
@AutoConfigureMockMvc
class AsrsTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    OutboxRelay relay;

    @Autowired
    InboundProcessor processor;

    @Autowired
    AsrsJobs jobs;

    static final class Site {
        String comp;
        long userId;
        RequestPostProcessor admin;
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
    final BlockingQueue<String[]> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        wes = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        wes.createContext("/wes/requests", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.add(new String[] {ex.getRequestHeaders().getFirst("X-Idempotency-Key"), new String(body, StandardCharsets.UTF_8)});
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        wes.start();
        a = site("AS", "DIRECT");
    }

    @AfterEach
    void tearDown() {
        wes.stop(0);
    }

    Site site(String prefix, String conn) throws Exception {
        Site s = new Site();
        s.comp = accounts.newComp(prefix);
        s.userId = accounts.newUser(s.comp, "admin1", PW);
        accounts.grant(s.comp, s.userId, "WMS", "wms-asrs", "SEARCH", "SAVE");
        accounts.grant(s.comp, s.userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        s.admin = accounts.as(s.comp, s.userId, "admin1");
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
        ledger(s, s.inNode, 30);
        String[] dev = device(s, "WES-ADP");
        s.deviceId = Long.parseLong(dev[0]);
        s.key = dev[1];
        String url = "DIRECT".equals(conn) ? ",\"wesUrl\":\"http://127.0.0.1:" + wes.getAddress().getPort() + "/wes\"" : "";
        s.asrsId = id(s, post("/api/wms/asrs"), "{\"plantCd\":\"P1\",\"asrsCd\":\"AS1\",\"asrsNm\":\"1동 자동창고\",\"storeNodeId\":" + s.storeNode
                + ",\"inNodeId\":" + s.inNode + ",\"outNodeId\":" + s.outNode + ",\"terminalId\":" + s.deviceId + ",\"connMode\":\"" + conn + "\"" + url + "}");
        s.typeId = id(s, post("/api/wms/asrs/ctr-types"), "{\"typeCd\":\"BIN4\",\"typeNm\":\"4칸 빈\",\"lengthMm\":600,\"widthMm\":400,\"heightMm\":300,"
                + "\"slotCnt\":4}");
        return s;
    }

    // =====================================================================
    // 정상 — 설치형(DIRECT)
    // =====================================================================

    @Test
    void 용기_정의_입고_요청_보내기_WES_콜백_랙에_넣은_시점에_원장() throws Exception {
        long ctr = ctr(a, "BIN-0001");
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":10},{\"slotNo\":2,\"itemCd\":\"zt-a\",\"qty\":5}]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"bin-0001\"]}");
        send(a, req).andExpect(jsonPath("$.data").value("SENT"));
        assertThat(relay.relay(a.comp).sent()).isEqualTo(1);
        String[] got = received.poll(5, TimeUnit.SECONDS);
        String key = str(a, "select req_key::text from wms_asrs_req where req_id = ?", req);
        assertThat(got[0]).isEqualTo(key);                                              // 외부 요청 id = 우리가 만든 reqKey
        assertThat(got[1]).contains("\"ctrCd\":\"BIN-0001\"").contains("\"itemCd\":\"ZT-A\"").contains("\"type\":\"IN\"");
        // 콜백: 접수 → 진행 → 출입구 도착(원장 없음) → 랙에 넣음(원장)
        cb(a, key, "ACCEPTED", "BIN-0001").andExpect(jsonPath("$.data[0]").value("STORED"));
        cb(a, key, "IN_PROGRESS", "BIN-0001");
        cb(a, key, "ARRIVED", "BIN-0001");
        processor.drain(a.comp);
        assertThat(stock(a, a.storeNode)).isEqualTo("0");
        assertThat(progress(a, req)).isEqualTo("IN_PROGRESS");
        cb(a, key, "STORED", "BIN-0001");
        processor.drain(a.comp);
        assertThat(stock(a, a.inNode)).isEqualTo("15");
        assertThat(stock(a, a.storeNode)).isEqualTo("15");
        assertThat(str(a, "select status from wms_ctr where ctr_id = ?", ctr)).isEqualTo("IN_SYSTEM");
        assertThat(progress(a, req)).isEqualTo("DONE");
        assertThat(str(a, "select string_agg(src_type || ':' || trx_type, ',' order by trx_id) from inv_trx where src_type = 'ASRS_IN'"))
                .isEqualTo("ASRS_IN:MOVE_OUT,ASRS_IN:MOVE_IN");
        mvc.perform(get("/api/wms/asrs/reqs/" + req).with(a.admin))
                .andExpect(jsonPath("$.data.events[*].eventCd").value(org.hamcrest.Matchers.contains("SENT", "STATUS", "STATUS", "STATUS",
                        "POSTED")));
        // 자동창고 안 용기는 사람이 내용을 고치지 않는다(WES 가 주인)
        contents(a, ctr, "[]").andExpect(status().isConflict());
    }

    @Test
    void C_같은_콜백_297회_D_늦은_옛_상태_충돌_G_오류_동반_완료() throws Exception {
        long c1 = ctr(a, "BIN-A");
        long c2 = ctr(a, "BIN-B");
        contents(a, c1, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":4}]");
        contents(a, c2, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":4}]");
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-A\",\"BIN-B\"]}");
        send(a, req);
        String key = str(a, "select req_key::text from wms_asrs_req where req_id = ?", req);
        // 같은 콜백 297회(현장 실측 — 상대 메시지 ID 는 매번 다름) → 원문 1행, 처리 한 번
        StringBuilder many = new StringBuilder("{\"callbacks\":[");
        for (int i = 0; i < 297; i++) {
            many.append(i == 0 ? "" : ",").append(cbBody(key, 1, "STORED", "BIN-A", "\"msgId\":\"m" + i + "\""));
        }
        ext(a.key, "/callback", many + "]}").andExpect(status().isOk()).andExpect(jsonPath("$.data[296]").value("DUP"));
        assertThat(str(a, "select count(*) || ':' || max(dup_count) from sys_inbound_msg where route_cd = 'WES_CALLBACK'")).isEqualTo("1:296");
        processor.drain(a.comp);
        assertThat(stock(a, a.storeNode)).isEqualTo("4");
        // 늦게 온 옛 상태 = STALE(되돌리지 않음), 같은 키 다른 내용 = CONFLICT(처리 안 함)
        ext(a.key, "/callback", cbBody(key, 1, "IN_PROGRESS", "BIN-A", null)).andExpect(status().isOk());
        ext(a.key, "/callback", cbBody(key, 1, "STORED", "BIN-A", "\"qty\":99")).andExpect(jsonPath("$.data[0]").value("CONFLICT"));
        processor.drain(a.comp);
        assertThat(str(a, "select status from wms_asrs_req_line where req_id = ? and line_no = 1", req)).isEqualTo("DONE");
        assertThat(str(a, "select count(*)::text from wms_asrs_event where event_cd = 'STALE'")).isEqualTo("1");
        // 완료 + 오류 = 실패 — 원장 없음
        ext(a.key, "/callback", cbBody(key, 2, "STORED", "BIN-B", "\"errors\":[{\"code\":\"E17\",\"text\":\"shelf blocked\"}]"));
        processor.drain(a.comp);
        assertThat(str(a, "select status || ':' || last_reason from wms_asrs_req_line where req_id = ? and line_no = 2", req))
                .isEqualTo("FAILED:WES_ERROR");
        assertThat(stock(a, a.storeNode)).isEqualTo("4");
        assertThat(progress(a, req)).isEqualTo("PARTIAL");
    }

    @Test
    void G_정의_뒤_투입구_재고가_사라지면_격리_원장은_그대로() throws Exception {
        long ctr = ctr(a, "BIN-S");
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":20}]");
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-S\"]}");
        send(a, req);
        accounts.exec(a.comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_OUT', ?, ?, 'AVAILABLE', -25, 'EA', 'MANUAL', now(), 'KEY', 't')""", a.comp, a.item, a.inNode);
        cb(a, str(a, "select req_key::text from wms_asrs_req where req_id = ?", req), "STORED", "BIN-S");
        processor.drain(a.comp);
        assertThat(str(a, "select status || ':' || exc_reason from wms_ctr where ctr_id = ?", ctr)).isEqualTo("QUARANTINE:STOCK_SHORT");
        assertThat(str(a, "select status || ':' || last_reason from wms_asrs_req_line where req_id = ?", req)).isEqualTo("FAILED:STOCK_SHORT");
        assertThat(stock(a, a.storeNode)).isEqualTo("0");
        // 격리 용기는 경계 교환에서 리젝트
        ext(a.key, "/exchange", "{\"scanned\":\"CTR:BIN-S\"}").andExpect(jsonPath("$.data.decision").value("REJECT"));
    }

    @Test
    void 경계_교환은_요청한_그_용기만_GO() throws Exception {
        long ctr = ctr(a, "BIN-X");
        ctr(a, "BIN-Y");
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":1}]");
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-X\"]}");
        ext(a.key, "/exchange", "{\"scanned\":\"CTR:BIN-X\"}").andExpect(jsonPath("$.data.decision").value("REJECT"));   // 아직 안 보냄
        send(a, req);
        ext(a.key, "/exchange", "{\"scanned\":\"CTR:bin-x\",\"direction\":\"IN\"}").andExpect(jsonPath("$.data.decision").value("GO"))
                .andExpect(jsonPath("$.data.lineNo").value(1));
        ext(a.key, "/exchange", "{\"scanned\":\"BIN-Y\"}").andExpect(jsonPath("$.data.decision").value("REJECT"));        // 요청 없음
        ext(a.key, "/exchange", "{\"scanned\":\"BIN-NONE\"}").andExpect(jsonPath("$.data.decision").value("REJECT"));     // 미등록
        ext(a.key, "/exchange", "{\"scanned\":\"\"}").andExpect(jsonPath("$.data.decision").value("REJECT"));             // no-read
        ext(a.key, "/exchange", "{\"scanned\":\"BIN-X\",\"direction\":\"OUT\"}").andExpect(jsonPath("$.errors[0].field").value("direction"));
        assertThat(str(a, "select count(*)::text from wms_asrs_event where event_cd = 'EXCHANGE'")).isEqualTo("5");
    }

    // =====================================================================
    // SaaS(EDGE)
    // =====================================================================

    @Test
    void 엣지는_요청을_당겨_가고_콜백은_엣지_메시지_길로() throws Exception {
        Site e = site("AE", "EDGE");
        long ctr = ctr(e, "BIN-E1");
        contents(e, ctr, "[{\"slotNo\":3,\"itemCd\":\"ZT-A\",\"qty\":7}]");
        long req = id(e, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + e.asrsId + ",\"ctrCds\":[\"BIN-E1\"]}");
        send(e, req).andExpect(jsonPath("$.data").value("QUEUED"));
        // 보내기 전(QUEUED)은 취소할 수 있지만 여기서는 가져가게
        MvcResult c = ext(e.key, "/requests/claim", "{\"max\":5}").andExpect(jsonPath("$.data.length()").value(1)).andReturn();
        String key = JsonPath.read(c.getResponse().getContentAsString(), "$.data[0].reqKey");
        assertThat((Integer) JsonPath.read(c.getResponse().getContentAsString(), "$.data[0].lines[0].contents[0].slot")).isEqualTo(3);
        ext(e.key, "/requests/claim", "{}").andExpect(jsonPath("$.data.length()").value(0));
        assertThat(str(e, "select send_state from wms_asrs_req where req_id = ?", req)).isEqualTo("SENT");
        mvc.perform(post("/ext/v1/edge/messages").header("Authorization", "Bearer " + e.key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"route\":\"WES_CALLBACK\"," + cbBody(key, 1, "STORED", "BIN-E1", null).substring(1) + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].result").value("STORED"));
        processor.drain(e.comp);
        assertThat(stock(e, e.storeNode)).isEqualTo("7");
        // 설치형 자동창고는 당겨 가기 없음
        ext(a.key, "/requests/claim", "{}").andExpect(status().isConflict());
    }

    // =====================================================================
    // 이상 사용 · A사로 B사 · 직접 SQL
    // =====================================================================

    @Test
    void B_마스터_정의_요청_검사() throws Exception {
        // 가상 로케이션 용도 · 투입구 = 가상 로케이션 · 공인 WES 주소
        postJson(a, "/api/wms/asrs", "{\"plantCd\":\"P1\",\"asrsCd\":\"AS2\",\"asrsNm\":\"x\",\"storeNodeId\":" + a.inNode + ",\"inNodeId\":"
                + a.inNode + ",\"outNodeId\":" + a.outNode + ",\"terminalId\":" + a.deviceId + ",\"wesUrl\":\"http://8.8.8.8/wes\"}")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItems("USAGE_AUTO_STORE", "SAME_AS_STORE", "NOT_PRIVATE")));
        long ctr = ctr(a, "BIN-V");
        contents(a, ctr, "[{\"slotNo\":5,\"itemCd\":\"ZT-A\",\"qty\":1},{\"slotNo\":1,\"itemCd\":\"NOPE\",\"qty\":1},{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":0}]")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItems("RANGE", "NOT_FOUND")));
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":31}]").andExpect(status().isConflict());   // 투입구 30뿐
        postJson(a, "/api/wms/asrs/reqs/in", "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-V\"]}")
                .andExpect(jsonPath("$.errors[0].code").value("CTR_NOT_LOADED"));
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":3}]");
        long r1 = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-V\"]}");
        postJson(a, "/api/wms/asrs/reqs/in", "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-V\"]}")
                .andExpect(jsonPath("$.errors[0].code").value("CTR_IN_REQUEST"));
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":2}]").andExpect(status().isConflict());     // 요청이 걸림
        send(a, r1);
        postJson(a, "/api/wms/asrs/reqs/" + r1 + "/cancel", "{\"rowVer\":1}").andExpect(status().isConflict());   // 보낸 뒤 = WES 주인
        send(a, r1).andExpect(status().isConflict());                                                               // 두 번 보내기
    }

    @Test
    void A사로_B사_요청_용기_콜백() throws Exception {
        long ctr = ctr(a, "BIN-AB");
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":2}]");
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-AB\"]}");
        send(a, req);
        String key = str(a, "select req_key::text from wms_asrs_req where req_id = ?", req);
        Site b = site("AB", "DIRECT");
        // B사 WES 가 A사 reqKey 로 콜백 → 저장은 되지만 처리 = 거부(UNKNOWN_REQ), A사 원장 그대로
        ext(b.key, "/callback", cbBody(key, 1, "STORED", "BIN-AB", null)).andExpect(status().isOk());
        processor.drain(b.comp);
        assertThat(accounts.query(b.comp, "select status || ':' || coalesce(result_cd, '-') from sys_inbound_msg where route_cd = 'WES_CALLBACK'",
                String.class)).isEqualTo("REJECTED:UNKNOWN_REQ");
        assertThat(stock(a, a.storeNode)).isEqualTo("0");
        mvc.perform(get("/api/wms/asrs/reqs/" + req).with(b.admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/asrs/ctrs/" + ctr).with(b.admin)).andExpect(status().isNotFound());
        ext(b.key, "/exchange", "{\"scanned\":\"BIN-AB\"}").andExpect(jsonPath("$.data.decision").value("REJECT"));
        postJson(b, "/api/wms/asrs/ctrs", "{\"asrsId\":" + a.asrsId + ",\"ctrCd\":\"X1\",\"typeId\":" + b.typeId + "}")
                .andExpect(jsonPath("$.errors[0].field").value("asrsId"));
        // 직접 SQL: B사 범위에서 A사 용기 사건 · A사 노드로 자동창고
        assertThat(sqlError(b.comp, "insert into wms_asrs_event (comp_cd, asrs_id, ctr_id, event_cd, src, occurred_at, recorded_by) "
                + "values (?, ?, ?, 'STATUS', 'SYSTEM', now(), 't')", b.comp, b.asrsId, ctr)).isEqualTo("23503 fk_wms_asrs_event_comp");
        assertThat(sqlError(b.comp, "update wms_asrs set in_node_id = ? where asrs_id = ?", a.inNode, b.asrsId))
                .isEqualTo("23503 fk_wms_asrs_node_comp");
        // 라벨러 아닌·자동창고 아닌 장치
        String[] plain = device(a, "COL-9");
        ext(plain[1], "/exchange", "{\"scanned\":\"BIN-AB\"}").andExpect(status().isNotFound());
    }

    @Test
    void 직접_SQL_원장_추가만_끝난_라인_고정_용기_코드_고정() throws Exception {
        long ctr = ctr(a, "BIN-Q");
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":1}]");
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-Q\"]}");
        postJson(a, "/api/wms/asrs/reqs/" + req + "/cancel", "{\"rowVer\":0}").andExpect(status().isOk());
        assertThat(sqlError(a.comp, "update wms_asrs_req_line set status = 'NOT_STARTED' where req_id = ?", req))
                .isEqualTo("55000 ck_wms_asrs_req_line_final");
        assertThat(sqlError(a.comp, "update wms_asrs_event set detail = 'x' where req_id = ?", req)).startsWith("42501");
        assertThat(sqlError(a.comp, "delete from wms_asrs_event where req_id = ?", req)).startsWith("42501");
        assertThat(sqlError(a.comp, "update wms_ctr set ctr_cd = 'OTHER' where ctr_id = ?", ctr)).isEqualTo("55000 ck_wms_ctr_fixed");
        assertThat(sqlError(a.comp, "update wms_ctr set status = 'QUARANTINE', exc_reason = 'NOT_A_CODE' where ctr_id = ?", ctr))
                .isEqualTo("23503 fk_wms_ctr_exc_reason_code");
        assertThat(sqlError(a.comp, "insert into wms_ctr_content (comp_cd, ctr_id, slot_no, item_id, qty, updated_by) values (?, ?, 9, ?, 1, 't')",
                a.comp, ctr, a.item)).isEqualTo("23503 fk_wms_ctr_content_comp");                     // 4칸 용기의 9번 칸
        assertThat(sqlError(a.comp, "delete from wms_ctr where ctr_id = ?", ctr)).startsWith("42501");
    }

    @Test
    void 오래_머문_요청은_경보_한_번() throws Exception {
        long ctr = ctr(a, "BIN-OLD");
        contents(a, ctr, "[{\"slotNo\":1,\"itemCd\":\"ZT-A\",\"qty\":1}]");
        long req = id(a, post("/api/wms/asrs/reqs/in"), "{\"asrsId\":" + a.asrsId + ",\"ctrCds\":[\"BIN-OLD\"]}");
        send(a, req);
        assertThat(jobs.flagStale()).isZero();
        accounts.exec(a.comp, "update wms_asrs_req set sent_at = now() - interval '2 hours' where req_id = ?", req);
        assertThat(jobs.flagStale()).isEqualTo(1);
        assertThat(jobs.flagStale()).isZero();                                          // 한 번만
        assertThat(str(a, "select detail from wms_asrs_event where event_cd = 'STALE_REQ'")).contains("NOT_STARTED");
    }

    @Test
    void 권한_API_직접() throws Exception {
        long viewer = accounts.newUser(a.comp, "viewer", PW);
        accounts.grant(a.comp, viewer, "VIEW", "wms-asrs", "SEARCH");
        RequestPostProcessor v = accounts.as(a.comp, viewer, "viewer");
        mvc.perform(get("/api/wms/asrs/reqs").with(v)).andExpect(status().isOk());
        mvc.perform(json(post("/api/wms/asrs/reqs/in"), "{}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/wms/asrs/ctrs/1/contents"), "{}").with(v)).andExpect(status().isForbidden());
        mvc.perform(post("/ext/v1/wes/callback").with(a.admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        ext("bad.key.x", "/callback", "{}").andExpect(status().isUnauthorized());
        ext(a.key, "/callback", "not json").andExpect(status().isBadRequest());
        ext(a.key, "/callback", "{\"reqKey\":\"x\"}").andExpect(jsonPath("$.data[0]").value(org.hamcrest.Matchers.startsWith("INVALID:KEY")));
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    long node(Site s, Long parent, String type, String cd, String nm) {
        return accounts.query(s.comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, s.comp, parent, type, cd, nm);
    }

    void ledger(Site s, long node, int qty) {
        accounts.exec(s.comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', ?, 'EA', 'MANUAL', now(), 'KEY', 't')""", s.comp, s.item, node, qty);
    }

    String stock(Site s, long node) {
        return str(s, "select coalesce(trim_scale(sum(qty))::text, '0') from inv_stock where node_id = ? and item_id = ?", node, s.item);
    }

    String progress(Site s, long req) throws Exception {
        MvcResult m = mvc.perform(get("/api/wms/asrs/reqs/" + req).with(s.admin)).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.progress");
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

    ResultActions cb(Site s, String key, String status, String ctrCd) throws Exception {
        return ext(s.key, "/callback", cbBody(key, 1, status, ctrCd, null));
    }

    ResultActions ext(String key, String path, String body) throws Exception {
        return mvc.perform(post(AsrsExtController.BASE + path).header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String[] device(Site s, String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"COLLECTOR\"}").with(s.admin)).andExpect(status().isOk()).andReturn();
        String body = m.getResponse().getContentAsString();
        return new String[] {String.valueOf(((Number) JsonPath.read(body, "$.data.terminalId")).longValue()), JsonPath.read(body, "$.data.code")};
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
