package io.smarthappy.module.production.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * ERP 연계 경계·가짜 ERP·ERP 업로드용 엑셀 (P2 7c, docs/25 4.11). 실적은 실제 POP API, 판정·완료·입고는 사무 API, 송신은 릴레이.
 * 결과 대응: OK → SENT, 429 → 재시도 후 성공, 매핑 없음 → FAIL, 저장 뒤 타임아웃 → FAIL UNKNOWN·자동 재전송 없음(멱등 없는 상대에 전표 1건).
 * 이상 사용: 정책 NONE·EXCEL 은 발행 없음, 기간 거꾸로·너무 김·종류 오타, 권한 없음, 같은 기간 두 번 내보내기(기록 2건), A사로 B사, 직접 SQL.
 */
@AutoConfigureMockMvc
class ErpTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String WO = "/api/production/wos";
    private static final String RCV = "/api/production/receipts";
    private static final String EXP = "/api/production/erp-export";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    OutboxRelay relay;

    @Autowired
    FakeErpAdapter fake;

    String comp;
    RequestPostProcessor admin;
    long woId;
    String woNo;
    long spot;
    long loc;
    String cookie;
    MockHttpSession s;
    String ip;
    String today;

    @BeforeEach
    void setUp() throws Exception {
        fake.reset();
        comp = accounts.newComp("ER");
        ip = "10.82." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-receipt", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-erp-export", "SEARCH", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        long line = node(area, "LINE", "L1");
        long cell = node(line, "CELL", "C1");
        long wh = node(null, "WH", "WH1");
        loc = node(wh, "LOC", "WH1-A01");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM 품목', 'FG', 'MAKE', 'KG', 't', 't')""", comp, comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't')""", comp, rid, cell);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-PIT', '기공', 'WELD', 't', 't')""", comp);
        woId = id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"10\",\"planStart\":\"" + today + "\"}")
                .with(admin)));
        mvc.perform(json(post(WO + "/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        woNo = accounts.query(comp, "select wo_no from prd_wo where wo_id = ?", String.class, woId);
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        cookie = terminal("TAB-01");
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        fake.reset();
    }

    // ---------- 도우미 ----------

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 노드");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String terminal(String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    /** 착수 → 양품 10·폐기 1 → 종료 → 완료판정 → 지시 완료 */
    private void runToDone() throws Exception {
        pop(post("/api/pop/procs/" + spot + "/events/start"), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}").andExpect(status().isOk());
        pop(post("/api/pop/procs/" + spot + "/results"), "{\"clientReqId\":\"" + req() + "\",\"goodQty\":\"10\",\"scrapQty\":\"1\","
                + "\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"}]}").andExpect(status().isOk());
        pop(post("/api/pop/procs/" + spot + "/events/end"), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}").andExpect(status().isOk());
        int rv = accounts.query(comp, "select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, spot);
        mvc.perform(json(post(WO + "/procs/" + spot + "/close"), "{\"rowVer\":" + rv + "}").with(admin)).andExpect(status().isOk());
        int wv = accounts.query(comp, "select row_ver from prd_wo where wo_id = ?", Integer.class, woId);
        mvc.perform(json(post(WO + "/" + woId + "/complete"), "{\"rowVer\":" + wv + "}").with(admin)).andExpect(status().isOk());
    }

    private long receive(String qty) throws Exception {
        long lot = accounts.query(comp, "select lot_id from prd_result where wo_proc_id = ? and lot_id is not null", Long.class, spot);
        String r = req();
        mvc.perform(json(post(RCV), "{\"clientReqId\":\"" + r + "\",\"woId\":" + woId + ",\"lotId\":" + lot + ",\"nodeId\":" + loc
                + ",\"qty\":\"" + qty + "\"}").with(admin)).andExpect(status().isOk());
        return accounts.query(comp, "select receipt_id from prd_receipt where client_req_id = ?", Long.class, r);
    }

    private void erpMap() {
        long sys = accounts.query(comp, """
                insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, created_by, updated_by)
                values (?, 'ERP1', 'ERP', 'ERP', 't', 't') returning system_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by)
                select ?, ?, 'ITEM', item_id, 'E-FG-01', 't', 't' from mst_item where item_cd = 'FG'""", comp, sys);
    }

    private void policy(String v) {
        runner.run(comp, () -> policies.set(ErpPublisher.POLICY, null, v, "t"));
    }

    private int erpOutbox() {
        return accounts.query(comp, "select count(*) from sys_outbox where topic like 'ERP_%'", Integer.class);
    }

    private String lastError(String bizKey) {
        return accounts.query(comp, "select status || ' ' || coalesce(last_error, '') from sys_outbox where biz_key = ?", String.class, bizKey);
    }

    // ---------- 자동 송신 ----------

    @Test
    void FAKE_는_완료_입고_정정을_보내고_NONE_EXCEL_은_발행하지_않는다() throws Exception {
        erpMap();
        policy("EXCEL");
        runToDone();
        receive("4");
        assertThat(erpOutbox()).isZero();   // EXCEL: 사람이 엑셀로 올린다
        policy("NONE");
        receive("1");
        assertThat(erpOutbox()).isZero();
        policy("FAKE");
        long r = receive("3");
        mvc.perform(json(post(RCV + "/" + r + "/correct"), "{\"reason\":\"수량 잘못\"}").with(admin)).andExpect(status().isOk());
        assertThat(erpOutbox()).isEqualTo(2);
        // 본문에 ERP 가 받을 값이 다 있다(송신기는 DB 를 못 쓴다), 정정은 음수
        assertThat(accounts.query(comp, """
                select string_agg((payload ->> 'erpItemCd') || ':' || (payload ->> 'qty') || ':' || (payload ->> 'whCd'), ',' order by outbox_id)
                  from sys_outbox where topic = 'ERP_RECEIPT'""", String.class)).isEqualTo("E-FG-01:3:WH1-A01,E-FG-01:-3:WH1-A01");
        assertThat(relay.relay(comp).sent()).isEqualTo(2);
        assertThat(fake.lookup(comp, "RCV:" + r)).isEqualTo(1);
        assertThat(fake.count(comp)).isEqualTo(2);
        // 완료 시각은 지시에 한 번 — 이후 완료 송신은 FAKE 정책 아래 새 지시에서
        assertThat(accounts.query(comp, "select done_at is not null from prd_wo where wo_id = ?", Boolean.class, woId)).isTrue();
    }

    @Test
    void 지시_완료_실적_본문은_마지막_공정_유효_양품과_폐기_합() throws Exception {
        erpMap();
        policy("FAKE");
        runToDone();
        assertThat(accounts.query(comp, """
                select (payload ->> 'system') || ':' || (payload ->> 'erpItemCd') || ':' || (payload ->> 'qty') || ':' || (payload ->> 'scrapQty')
                       || ':' || (payload ->> 'woNo') from sys_outbox where topic = 'ERP_WO_RESULT' and biz_key = ?""", String.class, "WO:" + woNo))
                .isEqualTo("FAKE:E-FG-01:10:1:" + woNo);
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        assertThat(fake.lookup(comp, "WO:" + woNo)).isEqualTo(1);
    }

    @Test
    void 매핑_없음은_FAIL_429_는_재시도_뒤_성공_타임아웃은_UNKNOWN_으로_다시_보내지_않는다() throws Exception {
        policy("FAKE");
        runToDone();                                                   // 매핑 없음 → erpItemCd null → 4xx
        assertThat(relay.relay(comp).failed()).isEqualTo(1);
        assertThat(lastError("WO:" + woNo)).startsWith("FAIL REJECTED: 400 ITEM_UNMAPPED");
        assertThat(fake.lookup(comp, "WO:" + woNo)).isZero();

        erpMap();
        fake.configure(1, 0);                                          // 모든 호출 429
        long r1 = receive("2");
        assertThat(relay.relay(comp).retried()).isEqualTo(1);
        assertThat(lastError("RCV:" + r1)).startsWith("NEW RATE_LIMITED");
        assertThat(fake.lookup(comp, "RCV:" + r1)).isZero();
        fake.configure(0, 0);
        accounts.exec(comp, "update sys_outbox set next_retry_at = now() where biz_key = ?", "RCV:" + r1);
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        assertThat(fake.lookup(comp, "RCV:" + r1)).isEqualTo(1);

        fake.configure(0, 1);                                          // 저장한 뒤 응답 못 받음
        long r2 = receive("2");
        assertThat(relay.relay(comp).failed()).isEqualTo(1);
        assertThat(lastError("RCV:" + r2)).startsWith("FAIL UNKNOWN");
        assertThat(fake.lookup(comp, "RCV:" + r2)).isEqualTo(1);       // 상대에는 들어갔다
        fake.configure(0, 0);
        accounts.exec(comp, "update sys_outbox set next_retry_at = now() where biz_key = ?", "RCV:" + r2);
        assertThat(relay.relay(comp).sent()).isZero();                 // FAIL 은 다시 집지 않는다 → 중복 전표 없음
        assertThat(fake.lookup(comp, "RCV:" + r2)).isEqualTo(1);
    }

    // ---------- 엑셀 ----------

    @Test
    void 엑셀_미리보기_열_건수_기록_같은_기간_두번() throws Exception {
        erpMap();
        runToDone();
        long r = receive("6");
        mvc.perform(json(post(RCV + "/" + r + "/correct"), "{\"reason\":\"수량 잘못\"}").with(admin)).andExpect(status().isOk());
        receive("4");
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", today).param("to", today).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.unmapped").value(0))
                .andExpect(jsonPath("$.data.rows[0].erpItemCd").value("E-FG-01")).andExpect(jsonPath("$.data.rows[0].qtyText").value("10"))
                .andExpect(jsonPath("$.data.rows[0].scrapQtyText").value("1")).andExpect(jsonPath("$.data.erpSystem").value("NONE"))
                .andExpect(jsonPath("$.data.exports.length()").value(0));
        mvc.perform(get(EXP).param("kind", "RECEIPT").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows[*].qtyText").value(Matchers.contains("6", "-6", "4")))
                .andExpect(jsonPath("$.data.rows[1].entryMethod").value("CORRECT"));
        // 엑셀: 숫자 셀·ERP 품목 코드 열, 기록 1건
        MvcResult m = mvc.perform(json(post(EXP + "/export"), "{\"kind\":\"RECEIPT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(m.getResponse().getContentAsByteArray()))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getLastRowNum()).isEqualTo(3);
            Row h = sh.getRow(0);
            assertThat(h.getCell(1).getStringCellValue()).isEqualTo("ERP 품목 코드");
            assertThat(sh.getRow(1).getCell(1).getStringCellValue()).isEqualTo("E-FG-01");
            assertThat(sh.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(-6.0);
            assertThat(sh.getRow(1).getCell(6).getStringCellValue()).isEqualTo("WH1-A01");
        }
        // 같은 기간 두 번 — 막지 않고 기록·알림(D20)
        mvc.perform(json(post(EXP + "/export"), "{\"kind\":\"RECEIPT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(kind || ':' || row_cnt || ':' || exported_by, ',') from prd_erp_export",
                String.class)).isEqualTo("RECEIPT:3:admin1,RECEIPT:3:admin1");
        mvc.perform(get(EXP).param("kind", "RECEIPT").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.exports.length()").value(2));
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.exports.length()").value(0));
        // 매핑을 끄면 경고 수
        accounts.exec(comp, "update mst_ext_id_map set use_yn = 'N'");
        mvc.perform(get(EXP).param("kind", "RECEIPT").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.unmapped").value(3)).andExpect(jsonPath("$.data.rows[0].erpItemCd").doesNotExist());
    }

    @Test
    void 엑셀_조건_오류와_권한() throws Exception {
        mvc.perform(get(EXP).param("kind", "X").param("from", today).param("to", today).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind")).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        mvc.perform(get(EXP).param("from", today).param("to", today).with(admin)).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", "2026-02-10").param("to", "2026-02-01").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", "2026-01-01").param("to", "2026-04-30").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        mvc.perform(get(EXP).param("kind", "RESULT").param("to", today).with(admin)).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(json(post(EXP + "/export"), "{\"kind\":\"RESULT\",\"from\":\"2026-02-10\",\"to\":\"2026-02-01\"}").with(admin))
                .andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from prd_erp_export", Integer.class)).isZero();
        // 조회만 있는 사람은 엑셀 불가, 권한 없는 사람은 조회도 불가
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "prd-erp-export", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", today).param("to", today).with(v)).andExpect(status().isOk());
        mvc.perform(json(post(EXP + "/export"), "{\"kind\":\"RESULT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}").with(v))
                .andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none1", PW);
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", today).param("to", today).with(accounts.as(comp, none, "none1")))
                .andExpect(status().isForbidden());
    }

    // ---------- 격리·직접 SQL ----------

    @Test
    void A사로_B사() throws Exception {
        erpMap();
        policy("FAKE");
        runToDone();
        mvc.perform(json(post(EXP + "/export"), "{\"kind\":\"RESULT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(admin)).andExpect(status().isOk());
        String a = comp;
        String b = accounts.newComp("EX");
        long bu = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, bu, "ADM", "prd-erp-export", "SEARCH", "EXCEL");
        RequestPostProcessor bp = accounts.as(b, bu, "admin1");
        mvc.perform(get(EXP).param("kind", "RESULT").param("from", today).param("to", today).with(bp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.exports.length()").value(0))
                .andExpect(jsonPath("$.data.erpSystem").value("NONE"));
        MvcResult m = mvc.perform(json(post(EXP + "/export"), "{\"kind\":\"RESULT\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}")
                .with(bp)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(m.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
        assertThat(accounts.query(b, "select count(*) from prd_erp_export", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(a, "select count(*) from prd_erp_export", Integer.class)).isEqualTo(1);
        // B 의 릴레이는 A 의 ERP 메시지를 집지 않는다, 가짜 ERP 도 회사별
        assertThat(relay.relay(b).sent()).isZero();
        assertThat(relay.relay(a).sent()).isEqualTo(1);
        assertThat(fake.lookup(b, "WO:" + woNo)).isZero();
        assertThat(fake.count(b)).isZero();
        // 직접 SQL(B 범위): A 회사 코드로 기록
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(b, """
                insert into prd_erp_export (comp_cd, kind, from_day, to_day, row_cnt, exported_by) values (?, 'RESULT', (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, 0, 'x')""",
                a)))).contains("42501");
    }

    @Test
    void 기록과_완료_시각_직접_SQL() throws Exception {
        String ins = "insert into prd_erp_export (comp_cd, kind, from_day, to_day, row_cnt, exported_by) values (?, ?, ?::date, ?::date, ?, 'x')";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "STOCK", today, today, 0))))
                .contains("ck_prd_erp_export_kind");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "RESULT", "2026-02-10", "2026-02-01", 0))))
                .contains("ck_prd_erp_export_range");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, "RESULT", today, today, -1))))
                .contains("ck_prd_erp_export_cnt");
        accounts.exec(comp, ins, comp, "RESULT", today, today, 0);
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update prd_erp_export set row_cnt = 9")))).contains("42501");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from prd_erp_export")))).contains("42501");
        // 진행 중 지시에 완료 시각을 지어내면 거부
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update prd_wo set done_at = now() where wo_id = ?", woId))))
                .contains("ck_prd_wo_done_at");
    }
}
