package io.smarthappy.module.master.cust;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.master.cust.CustDtos.CustForm;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/** 거래처 엑셀 올리기 (P1 2단계, 07 4.1 템플릿 03). 검증은 화면 저장과 같은 CustService.validate — 담당자는 대상 아님 */
@Component
@RequiredArgsConstructor
public class CustImportTemplate implements ImportTemplate {

    static final String CODE = "MST_CUST";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("custCd", "mstCust.col.custCd", true, "help.mst-cust.custCd", null, "C-001").asText(),
            new ImportColumn("custNm", "mstCust.col.custNm", true, "help.mst-cust.custNm", null, "한빛정밀(주)"),
            new ImportColumn("custType", "mstCust.col.custType", true, "help.mst-cust.custType", "CUST_TYPE", "SALES"),
            new ImportColumn("bizNo", "mstCust.col.bizNo", false, "help.mst-cust.bizNo", null, "124-81-00998").asText(),
            new ImportColumn("ceoNm", "mstCust.col.ceoNm", false, null, null, null),
            new ImportColumn("tel", "mstCust.col.tel", false, null, null, "055-000-0000").asText(),
            new ImportColumn("email", "mstCust.col.email", false, null, null, "buyer@example.com"),
            new ImportColumn("addr", "mstCust.col.addr", false, null, null, null),
            new ImportColumn("remark", "mstCust.col.remark", false, null, null, null),
            new ImportColumn("useYn", "mstCust.col.useYn", false, "import.guide.yn", null, "Y"));

    private final CustService service;
    private final CustMapper mapper;
    private final CodeService codes;
    private final JdbcTemplate jdbc;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String progCd() {
        return CustService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.mstCust";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        Map<String, Existing> existing = new HashMap<>();
        jdbc.query("""
                select cust_id, row_ver, cust_cd, cust_nm, cust_type, biz_no, ceo_nm, tel, email, addr, remark, use_yn
                  from mst_cust""", rs -> {
            Map<String, String> v = new LinkedHashMap<>();
            for (String[] f : new String[][] {{"custCd", "cust_cd"}, {"custNm", "cust_nm"}, {"custType", "cust_type"},
                {"bizNo", "biz_no"}, {"ceoNm", "ceo_nm"}, {"tel", "tel"}, {"email", "email"}, {"addr", "addr"},
                {"remark", "remark"}, {"useYn", "use_yn"}}) {
                v.put(f[0], rs.getString(f[1]));
            }
            existing.put(rs.getString("cust_cd"), new Existing(rs.getLong("cust_id"), rs.getInt("row_ver"), v));
        });
        ImportCodes types = new ImportCodes(codes, "CUST_TYPE");
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return key == null ? null : existing.get(key);
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                CustForm in = new CustForm(cells.get("custCd"), cells.get("custNm"), types.resolve(cells.get("custType")),
                        cells.get("bizNo"), cells.get("ceoNm"), cells.get("tel"), cells.get("email"), cells.get("addr"),
                        cells.get("remark"), ImportCodes.yn(cells.get("useYn")), null);
                FieldErrors e = FieldErrors.create();
                // 코드 중복은 엔진이 본다(기존 행이면 NEW 가 아니라 CHANGE·SAME)
                CustForm f = service.validate(in, cd -> false, e);
                boolean keyOk = e.list().stream().noneMatch(x -> "custCd".equals(x.field()));
                return new Validated(keyOk ? f.custCd() : null, values(f), e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                return mapper.insert(form(v, null), by);
            }

            @Override
            public void insertAll(List<Map<String, String>> rows, String by) {
                jdbc.batchUpdate("""
                        insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, biz_no, ceo_nm, tel, email, addr, remark,
                                              use_yn, created_by, updated_by)
                        values (hb_current_comp(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        rows.stream().map(r -> {
                            CustForm x = form(r, null);
                            return new Object[] {x.custCd(), x.custNm(), x.custType(), x.bizNo(), x.ceoNm(), x.tel(),
                                x.email(), x.addr(), x.remark(), x.useYn(), by, by};
                        }).toList());
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return mapper.update(id, form(v, rowVer), by);
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from mst_cust", Long.class);
                return n == null ? 0 : n;
            }
        };
    }

    private static Map<String, String> values(CustForm f) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("custCd", f.custCd());
        v.put("custNm", f.custNm());
        v.put("custType", f.custType());
        v.put("bizNo", f.bizNo());
        v.put("ceoNm", f.ceoNm());
        v.put("tel", f.tel());
        v.put("email", f.email());
        v.put("addr", f.addr());
        v.put("remark", f.remark());
        v.put("useYn", f.useYn());
        return v;
    }

    private static CustForm form(Map<String, String> v, Integer rowVer) {
        return new CustForm(v.get("custCd"), v.get("custNm"), v.get("custType"), v.get("bizNo"), v.get("ceoNm"),
                v.get("tel"), v.get("email"), v.get("addr"), v.get("remark"), v.get("useYn") == null ? "Y" : v.get("useYn"),
                rowVer);
    }
}
