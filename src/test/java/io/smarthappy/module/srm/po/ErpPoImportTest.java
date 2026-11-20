package io.smarthappy.module.srm.po;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * SRM S3b ERP 발주 받기 (docs/31 4.7, D1) — 정책 ERP 일 때만, ERP 번호마다 발주 하나(source ERP, 발행·통지), 내부 코드·외부 ID 둘 다,
 * 같은 번호 다시 받기 멱등(SAME / EXISTS, 바뀐 행은 오류), 머리 불일치, 1,000행 묶음을 넘는 발주, 검증 뒤 정책 전환 롤백,
 * ERP 발주는 SRM 에서 못 고치고 회신은 된다, A사로 B사, 직접 SQL(매핑 대상·중복).
 */
@AutoConfigureMockMvc
class ErpPoImportTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String IMP = "/api/platform/import";
    private static final String PO = "/api/srm/pos";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long vCust;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("ER");
        long buyer = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, buyer, "PUR", "srm-ack", "SEARCH", "SAVE");
        me = accounts.as(comp, buyer, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        vCust = seed(comp, "ERP");
    }

    private long seed(String c, String source) {
        accounts.addPlant(c, "P1", "1공장");
        policy(c, "SRM_PO_SOURCE", source);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-A', '수지', 'RM', 'BUY', 'KG', 'N', 't', 't')""", c, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, email, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 'v100@example.com', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, pay_terms_cd, created_by, updated_by) values (?, ?, 'KRW', 'NET30', 't', 't')", id, c);
        long sys = accounts.query(c, "insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, created_by, updated_by) "
                + "values (?, 'ERP1', '사내 ERP', 'ERP', 't', 't') returning system_id", Long.class, c);
        accounts.exec(c, "insert into mst_ext_system (comp_cd, system_cd, system_nm, system_type, created_by, updated_by) "
                + "values (?, 'PORTAL', '고객 포털', 'OTHER', 't', 't')", c);
        accounts.exec(c, "insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by) "
                + "values (?, ?, 'CUST', ?, 'vend-9', 't', 't')", c, sys, id);
        accounts.exec(c, "insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by) "
                + "select ?, ?, 'ITEM', item_id, 'mat-001', 't', 't' from mst_item where item_cd = 'RESIN-A'", c, sys);
        return id;
    }

    private void policy(String c, String key, String value) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't') "
                + "on conflict do nothing", c, key, value);
        accounts.exec(c, "update sys_policy set policy_value = ? where policy_key = ?", value, key);
    }

    /** erpSystemCd, erpPoNo, lineNo, custCd, plantCd, whCd, poDate, currencyCd, itemCd, qty, unitPrice, dueDate, remark */
    private Object[] row(String po, int line, String cust, String item, Object qty, String price) {
        return new Object[] {"ERP1", po, line, cust, "P1", null, today.minusDays(1).toString(), null, item, qty, price,
            today.plusDays(20).toString(), null};
    }

    private byte[] fill(RequestPostProcessor who, List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/SRM_ERP_PO/file").with(csrf()).with(who))
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

    private long upload(RequestPostProcessor who, byte[] file, String mode) throws Exception {
        var req = multipart(IMP + "/templates/SRM_ERP_PO/jobs").file(new MockMultipartFile("file", "erp.xlsx", XLSX, file)).with(csrf()).with(who);
        if (mode != null) {
            req.param("mode", mode);
        }
        MvcResult m = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private ResultActions execute(RequestPostProcessor who, String c, long job) throws Exception {
        int ver = accounts.query(c, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    private String job(RequestPostProcessor who, long job) throws Exception {
        return mvc.perform(get(IMP + "/jobs/" + job).with(who)).andReturn().getResponse().getContentAsString();
    }

    private List<String> errorCodes(RequestPostProcessor who, long job) throws Exception {
        return JsonPath.read(mvc.perform(get(IMP + "/jobs/" + job + "/rows?status=ERROR").with(who))
                .andReturn().getResponse().getContentAsString(), "$.list[*].errors[*].code");
    }

    private long poId(String c, String erpNo) {
        return accounts.query(c, "select internal_id from mst_ext_id_map where entity_type = 'PO' and external_id = ?", Long.class, erpNo);
    }

    @Test
    void ERP번호마다_발주하나_발행_통지_내부코드와_외부ID_둘다_행번호_금액() throws Exception {
        long job = upload(me, fill(me, List.of(
                row("4500000001", 10, "V100", "BOLT-M8", 100, "120.5"),
                row("4500000001", 20, "vend-9", "mat-001", "2.5", "10.33"),     // ERP 거래처·품목 외부 ID(대소문자 그대로)
                row("4500000002", 10, "v100", "bolt-m8", 3, "1"))), null);
        assertThat((Integer) JsonPath.read(job(me, job), "$.data.newCount")).isEqualTo(3);
        assertThat(accounts.query(comp, "select count(*) from srm_po", Integer.class)).isZero();      // 검증은 본 테이블에 쓰지 않는다
        execute(me, comp, job).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(3))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        long a = poId(comp, "4500000001");
        String d = mvc.perform(get(PO + "/" + a).with(me)).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(d, "$.data.po.source")).isEqualTo("ERP");
        assertThat((String) JsonPath.read(d, "$.data.po.status")).isEqualTo("ISSUED");
        assertThat((String) JsonPath.read(d, "$.data.po.erpPoNo")).isEqualTo("4500000001");
        assertThat((String) JsonPath.read(d, "$.data.po.poDate")).isEqualTo(today.minusDays(1).toString());
        assertThat((String) JsonPath.read(d, "$.data.po.payTermsCd")).isEqualTo("NET30");
        assertThat((List<Integer>) JsonPath.read(d, "$.data.lines[*].lineNo")).containsExactly(10, 20);
        assertThat((String) JsonPath.read(d, "$.data.lines[0].amt")).isEqualTo("12050");
        assertThat((String) JsonPath.read(d, "$.data.lines[1].amt")).isEqualTo("26");          // 25.825 → 26 (KRW 0자리)
        assertThat((String) JsonPath.read(d, "$.data.po.totalAmt")).isEqualTo("12076");
        assertThat((List<?>) JsonPath.read(d, "$.data.revs")).hasSize(1);
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'SRM_PO_ISSUED'", Integer.class)).isEqualTo(2);
        // ERP 번호로 찾기
        mvc.perform(get(PO + "?from=" + today.minusDays(5) + "&to=" + today + "&keyword=4500000002").with(me))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 같은번호_다시받기는_멱등_바뀐행은_오류_머리불일치_정책_입력검사() throws Exception {
        List<Object[]> base = List.of(row("4500000001", 10, "V100", "BOLT-M8", 100, "120.5"), row("4500000001", 20, "V100", "RESIN-A", "2.5", "10"));
        execute(me, comp, upload(me, fill(me, base), null)).andExpect(status().isOk());
        // 같은 파일: 덮어쓰기 모드 = SAME(반영 0), 추가만 모드 = 이미 있음 — 발주는 여전히 1건
        long same = upload(me, fill(me, base), "UPSERT");
        assertThat((Integer) JsonPath.read(job(me, same), "$.data.sameCount")).isEqualTo(2);
        execute(me, comp, same).andExpect(jsonPath("$.data.result.inserted").value(0));
        long again = upload(me, fill(me, base), null);
        assertThat(errorCodes(me, again)).containsOnly("EXISTS");
        assertThat(accounts.query(comp, "select count(*) from srm_po", Integer.class)).isEqualTo(1);
        // 수량을 바꾼 행·새 행 → 변경 오류
        long changed = upload(me, fill(me, List.of(row("4500000001", 10, "V100", "BOLT-M8", 90, "120.5"),
                row("4500000001", 30, "V100", "BOLT-M8", 1, "1"))), "UPSERT");
        assertThat(errorCodes(me, changed)).containsOnly("SRM_ERP_PO_CHANGED");
        // 머리 불일치(같은 번호 공장·발주일 다름) / 미래 발주일 / 납기 < 발주일 / 없는 시스템·ERP 아닌 시스템 / 없는 품목 외부 ID / 단가 없음·0
        Object[] other = row("4500000009", 20, "V100", "BOLT-M8", 1, "1");
        other[6] = today.minusDays(3).toString();
        Object[] future = row("4500000010", 10, "V100", "BOLT-M8", 1, "1");
        future[6] = today.plusDays(1).toString();
        Object[] early = row("4500000011", 10, "V100", "BOLT-M8", 1, "1");
        early[11] = today.minusDays(5).toString();
        Object[] noSys = row("4500000012", 10, "V100", "BOLT-M8", 1, "1");
        noSys[0] = "NOPE";
        Object[] portal = row("4500000013", 10, "V100", "BOLT-M8", 1, "1");
        portal[0] = "PORTAL";
        long bad = upload(me, fill(me, List.of(row("4500000009", 10, "V100", "BOLT-M8", 1, "1"), other, future, early, noSys, portal,
                row("4500000014", 10, "V100", "MAT-001", 1, "1"),            // 외부 ID 는 대소문자 구분 → 없음
                row("4500000015", 10, "V100", "BOLT-M8", 1, "0"), row("4500000016", 10, "V100", "BOLT-M8", "1.5", "1"))), null);
        assertThat(errorCodes(me, bad)).contains("SRM_ERP_HEAD_MISMATCH", "FUTURE", "SRM_ERP_DUE_BEFORE_PO", "NO_SUCH_CODE", "POSITIVE", "SCALE");
        assertThat((Integer) JsonPath.read(job(me, bad), "$.data.errorCount")).isEqualTo(8);
        // 거래 정지 협력사
        accounts.exec(comp, "update srm_supplier set trade_status = 'BLOCKED', status_reason = '품질' where cust_id = ?", vCust);
        assertThat(errorCodes(me, upload(me, fill(me, List.<Object[]>of(row("4500000020", 10, "V100", "BOLT-M8", 1, "1"))), null)))
                .containsExactly("SUPPLIER_BLOCKED");
        accounts.exec(comp, "update srm_supplier set trade_status = 'ACTIVE', status_reason = null where cust_id = ?", vCust);
        // 정책 SRM 이면 받지 않음, 검증 뒤 SRM 으로 바꾸면 실행 전체 롤백
        long ok = upload(me, fill(me, List.<Object[]>of(row("4500000030", 10, "V100", "BOLT-M8", 1, "1"))), null);
        policy(comp, "SRM_PO_SOURCE", "SRM");
        assertThat(errorCodes(me, upload(me, fill(me, List.<Object[]>of(row("4500000031", 10, "V100", "BOLT-M8", 1, "1"))), null)))
                .containsExactly("SRM_NOT_ERP_SOURCE");
        execute(me, comp, ok).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from srm_po", Integer.class)).isEqualTo(1);
    }

    @Test
    void 천행_묶음을_넘는_발주도_행을_다_넣고_한번_발행_ERP발주는_SRM에서_못고치고_회신은_된다() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 1005; i++) {
            rows.add(row("4500000100", i, "V100", "BOLT-M8", 1, "2"));
        }
        rows.add(row("4500000101", 1, "V100", "BOLT-M8", 1, "2"));
        execute(me, comp, upload(me, fill(me, rows), null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(1006));
        long a = poId(comp, "4500000100");
        assertThat(accounts.query(comp, "select count(*) from srm_po_line where po_id = ?", Integer.class, a)).isEqualTo(1005);
        assertThat(accounts.query(comp, "select status || ':' || total_amt::int from srm_po where po_id = ?", String.class, a)).isEqualTo("ISSUED:2010");
        assertThat(accounts.query(comp, "select jsonb_array_length(snapshot -> 'lines') from srm_po_rev where po_id = ?", Integer.class, a)).isEqualTo(1005);
        // ERP 주인: 수정·개정 막힘(SRM_ERP_OWNED), 회신은 됨
        int rv = accounts.query(comp, "select row_ver from srm_po where po_id = ?", Integer.class, a);
        mvc.perform(post(PO + "/" + a + "/revise").with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + rv + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_ERP_OWNED"));
        mvc.perform(put(PO + "/" + a).with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + rv + ",\"lines\":[]}"))
                .andExpect(jsonPath("$.code").value("SRM_ERP_OWNED"));
        long b = poId(comp, "4500000101");
        long line = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, b);
        mvc.perform(post("/api/srm/acks/pos/" + b).with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rev\":0,\"lines\":[{\"poLineId\":" + line + ",\"promisedDate\":\"" + today.plusDays(10) + "\",\"promisedQty\":\"1\"}]}"))
                .andExpect(jsonPath("$.data.ackStatus").value("ACCEPTED"));
    }

    @Test
    void B사로_A사_ERP시스템_발주번호_외부ID_막힘_같은번호는_회사마다_따로_직접SQL_매핑대상() throws Exception {
        execute(me, comp, upload(me, fill(me, List.<Object[]>of(row("4500000001", 10, "V100", "BOLT-M8", 1, "1"))), null)).andExpect(status().isOk());
        long aPo = poId(comp, "4500000001");
        String other = accounts.newComp("EB");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-po", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        long bCust = seed(other, "ERP");
        // B사에 ERP2 만 있으면 A사 시스템 코드 ERP1 은 없는 것 — 여기서는 B사도 ERP1 이 있어 같은 번호를 따로 받는다
        execute(b, other, upload(b, fill(b, List.<Object[]>of(row("4500000001", 10, "vend-9", "BOLT-M8", 7, "1"))), null))
                .andExpect(status().isOk());
        long bPo = poId(other, "4500000001");
        assertThat(bPo).isNotEqualTo(aPo);
        assertThat(accounts.query(other, "select cust_id from srm_po where po_id = ?", Long.class, bPo)).isEqualTo(bCust);
        assertThat(accounts.query(comp, "select count(*) from srm_po_line where po_id = ? and qty = 1", Integer.class, aPo)).isEqualTo(1);
        // B사 화면으로 A사 발주
        mvc.perform(get(PO + "/" + aPo).with(b)).andExpect(status().isNotFound());
        // B사 시스템을 지우듯 끄면 ERP1 은 B사에서 없는 시스템(A사 시스템은 안 보인다)
        accounts.exec(other, "update mst_ext_system set use_yn = 'N' where system_cd = 'ERP1'");
        assertThat(errorCodes(b, upload(b, fill(b, List.<Object[]>of(row("4500000002", 10, "V100", "BOLT-M8", 1, "1"))), null)))
                .containsExactly("NO_SUCH_CODE");
        // 직접 SQL(B사 범위): A사 발주를 B사 시스템 PO 매핑으로 / SRM 이 만든 발주를 PO 매핑으로 / 같은 번호 두 번째 매핑
        long bSys = accounts.query(other, "select system_id from mst_ext_system where system_cd = 'ERP1'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by) values (?, ?, 'PO', ?, 'X1', 't', 't')",
                other, bSys, aPo)))).contains("fk_mst_ext_id_map_target");
        long aSys = accounts.query(comp, "select system_id from mst_ext_system where system_cd = 'ERP1'", Long.class);
        long srmPo = accounts.query(comp, """
                insert into srm_po (comp_cd, po_no, po_date, cust_id, plant_cd, currency_cd, buyer_user_id, created_by, updated_by)
                select ?, 'SRM-1', (now() at time zone 'Asia/Seoul')::date, ?, 'P1', 'KRW', min(user_id), 't', 't' from sys_user where comp_cd = ? returning po_id""",
                Long.class, comp, vCust, comp);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by) values (?, ?, 'PO', ?, 'X2', 't', 't')",
                comp, aSys, srmPo)))).contains("fk_mst_ext_id_map_target");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_ext_id_map set internal_id = ? where entity_type = 'PO' and external_id = '4500000001'", srmPo))))
                .contains("ck_mst_ext_id_map_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, created_by, updated_by) "
                        + "select ?, ?, 'PO', ?, '4500000001', 't', 't'", comp, aSys, aPo))).orElse("")).startsWith("uq_mst_ext_id_map_");
        // 권한: 발주 조회만이면 올리기 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "srm-po", "SEARCH");
        mvc.perform(multipart(IMP + "/templates/SRM_ERP_PO/jobs").file(new MockMultipartFile("file", "erp.xlsx", XLSX,
                fill(me, List.<Object[]>of(row("4500000003", 10, "V100", "BOLT-M8", 1, "1"))))).with(csrf())
                .with(accounts.as(comp, viewer, "viewer1"))).andExpect(status().isForbidden());
    }
}
