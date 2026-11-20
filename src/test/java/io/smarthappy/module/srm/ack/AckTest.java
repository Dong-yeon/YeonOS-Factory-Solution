package io.smarthappy.module.srm.ack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM S5 납기 회신 (docs/31 4.5) — 분할 납기·상태(수락·부분·거절)·경고(모자람·늦음), 재회신 = 새 순번(최신 유효),
 * 개정하면 이전 개정 회신, 취소·작성 중 발주 거부, 초과·같은 날·발주일 전·미래 거부, 직접 SQL(추가만·같은 트랜잭션·상태 우회),
 * 엑셀 회신(발주마다 묶음 하나·다른 협력사·이전 개정), 진행 현황(회신 안 옴·늦음·임박), A사로 B사, 권한, 동시.
 */
@AutoConfigureMockMvc
class AckTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String ACK = "/api/srm/acks";
    private static final String IMP = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long buyer;
    RequestPostProcessor me;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AK");
        buyer = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, buyer, "PUR", "srm-ack", "SEARCH", "SAVE");
        accounts.grant(comp, buyer, "PUR", "srm-po-status", "SEARCH");
        me = accounts.as(comp, buyer, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        seed(comp);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        item(c, "BOLT-M8", "EA");
        item(c, "RESIN-A", "KG");
        supplier(c, "V100");
        supplier(c, "V200");
    }

    private void item(String c, String cd, String unit) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", c, cd, cd + " 품목", unit);
    }

    private void supplier(String c, String cd) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 상사");
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Object data(ResultActions r, String path) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), path);
    }

    private String poDetail(RequestPostProcessor who, long id) throws Exception {
        return mvc.perform(get(PO + "/" + id).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void act(RequestPostProcessor who, long id, String action, String reason) throws Exception {
        int rv = JsonPath.read(poDetail(who, id), "$.data.po.rowVer");
        postAs(who, PO + "/" + id + "/" + action, "{\"rowVer\":" + rv + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}")
                .andExpect(status().isOk());
    }

    /** 발행된 발주(결재 정책 N → 확정 → 발행). 행: BOLT-M8 100 EA, RESIN-A 2.5 KG — 요구 납기 +20일 */
    private long issued(RequestPostProcessor who, String cust) throws Exception {
        String due = today.plusDays(20).toString();
        long id = ((Number) data(postAs(who, PO, "{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"lines\":["
                + "{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"10\",\"dueDate\":\"" + due + "\"},"
                + "{\"itemCd\":\"RESIN-A\",\"qty\":\"2.5\",\"unitPrice\":\"100\",\"dueDate\":\"" + due + "\"}]}"), "$.data")).longValue();
        act(who, id, "confirm", null);
        act(who, id, "issue", null);
        return id;
    }

    private String detail(RequestPostProcessor who, long poId) throws Exception {
        return mvc.perform(get(ACK + "/pos/" + poId).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long line(long poId, int idx) throws Exception {
        return ((Number) JsonPath.read(detail(me, poId), "$.data.lines[" + idx + "].poLineId")).longValue();
    }

    private int rev(long poId) throws Exception {
        return JsonPath.read(detail(me, poId), "$.data.po.rev");
    }

    private static String split(long lineId, String date, String qty) {
        return "{\"poLineId\":" + lineId + ",\"promisedDate\":\"" + date + "\",\"promisedQty\":\"" + qty + "\"}";
    }

    private ResultActions ack(RequestPostProcessor who, long poId, int rev, String linesJson) throws Exception {
        return postAs(who, ACK + "/pos/" + poId, "{\"rev\":" + rev + ",\"ackByNm\":\"김협력\",\"lines\":[" + linesJson + "]}");
    }

    private String poStatus(long poId) throws Exception {
        return JsonPath.read(poDetail(me, poId), "$.data.po.status");
    }

    private String d(int plus) {
        return today.plusDays(plus).toString();
    }

    // ---------- 정상 ----------

    @Test
    void 분할납기로_다_약속하면_수락_발주는_ACKED_모자라면_부분과_경고_늦은약속도_경고_재회신은_새순번이_유효() throws Exception {
        long po = issued(me, "V100");
        long l1 = line(po, 0);
        long l2 = line(po, 1);
        // 100 EA 를 60 + 40 두 날로, 2.5 KG 한 번에 → 수락
        ResultActions r = ack(me, po, 0, split(l1, d(10), "60") + "," + split(l1, d(15), "40") + "," + split(l2, d(20), "2.5"));
        r.andExpect(jsonPath("$.data.ackSeq").value(1)).andExpect(jsonPath("$.data.ackStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.warnings.length()").value(0));
        assertThat(poStatus(po)).isEqualTo("ACKED");
        String dt = detail(me, po);
        assertThat((String) JsonPath.read(dt, "$.data.lines[0].promisedQty")).isEqualTo("100");
        assertThat((String) JsonPath.read(dt, "$.data.lines[0].promisedFirst")).isEqualTo(d(10));
        assertThat((String) JsonPath.read(dt, "$.data.lines[0].promisedLast")).isEqualTo(d(15));
        assertThat((String) JsonPath.read(dt, "$.data.po.ackStatus")).isEqualTo("ACCEPTED");
        // 재회신: 행 2 는 약속 없음 + 행 1 은 80 만, 요구 납기(+20)보다 늦은 +25 → 부분 + 경고 SHORT 2개·LATE 1개
        ack(me, po, 0, split(l1, d(25), "80"))
                .andExpect(jsonPath("$.data.ackSeq").value(2)).andExpect(jsonPath("$.data.ackStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.warnings[?(@.kind == 'SHORT')].lineNo").value(Matchers.containsInAnyOrder(1, 2)))
                .andExpect(jsonPath("$.data.warnings[?(@.kind == 'LATE')].lineNo").value(Matchers.contains(1)));
        dt = detail(me, po);
        assertThat((Integer) JsonPath.read(dt, "$.data.po.ackSeq")).isEqualTo(2);
        assertThat((List<Boolean>) JsonPath.read(dt, "$.data.acks[*].valid")).containsExactly(true, false);
        assertThat((String) JsonPath.read(dt, "$.data.lines[0].promisedQty")).isEqualTo("80");
        assertThat((Object) JsonPath.read(dt, "$.data.lines[1].promisedQty")).isNull();
        // 이전 묶음은 그대로 남는다(추가만)
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack_line", Integer.class)).isEqualTo(4);
        // 거절 재회신 → 발주는 ISSUED 로, 사유 필수
        postAs(me, ACK + "/pos/" + po, "{\"rev\":0,\"reject\":true}").andExpect(jsonPath("$.errors[0].field").value("rejectReason"));
        postAs(me, ACK + "/pos/" + po, "{\"rev\":0,\"reject\":true,\"rejectReason\":\"설비 고장으로 생산 불가\"}")
                .andExpect(jsonPath("$.data.ackStatus").value("REJECTED"));
        assertThat(poStatus(po)).isEqualTo("ISSUED");
        // 거절에 약속 행을 붙이면 400
        postAs(me, ACK + "/pos/" + po, "{\"rev\":0,\"reject\":true,\"rejectReason\":\"x\",\"lines\":[" + split(l1, d(5), "1") + "]}")
                .andExpect(status().isBadRequest());
        // 다시 수락 → ACKED
        ack(me, po, 0, split(l1, d(10), "100") + "," + split(l2, d(10), "2.5")).andExpect(jsonPath("$.data.ackSeq").value(4));
        assertThat(poStatus(po)).isEqualTo("ACKED");
    }

    @Test
    void 개정하면_이전개정_회신은_유효아님_작성중_취소된_발주는_회신거부_개정번호가_다르면_409() throws Exception {
        long po = issued(me, "V100");
        ack(me, po, 0, split(line(po, 0), d(10), "100") + "," + split(line(po, 1), d(10), "2.5")).andExpect(status().isOk());
        act(me, po, "revise", null);                 // ACKED → DRAFT rev 1
        ack(me, po, 1, split(line(po, 0), d(10), "100")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(me, po, "confirm", null);
        act(me, po, "issue", null);
        String dt = detail(me, po);
        assertThat((Object) JsonPath.read(dt, "$.data.po.ackStatus")).isNull();
        assertThat((Boolean) JsonPath.read(dt, "$.data.acks[0].oldRev")).isTrue();
        assertThat((Boolean) JsonPath.read(dt, "$.data.acks[0].valid")).isFalse();
        // 화면이 옛 개정(0)을 들고 회신 → 409
        ack(me, po, 0, split(line(po, 0), d(10), "100")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        // 회신 없음 목록에 보인다
        mvc.perform(get(ACK + "/pos?from=" + d(-1) + "&to=" + d(0) + "&state=NO_ACK").with(me))
                .andExpect(jsonPath("$.data[*].poId").value(Matchers.hasItem((int) po)));
        ack(me, po, 1, split(line(po, 0), d(10), "100")).andExpect(jsonPath("$.data.ackSeq").value(2));
        // 취소된 발주는 회신 거부
        act(me, po, "cancel", "협력사 사정");
        ack(me, po, 1, split(line(po, 0), d(10), "100")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        // 발행 전 발주는 회신 화면에서도 없는 것(404)
        long draft = ((Number) data(postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[]}"), "$.data")).longValue();
        mvc.perform(get(ACK + "/pos/" + draft).with(me)).andExpect(status().isNotFound());
        ack(me, draft, 0, "").andExpect(status().isConflict());
    }

    // ---------- 이상 사용 ----------

    @Test
    void 초과_같은날_발주일전_미래회신일_빈회신_다른발주행_잘못된수량은_거부() throws Exception {
        long po = issued(me, "V100");
        long l1 = line(po, 0);
        long l2 = line(po, 1);
        // 60 + 50 > 100 → 두 줄 모두 SRM_ACK_OVER(인자 = 열린 수량)
        ack(me, po, 0, split(l1, d(10), "60") + "," + split(l1, d(12), "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.code == 'SRM_ACK_OVER')].rowKey").value(Matchers.containsInAnyOrder("0", "1")))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("100")));
        // 같은 행 같은 날 두 줄
        ack(me, po, 0, split(l1, d(10), "10") + "," + split(l1, d(10), "10"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE")).andExpect(jsonPath("$.errors[0].rowKey").value("1"));
        // 발주일 전 약속, 단위 자릿수(EA = 0) 넘는 수량, 0 수량, 날짜 형식
        ack(me, po, 0, split(l1, d(-1), "10") + "," + split(l2, d(3), "0") + "," + split(l1, d(4), "1.5") + "," + split(l1, "2026-13-01", "1"))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(Matchers.contains("SRM_ACK_BEFORE_PO")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code").value(Matchers.contains("POSITIVE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(Matchers.contains("SCALE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value(Matchers.contains("FORMAT")));
        // 회신일 미래·발주일 전
        postAs(me, ACK + "/pos/" + po, "{\"rev\":0,\"ackOn\":\"" + d(1) + "\",\"lines\":[" + split(l1, d(5), "1") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postAs(me, ACK + "/pos/" + po, "{\"rev\":0,\"ackOn\":\"" + d(-3) + "\",\"lines\":[" + split(l1, d(5), "1") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("SRM_ACK_BEFORE_PO"));
        // 빈 회신 · 개정 없음 · 이상한 본문
        ack(me, po, 0, "").andExpect(jsonPath("$.errors[0].code").value("LINES_REQUIRED"));
        postAs(me, ACK + "/pos/" + po, "{\"lines\":[]}").andExpect(status().isBadRequest());
        postAs(me, ACK + "/pos/" + po, "[1,2]").andExpect(status().isBadRequest());
        // 다른 발주의 행 id → 400(행 오류가 아니라 요청 오류)
        long other = issued(me, "V200");
        ack(me, po, 0, split(line(other, 0), d(5), "1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        // 회신자 이름 길이·제어문자
        postAs(me, ACK + "/pos/" + po, "{\"rev\":0,\"ackByNm\":\"" + "가".repeat(101) + "\",\"lines\":[" + split(l1, d(5), "1") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack", Integer.class)).isZero();
        assertThat(poStatus(po)).isEqualTo("ISSUED");
    }

    @Test
    void 직접SQL_회신은_추가만_행은_같은트랜잭션에서만_상태만_ACKED로_바꾸기_초과는_DB가_막는다() throws Exception {
        long po = issued(me, "V100");
        long l1 = line(po, 0);
        ack(me, po, 0, split(l1, d(10), "100") + "," + split(line(po, 1), d(10), "2.5")).andExpect(status().isOk());
        long ackId = accounts.query(comp, "select ack_id from srm_po_ack", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_ack set ack_by_nm = 'x' where ack_id = ?", ackId)))).contains("ck_srm_po_ack_append_only");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_ack set ack_status = 'PARTIAL' where ack_id = ?", ackId)))).contains("ck_srm_po_ack_append_only");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_po_ack_line"))).rootCause()
                .hasMessageContaining("permission denied");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update srm_po_ack_line set promised_qty = 1"))).rootCause()
                .hasMessageContaining("permission denied");
        // 나중 트랜잭션에서 약속 덧붙이기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_po_ack_line (comp_cd, ack_id, po_line_id, promised_date, promised_qty, created_by) values (?, ?, ?, (now() at time zone 'Asia/Seoul')::date + 30, 1, 't')",
                comp, ackId, l1)))).contains("ck_srm_po_ack_line_same_tx");
        // 회신 없이 상태만 ACKED → ISSUED 로
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'ISSUED' where po_id = ?", po)))).contains("ck_srm_po_ack_required");
        // 한 트랜잭션에서 묶음 + 열린 수량 초과 약속 → 트리거
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                do $$ declare v bigint; begin
                  insert into srm_po_ack (comp_cd, po_id, rev, ack_seq, source, ack_status, ack_on, created_by)
                  values (hb_current_comp(), %d, 0, 9, 'MANUAL', 'PARTIAL', (now() at time zone 'Asia/Seoul')::date, 't') returning ack_id into v;
                  insert into srm_po_ack_line (comp_cd, ack_id, po_line_id, promised_date, promised_qty, created_by)
                  values (hb_current_comp(), v, %d, (now() at time zone 'Asia/Seoul')::date + 5, 101, 't');
                end $$""".formatted(po, l1))))).contains("ck_srm_po_ack_line_over");
        // 거절 사유 없는 거절(CHECK — NULL 통과 함정), 이전 개정 번호
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_po_ack (comp_cd, po_id, rev, ack_seq, source, ack_status, ack_on, created_by) values (?, ?, 0, 9, 'MANUAL', 'REJECTED', (now() at time zone 'Asia/Seoul')::date, 't')",
                comp, po)))).contains("ck_srm_po_ack_reject");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_po_ack (comp_cd, po_id, rev, ack_seq, source, ack_status, ack_on, created_by) values (?, ?, 5, 9, 'MANUAL', 'PARTIAL', (now() at time zone 'Asia/Seoul')::date, 't')",
                comp, po)))).contains("ck_srm_po_ack_rev");
    }

    @Test
    void 같은_발주를_동시에_두번_회신하면_순번이_겹치지_않고_같은요청키_연타는_한건() throws Exception {
        long po = issued(me, "V100");
        long l1 = line(po, 0);
        String body = "{\"rev\":0,\"lines\":[" + split(l1, d(10), "100") + "]}";
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> code(body, null));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> code(body, null));
        assertThat(List.of(a.join(), b.join())).containsOnly(200);
        assertThat(accounts.query(comp, "select string_agg(ack_seq::text, ',' order by ack_seq) from srm_po_ack", String.class)).isEqualTo("1,2");
        assertThat(code(body, "ack-key-0001")).isEqualTo(200);
        assertThat(code(body, "ack-key-0001")).isEqualTo(409);
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack", Integer.class)).isEqualTo(3);
    }

    private int code(String body, String key) {
        try {
            var req = post(ACK + "/pos/" + accounts.query(comp, "select max(po_id) from srm_po", Long.class)).with(csrf()).with(me)
                    .contentType(MediaType.APPLICATION_JSON).content(body);
            if (key != null) {
                req.header("X-Idempotency-Key", key);
            }
            return mvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 엑셀 회신 ----------

    private byte[] fill(List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/SRM_PO_ACK/file").with(csrf()).with(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (Object[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (values[c] != null) {
                        row.createCell(c).setCellValue(String.valueOf(values[c]));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private long upload(RequestPostProcessor who, byte[] file) throws Exception {
        MvcResult m = mvc.perform(multipart(IMP + "/templates/SRM_PO_ACK/jobs")
                        .file(new MockMultipartFile("file", "ack.xlsx", XLSX, file)).with(csrf()).with(who))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private ResultActions execute(RequestPostProcessor who, String c, long job) throws Exception {
        int ver = accounts.query(c, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    private List<String> errorCodes(RequestPostProcessor who, long job) throws Exception {
        return JsonPath.read(mvc.perform(get(IMP + "/jobs/" + job + "/rows?status=ERROR").with(who))
                .andReturn().getResponse().getContentAsString(), "$.list[*].errors[*].code");
    }

    @Test
    void 엑셀회신은_발주마다_새묶음_하나_다른협력사_이전개정_없는행_취소발주_파일안초과는_행오류() throws Exception {
        long a = issued(me, "V100");
        long b = issued(me, "V200");
        long cancelled = issued(me, "V100");
        act(me, cancelled, "cancel", "중복 발주");
        String aNo = JsonPath.read(detail(me, a), "$.data.po.poNo");
        String bNo = JsonPath.read(detail(me, b), "$.data.po.poNo");
        String cNo = JsonPath.read(poDetail(me, cancelled), "$.data.po.poNo");
        long job = upload(me, fill(List.of(
                new Object[] {"V100", aNo, null, 1, d(10), 60, "이공급", null, null},
                new Object[] {"V100", aNo, null, 1, d(14), 40, null, null, null},
                new Object[] {"V100", aNo, 0, 2, d(10), "2.5", null, null, null},
                new Object[] {"V200", bNo, null, 1, d(9), 30, null, null, null},
                new Object[] {"V100", bNo, null, 2, d(9), 1, null, null, null},           // 다른 협력사
                new Object[] {"V200", bNo, 3, 2, d(9), 1, null, null, null},              // 이전·없는 개정
                new Object[] {"V200", bNo, null, 9, d(9), 1, null, null, null},           // 없는 행
                new Object[] {"V200", bNo, null, 1, d(11), 80, null, null, null},         // 파일 안 합 110 > 100
                new Object[] {"V100", cNo, null, 1, d(9), 1, null, null, null},           // 취소된 발주
                new Object[] {"V100", "PO-NONE", null, 1, d(9), 1, null, null, null})));
        mvc.perform(get(IMP + "/jobs/" + job).with(me))
                .andExpect(jsonPath("$.data.newCount").value(4)).andExpect(jsonPath("$.data.errorCount").value(6));
        assertThat(errorCodes(me, job)).contains("SRM_OTHER_SUPPLIER", "SRM_OLD_REV", "NO_SUCH_CODE", "SRM_ACK_OVER", "SRM_PO_NOT_OPEN");
        // 검증은 본 테이블에 쓰지 않는다
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack", Integer.class)).isZero();
        execute(me, comp, job).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(4))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack where source = 'IMPORT' and import_job_id = ?", Integer.class, job))
                .isEqualTo(2);
        String da = detail(me, a);
        assertThat((String) JsonPath.read(da, "$.data.po.ackStatus")).isEqualTo("ACCEPTED");
        assertThat((String) JsonPath.read(da, "$.data.acks[0].ackByNm")).isEqualTo("이공급");
        assertThat((String) JsonPath.read(detail(me, b), "$.data.po.ackStatus")).isEqualTo("PARTIAL");
        assertThat(poStatus(a)).isEqualTo("ACKED");
        // 검증 뒤 개정되면 실행 전체 롤백
        long job2 = upload(me, fill(List.<Object[]>of(new Object[] {"V100", aNo, null, 1, d(10), 100, null, null, null})));
        act(me, a, "revise", null);
        execute(me, comp, job2).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack where import_job_id = ?", Integer.class, job2)).isZero();
    }

    // ---------- 진행 현황 ----------

    @Test
    void 진행현황_회신기한_지나면_회신안옴_늦은약속은_늦어질발주_요구납기_임박() throws Exception {
        long late = issued(me, "V100");
        long noAck = issued(me, "V200");
        accounts.exec(comp, "update srm_po set issued_at = now() - interval '5 days' where po_id = ?", noAck);
        ack(me, late, 0, split(line(late, 0), d(27), "100") + "," + split(line(late, 1), d(10), "2.5")).andExpect(status().isOk());
        String q = "/api/srm/po-status?from=" + d(-1) + "&to=" + d(0);
        mvc.perform(get(q + "&view=NO_ACK").with(me))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].poId").value(Matchers.everyItem(Matchers.is((int) noAck))))
                .andExpect(jsonPath("$.data[0].overdue").value(true));
        mvc.perform(get(q + "&view=LATE").with(me))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].lineNo").value(1)).andExpect(jsonPath("$.data[0].lateDays").value(7));
        mvc.perform(get(q + "&view=DUE_SOON&soonDays=19").with(me)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(q + "&view=DUE_SOON&soonDays=20").with(me)).andExpect(jsonPath("$.data.length()").value(4));
        mvc.perform(get(q).with(me)).andExpect(jsonPath("$.data.length()").value(4));
        // 회신 기한 정책 10일이면 아직 아님
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_ACK_DUE_DAYS', '10', 't', 't')", comp);
        mvc.perform(get(q + "&view=NO_ACK").with(me)).andExpect(jsonPath("$.data.length()").value(0));
        // 조건 검사: 기간 필수·보기 값·임박 일수 범위
        mvc.perform(get("/api/srm/po-status").with(me)).andExpect(status().isBadRequest());
        mvc.perform(get(q + "&view=BAD").with(me)).andExpect(status().isBadRequest());
        mvc.perform(get(q + "&soonDays=61").with(me)).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
    }

    // ---------- A사로 B사 · 권한 · ERP 주인 ----------

    @Test
    void B사로_A사_발주_회신조회_입력_엑셀_직접SQL_막힘_권한과_ERP주인() throws Exception {
        long aPo = issued(me, "V100");
        long aLine = line(aPo, 0);
        String aNo = JsonPath.read(detail(me, aPo), "$.data.po.poNo");
        String other = accounts.newComp("AB");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(other, bu, "PUR", "srm-ack", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        seed(other);
        long bPo = issued(b, "V100");
        // 조회·입력: A사 발주는 없는 것
        mvc.perform(get(ACK + "/pos/" + aPo).with(b)).andExpect(status().isNotFound());
        ack(b, aPo, 0, split(aLine, d(5), "1")).andExpect(status().isNotFound());
        mvc.perform(get(ACK + "/pos?from=" + d(-1) + "&to=" + d(0) + "&state=ALL").with(b))
                .andExpect(jsonPath("$.data[*].poId").value(Matchers.not(Matchers.hasItem((int) aPo))));
        // B사 발주에 A사 발주 행 id → 400
        ack(b, bPo, 0, split(aLine, d(5), "1")).andExpect(status().isBadRequest());
        // 엑셀: 회사마다 따로 채번이라 같은 번호는 자기 회사 발주로만 읽힌다 — A사에만 있는 번호(2번째 발주)는 없는 발주
        String bNo = JsonPath.read(detail(b, bPo), "$.data.po.poNo");
        assertThat(bNo).isEqualTo(aNo);
        long aPo2 = issued(me, "V100");
        String aOnly = JsonPath.read(detail(me, aPo2), "$.data.po.poNo");
        long job = upload(b, fill(List.<Object[]>of(new Object[] {"V100", aOnly, null, 1, d(5), 1, null, null, null})));
        assertThat(errorCodes(b, job)).containsExactly("NO_SUCH_CODE");
        // 직접 SQL(B사 범위): A사 발주에 회신 묶음 → 회사 확인 트리거(23503)가 RLS 보다 먼저
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into srm_po_ack (comp_cd, po_id, rev, ack_seq, source, ack_status, ack_on, created_by) values (?, ?, 0, 1, 'MANUAL', 'PARTIAL', (now() at time zone 'Asia/Seoul')::date, 't')",
                other, aPo)))).contains("fk_srm_po_ack_po_comp");
        // 다른 회사 comp_cd 로 넣기 → RLS(또는 트리거)
        Throwable t = catchThrowable(() -> accounts.exec(other,
                "insert into srm_po_ack (comp_cd, po_id, rev, ack_seq, source, ack_status, ack_on, created_by) values (?, ?, 0, 1, 'MANUAL', 'PARTIAL', (now() at time zone 'Asia/Seoul')::date, 't')",
                comp, aPo));
        assertThat(PgErrors.isRlsViolation(t) || PgErrors.constraint(t).orElse("").equals("fk_srm_po_ack_po_comp")).isTrue();
        assertThat(accounts.query(other, "select count(*) from srm_po_ack", Integer.class)).isZero();
        // 권한: 조회만 있으면 입력 403, 진행 현황 권한 없으면 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "srm-ack", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(ACK + "/pos/" + aPo).with(v)).andExpect(status().isOk());
        ack(v, aPo, 0, split(aLine, d(5), "1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/srm/po-status?from=" + d(-1) + "&to=" + d(0)).with(v)).andExpect(status().isForbidden());
        mvc.perform(post(IMP + "/templates/SRM_PO_ACK/file").with(csrf()).with(v)).andExpect(status().isOk());
        // ERP 가 발주의 주인이어도 회신은 SRM 에서(D1)
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_SOURCE', 'ERP', 't', 't')", comp);
        ack(me, aPo, 0, split(aLine, d(5), "100") + "," + split(line(aPo, 1), d(5), "2.5"))
                .andExpect(jsonPath("$.data.ackStatus").value("ACCEPTED"));
    }
}
