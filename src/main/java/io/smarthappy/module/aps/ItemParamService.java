package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ItemParamDtos.ParamCrit;
import io.smarthappy.module.aps.ItemParamDtos.ParamForm;
import io.smarthappy.module.aps.ItemParamDtos.ParamPage;
import io.smarthappy.module.aps.ItemParamDtos.ParamRow;
import io.smarthappy.module.aps.ItemParamDtos.ParamSort;
import io.smarthappy.module.aps.ItemParamDtos.ParamValue;
import io.smarthappy.module.aps.MpsDtos.ItemLookup;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 계획 기준정보 (aps-item-param) — APS 8단계, docs/38 17장(docs/63 G1, 결정 AP-6).
 * 공장 × 품목 한 행: 안전재고·로트 규칙(EX·FX·MIN)·로트 수량·배수·자체 생산 리드타임·확정 구간. 구매 조건(리드타임·MOQ·배수)은 SRM 공급원을 읽는다.
 * 삭제 없음(사용 중지), 공장·품목은 등록 때만. 다른 화면의 값을 조용히 바꾸지 않게 rowVer 로 수정한다.
 */
@Service
@RequiredArgsConstructor
public class ItemParamService {

    static final String PROG = "aps-item-param";
    static final int MAX_LEAD_DAYS = 365;
    static final int MAX_FENCE_DAYS = 60;

    private final ItemParamMapper mapper;
    private final OrderService orders;
    private final PolicyService policies;

