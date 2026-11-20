package io.smarthappy.module.master.equip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.equip.AssetDtos.AssetExport;
import io.smarthappy.module.master.equip.AssetDtos.AssetForm;
import io.smarthappy.module.master.equip.AssetDtos.AssetRow;
import io.smarthappy.module.master.equip.AssetDtos.AssetSearch;
import io.smarthappy.module.master.equip.AssetDtos.AssetSort;
import io.smarthappy.module.master.equip.AssetDtos.AssetValue;
import io.smarthappy.module.master.equip.AssetDtos.ClassForm;
import io.smarthappy.module.master.equip.AssetDtos.ClassRow;
import io.smarthappy.module.master.equip.AssetDtos.EquipExport;
import io.smarthappy.module.master.equip.AssetDtos.EquipForm;
import io.smarthappy.module.master.equip.AssetDtos.EquipRow;
import io.smarthappy.module.master.equip.AssetDtos.EquipSearch;
import io.smarthappy.module.master.equip.AssetDtos.EquipSort;
import io.smarthappy.module.master.equip.AssetDtos.IdVer;
import io.smarthappy.module.master.equip.AssetDtos.InstallRequest;
import io.smarthappy.module.master.equip.AssetDtos.InstallRow;
import io.smarthappy.module.master.equip.AssetMapper.EquipValue;
import io.smarthappy.module.master.equip.EquipDtos.NodeRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 설비(mst-equip)·물리 자산(mst-asset) — P1 4b, docs/19 "4b 상세".
 *
 * - 설비 위치 = 장비 계층의 사용 중 LINE·CELL(같은 공장). 클래스는 회사 안 사용 중 클래스. 판정은 트리거, 서비스는 형식 + 제약 → 칸 오류
 * - 설치 이력 = 원장 변형: 설치(새 행) · 해체(열린 구간 한 번 닫기) · 무효(삭제 대신). 같은 자산 두 곳 동시 설치는 배제 제약
 * - 시각 입력은 회사 시간대의 "yyyy-MM-ddTHH:mm"(화면 datetime-local), 미래 시각 금지
 */
@Service
@RequiredArgsConstructor
public class AssetService {

    static final String PROG_EQUIP = "mst-equip";
    static final String PROG_ASSET = "mst-asset";
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    /** 천 단위 쉼표 숫자 — 쉼표가 있으면 이 모양만 받는다(hb.numbers GROUPED, QA N-a 와 같은 규칙) */
    static final Pattern GROUPED = Pattern.compile("^[1-9]\\d{0,2}(,\\d{3})+$");
    static final Set<String> ASSET_TYPES = Set.of("MOLD", "JIG", "GAUGE");
    static final List<String> EQUIP_SORT = List.of("equipCd,asc");
    static final List<String> ASSET_SORT = List.of("assetCd,asc");
    /** 시계 차이 허용(분) — 그보다 미래면 거부 */
    static final int FUTURE_TOLERANCE_MIN = 5;

    static final Map<String, String[]> CONSTRAINT_FIELD = Map.ofEntries(
            Map.entry("uq_mst_equip_class_cd", new String[] {"classCd", "DUPLICATE"}),
            Map.entry("uq_mst_equip_cd", new String[] {"equipCd", "DUPLICATE"}),
            Map.entry("fk_mst_equip_class_comp", new String[] {"classId", "INACTIVE"}),
            Map.entry("fk_mst_equip_class", new String[] {"classId", "NOT_FOUND"}),
            Map.entry("fk_mst_equip_node_plant", new String[] {"nodeId", "NOT_FOUND"}),
            Map.entry("fk_mst_equip_node", new String[] {"nodeId", "NOT_FOUND"}),
            Map.entry("ck_mst_equip_node_type", new String[] {"nodeId", "PLACE"}),
            Map.entry("fk_mst_equip_plant", new String[] {"plantCd", "NOT_FOUND"}),
            Map.entry("uq_mst_phys_asset_cd", new String[] {"assetCd", "DUPLICATE"}),
            Map.entry("fk_mst_phys_asset_item_comp", new String[] {"itemCd", "NOT_FOUND"}),
            // 교정 기록이 있는 게이지 (P3 QMS Q2a — 최근 교정일은 교정 기록에서, 유형 고정)
            Map.entry("ck_mst_phys_asset_calib_derived", new String[] {"lastCalibDate", "CALIB_DERIVED"}),
            Map.entry("ck_mst_phys_asset_gauge_calib", new String[] {"assetType", "GAUGE_CALIB"}),
            Map.entry("ex_mst_asset_install", new String[] {"installedAt", "OVERLAP"}),
            Map.entry("ck_mst_asset_install_period", new String[] {"removedAt", "BEFORE_INSTALL"}),
            Map.entry("fk_mst_asset_install_comp", new String[] {"equipCd", "INACTIVE"}),
            // 클래스를 바꿨는데 지금 속성 값이 새 클래스 정의에 맞지 않음 (P1 7b V027 hb_check_attrs)
            Map.entry("ck_attrs_unknown", new String[] {"classId", "ATTRS_CLASS"}),
            Map.entry("ck_attrs_required", new String[] {"classId", "ATTRS_CLASS"}),
            Map.entry("ck_attrs_value", new String[] {"classId", "ATTRS_CLASS"}));

