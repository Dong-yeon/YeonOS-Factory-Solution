package io.smarthappy.module.wms.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
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
 * 라벨 보강 E1 (docs/65 18장) — 설치형(DIRECT) 끝까지: 렌더 → 아웃박스 → 가짜 Zebra → 상태 확인(~HQES·odometer) → PRINTED → 검증 VERIFIED.
 * 정상 + 이상 사용(B 입력·C 동시·D 상태·F 흐름·G 외부 장애·I 주입) + A사로 B사 + 직접 SQL + API 직접.
 */
@AutoConfigureMockMvc
class LabelE1Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    OutboxRelay relay;

    @Autowired
    LabelJobs jobs;

    @Autowired
    LabelPrintService printService;

    @Autowired
    TenantRunner runner;

    String comp;
    long userId;
    RequestPostProcessor admin;
    FakeZebra zebra;

    @BeforeEach
    void setUp() throws IOException {
        zebra = new FakeZebra();
        comp = accounts.newComp("LE");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "WMS", "wms-label", "SEARCH", "SAVE", "PRINT");
        accounts.grant(comp, userId, "WMS", "wms-print-job", "SEARCH", "PRINT", "APPROVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        setPorts(comp, zebra.port() + ",9100");
    }

    @AfterEach
    void tearDown() throws IOException {
        zebra.close();
        SecurityContextHolder.clearContext();
    }

    // =====================================================================
    // 정상 — 설치형 끝까지
    // =====================================================================

    @Test
    void 설치형_보냄_확인_검증_3단() throws Exception {
        long p = printer("ZB1", "ANY", "");
        long t = template("ITEM", "IT1", "^XA^CI28^FO10,10^A0N,30,30^FD{{itemCd}}^FS^FO10,60^BCN,50^FD{{barcode}}^FS^XZ", true);
        long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin));
        assertThat(state(job)).isEqualTo("QUEUED");
        // 상태 확인을 먼저 한 번(기준 odometer) — 확인 안 된 작업 없음
        probe();
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        assertThat(zebra.take()).contains("^FH_^FD84910-2S000^FS");
        assertThat(state(job)).isEqualTo("SENT");
        // 찍힌 뒤 확인 → PRINTED(odometer 1000 → 1001)
        probe();
        assertThat(state(job)).isEqualTo("PRINTED");
        assertThat(accounts.query(comp, "select detail from wms_print_job_event where job_id = ? and event_cd = 'PRINTED'", String.class, job))
                .isEqualTo("odometer 1000→1001");
        assertThat(accounts.query(comp, "select state_cd || ':' || odometer || ':' || odometer_base from wms_printer_status", String.class))
                .isEqualTo("OK:1001:1001");
        // 검증: 다른 값 → VERIFY_NG(상태 그대로), 심볼 식별자·공백 붙은 같은 값 → VERIFIED
        postJson("/api/wms/print-jobs/" + job + "/verify", "{\"scanned\":\"ITEM:OTHER\"}", admin)
                .andExpect(jsonPath("$.data.ok").value(false)).andExpect(jsonPath("$.data.stateCd").value("PRINTED"));
        postJson("/api/wms/print-jobs/" + job + "/verify", "{\"scanned\":\"]C1ITEM:SAMPLE-0001 \\r\\n\"}", admin)
                .andExpect(jsonPath("$.data.ok").value(true)).andExpect(jsonPath("$.data.stateCd").value("VERIFIED"));
        mvc.perform(get("/api/wms/print-jobs/" + job + "/events").with(admin))
                .andExpect(jsonPath("$.data[*].eventCd").value(Matchers.contains("WARN", "PRINTED", "VERIFY_NG", "VERIFIED")));       // 견본 품목명 한글 + 글꼴 없는 프린터 = 경고
        // 목록: 상태 3단 칸·필터·경고 수
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get("/api/wms/print-jobs").param("from", today).param("to", today).param("printState", "VERIFIED").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].printState").value("VERIFIED"))
                .andExpect(jsonPath("$.list[0].warnCount").value(2));
        mvc.perform(get("/api/wms/print-jobs").param("from", today).param("to", today).param("printState", "DONE").with(admin))
                .andExpect(jsonPath("$.errors[0].field").value("printState"));
        // 프린터 목록에 마지막 상태
        mvc.perform(get("/api/wms/printers").with(admin)).andExpect(jsonPath("$.data[0].printerState").value("OK"))
                .andExpect(jsonPath("$.data[0].odometer").value(1001));
    }

    @Test
    void 증가량이_모자라면_기다리고_남는_증가량은_버린다_부수와_PQ() throws Exception {
        long p = printer("ZB1", "ANY", "");
        long t = template("ITEM", "IT1", "^XA^FO10,10^A0N,30,30^FD{{itemCd}}^FS^PQ2^XZ", true);
        probe();                                                                   // 기준 1000
        long a = run(() -> printService.request("ITEM", t, p, 3, List.of(new PrintItem("A", Map.of())))).get(0);   // 3부 × PQ2 = 6장
        zebra.holdPrinting = true;                                                      // 받기만, 아직 안 찍힘
        relay.relay(comp);
        zebra.take();
        zebra.odometer.addAndGet(5);                                                    // 5장만 나옴
        probe();
        assertThat(state(a)).isEqualTo("SENT");
        zebra.odometer.addAndGet(1 + 7);                                                // 나머지 1장 + 다른 곳에서 찍은 7장
        probe();
        assertThat(state(a)).isEqualTo("PRINTED");
        // 기다리는 작업이 없으니 남은 7장은 버림 → 기준 = 지금
        assertThat(accounts.query(comp, "select odometer_base = odometer from wms_printer_status", Boolean.class)).isTrue();
    }

    @Test
    void G_프린터_오류는_PRINTER_ERROR_고치면_이어서_PRINTED_연결_안되면_OFFLINE() throws Exception {
        long p = printer("ZB1", "ANY", "");
        long t = template("ITEM", "IT1", "^XA^FD{{itemCd}}^FS^XZ", true);
        probe();
        long job = run(() -> printService.request("ITEM", t, p, 1, List.of(new PrintItem("A", Map.of())))).get(0);
        zebra.errorBits = 0x1 | 0x4;                                                     // 용지 없음 + 헤드 열림
        zebra.warningBits = 0x8;
        relay.relay(comp);
        zebra.take();
        probe();
        assertThat(state(job)).isEqualTo("PRINTER_ERROR");
        assertThat(accounts.query(comp, "select reason_cd || '|' || detail from wms_print_job_event where job_id = ?", String.class, job))
                .isEqualTo("MEDIA_OUT|MEDIA_OUT,HEAD_OPEN");
        assertThat(accounts.query(comp, "select state_cd || '|' || errors || '|' || warnings from wms_printer_status", String.class))
                .isEqualTo("ERROR|MEDIA_OUT,HEAD_OPEN|PAPER_NEAR_END");
        probe();                                                                   // 같은 오류가 계속 — 사건은 한 번
        assertThat(accounts.query(comp, "select count(*) from wms_print_job_event where job_id = ?", Integer.class, job)).isEqualTo(1);
        // 용지 넣고 찍힘
        zebra.errorBits = 0;
        zebra.warningBits = 0;
        zebra.odometer.incrementAndGet();
        probe();
        assertThat(state(job)).isEqualTo("PRINTED");
        // 프린터가 꺼짐 → OFFLINE, 상태 질의에 답이 없는 프린터 → UNKNOWN
        zebra.silent = true;
        mvc.perform(post("/api/wms/printers/" + p + "/probe").with(csrf()).with(admin)).andExpect(jsonPath("$.data.printerState").value("UNKNOWN"));
        zebra.close();
        mvc.perform(post("/api/wms/printers/" + p + "/probe").with(csrf()).with(admin)).andExpect(jsonPath("$.data.printerState").value("OFFLINE"));
        assertThat(state(job)).isEqualTo("PRINTED");                                    // 끝난 작업은 그대로
    }

    @Test
    void D_사람_확정은_모르는_작업만_LOT_은_승인() throws Exception {
        long p = printer("ZB1", "ANY", "");
        long t = template("LOT", "LT1", "^XA^FD{{lotNo}}^FS^XZ", true);
        long job = run(() -> printService.request("LOT", t, p, 1, List.of(new PrintItem("LOT:1", Map.of("lotNo", "L1"))))).get(0);
        // 보내기 전은 닫을 수 없다(아웃박스가 그래도 보낸다)
        postJson("/api/wms/print-jobs/" + job + "/resolve", "{\"stateCd\":\"CANCELLED\",\"reason\":\"취소\"}", admin)
                .andExpect(status().isConflict());
        probe();
        zebra.errorBits = 0x2;
        relay.relay(comp);
        zebra.take();
        probe();
        assertThat(state(job)).isEqualTo("PRINTER_ERROR");
        postJson("/api/wms/print-jobs/" + job + "/resolve", "{\"stateCd\":\"CANCELLED\",\"reason\":\" \"}", admin)
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson("/api/wms/print-jobs/" + job + "/resolve", "{\"stateCd\":\"VERIFIED\",\"reason\":\"억지\"}", admin)
                .andExpect(jsonPath("$.errors[0].field").value("stateCd"));
        long clerk = accounts.newUser(comp, "clerk", PW);
        accounts.grant(comp, clerk, "CLERK", "wms-print-job", "SEARCH", "PRINT");
        postJson("/api/wms/print-jobs/" + job + "/resolve", "{\"stateCd\":\"CANCELLED\",\"reason\":\"리본 교체 후 재발행\"}",
                accounts.as(comp, clerk, "clerk")).andExpect(status().isForbidden());
        postJson("/api/wms/print-jobs/" + job + "/resolve", "{\"stateCd\":\"CANCELLED\",\"reason\":\"리본 교체 후 재발행\"}", admin)
                .andExpect(jsonPath("$.data").value("CANCELLED"));
        // 닫힌 작업 — 늦게 오른 odometer 는 STALE 로만(상태를 되돌리지 않는다)
        zebra.errorBits = 0;
        zebra.odometer.incrementAndGet();
        probe();
        assertThat(state(job)).isEqualTo("CANCELLED");
        // 검증도 끝난 작업이면 409
        postJson("/api/wms/print-jobs/" + job + "/verify", "{\"scanned\":\"LOT:1\"}", admin).andExpect(status().isConflict());
    }

    // =====================================================================
    // 6.3 한글 글꼴 · 6.4 미리보기 · 6.5 템플릿 규칙 · 6.6 GS1
    // =====================================================================

    @Test
    void 한글_값_글꼴_없는_프린터는_경고_사건() throws Exception {
        long noFont = printer("ZB1", "ANY", "");
        long withFont = printer("ZB2", "ANY", ",\"cjkFontYn\":\"Y\",\"cjkFontFile\":\"e:nanumgothic.ttf\"");
        assertThat(accounts.query(comp, "select cjk_font_file from wms_printer where printer_id = ?", String.class, withFont))
                .isEqualTo("E:NANUMGOTHIC.TTF");
        long t = template("ITEM", "IT1", LabelFields.sampleBody("ITEM"), true);
        long a = run(() -> printService.request("ITEM", t, noFont, 1, List.of(new PrintItem("A", Map.of("itemNm", "브래킷"))))).get(0);
        long b = run(() -> printService.request("ITEM", t, withFont, 1, List.of(new PrintItem("B", Map.of("itemNm", "브래킷"))))).get(0);
        long c = run(() -> printService.request("ITEM", t, noFont, 1, List.of(new PrintItem("C", Map.of("itemNm", "BRACKET"))))).get(0);
        assertThat(accounts.query(comp, "select string_agg(job_id || ':' || event_cd || ':' || reason_cd, ',') from wms_print_job_event",
                String.class)).isEqualTo(a + ":WARN:NO_CJK_FONT");
        assertThat(b + c).isPositive();
        postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"X\",\"printerNm\":\"x\",\"host\":\"10.1.1.1\",\"port\":9100,"
                + "\"cjkFontYn\":\"M\",\"cjkFontFile\":\"C:/fonts/x.ttf\",\"mediaWidthMm\":5,\"statusCheckYn\":\"Q\",\"connMode\":\"CLOUD\"}", admin)
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("cjkFontYn", "cjkFontFile", "mediaWidthMm",
                        "statusCheckYn", "connMode")));
    }

    @Test
    void 미리보기_배치와_경고_두_벌() throws Exception {
        String body = "^XA^PW400^LL240^FO20,16^A0N,30,30^FD{{itemNm}}^FS^FO20,60^AJN,22,22^FB200,1,0,L^FD{{spec}}^FS"
                + "^FO300,200^BQN,2,4^FDQA,{{barcode}}^FS^XZ";
        MvcResult r = postJson("/api/wms/labels/preview", "{\"labelType\":\"ITEM\",\"body\":" + q(body)
                + ",\"widthMm\":50,\"heightMm\":30,\"dpi\":203}", admin).andExpect(status().isOk()).andReturn();
        String json = r.getResponse().getContentAsString();
        List<String> warns = JsonPath.read(json, "$.data.warnings");
        // 한글 칸 기본 글꼴 · ^CI28 없음 · 별칭 J 대응 없음 · QR 이 라벨 아래로
        assertThat(warns).contains("CJK_FONT_MISSING:itemNm", "CI28_MISSING", "OFF_LABEL:barcode");
        List<String> longWarns = JsonPath.read(json, "$.data.longWarnings");
        assertThat(longWarns).contains("OVERFLOW_X:itemNm", "FB_LINES:spec");                    // 품목명 한글 40자 · 규격 40자
        List<String> texts = JsonPath.read(json, "$.data.warningTexts");
        assertThat(texts).anyMatch(s -> s.contains("itemNm") && s.contains("한글"));
        assertThat((Integer) JsonPath.read(json, "$.data.layout.widthDots")).isEqualTo(400);
        List<String> kinds = JsonPath.read(json, "$.data.layout.elements[*].kind");
        assertThat(kinds).containsExactly("TEXT", "TEXT", "QR");
        // 견본 템플릿은 경고 없음(한글 글꼴 J 를 ^CW 로 대응)
        MvcResult s = postJson("/api/wms/labels/preview", "{\"labelType\":\"LOT\",\"body\":" + q(LabelFields.sampleBody("LOT")) + "}", admin)
                .andReturn();
        assertThat((List<?>) JsonPath.read(s.getResponse().getContentAsString(), "$.data.warnings")).isEmpty();
    }

    @Test
    void 템플릿_규칙_거래처_품목_기본_순서와_dpi_용지_검사() throws Exception {
        long p = printer("ZB1", "ANY", ",\"mediaWidthMm\":60");
        long dflt = template("SHIP", "S-STD", "^XA^FDSTD{{shipNo}}^FS^XZ", true);
        long forItem = template("SHIP", "S-ITEM", "^XA^FDITEM{{shipNo}}^FS^XZ", false);
        long forCust = template("SHIP", "S-CUST", "^XA^FDCUST{{shipNo}}^FS^XZ", false);
        long item = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BRKT', 'ZT 브래킷', 'FG', 'MAKE', 'EA', 't', 't') returning item_id""", Long.class, comp);
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'ZT-C1', 'ZT 고객', 'SALES', 't', 't') returning cust_id""", Long.class, comp);
        long rItem = id(postJson("/api/wms/label-rules", "{\"labelType\":\"SHIP\",\"scopeCd\":\"ITEM\",\"itemId\":" + item + ",\"tplId\":"
                + forItem + "}", admin));
        id(postJson("/api/wms/label-rules", "{\"labelType\":\"SHIP\",\"scopeCd\":\"CUST\",\"custId\":" + cust + ",\"tplId\":" + forCust + "}",
                admin));
        // 같은 대상 두 번 · 다른 유형 템플릿 · 대상 없음
        postJson("/api/wms/label-rules", "{\"labelType\":\"SHIP\",\"scopeCd\":\"ITEM\",\"itemId\":" + item + ",\"tplId\":" + dflt + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long lotTpl = template("LOT", "L1", "^XA^FD{{lotNo}}^FS^XZ", true);
        postJson("/api/wms/label-rules", "{\"labelType\":\"SHIP\",\"scopeCd\":\"CUST\",\"custId\":" + cust + ",\"tplId\":" + lotTpl + "}", admin)
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.hasItem("LABEL_TYPE_MISMATCH")));
        postJson("/api/wms/label-rules", "{\"labelType\":\"SHIP\",\"scopeCd\":\"CUST\",\"tplId\":" + dflt + "}", admin)
                .andExpect(jsonPath("$.errors[0].field").value("custId"));
        // 거래처 > 품목 > 기본
        List<Long> ids = run(() -> printService.request("SHIP", null, p, 1, List.of(
                new PrintItem("S1", Map.of("shipNo", "1"), cust, item),
                new PrintItem("S2", Map.of("shipNo", "2"), null, item),
                new PrintItem("S3", Map.of("shipNo", "3"), null, null),
                new PrintItem("S4", Map.of("shipNo", "4")))));
        assertThat(accounts.query(comp, "select string_agg(t.tpl_cd, ',' order by j.job_id) from wms_print_job j join wms_label_tpl t "
                + "on t.tpl_id = j.tpl_id where j.job_id = any(?)", String.class, (Object) ids.toArray(new Long[0])))
                .isEqualTo("S-CUST,S-ITEM,S-STD,S-STD");
        // 규칙 사용 중지 → 기본으로, 대상은 고정
        mvc.perform(json(put("/api/wms/label-rules/" + rItem), "{\"tplId\":" + forItem + ",\"useYn\":\"N\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/wms/label-rules/" + rItem), "{\"scopeCd\":\"CUST\",\"tplId\":" + forItem + ",\"rowVer\":1}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        long s5 = run(() -> printService.request("SHIP", null, p, 1, List.of(new PrintItem("S5", Map.of(), null, item))).get(0));
        assertThat(tplOf(s5)).isEqualTo("S-STD");
        // dpi 다름 · 용지보다 넓음 → 거부(작업 없음)
        long wide = id(postJson("/api/wms/labels", "{\"labelType\":\"ITEM\",\"tplCd\":\"WIDE\",\"tplNm\":\"w\",\"widthMm\":100,"
                + "\"heightMm\":70,\"body\":\"^XA^FD{{itemCd}}^FS^XZ\"}", admin));
        long dpi300 = id(postJson("/api/wms/labels", "{\"labelType\":\"ITEM\",\"tplCd\":\"D300\",\"tplNm\":\"d\",\"widthMm\":50,"
                + "\"heightMm\":30,\"dpi\":300,\"body\":\"^XA^FD{{itemCd}}^FS^XZ\"}", admin));
        postJson("/api/wms/labels/" + wide + "/test-print", "{\"printerId\":" + p + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("MEDIA_TOO_SMALL"));
        postJson("/api/wms/labels/" + dpi300 + "/test-print", "{\"printerId\":" + p + "}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("DPI_MISMATCH"));
        mvc.perform(get("/api/wms/labels/" + wide).with(admin)).andExpect(jsonPath("$.data.sizeCd").value("100x70"));
        mvc.perform(get("/api/wms/label-rules/targets").param("scope", "ITEM").param("q", "brkt").with(admin))
                .andExpect(jsonPath("$.data[0].cd").value("ZT-BRKT"));
    }

    @Test
    void GS1_데이터매트릭스_FNC1_과_주입_차단() throws Exception {
        long p = printer("ZB1", "ANY", "");
        // 제어 문자 없는 ^BX · 다른 글자와 섞임 · ^BX 밖 → 저장 거부
        for (String bad : List.of("^XA^FO10,10^BXN,6,200^FD{{gs1}}^FS^XZ", "^XA^BXN,6,200,,,,@^FDX{{gs1}}^FS^XZ",
                "^XA^FO10,10^A0N,20,20^FD{{gs1}}^FS^XZ", "^XA^BXN,6,200,,,,_^FD{{gs1}}^FS^XZ")) {
            postJson("/api/wms/labels", "{\"labelType\":\"LOT\",\"tplCd\":\"G\",\"tplNm\":\"g\",\"widthMm\":50,\"heightMm\":30,\"body\":"
                    + q(bad) + "}", admin).andExpect(jsonPath("$.errors[*].code").value(Matchers.hasItem("GS1_FIELD")));
        }
        long t = template("LOT", "G1", "^XA^FO10,10^BXN,6,200,,,,@^FD{{gs1}}^FS^FO10,150^A0N,20,20^FD{{lotNo}}^FS^XZ", true);
        long job = run(() -> printService.request("LOT", t, p, 1, List.of(new PrintItem("LOT:L1", Map.of("lotNo", "L1",
                "gs1", "(01)08801234567893(10)L1(17)271231(21)S9"))))).get(0);
        String zpl = accounts.query(comp, "select zpl from wms_print_job where job_id = ?", String.class, job);
        // FNC1 = @1 — 맨 앞(GS1 모드) + 가변 AI(10) 뒤에만, 마지막(21) 뒤는 없음. ^BX 필드에는 ^FH 가 없다
        assertThat(zpl).contains("^BXN,6,200,,,,@^FD@10108801234567893" + "10L1@117271231" + "21S9^FS")
                .contains("^FH_^FDL1^FS");
        // 주입·잘못된 값: ^ 글자 · 검사 숫자 · 지원 안 하는 AI · 괄호 형식 아님
        for (String[] bad : new String[][] {{"(10)A^XZ", "VALUE_CHARSET"}, {"(01)08801234567890", "GS1_CHECK_DIGIT"},
                {"(99)X", "GS1_UNKNOWN_AI"}, {"010880123456789310L1", "GS1_FORMAT"}, {"(10)A@1(21)B", "VALUE_CHARSET"}}) {
            Throwable e = catchThrowable(() -> run(() -> printService.request("LOT", t, p, 1,
                    List.of(new PrintItem("LOT:X", Map.of("gs1", bad[0]))))));
            assertThat(e).as(bad[0]).isInstanceOf(io.smarthappy.platform.common.web.BusinessException.class);
            assertThat(((io.smarthappy.platform.common.web.BusinessException) e).fieldErrors().get(0).code()).as(bad[0]).isEqualTo(bad[1]);
        }
    }

    // =====================================================================
    // A사로 B사 · 직접 SQL · 권한
    // =====================================================================

    @Test
    void A사로_B사_작업_사건_프린터_규칙() throws Exception {
        long p = printer("ZB1", "ANY", "");
        long t = template("ITEM", "IT1", "^XA^FD{{itemCd}}^FS^XZ", true);
        long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin));
        long aItem = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-A', 'A사 품목', 'FG', 'MAKE', 'EA', 't', 't') returning item_id""", Long.class, comp);
        String other = accounts.newComp("LF");
        long bUser = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bUser, "WMS", "wms-label", "SEARCH", "SAVE", "PRINT");
        accounts.grant(other, bUser, "WMS", "wms-print-job", "SEARCH", "PRINT", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bUser, "badmin");
        mvc.perform(get("/api/wms/print-jobs/" + job + "/events").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/wms/print-jobs/" + job + "/verify"), "{\"scanned\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/wms/print-jobs/" + job + "/resolve"), "{\"stateCd\":\"PRINTED\",\"reason\":\"남의 것\"}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/wms/printers/" + p + "/probe").with(csrf()).with(b)).andExpect(status().isNotFound());
        long bTpl = id(mvc.perform(json(post("/api/wms/labels"), "{\"labelType\":\"ITEM\",\"tplCd\":\"T\",\"tplNm\":\"B\",\"widthMm\":50,"
                + "\"heightMm\":30,\"body\":\"^XA^FD{{itemCd}}^FS^XZ\"}").with(b)));
        // B사 규칙에 A사 품목 → 대상 없음
        mvc.perform(json(post("/api/wms/label-rules"), "{\"labelType\":\"ITEM\",\"scopeCd\":\"ITEM\",\"itemId\":" + aItem + ",\"tplId\":"
                + bTpl + "}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get("/api/wms/label-rules/targets").param("scope", "ITEM").param("q", "ZT").with(b))
                .andExpect(jsonPath("$.data.length()").value(0));
        // 직접 SQL: B사 범위에서 A사 작업에 사건 · A사 품목 규칙 · A사 프린터 상태
        assertThat(sqlError(other, """
                insert into wms_print_job_event (comp_cd, job_id, event_cd, src, occurred_at, recorded_by)
                values (?, ?, 'PRINTED', 'SYSTEM', now(), 't')""", other, job)).isEqualTo("23503 fk_wms_print_job_event_comp");
        assertThat(sqlError(other, """
                insert into wms_label_tpl_rule (comp_cd, label_type, scope_cd, item_id, tpl_id, created_by, updated_by)
                values (?, 'ITEM', 'ITEM', ?, ?, 't', 't')""", other, aItem, bTpl)).isEqualTo("23503 fk_wms_label_tpl_rule_item_comp");
        assertThat(sqlError(other, "insert into wms_printer_status (comp_cd, printer_id, src) values (?, ?, 'DIRECT')", other, p))
                .isEqualTo("23503 fk_wms_printer_status_comp");
    }

    @Test
    void 직접_SQL_사건_원장은_추가만_사람_닫기는_사유() throws Exception {
        long p = printer("ZB1", "ANY", "");
        long t = template("ITEM", "IT1", "^XA^FD{{itemCd}}^FS^XZ", true);
        long job = id(postJson("/api/wms/labels/" + t + "/test-print", "{\"printerId\":" + p + "}", admin));
        accounts.exec(comp, "insert into wms_print_job_event (comp_cd, job_id, event_cd, src, occurred_at, recorded_by) "
                + "values (?, ?, 'WARN', 'SYSTEM', now(), 't')", comp, job);
        assertThat(sqlError(comp, "update wms_print_job_event set event_cd = 'PRINTED' where job_id = ?", job)).startsWith("42501");
        assertThat(sqlError(comp, "delete from wms_print_job_event where job_id = ?", job)).startsWith("42501");
        assertThat(sqlError(comp, "insert into wms_print_job_event (comp_cd, job_id, event_cd, src, occurred_at, recorded_by) "
                + "values (?, ?, 'CANCELLED', 'USER', now(), 't')", comp, job)).isEqualTo("23514 ck_wms_print_job_event_user");
        assertThat(sqlError(comp, "insert into wms_print_job_event (comp_cd, job_id, event_cd, src, occurred_at, recorded_by) "
                + "values (?, ?, 'DONE', 'SYSTEM', now(), 't')", comp, job)).isEqualTo("23514 ck_wms_print_job_event_cd");
        assertThat(sqlError(comp, "update wms_printer set cjk_font_file = 'C:\\x.ttf' where printer_id = ?", p))
                .isEqualTo("23514 ck_wms_printer_cjk_file");
    }

    @Test
    void 권한_API_직접() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "wms-label", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "wms-print-job", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        postJson("/api/wms/print-jobs/1/verify", "{\"scanned\":\"x\"}", v).andExpect(status().isForbidden());
        postJson("/api/wms/print-jobs/1/resolve", "{\"stateCd\":\"PRINTED\",\"reason\":\"xx\"}", v).andExpect(status().isForbidden());
        mvc.perform(post("/api/wms/printers/1/probe").with(csrf()).with(v)).andExpect(status().isForbidden());
        postJson("/api/wms/label-rules", "{}", v).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/label-rules").with(v)).andExpect(status().isOk());
        mvc.perform(get("/api/wms/print-jobs/1/events").with(v)).andExpect(status().isNotFound());
        // CSRF 없이
        mvc.perform(post("/api/wms/print-jobs/1/verify").contentType(MediaType.APPLICATION_JSON).content("{}").with(admin))
                .andExpect(status().isForbidden());
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    interface Work<T> {
        T get() throws Exception;
    }

    <T> T run(Work<T> w) {
        return runner.call(comp, () -> {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new LoginUser(userId, comp, "admin1", "admin1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            try {
                return w.get();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    /** 정기 작업 한 번 — 주기를 기다리지 않게 마지막 확인 시각을 뒤로 */
    int probe() {
        accounts.exec(comp, "update wms_printer_status set checked_at = now() - interval '1 hour'");
        return jobs.probe();
    }

    String tplOf(long job) {
        return accounts.query(comp, "select t.tpl_cd from wms_print_job j join wms_label_tpl t on t.tpl_id = j.tpl_id where j.job_id = ?",
                String.class, job);
    }

    String state(long job) {
        return runner.call(comp, () -> printService.job(job).getPrintState());
    }

    void setPorts(String c, String ports) {
        accounts.exec(c, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'WMS_PRINTER_PORTS', ?, 't', 't')
                on conflict (comp_cd, plant_cd, policy_key) do update set policy_value = excluded.policy_value""", c, ports);
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

    private long printer(String cd, String usage, String extra) throws Exception {
        return id(postJson("/api/wms/printers", "{\"plantCd\":\"P1\",\"printerCd\":\"" + cd + "\",\"printerNm\":\"가짜\","
                + "\"host\":\"127.0.0.1\",\"port\":" + zebra.port() + ",\"usage\":\"" + usage + "\"" + extra + "}", admin));
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private long template(String type, String cd, String body, boolean dflt) throws Exception {
        return id(postJson("/api/wms/labels", "{\"labelType\":\"" + type + "\",\"tplCd\":\"" + cd + "\",\"tplNm\":\"" + cd
                + "\",\"widthMm\":50,\"heightMm\":30,\"body\":" + q(body) + ",\"defaultYn\":\"" + (dflt ? "Y" : "N") + "\"}", admin));
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }
}
