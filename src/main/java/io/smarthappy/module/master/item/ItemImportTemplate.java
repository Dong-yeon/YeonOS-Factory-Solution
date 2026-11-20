package io.smarthappy.module.master.item;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.master.ext.ExtService;
import io.smarthappy.module.master.item.ItemDtos.ItemForm;
import io.smarthappy.module.master.item.ItemDtos.ItemValue;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 품목 엑셀 올리기 (P1 2단계, 07 4.1 템플릿 04). 검증은 화면 저장과 같은 ItemService.validate.
 * 단위는 이 회사가 쓸 수 있는 단위 목록을 한 번 읽어 쓴다(행마다 조회하지 않음). 기존 품목은 단위가 그대로면 사용 중지된 단위도 허용.
 *
 * 버전 2 (docs/17 9장): 정책 ITEM_CD_MODE=AUTO 면 코드 칸을 비운 행 = 새 품목(실행 때 채번 — 미리보기는 번호를 쓰지 않는다),
 * 코드를 채운 행은 기존 품목 갱신만. 끝 열 "ERP 품번" = 사용 중 ERP 시스템 매핑 — 새 행·매핑 없는 기존 품목에만 붙이고
 * 다른 ERP 품번이 이미 있으면 FIXED(바꾸기는 화면에서 끄고 새로).
 */
@Component
@RequiredArgsConstructor
public class ItemImportTemplate implements ImportTemplate {