    static final List<ColumnSpec> EQUIP_COLUMNS = List.of(
            ColumnSpec.text("equipCd", "equip.col.equipCd", 130).withSort().withPriority(1),
            ColumnSpec.text("equipNm", "equip.col.equipNm", 180).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("classNm", "equip.col.classNm", 110).withPriority(2),
            ColumnSpec.text("plantCd", "equip.col.plantCd", 70).withPriority(3),
            ColumnSpec.text("nodePath", "equip.col.nodePath", 200).withPriority(1),
            ColumnSpec.text("maker", "equip.col.maker", 110).withPriority(3),
            ColumnSpec.text("model", "equip.col.model", 110).withPriority(3),
            ColumnSpec.text("assetNo", "equip.col.assetNo", 100).withPriority(3),
            ColumnSpec.of("assetCnt", "equip.col.assetCnt", ColumnSpec.ColumnType.INT, 80).withPriority(2),
            ColumnSpec.of("useYn", "equip.col.useYn", ColumnSpec.ColumnType.YN, 60),
            ColumnSpec.of("updatedAt", "equip.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    static final List<ColumnSpec> ASSET_COLUMNS = List.of(
            ColumnSpec.text("assetCd", "asset.col.assetCd", 130).withSort().withPriority(1),
            ColumnSpec.code("assetType", "asset.col.assetType", "PHYS_ASSET_TYPE", 80).withSort().withPriority(1),
            ColumnSpec.text("assetNm", "asset.col.assetNm", 180).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("itemCd", "asset.col.itemCd", 130).withPriority(3),
            ColumnSpec.text("curEquipCd", "asset.col.curEquip", 120).withPriority(1),
            ColumnSpec.of("curInstalledAt", "asset.col.curInstalledAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
            ColumnSpec.of("nextCalibDate", "asset.col.nextCalibDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.of("useYn", "asset.col.useYn", ColumnSpec.ColumnType.YN, 60),
            ColumnSpec.of("updatedAt", "asset.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    private final AssetMapper mapper;
    private final EquipMapper equipMapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final BizClock clock;

    public record Page<T>(List<T> list, Long total) {
    }

    // ================================================================== 설비 클래스

    @Transactional(readOnly = true)
    public List<ClassRow> classes() {
        return mapper.findClasses();
    }

    @Transactional
    public long saveClass(Long classId, ClassForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = code(in.classCd(), "classCd", e);
        String nm = text(in.classNm(), "classNm", 100, true, e);
        String use = yn(in.useYn(), "useYn", e);
        String remark = text(in.remark(), "remark", 500, false, e);
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        e.throwIfAny();
        ClassForm f = new ClassForm(cd, nm, sort, use, remark, in.rowVer());
        try {
            if (classId == null) {
                inbox.claimRequestKey("MST_EQUIP_CLASS");
                return mapper.insertClass(f, me.loginId());
            }
            ClassRow cur = mapper.findClass(classId);
            if (cur == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (in.rowVer() == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            if ("N".equals(use) && "Y".equals(cur.getUseYn()) && cur.getEquipCnt() > 0) {
                throw fieldError("useYn", "HAS_EQUIP");       // 사용 중인 설비가 있는 클래스
            }
            if (mapper.updateClass(classId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            return classId;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ================================================================== 설비

    @Transactional(readOnly = true)
    public Page<EquipRow> equips(String plantCd, Long classId, String equipCd, String equipNm, String useYn, Integer page,
            Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, EquipSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), EQUIP_SORT);
        EquipSearch q = equipSearch(plantCd, classId, equipCd, equipNm, useYn, pq);
        return new Page<>(mapper.findEquips(q), pq.isWithTotal() ? mapper.countEquips(q) : null);
    }

    @Transactional(readOnly = true)
    public EquipRow equip(long equipId) {
        EquipRow r = mapper.findEquip(equipId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    /** 설치 위치 선택지: 그 공장의 사용 중 LINE·CELL (경로순) */
    @Transactional(readOnly = true)
    public List<NodeRow> placeNodes(String plantCd) {
        return plantCd == null || plantCd.isBlank() ? List.of() : mapper.placeNodes(plantCd.strip());
    }

    @Transactional
    public long createEquip(EquipForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_EQUIP_CREATE");
        EquipValue v = validateEquip(in);
        try {
            return mapper.insertEquip(v, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void updateEquip(long equipId, EquipForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        lockEquip(equipId);                                  // 설치와 직렬화한 뒤 설치 수를 센다
        EquipRow cur = equip(equipId);
        EquipValue v = validateEquip(in);
        if ("N".equals(v.useYn()) && "Y".equals(cur.getUseYn()) && mapper.openInstallsOfEquip(equipId) > 0) {
            throw fieldError("useYn", "HAS_ASSET");          // 금형·지그가 설치돼 있다 — 해체 먼저
        }
        try {
            if (mapper.updateEquip(equipId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public int disableEquips(List<IdVer> items) {
        LoginUser me = me();
        checkIdVers(items);
        for (IdVer i : items) {
            lockEquip(i.id());
            if (mapper.openInstallsOfEquip(i.id()) > 0) {
                throw fieldError("useYn", "HAS_ASSET");
            }
            if (mapper.disableEquip(i.id(), i.rowVer(), me.loginId()) == 0) {
                throw mapper.findEquip(i.id()) == null ? new BusinessException(HbErrorCode.NOT_FOUND)
                        : new BusinessException(HbErrorCode.CONFLICT);
            }
        }
        return items.size();
    }

    private EquipValue validateEquip(EquipForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = blank(in.plantCd()) ? null : in.plantCd().strip();
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else if (equipMapper.findPlant(plant) == null) {
            e.add("plantCd", "NOT_FOUND");
        }
        String cd = code(in.equipCd(), "equipCd", e);
        String nm = text(in.equipNm(), "equipNm", 100, true, e);
        if (in.classId() == null) {
            e.add("classId", "REQUIRED");
        }
        String maker = text(in.maker(), "maker", 100, false, e);
        String model = text(in.model(), "model", 100, false, e);
        String serial = text(in.serialNo(), "serialNo", 100, false, e);
        String assetNo = text(in.assetNo(), "assetNo", 50, false, e);
        LocalDate installDate = date(in.installDate(), "installDate", e);
        String use = yn(in.useYn(), "useYn", e);
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        return new EquipValue(plant, cd, nm, in.classId(), in.nodeId(), maker, model, serial, assetNo, installDate, use, remark,
                in.rowVer());
    }

    // ================================================================== 물리 자산

    @Transactional(readOnly = true)
    public Page<AssetRow> assets(String assetType, String assetCd, String assetNm, String useYn, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, AssetSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), ASSET_SORT);
        AssetSearch q = assetSearch(assetType, assetCd, assetNm, useYn, pq);
        return new Page<>(mapper.findAssets(q), pq.isWithTotal() ? mapper.countAssets(q) : null);
    }

    @Transactional(readOnly = true)
    public AssetRow asset(long assetId) {
        AssetRow r = mapper.findAsset(assetId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    @Transactional
    public long createAsset(AssetForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_ASSET_CREATE");
        AssetValue v = validateAsset(in, null);
        try {
            return mapper.insertAsset(v, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void updateAsset(long assetId, AssetForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        lockAsset(assetId);                                  // 설치와 직렬화한 뒤 설치 중인지 본다
        AssetRow cur = asset(assetId);
        AssetValue v = validateAsset(in, cur.getItemId());
        if ("N".equals(v.useYn()) && cur.getCurInstallId() != null) {
            throw fieldError("useYn", "INSTALLED");          // 설치 중 — 해체 먼저
        }
        try {
            if (mapper.updateAsset(assetId, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public int disableAssets(List<IdVer> items) {
        LoginUser me = me();
        checkIdVers(items);
        for (IdVer i : items) {
            lockAsset(i.id());
            AssetRow cur = asset(i.id());
            if (cur.getCurInstallId() != null) {
                throw fieldError("useYn", "INSTALLED");
            }
            if (mapper.disableAsset(i.id(), i.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        }
        return items.size();
    }

    /** curItemId = 지금 대상 품목(새 자산 null) — 그대로 두는 사용 중지 품목은 허용, 새로 고르는 건 INACTIVE */
    private AssetValue validateAsset(AssetForm in, Long curItemId) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = code(in.assetCd(), "assetCd", e);
        String type = in.assetType();
        if (blank(type)) {
            e.add("assetType", "REQUIRED");
        } else if (!ASSET_TYPES.contains(type)) {
            e.add("assetType", "CODE");
        }
        String nm = text(in.assetNm(), "assetNm", 100, true, e);
        String serial = text(in.serialNo(), "serialNo", 100, false, e);
        Long itemId = null;
        if (!blank(in.itemCd())) {
            itemId = mapper.findItemId(in.itemCd().strip().toUpperCase(Locale.ROOT));
            if (itemId == null) {
                e.add("itemCd", "NOT_FOUND");
            } else if (!itemId.equals(curItemId) && !"Y".equals(mapper.itemUseYn(itemId))) {
                e.add("itemCd", "INACTIVE");
            }
        }
        Long shots = null;
        if (!blank(in.lifeShots())) {
            String raw = in.lifeShots().strip();
            // 쉼표는 천 단위 자리일 때만(hb.numbers GROUPED 와 같은 규칙) — 자리 확인 없이 지우면 "1,23" 이 123 으로 조용히 저장된다
            if (raw.indexOf(',') >= 0 && !GROUPED.matcher(raw).matches()) {
                e.add("lifeShots", "FORMAT");
            } else {
                String s = raw.replace(",", "");
                if (!s.matches("^[0-9]{1,12}$") || Long.parseLong(s) <= 0) {
                    e.add("lifeShots", "POSITIVE");
                } else {
                    shots = Long.parseLong(s);
                }
            }
        }
        if (in.calibCycleDays() != null && (in.calibCycleDays() < 1 || in.calibCycleDays() > 3650)) {
            e.add("calibCycleDays", "CODE");
        }
        LocalDate lastCalib = date(in.lastCalibDate(), "lastCalibDate", e);
        if (lastCalib != null && lastCalib.isAfter(clock.today())) {
            e.add("lastCalibDate", "FUTURE");
        }
        String use = yn(in.useYn(), "useYn", e);
        String remark = text(in.remark(), "remark", 1000, false, e);
        e.throwIfAny();
        return new AssetValue(cd, type, nm, serial, itemId, shots, in.calibCycleDays(), lastCalib, use, remark, in.rowVer());
    }

    // ================================================================== 설치 이력

    @Transactional(readOnly = true)
    public List<InstallRow> installs(long assetId) {
        asset(assetId);
        List<InstallRow> rows = mapper.findInstalls(assetId);
        Map<String, String> zones = new HashMap<>();
        rows.forEach(r -> r.setTimeZone(zones.computeIfAbsent(r.getPlantCd(), p -> clock.zone(p).getId())));
        return rows;
    }

    @Transactional(readOnly = true)
    public List<EquipRow> installTargets() {
        return mapper.activeEquips();
    }

    /** 설치 — 지금 다른 곳에 설치 중이면 해체 먼저(ASSET_INSTALLED). 과거 시각 설치는 이력과 겹치지 않을 때만(배제 제약) */
    @Transactional
    public long install(long assetId, InstallRequest req) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_ASSET_INSTALL");
        lockAsset(assetId);                                  // 같은 자산 동시 설치·사용 중지와 한 줄로 → 뒤쪽은 ASSET_INSTALLED·INACTIVE
        AssetRow a = asset(assetId);
        FieldErrors e = FieldErrors.create();
        EquipRow eq = null;
        if (req == null || blank(req.equipCd())) {
            e.add("equipCd", "REQUIRED");
        } else {
            eq = mapper.findEquipByCd(req.equipCd().strip().toUpperCase(Locale.ROOT));
            if (eq == null) {
                e.add("equipCd", "NOT_FOUND");
            } else if (!"Y".equals(eq.getUseYn())) {
                e.add("equipCd", "INACTIVE");
            }
        }
        OffsetDateTime at = dateTime(req == null ? null : req.installedAt(), "installedAt", e,
                clock.zone(eq == null ? null : eq.getPlantCd()));
        String remark = req == null ? null : text(req.remark(), "remark", 500, false, e);
        if (!"Y".equals(a.getUseYn())) {
            e.add("assetCd", "INACTIVE");
        }
        e.throwIfAny();
        if (a.getCurInstallId() != null) {
            // 설치는 항상 열린 구간 — 지금 설치 중이면 두 곳 동시가 된다. 다른 설비로 옮기려면 해체 먼저
            throw new BusinessException(HbErrorCode.ASSET_INSTALLED, a.getCurEquipCd());
        }
        try {
            return mapper.insertInstall(assetId, eq.getEquipId(), at == null ? nowMicros() : at, remark,
                    me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void remove(long installId, String removedAt) {
        LoginUser me = me();
        InstallRow cur = install(installId);
        FieldErrors e = FieldErrors.create();
        OffsetDateTime at = dateTime(removedAt, "removedAt", e, clock.zone(cur.getPlantCd()));
        e.throwIfAny();
        OffsetDateTime when = at == null ? nowMicros() : at;
        if (cur.getRemovedAt() != null || "Y".equals(cur.getVoidYn())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!when.isAfter(cur.getInstalledAt())) {
            throw fieldError("removedAt", "BEFORE_INSTALL");
        }
        try {
            if (mapper.removeInstall(installId, when, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void voidInstall(long installId, String reason) {
        LoginUser me = me();
        install(installId);
        FieldErrors e = FieldErrors.create();
        String r = text(reason, "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.voidInstall(installId, r, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private InstallRow install(long installId) {
        InstallRow r = mapper.findInstall(installId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    // ================================================================== 엑셀

    public List<ColumnSpec> equipColumns() {
        return EQUIP_COLUMNS;
    }

    public List<ColumnSpec> assetColumns() {
        return ASSET_COLUMNS;
    }

    @Transactional(readOnly = true)
    public long exportEquips(EquipExport req, HttpServletResponse response) throws IOException {
        EquipExport r = req == null ? new EquipExport(null, null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(EQUIP_COLUMNS, r.columns());
        EquipSearch q = equipSearch(r.plantCd(), r.classId(), r.equipCd(), r.equipNm(), r.useYn(),
                PageQuery.sortOnly(r.sort(), EquipSort.values(), EQUIP_SORT));
        return excel.export(response, new ExportJob(PROG_EQUIP, "prog.mstEquip", cols, mapper.countEquips(q), List.of()), w -> {
            try (Cursor<EquipRow> c = mapper.cursorEquips(q)) {
                c.forEach(w);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    @Transactional(readOnly = true)
    public long exportAssets(AssetExport req, HttpServletResponse response) throws IOException {
        AssetExport r = req == null ? new AssetExport(null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(ASSET_COLUMNS, r.columns());
        AssetSearch q = assetSearch(r.assetType(), r.assetCd(), r.assetNm(), r.useYn(),
                PageQuery.sortOnly(r.sort(), AssetSort.values(), ASSET_SORT));
        return excel.export(response, new ExportJob(PROG_ASSET, "prog.mstAsset", cols, mapper.countAssets(q), List.of()), w -> {
            try (Cursor<AssetRow> c = mapper.cursorAssets(q)) {
                c.forEach(w);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    // ================================================================== 공통

    private EquipSearch equipSearch(String plantCd, Long classId, String cd, String nm, String useYn, PageQuery pq) {
        String c = blank(cd) ? null : cd.strip().toUpperCase(Locale.ROOT);
        String n = blank(nm) ? null : nm.strip();
        if ((c != null && c.length() > 30) || (n != null && n.length() > 100) || badYn(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new EquipSearch(blank(plantCd) ? null : plantCd.strip(), classId, c == null ? null : like(c) + "%",
                n == null ? null : "%" + like(n) + "%", blank(useYn) ? null : useYn, pq);
    }

    private AssetSearch assetSearch(String type, String cd, String nm, String useYn, PageQuery pq) {
        String c = blank(cd) ? null : cd.strip().toUpperCase(Locale.ROOT);
        String n = blank(nm) ? null : nm.strip();
        if ((c != null && c.length() > 30) || (n != null && n.length() > 100) || badYn(useYn)
                || (!blank(type) && !ASSET_TYPES.contains(type))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new AssetSearch(blank(type) ? null : type, c == null ? null : like(c) + "%",
                n == null ? null : "%" + like(n) + "%", blank(useYn) ? null : useYn, pq);
    }

    /**
     * "yyyy-MM-ddTHH:mm"(설비 공장 시간대, 없으면 회사 — docs/25 D5) → 시각. 비면 null(= 지금). 미래(허용 오차 넘음)는 FUTURE.
     * DST 로 없는 시각은 DST_GAP, 겹치는 시각은 앞쪽(BizClock.toInstant).
     * DB(timestamptz)와 같은 µs 로 자른다 — 1µs 미만 차이가 서버 검사는 지나고 DB 반올림 뒤 CHECK 에 걸리지 않게.
     * 허용 오차 안의 미래는 시계 차이로 보고 지금으로 당긴다 — 설치 직후 "지금 해체" 가 막히지 않게
     */
    OffsetDateTime dateTime(String raw, String field, FieldErrors e, ZoneId zone) {
        if (blank(raw)) {
            return null;
        }
        try {
            Instant inst = BizClock.toInstant(LocalDateTime.parse(raw.strip()), zone);
            if (inst == null) {
                e.add(field, "DST_GAP");
                return null;
            }
            OffsetDateTime t = inst.atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.MICROS);
            OffsetDateTime now = nowMicros();
            if (t.isAfter(now.plusMinutes(FUTURE_TOLERANCE_MIN))) {
                e.add(field, "FUTURE");
                return null;
            }
            if (t.isAfter(now)) {
                t = now;
            }
            if (t.getYear() < 2000) {
                e.add(field, "FORMAT");
                return null;
            }
            return t;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    private OffsetDateTime nowMicros() {
        return clock.now().toOffsetDateTime().truncatedTo(ChronoUnit.MICROS);
    }

    private void lockAsset(long assetId) {
        if (mapper.lockAsset(assetId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private void lockEquip(long equipId) {
        if (mapper.lockEquip(equipId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private static LocalDate date(String raw, String field, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(raw.strip());
            if (d.getYear() < 1950 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    private static void checkIdVers(List<IdVer> items) {
        if (items == null || items.isEmpty() || items.size() > 1000 || items.stream().anyMatch(Objects::isNull)
                || items.stream().anyMatch(i -> i.id() == null || i.rowVer() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException ex) {
        String c = PgErrors.constraint(ex).orElse(null);
        String[] fe = c == null ? null : CONSTRAINT_FIELD.get(c);
        return fe == null ? ex : fieldError(fe[0], fe[1]);
    }

    private static String code(String raw, String field, FieldErrors e) {
        String cd = blank(raw) ? null : raw.strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add(field, "REQUIRED");
        } else if (!CODE.matcher(cd).matches()) {
            e.add(field, "FORMAT");
        }
        return cd;
    }

    private static String yn(String raw, String field, FieldErrors e) {
        String v = blank(raw) ? "Y" : raw;
        if (!"Y".equals(v) && !"N".equals(v)) {
            e.add(field, "CODE");
        }
        return v;
    }

    private static boolean badYn(String v) {
        return !blank(v) && !"Y".equals(v) && !"N".equals(v);
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