    /** grid 열 — 로트 규칙은 시스템 고정 값이라 LABEL(문구 apsMps.lot.*), 조달 유형은 공통코드 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("plantCd", "apsParam.col.plant", 70).withSort().withPriority(2),
            ColumnSpec.text("itemCd", "apsParam.col.itemCd", 130).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "apsParam.col.itemNm", 160).withSort().withFlex().withPriority(1),
            ColumnSpec.code("procType", "apsParam.col.procType", "PROC_TYPE", 80).withSort().withPriority(3),
            ColumnSpec.text("safetyQty", "apsParam.col.safetyQty", 90),
            ColumnSpec.labels("lotRule", "apsParam.col.lotRule", "apsMps.lot.", 110, LotSizing.EX, LotSizing.FX, LotSizing.MIN),
            ColumnSpec.text("lotQty", "apsParam.col.lotQty", 80),
            ColumnSpec.text("lotMult", "apsParam.col.lotMult", 70).withPriority(3),
            ColumnSpec.of("makeLeadDays", "apsParam.col.makeLeadDays", ColumnType.INT, 80).withPriority(3),
            ColumnSpec.of("fenceDays", "apsParam.col.fenceDays", ColumnType.INT, 80).withPriority(3),
            ColumnSpec.of("useYn", "apsParam.col.useYn", ColumnType.YN, 70).withPriority(2),
            ColumnSpec.of("updatedAt", "apsParam.col.updatedAt", ColumnType.DATETIME, 140).withSort().withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> plants() {
        return mapper.findPlants();
    }

    @Transactional(readOnly = true)
    public ParamPage grid(String plantCd, String keyword, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        if (keyword != null && keyword.length() > 100 || plantCd != null && plantCd.length() > 20) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String use = VisibleText.isBlank(useYn) ? null : useYn.strip().toUpperCase(Locale.ROOT);
        if (use != null && !"Y".equals(use) && !"N".equals(use)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ParamCrit c = new ParamCrit(VisibleText.isBlank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                VisibleText.isBlank(keyword) ? null : "%" + likeEscape(keyword.strip()) + "%", use);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ParamSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("itemCd,asc"));
        return new ParamPage(mapper.findPage(c, pq), pq.isWithTotal() ? mapper.count(c) : null);
    }

    @Transactional(readOnly = true)
    public ParamRow get(long paramId) {
        ParamRow r = mapper.find(paramId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    /** 등록(id null) 또는 수정 — 검사 전부 모아 400, rowVer 다르면 409, 같은 공장·품목 행이 있으면 409(칸 오류 itemCd) */
    @Transactional
    public ParamRow save(Long paramId, ParamForm in) {
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ParamRow cur = null;
        String plant = null;
        Long itemId = null;
        String unit = null;
        String procType = null;
        if (paramId != null) {
            cur = mapper.find(paramId);
            if (cur == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (in.rowVer() == null) {
                e.add("rowVer", "REQUIRED");
            }
            plant = cur.getPlantCd();
            itemId = cur.getItemId();
            unit = cur.getUnitCd();
            procType = cur.getProcType();
        } else {
            if (VisibleText.isBlank(in.plantCd())) {
                e.add("plantCd", "REQUIRED");
            } else {
                plant = in.plantCd().strip().toUpperCase(Locale.ROOT);
                if (plant.length() > 20 || !"Y".equals(mapper.plantUseYn(plant))) {
                    e.add("plantCd", "NO_SUCH_CODE");
                }
            }
            if (VisibleText.isBlank(in.itemCd())) {
                e.add("itemCd", "REQUIRED");
            } else {
                String cd = in.itemCd().strip().toUpperCase(Locale.ROOT);
                ItemLookup item = cd.length() > 100 ? null : mapper.lookupItem(cd);
                if (item == null) {
                    e.add("itemCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(item.getUseYn())) {
                    e.add("itemCd", "INACTIVE");
                } else {
                    itemId = item.getItemId();
                    unit = item.getUnitCd();
                    procType = item.getProcType();
                }
            }
        }
        int scale = OrderService.scaleOf(orders.unitScales(), unit);
        BigDecimal safety = OrderService.qty(in.safetyQty(), "safetyQty", scale, false, e);
        String rule = VisibleText.isBlank(in.lotRule()) ? LotSizing.EX : in.lotRule().strip().toUpperCase(Locale.ROOT);
        if (!LotSizing.isRule(rule)) {
            e.add("lotRule", "FORMAT");
        }
        BigDecimal lotQty = OrderService.qty(in.lotQty(), "lotQty", scale, true, e);
        boolean lotGiven = !VisibleText.isBlank(in.lotQty());
        if (LotSizing.EX.equals(rule) && lotGiven) {
            e.add("lotQty", "APS_LOT_EX_NO_QTY");          // 조용히 지우지 않는다 — 화면은 EX 면 칸을 비운다
        } else if (!LotSizing.EX.equals(rule) && LotSizing.isRule(rule) && !lotGiven) {
            e.add("lotQty", "REQUIRED");
        }
        BigDecimal mult = OrderService.qty(in.lotMult(), "lotMult", scale, true, e);
        if (LotSizing.FX.equals(rule) && !VisibleText.isBlank(in.lotMult())) {
            e.add("lotMult", "APS_LOT_FX_NO_MULT");
        }
        Integer lead = in.makeLeadDays();
        if (lead != null) {
            if (lead < 0 || lead > MAX_LEAD_DAYS) {
                e.add("makeLeadDays", "RANGE", 0, MAX_LEAD_DAYS);
            } else if (procType != null && !"MAKE".equals(procType)) {
                e.add("makeLeadDays", "APS_PARAM_NOT_MAKE");
            }
        }
        Integer fence = in.fenceDays();
        if (fence != null && (fence < 0 || fence > MAX_FENCE_DAYS)) {
            e.add("fenceDays", "RANGE", 0, MAX_FENCE_DAYS);
        }
        String remark = OrderService.text(in.remark(), "remark", 500, e);
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip().toUpperCase(Locale.ROOT);
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "FORMAT");
        }
        e.throwIfAny();
        ParamValue v = new ParamValue(plant, itemId, safety == null ? BigDecimal.ZERO : safety, rule,
                LotSizing.EX.equals(rule) ? null : lotQty, mult, lead, fence, remark, use);
        long id;
        try {
            if (cur == null) {
                id = mapper.insert(v, me.loginId());
            } else {
                if (mapper.update(paramId, v, in.rowVer(), me.loginId()) == 0) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
                id = paramId;
            }
        } catch (DataIntegrityViolationException ex) {
            if ("uq_aps_item_param".equals(PgErrors.constraint(ex).orElse(""))) {
                throw new BusinessException(HbErrorCode.CONFLICT)
                        .withFieldErrors(FieldErrors.create().add("itemCd", "APS_PARAM_DUP").list());
            }
            throw ex;
        }
        return mapper.find(id);
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