    static final String CODE = "MST_ITEM";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("itemCd", "mstItem.col.itemCd", true, "help.mst-item.itemCd", null, "84910-2S000").asText(),
            new ImportColumn("itemNm", "mstItem.col.itemNm", true, "help.mst-item.itemNm", null, "프런트 사이드 멤버 ASSY"),
            new ImportColumn("spec", "mstItem.col.spec", false, null, null, "SPFC590 1.6t"),
            new ImportColumn("itemType", "mstItem.col.itemType", true, null, "ITEM_TYPE", "FG"),
            new ImportColumn("procType", "mstItem.col.procType", true, null, "PROC_TYPE", "MAKE"),
            new ImportColumn("unitCd", "mstItem.col.unitCd", true, "help.mst-item.unitCd", "UNIT", "EA"),
            new ImportColumn("lotCtrlYn", "mstItem.col.lotCtrlYn", false, "import.guide.yn", null, "Y"),
            new ImportColumn("netMassKg", "mstItem.col.netMassKg", false, "help.mst-item.netMassKg", null, "4.85"),
            new ImportColumn("hsCd", "mstItem.col.hsCd", false, "help.mst-item.hsCd", null, "870829").asText(),
            new ImportColumn("remark", "mstItem.col.remark", false, null, null, null),
            new ImportColumn("useYn", "mstItem.col.useYn", false, "import.guide.yn", null, "Y"),
            // 표준단가는 맨 끝(docs/29_P3 29장) — 이미 받아 둔 양식·열 순서가 그대로 맞게
            new ImportColumn("stdPrice", "mstItem.col.stdPrice", false, "help.mst-item.stdPrice", null, "12500"),
            // ERP 품번(docs/17 9장, 버전 2) — 외부 코드라 대소문자 그대로
            new ImportColumn("erpItemCd", "mstItem.col.erpItemCd", false, "help.mst-item.erpItemCd", null, "84910-2S000-ERP").asText());

    private final ItemService service;
    private final ItemMapper mapper;
    private final ExtService ext;
    private final CodeService codes;
    private final JdbcTemplate jdbc;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String progCd() {
        return ItemService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.mstItem";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        Long erpSys = ext.erpSystemId();
        Map<String, Long> erpOwner = ext.erpItemMaps(erpSys);          // ERP 품번 → 품목 id (사용 중 매핑)
        Map<Long, String> erpOf = new HashMap<>();
        erpOwner.forEach((cd, id) -> erpOf.put(id, cd));
        Map<String, Existing> existing = new HashMap<>();
        jdbc.query("""
                select item_id, row_ver, item_cd, item_nm, spec, item_type, proc_type, unit_cd, lot_ctrl_yn, net_mass_kg,
                       hs_cd, remark, use_yn, std_price
                  from mst_item""", rs -> {
            Map<String, String> v = new LinkedHashMap<>();
            for (String[] f : new String[][] {{"itemCd", "item_cd"}, {"itemNm", "item_nm"}, {"spec", "spec"},
                {"itemType", "item_type"}, {"procType", "proc_type"}, {"unitCd", "unit_cd"}, {"lotCtrlYn", "lot_ctrl_yn"},
                {"hsCd", "hs_cd"}, {"remark", "remark"}, {"useYn", "use_yn"}}) {
                v.put(f[0], rs.getString(f[1]));
            }
            BigDecimal mass = rs.getBigDecimal("net_mass_kg");
            v.put("netMassKg", mass == null ? null : mass.stripTrailingZeros().toPlainString());
            v.put("stdPrice", ItemService.plain(rs.getBigDecimal("std_price")));
            long id = rs.getLong("item_id");
            v.put("erpItemCd", erpOf.get(id));
            existing.put(rs.getString("item_cd"), new Existing(id, rs.getInt("row_ver"), v));
        });
        ImportCodes types = new ImportCodes(codes, "ITEM_TYPE");
        ImportCodes procs = new ImportCodes(codes, "PROC_TYPE");
        ImportCodes units = new ImportCodes(codes, "UNIT");   // 시스템 + 이 회사 단위(RLS), 사용 중인 것만
        // 기본 단위를 바꿀 수 없는 품목(BOM·사용 중 환산·작업지시·재고 원장·수주·LOT) — ItemMapper.unitInUse·hb_check_item_unit_in_use 와
        // 같은 조건, 행마다 조회하지 않게 한 번에
        boolean erpPull = service.erpPull();   // ERP 가 품목 주인이면 새 행·주인 필드 변경 행은 오류 (docs/22)
        boolean auto = service.autoCd();       // 코드 칸을 비우면 채번 (docs/17 9장)
        Set<Long> unitLocked = new HashSet<>(jdbc.queryForList("""
                select item_id from mst_bom union select item_id from mst_bom_line
                union select item_id from mst_unit_conv where use_yn = 'Y'
                union select item_id from prd_wo union select distinct item_id from inv_trx
                union select item_id from sal_order_line union select item_id from lot_master""", Long.class));
        Set<String> erpInFile = new HashSet<>();   // 파일 안 같은 ERP 품번 — 둘째부터 오류
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return key == null ? null : existing.get(key);
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                String rawCd = cells.get("itemCd");
                boolean blankCd = rawCd == null || rawCd.isBlank();
                Existing cur = blankCd ? null : existing.get(rawCd.strip().toUpperCase(Locale.ROOT));
                boolean autoNew = auto && blankCd;          // AUTO 새 행 — 번호는 실행 때
                ItemForm in = new ItemForm(autoNew ? ItemService.AUTO_PLACEHOLDER : rawCd, cells.get("itemNm"), cells.get("spec"),
                        types.resolve(cells.get("itemType")), procs.resolve(cells.get("procType")),
                        units.resolve(cells.get("unitCd")), ImportCodes.yn(cells.get("lotCtrlYn")),
                        cells.get("netMassKg"), cells.get("hsCd"), cells.get("remark"), ImportCodes.yn(cells.get("useYn")),
                        cells.get("stdPrice"), null);
                FieldErrors e = FieldErrors.create();
                ItemValue v = service.validate(in, cd -> false,
                        unit -> units.contains(unit) || (cur != null && unit.equals(cur.values().get("unitCd"))), e);
                if (auto && !blankCd && cur == null) {
                    e.add("itemCd", "AUTO_NUMBERED");         // AUTO 에서 새 코드를 넣음 — 기존 품목 갱신만 코드를 쓴다
                }
                if (cur != null && v.unitCd() != null && !v.unitCd().equals(cur.values().get("unitCd"))
                        && unitLocked.contains(cur.id())) {
                    e.add("unitCd", "IN_USE");
                }
                String erp = erpCell(cells.get("erpItemCd"), cur, e);
                if (erpPull) {
                    if (cur == null) {
                        e.add("itemCd", "ERP_OWNED");
                    } else {
                        // 변경 행의 빈 칸은 "그대로"(ImportService 규칙) — 채운 칸만 비교(7단계 QA 결함 #5)
                        ItemService.erpOwnedChanged(cur.values(), v).stream()
                                .filter(f -> cells.get(f) != null && !cells.get(f).isBlank())
                                .forEach(f -> e.add(f, "ERP_OWNED"));
                        if (erp != null && !erp.equals(cur.values().get("erpItemCd"))) {
                            e.add("erpItemCd", "ERP_OWNED");  // ERP 품번도 ERP 가 정한다
                        }
                    }
                }
                boolean keyOk = e.list().stream().noneMatch(x -> "itemCd".equals(x.field()));
                Map<String, String> values = values(autoNew ? v.withItemCd(null) : v);
                values.put("erpItemCd", erp);
                return new Validated(keyOk && !autoNew ? v.itemCd() : null, values, e.list());
            }

            /** ERP 품번 칸 — 형식·ERP 시스템·다른 품목이 씀·이미 다른 ERP 품번·파일 안 중복. 빈 칸은 null(기존 행은 "그대로") */
            private String erpCell(String raw, Existing cur, FieldErrors e) {
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                int before = e.list().size();
                String cd = ExtService.cleanExternalId(raw, "erpItemCd", e);
                if (e.list().size() > before) {
                    return null;
                }
                Long owner = erpOwner.get(cd);
                String mine = cur == null ? null : cur.values().get("erpItemCd");
                if (erpSys == null) {
                    e.add("erpItemCd", "NO_ERP_SYSTEM");
                } else if (owner != null && (cur == null || owner != cur.id())) {
                    e.add("erpItemCd", "DUPLICATE");
                } else if (mine != null && !mine.equals(cd)) {
                    e.add("erpItemCd", "FIXED");
                } else if (mine == null && !erpInFile.add(cd)) {
                    e.add("erpItemCd", "FILE_DUPLICATE");
                }
                return cd;
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                refuseIfPull();
                ItemValue x = value(withCode(v), null);
                long id = mapper.insert(x, by);
                attachErp(List.<String[]>of(new String[] {x.itemCd(), v.get("erpItemCd")}), by);
                return id;
            }

            /** 실행 세션은 실행 트랜잭션에서 새로 연다 — 검증 뒤 정책이 PULL 로 바뀌었으면 새 품목은 넣지 않는다(결함 #6) */
            private void refuseIfPull() {
                if (erpPull) {
                    throw new BusinessException(HbErrorCode.ERP_OWNED);
                }
            }

            /** AUTO 새 행(코드 null)은 여기서 채번 — 실행 트랜잭션 안이라 롤백되면 번호도 롤백(결번 없음) */
            private Map<String, String> withCode(Map<String, String> v) {
                if (v.get("itemCd") != null) {
                    return v;
                }
                if (!auto) {
                    // 검증은 AUTO 였는데 실행 때 MANUAL — 코드 없는 새 행을 넣지 않는다(정책을 다시 본다, 결함 #6 과 같은 이유)
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                Map<String, String> m = new LinkedHashMap<>(v);
                m.put("itemCd", service.nextItemCd());
                return m;
            }

            @Override
            public void insertAll(List<Map<String, String>> rows, String by) {
                refuseIfPull();
                List<ItemValue> xs = rows.stream().map(r -> value(withCode(r), null)).toList();
                jdbc.batchUpdate("""
                        insert into mst_item (comp_cd, item_cd, item_nm, spec, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                              net_mass_kg, hs_cd, remark, use_yn, std_price, created_by, updated_by)
                        values (hb_current_comp(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        xs.stream().map(x -> new Object[] {x.itemCd(), x.itemNm(), x.spec(), x.itemType(), x.procType(),
                            x.unitCd(), x.lotCtrlYn(), x.netMassKg(), x.hsCd(), x.remark(), x.useYn(), x.stdPrice(), by, by})
                                .toList());
                List<String[]> erp = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    erp.add(new String[] {xs.get(i).itemCd(), rows.get(i).get("erpItemCd")});
                }
                attachErp(erp, by);
            }

            /** [품목코드, ERP 품번] 중 ERP 품번이 있는 것만 매핑. 그 사이 ERP 시스템이 꺼졌으면 전체 취소 */
            private void attachErp(List<String[]> pairs, String by) {
                List<Object[]> args = pairs.stream().filter(p -> p[1] != null)
                        .map(p -> new Object[] {erpSys, p[1], by, by, p[0]}).map(a -> (Object[]) a).toList();
                if (args.isEmpty()) {
                    return;
                }
                if (erpSys == null) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                // 같은 ERP 품번을 그 사이 다른 품목이 붙였으면 부분 유일 인덱스 → DuplicateKey → ImportService 가 IMPORT_STALE
                jdbc.batchUpdate("""
                        insert into mst_ext_id_map (comp_cd, system_id, entity_type, internal_id, external_id, use_yn,
                                                    created_by, updated_by)
                        select hb_current_comp(), ?, 'ITEM', item_id, ?, 'Y', ?, ? from mst_item where item_cd = ?""", args);
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                Existing cur = existing.values().stream().filter(x -> x.id() == id).findFirst().orElse(null);
                if (erpPull && cur != null && (ItemService.ERP_OWNED.stream()
                        .anyMatch(f -> !Objects.equals(cur.values().get(f), v.get(f)))
                        || !Objects.equals(cur.values().get("erpItemCd"), v.get("erpItemCd")))) {
                    throw new BusinessException(HbErrorCode.ERP_OWNED);
                }
                int n = mapper.update(id, value(v, rowVer), by);
                String erp = v.get("erpItemCd");
                String mine = cur == null ? null : cur.values().get("erpItemCd");
                if (n > 0 && erp != null && !erp.equals(mine)) {
                    if (mine != null) {
                        throw new BusinessException(HbErrorCode.CONFLICT);   // 검증 뒤 다른 ERP 품번이 붙음
                    }
                    attachErp(List.<String[]>of(new String[] {v.get("itemCd"), erp}), by);
                }
                return n;
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from mst_item", Long.class);
                return n == null ? 0 : n;
            }
        };
    }

    private static Map<String, String> values(ItemValue f) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("itemCd", f.itemCd());
        v.put("itemNm", f.itemNm());
        v.put("spec", f.spec());
        v.put("itemType", f.itemType());
        v.put("procType", f.procType());
        v.put("unitCd", f.unitCd());
        v.put("lotCtrlYn", f.lotCtrlYn());
        v.put("netMassKg", f.netMassKg() == null ? null : f.netMassKg().toPlainString());
        v.put("hsCd", f.hsCd());
        v.put("remark", f.remark());
        v.put("useYn", f.useYn());
        v.put("stdPrice", ItemService.plain(f.stdPrice()));
        return v;
    }

    private static ItemValue value(Map<String, String> v, Integer rowVer) {
        String mass = v.get("netMassKg");
        String price = v.get("stdPrice");
        return new ItemValue(v.get("itemCd"), v.get("itemNm"), v.get("spec"), v.get("itemType"), v.get("procType"),
                v.get("unitCd"), v.get("lotCtrlYn") == null ? "Y" : v.get("lotCtrlYn"),
                mass == null ? null : new BigDecimal(mass), v.get("hsCd"), v.get("remark"),
                v.get("useYn") == null ? "Y" : v.get("useYn"), price == null ? null : new BigDecimal(price), rowVer);
    }
}
