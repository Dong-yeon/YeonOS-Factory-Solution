package io.smarthappy.module.wms.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 라벨 템플릿·프린터·인쇄 큐 (P4 W2, docs/30) — 가짜 TCP 프린터로 끝까지(렌더 → 아웃박스 → 송신기 → 프린터).
 * 정상 + 이상 사용(B 입력·C 동시·F 흐름·G 외부 장애·I 주입) + A사로 B사 + 직접 SQL.
 */
@AutoConfigureMockMvc
class LabelPrintTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    OutboxRelay relay;

    @Autowired
    LabelPrintHandler handler;

    @Autowired
    LabelPrintService printService;

    @Autowired
    TenantRunner runner;

    String comp;
    long userId;
    RequestPostProcessor admin;
    FakePrinter printer;
    long wh;
    long bin;

    /** 테스트용 라벨 프린터 — 연결마다 받은 바이트를 큐에 */
    static final class FakePrinter implements AutoCloseable {
        final ServerSocket server;
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final Thread thread;

        FakePrinter() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        in.transferTo(b);
                        received.add(b.toString(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        // 닫힘
                    }
                }
            }, "fake-label-printer");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        String take() throws InterruptedException {
            return received.poll(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        printer = new FakePrinter();
        comp = accounts.newComp("LP");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "WMS", "wms-label", "SEARCH", "SAVE", "PRINT");
        accounts.grant(comp, userId, "WMS", "wms-print-job", "SEARCH", "PRINT", "APPROVE");
        accounts.grant(comp, userId, "WMS", "wms-loc", "SEARCH", "SAVE", "PRINT");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        setPorts(comp, printer.port() + ",9100");
        wh = node(comp, "P1", null, "WH", "RM-WH", "원자재 창고");
        bin = node(comp, "P1", wh, "LOC", "A-01", "A랙");
    }

    @AfterEach
    void tearDown() throws IOException {
        printer.close();
        handler.setResolver(PrinterAddress.SYSTEM);
        SecurityContextHolder.clearContext();
    }

    void setPorts(String c, String ports) {
        accounts.exec(c, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'WMS_PRINTER_PORTS', ?, 't', 't')
                on conflict (comp_cd, plant_cd, policy_key) do update set policy_value = excluded.policy_value""", c, ports);
    }

    long node(String c, String plant, Long parent, String type, String cd, String nm) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, nm);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long fakePrinter(String cd, String usage) throws Exception {
        return id(postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"" + cd + "\",\"printerNm\":\"가짜\","
                + "\"host\":\"127.0.0.1\",\"port\":" + printer.port() + ",\"usage\":\"" + usage + "\"}", admin));
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private long template(String type, String cd, String body, boolean dflt) throws Exception {
        return id(postJson("/api/wms/labels", "{\"labelType\":\"" + type + "\",\"tplCd\":\"" + cd + "\",\"tplNm\":\"" + cd
                + "\",\"widthMm\":50,\"heightMm\":30,\"body\":" + q(body) + ",\"defaultYn\":\"" + (dflt ? "Y" : "N") + "\"}", admin));
    }

    private int count(String table) {
        return accounts.query(comp, "select count(*) from " + table, Integer.class);
    }

    private String outboxStatus(long jobId) {
        return accounts.query(comp, """
                select o.status from wms_print_job j join sys_outbox o on o.message_id = j.outbox_message_id where j.job_id = ?""",
                String.class, jobId);
    }

    private long lastJob() {
        return accounts.query(comp, "select max(job_id) from wms_print_job", Long.class);
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    // =====================================================================
    // 정상 — 끝까지
    // =====================================================================

    @Test
    void 로케이션_라벨_인쇄가_가짜_프린터까지_도착하고_상태는_아웃박스에서() throws Exception {
        long p = fakePrinter("ZB1", "LOC");
        template("LOC", "LOC-STD", LabelFields.sampleBody("LOC"), true);
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + wh + "],\"includeChildren\":true,\"printerId\":" + p
                + ",\"copies\":2}", admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.jobs").value(2));
        assertThat(count("wms_print_job")).isEqualTo(2);
        long job = lastJob();
        assertThat(outboxStatus(job)).isEqualTo("NEW");                     // 아직 안 보냄 — 커밋된 뒤 릴레이가
        assertThat(relay.relay(comp).sent()).isEqualTo(2);
        String first = printer.take();
        String second = printer.take();
        String all = first + second;
        assertThat(all).contains("^FH_^FDRM-WH^FS").contains("^FH_^FDLOC:P1:A-01^FS").contains("^FH_^FDP1/RM-WH/A-01^FS");
        assertThat(first.split("\\^XA", -1).length - 1).isEqualTo(2);       // 2장 = 같은 ZPL 두 번
        assertThat(outboxStatus(job)).isEqualTo("SENT");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get("/api/wms/print-jobs").param("from", today).param("to", today).param("status", "SENT").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[0].printerCd").value("ZB1"));
        mvc.perform(get("/api/wms/print-jobs/" + job).with(admin)).andExpect(jsonPath("$.data.zpl").exists())
                .andExpect(jsonPath("$.data.labelValues").value(org.hamcrest.Matchers.containsString("LOC:P1:A-01")));
        // 같은 메시지를 두 번 보내도 작업 행은 1건(상태 파생) — 아웃박스 행을 되돌려 다시 보냄
        accounts.exec(comp, "update sys_outbox set status = 'NEW', next_retry_at = now() where message_id = "
                + "(select outbox_message_id from wms_print_job where job_id = ?)", job);
        relay.relay(comp);
        assertThat(printer.take()).isNotNull();
        assertThat(count("wms_print_job")).isEqualTo(2);
    }

    @Test
    void 이름에_든_ZPL_명령은_글자로만_찍힌다() throws Exception {
        long p = fakePrinter("ZB1", "ANY");
        template("LOC", "LOC-STD", "^XA^FO10,10^FD{{locNm}}^FS^XZ", true);
        long evil = node(comp, "P1", wh, "LOC", "EVIL", "x^FS^XZ^XA~JR^JUS^FO0,0^GB800,800,800^FS");
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + evil + "],\"printerId\":" + p + "}", admin)
                .andExpect(status().isOk());
        relay.relay(comp);
        String zpl = printer.take();
        assertThat(zpl).isEqualTo("^XA^FO10,10^FH_^FDx_5EFS_5EXZ_5EXA_7EJR_5EJUS_5EFO0,0_5EGB800,800,800_5EFS^FS^XZ");
    }

    @Test
    void 템플릿_미리보기_견본_시험인쇄() throws Exception {
        long p = fakePrinter("ZB1", "ANY");
        mvc.perform(get("/api/wms/labels/sample").param("labelType", "LOT").with(admin))
                .andExpect(jsonPath("$.data.fields").value(org.hamcrest.Matchers.hasItem("lotNo")));
        postJson("/api/wms/labels/preview", "{\"labelType\":\"LOC\",\"body\":\"^XA^FD{{locCd}}^FS^XZ\"}", admin)
                .andExpect(jsonPath("$.data.zpl").value("^XA^FH_^FDA-01-03^FS^XZ"));             // 견본 값
        long t = template("LOT", "LOT-STD", LabelFields.sampleBody("LOT"), true);
        long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin));
        assertThat(accounts.query(comp, "select test_yn || biz_key from wms_print_job where job_id = ?", String.class, job))
                .isEqualTo("YTEST:LOT-STD");
        relay.relay(comp);
        assertThat(printer.take()).contains("SAMPLE");
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void B_템플릿_본문_검사_기본_템플릿은_유형마다_하나() throws Exception {
        postJson("/api/wms/labels", "{\"labelType\":\"LOC\",\"tplCd\":\"BAD\",\"tplNm\":\"x\",\"widthMm\":50,\"heightMm\":30,"
                + "\"body\":\"^XA^CC+^BY{{locCd}}^FD{{password}}^FS^XZ\"}", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItems("FORBIDDEN_CMD", "UNKNOWN_FIELD", "OUTSIDE_FD")));
        postJson("/api/wms/labels", "{\"labelType\":\"BOX\",\"tplCd\":\"b c\",\"tplNm\":\"\",\"widthMm\":5,\"heightMm\":301.55,"
                + "\"dpi\":72,\"body\":\"\"}", admin)
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems("labelType", "tplCd", "tplNm", "widthMm",
                        "heightMm", "dpi")));
        long a = template("LOC", "A", "^XA^FD{{locCd}}^FS^XZ", true);
        long b = template("LOC", "B", "^XA^FD{{locCd}}^FS^XZ", true);              // 새 기본 → A 는 내려간다
        assertThat(accounts.query(comp, "select string_agg(tpl_cd || default_yn, ',' order by tpl_cd) from wms_label_tpl",
                String.class)).isEqualTo("AN,BY");
        mvc.perform(json(put("/api/wms/labels/" + b), "{\"tplNm\":\"B\",\"widthMm\":50,\"heightMm\":30,\"body\":\"^XA^XZ\","
                + "\"defaultYn\":\"Y\",\"useYn\":\"N\",\"rowVer\":0}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DEFAULT_INACTIVE"));
        mvc.perform(json(put("/api/wms/labels/" + a), "{\"labelType\":\"ITEM\",\"tplCd\":\"Z\",\"tplNm\":\"A\",\"widthMm\":50,"
                + "\"heightMm\":30,\"body\":\"^XA^XZ\",\"rowVer\":1}").with(admin))
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.contains("FIXED", "FIXED")));
        postJson("/api/wms/labels", "{\"labelType\":\"ITEM\",\"tplCd\":\"a\",\"tplNm\":\"x\",\"widthMm\":50,\"heightMm\":30,"
                + "\"body\":\"^XA^XZ\"}", admin).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));   // 대문자로 같은 코드
        assertThat(count("wms_label_tpl")).isEqualTo(2);
    }

    @Test
    void B_프린터_주소는_사설_대역만_포트는_정책_목록만() throws Exception {
        String base = "{\"plantCd\":\"P1\",\"printerCd\":\"X\",\"printerNm\":\"x\",\"host\":";
        postJson("/api/wms/printers", base + "\"8.8.8.8\",\"port\":9100}", admin).andExpect(jsonPath("$.errors[0].code").value("NOT_PRIVATE"));
        postJson("/api/wms/printers", base + "\"169.254.169.254\",\"port\":9100}", admin).andExpect(jsonPath("$.errors[0].code").value("NOT_PRIVATE"));
        postJson("/api/wms/printers", base + "\"10.1.1.1\",\"port\":22}", admin).andExpect(jsonPath("$.errors[0].code").value("PORT_NOT_ALLOWED"));
        postJson("/api/wms/printers", base + "\"http://10.1.1.1/x\",\"port\":9100}", admin).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson("/api/wms/printers", base + "\"10.1.1.1\",\"port\":9100,\"dpi\":72,\"usage\":\"FAX\"}", admin)
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("dpi", "usage")));
        postJson("/api/wms/printers", "{\"plantCd\":\"P9\",\"printerCd\":\"X\",\"printerNm\":\"x\",\"host\":\"10.1.1.1\"}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        long ok = id(postJson("/api/wms/printers", base + "\"zebra-01.factory.local\",\"port\":9100}", admin));
        postJson("/api/wms/printers", base + "\"10.1.1.2\",\"port\":9100}", admin).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 저장 뒤 정책에서 포트를 빼면 인쇄 요청이 막힌다
        long t = template("LOC", "T", "^XA^FD{{locCd}}^FS^XZ", true);
        setPorts(comp, String.valueOf(printer.port()));
        postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + ok + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("PORT_NOT_ALLOWED"));
        assertThat(count("wms_print_job")).isZero();
    }

    @Test
    void F_프린터_용도_사용중지_템플릿_유형_불일치_기본_없음() throws Exception {
        long locOnly = fakePrinter("ZL", "LOC");
        long lot = template("LOT", "LOT1", LabelFields.sampleBody("LOT"), true);
        postJson("/api/wms/labels/" + lot + "/test-print", "{\"printerId\":" + locOnly + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("USAGE_MISMATCH"));
        // LOC 기본 템플릿 없음
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + bin + "],\"printerId\":" + locOnly + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("NO_DEFAULT_TEMPLATE"));
        // LOT 템플릿을 로케이션 라벨로
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + bin + "],\"printerId\":" + locOnly + ",\"tplId\":" + lot + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("LABEL_TYPE_MISMATCH"));
        accounts.exec(comp, "update wms_printer set use_yn = 'N' where printer_id = ?", locOnly);
        template("LOC", "LOC1", "^XA^FD{{locCd}}^FS^XZ", true);
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + bin + "],\"printerId\":" + locOnly + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 다른 공장 노드·구역 노드·사용 중지 노드만
        long p = fakePrinter("ZB2", "ANY");
        long p2wh = node(comp, "P2", null, "WH", "P2WH", "2공장 창고");
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + p2wh + "],\"printerId\":" + p + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        long area = node(comp, "P1", null, "AREA", "BODY", "차체");
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + area + "],\"printerId\":" + p + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_STORAGE_NODE"));
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", bin);
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + bin + "],\"printerId\":" + p + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + wh + "],\"printerId\":" + p + ",\"copies\":100}", admin)
                .andExpect(jsonPath("$.errors[0].field").value("copies"));
        assertThat(count("wms_print_job")).isZero();
    }

    @Test
    void G_프린터_꺼짐은_재시도_DNS_가_공인주소로_바뀌면_실패() throws Exception {
        long t = template("LOC", "T", "^XA^FD{{locCd}}^FS^XZ", true);
        int deadPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = s.getLocalPort();
        }
        setPorts(comp, printer.port() + "," + deadPort);
        long dead = id(postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"DEAD\",\"printerNm\":\"꺼짐\","
                + "\"host\":\"127.0.0.1\",\"port\":" + deadPort + "}", admin));
        long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + dead + "}", admin));
        assertThat(relay.relay(comp).retried()).isEqualTo(1);
        assertThat(accounts.query(comp, "select status || ':' || attempts || ':' || (last_error like 'ConnectException%') from sys_outbox "
                + "where message_id = (select outbox_message_id from wms_print_job where job_id = ?)", String.class, job))
                .isEqualTo("NEW:1:true");
        // 이름 프린터: 저장 때는 형식만, 보낼 때 해석 결과가 공인이면 FAIL(재시도 없음)
        long named = id(postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"NAMED\",\"printerNm\":\"이름\","
                + "\"host\":\"printer.factory.test\",\"port\":" + printer.port() + "}", admin));
        handler.setResolver(h -> new InetAddress[] {InetAddress.getByAddress(h, new byte[] {93, (byte) 184, (byte) 216, 34})});
        long job2 = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + named + "}", admin));
        accounts.exec(comp, "update sys_outbox set next_retry_at = now() + interval '1 hour' where message_id = "
                + "(select outbox_message_id from wms_print_job where job_id = ?)", job);
        assertThat(relay.relay(comp).failed()).isEqualTo(1);
        assertThat(outboxStatus(job2)).isEqualTo("FAIL");
        assertThat(printer.received).isEmpty();
    }

    @Autowired
    LabelService labelService;

    /** docs/67 F3 — SaaS(사설망 직접 연결 끔)에서는 프린터 주소 저장·인쇄 요청·송신 모두 막힌다. 켜져 있으면 그대로(위 정상 시험들) */
    @Test
    void F3_사설망_직접_연결이_꺼지면_저장_요청_송신_모두_막힌다() throws Exception {
        long p = fakePrinter("ZB1", "ANY");
        long t = template("LOT", "LOT-STD", LabelFields.sampleBody("LOT"), true);
        io.smarthappy.platform.config.LanDirectGate on = io.smarthappy.platform.config.LanDirectGate.fixed(true);
        io.smarthappy.platform.config.LanDirectGate off = io.smarthappy.platform.config.LanDirectGate.fixed(false);
        try {
            labelService.setLanDirect(off);
            printService.setLanDirect(off);
            // 새 주소 저장 거부(사설 IP·이름 모두) — 칸 오류
            postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"N1\",\"printerNm\":\"x\",\"host\":\"10.1.1.9\",\"port\":9100}", admin)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LAN_DIRECT_OFF"));
            postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"N2\",\"printerNm\":\"x\",\"host\":\"zebra.factory.local\",\"port\":9100}", admin)
                    .andExpect(jsonPath("$.errors[0].code").value("LAN_DIRECT_OFF"));
            // 이미 저장된 프린터로 인쇄 요청도 거부 — 작업·아웃박스가 생기지 않는다
            int jobs = count("wms_print_job");
            postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LAN_DIRECT_OFF"));
            assertThat(count("wms_print_job")).isEqualTo(jobs);

            // 송신기: 켜져 있을 때 쌓인 메시지(또는 직접 SQL)도 꺼진 뒤에는 보내지 않는다 — 재시도 없이 FAIL
            labelService.setLanDirect(on);
            printService.setLanDirect(on);
            long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin));
            handler.setLanDirect(off);
            assertThat(relay.relay(comp).failed()).isEqualTo(1);
            assertThat(outboxStatus(job)).isEqualTo("FAIL");
            assertThat(printer.received).isEmpty();
        } finally {
            labelService.setLanDirect(on);
            printService.setLanDirect(on);
            handler.setLanDirect(on);
        }
    }

    @Test
    void C_업무_트랜잭션이_롤백되면_작업도_아웃박스도_없다() throws Exception {
        long p = fakePrinter("ZB1", "ANY");
        template("LOC", "T", "^XA^FD{{locCd}}^FS^XZ", true);
        int before = accounts.query(comp, "select count(*) from sys_outbox", Integer.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userId, comp, "admin1", "admin1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Throwable t = catchThrowable(() -> runner.run(comp, () -> printService.request("LOC", null, p, 1,
                List.of(new PrintItem("LOC:1", Map.of("locCd", "A")), new PrintItem("x".repeat(101), Map.of("locCd", "B"))))));
        assertThat(t).isNotNull();                                                   // 두 번째 항목 bizKey 101자
        assertThat(count("wms_print_job")).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_outbox", Integer.class)).isEqualTo(before);
        // 트랜잭션 없이 부르면 MANDATORY
        assertThat(catchThrowable(() -> printService.request("LOC", null, p, 1, List.of(new PrintItem("LOC:1", Map.of())))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void 재발행은_사유_필수_LOT_은_승인권한_같은_ZPL() throws Exception {
        long p = fakePrinter("ZB1", "ANY");
        long lot = template("LOT", "LOT1", LabelFields.sampleBody("LOT"), true);
        long job = id(postJson("/api/wms/labels/" + lot + "/test-print", "{\"printerId\":" + p + "}", admin));
        postJson("/api/wms/print-jobs/" + job + "/reprint", "{\"reason\":\" \"}", admin).andExpect(jsonPath("$.errors[0].field").value("reason"));
        long printOnly = accounts.newUser(comp, "clerk", PW);
        accounts.grant(comp, printOnly, "CLERK", "wms-print-job", "SEARCH", "PRINT");
        RequestPostProcessor clerk = accounts.as(comp, printOnly, "clerk");
        postJson("/api/wms/print-jobs/" + job + "/reprint", "{\"reason\":\"라벨 훼손\"}", clerk).andExpect(status().isForbidden());
        long re = id(postJson("/api/wms/print-jobs/" + job + "/reprint", "{\"reason\":\"라벨 훼손\"}", admin));
        assertThat(accounts.query(comp, "select (a.zpl = b.zpl and b.reprint_of_id = a.job_id)::text from wms_print_job a, wms_print_job b "
                + "where a.job_id = ? and b.job_id = ?", String.class, job, re)).isEqualTo("true");
        // 로케이션 라벨 재발행은 PRINT 만으로
        template("LOC", "LOC1", "^XA^FD{{locCd}}^FS^XZ", true);
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[" + bin + "],\"printerId\":" + p + "}", admin).andExpect(status().isOk());
        postJson("/api/wms/print-jobs/" + lastJob() + "/reprint", "{\"reason\":\"찢어짐\"}", clerk).andExpect(status().isOk());
        // 직접 SQL: 사유 없는 재발행 행, 작업 수정·삭제
        assertThat(sqlError(comp, "update wms_print_job set copies = 5 where job_id = ?", job)).startsWith("42501");
        assertThat(sqlError(comp, "delete from wms_print_job where job_id = ?", job)).startsWith("42501");
    }

    @Test
    void 이력_조회_기간_필수_상한() throws Exception {
        mvc.perform(get("/api/wms/print-jobs").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/wms/print-jobs").param("from", "2026-01-01").param("to", "2026-06-30").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/wms/print-jobs").param("from", "2026-10-01").param("to", "2026-10-20").param("status", "DONE")
                .with(admin)).andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(get("/api/wms/print-jobs").param("from", "2026-10-01").param("to", "2026-10-20").param("bizKey", "%_")
                .with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/wms/print-jobs/columns").with(admin)).andExpect(status().isOk());
    }

    @Test
    void A사로_B사_프린터_템플릿_작업() throws Exception {
        long p = fakePrinter("ZB1", "ANY");
        long t = template("LOC", "T", "^XA^FD{{locCd}}^FS^XZ", true);
        long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin));
        String other = accounts.newComp("LB");
        long bUser = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bUser, "WMS", "wms-label", "SEARCH", "SAVE", "PRINT");
        accounts.grant(other, bUser, "WMS", "wms-print-job", "SEARCH", "PRINT", "APPROVE");
        accounts.addPlant(other, "P1", "B사");
        RequestPostProcessor b = accounts.as(other, bUser, "badmin");
        long bTpl = id(mvc.perform(json(post("/api/wms/labels"), "{\"labelType\":\"LOC\",\"tplCd\":\"T\",\"tplNm\":\"B\",\"widthMm\":50,"
                + "\"heightMm\":30,\"body\":\"^XA^FD{{locCd}}^FS^XZ\",\"defaultYn\":\"Y\"}").with(b)));
        // B사가 A사 프린터·템플릿·작업
        mvc.perform(json(post("/api/wms/labels/" + bTpl + "/test-print"), "{\"printerId\":" + p + "}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(json(post("/api/wms/labels/" + t + "/test-print"), "{\"printerId\":" + p + "}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/labels/" + t).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/print-jobs/" + job).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/wms/print-jobs/" + job + "/reprint"), "{\"reason\":\"남의 것\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/wms/printers/" + p), "{\"printerNm\":\"x\",\"host\":\"10.0.0.1\",\"port\":9100,\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/printers").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // 직접 SQL: B사 범위에서 A사 프린터·템플릿으로 작업 행
        assertThat(sqlError(other, """
                insert into wms_print_job (comp_cd, plant_cd, printer_id, tpl_id, label_type, biz_key, zpl, zpl_sha256,
                                           outbox_message_id, requested_by)
                values (?, 'P1', ?, ?, 'LOC', 'X', '^XA^XZ', repeat('a', 64), gen_random_uuid(), 't')""", other, p, bTpl))
                .isEqualTo("23503 fk_wms_print_job_printer_comp");
        assertThat(count("wms_print_job")).isEqualTo(1);
    }

    @Test
    void 권한() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "wms-label", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "wms-loc", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        mvc.perform(get("/api/wms/labels").with(v)).andExpect(status().isOk());
        postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"X\",\"printerNm\":\"x\",\"host\":\"10.0.0.1\"}", v)
                .andExpect(status().isForbidden());
        postJson("/api/wms/labels/1/test-print", "{\"printerId\":1}", v).andExpect(status().isForbidden());
        postJson("/api/wms/loc/print", "{\"plantCd\":\"P1\",\"nodeIds\":[1],\"printerId\":1}", v).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/loc/printers").param("plantCd", "P1").with(v)).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/print-jobs/printers").with(v)).andExpect(status().isForbidden());
    }
}
