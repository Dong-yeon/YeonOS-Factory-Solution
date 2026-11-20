package io.smarthappy.module.master.bom;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomDtos.BatchResult;
import io.smarthappy.module.master.bom.BomDtos.ConvBatch;
import io.smarthappy.module.master.bom.BomDtos.ConvItem;
import io.smarthappy.module.master.bom.BomDtos.ConvRow;
import io.smarthappy.module.master.bom.BomDtos.CreatedKey;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 품목별 단위 환산 (1 from_unit = factor × 품목 기본 단위). 품목관리 화면에서 편집 — 권한도 mst-item.
 * BOM 확정 검사가 "행 단위 → 자품목 기본 단위" 환산이 있는지 본다.
 */
@Service
@RequiredArgsConstructor
public class UnitConvService {

    static final String PROG = "mst-item";
    static final int FACTOR_SCALE = 9;
    static final int FACTOR_INT_DIGITS = 9;

    private final BomMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;

    @Transactional(readOnly = true)
    public List<ConvRow> list(long itemId) {
        item(itemId);
        return mapper.findConvs(itemId);
    }

    /** 일괄 저장 — 품목 버전을 올려 동시 편집을 막는다. 삭제는 사용 안 함 */
    @Transactional
    public BatchResult save(long itemId, int itemRowVer, ConvBatch batch) {
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        inbox.claimRequestKey("MST_UNIT_CONV");
        List<ConvItem> created = batch == null || batch.created() == null ? List.of() : batch.created();
        List<ConvItem> updated = batch == null || batch.updated() == null ? List.of() : batch.updated();
        List<ConvItem> deleted = batch == null || batch.deleted() == null ? List.of() : batch.deleted();
        if (created.size() + updated.size() + deleted.size() > policies.intValue(Policies.GRID_BATCH_MAX_ROWS)
                || created.stream().anyMatch(Objects::isNull) || updated.stream().anyMatch(Objects::isNull) || deleted.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ItemLookup item = item(itemId);
        if (mapper.touchItem(itemId, itemRowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors errors = FieldErrors.create();
        Set<String> keys = new HashSet<>();
        Set<String> units = new HashSet<>();
        List<Long> touched = new ArrayList<>();
        for (ConvItem d : deleted) {
            key(d, keys);
            if (d.convId() == null || d.rowVer() == null) {
                errors.row(d.rowKey()).add("convId", "REQUIRED");
            } else {
                touched.add(d.convId());
            }
        }
        updated.forEach(u -> touched.add(u.convId()));
        mapper.findConvs(itemId).stream().filter(c -> !touched.contains(c.getConvId()))
                .forEach(c -> units.add(c.getFromUnit()));
        List<ConvItem> cu = new ArrayList<>();
        List<BigDecimal> fu = new ArrayList<>();
        List<ConvItem> cc = new ArrayList<>();
        List<BigDecimal> fc = new ArrayList<>();
        for (ConvItem u : updated) {
            key(u, keys);
            if (u.convId() == null || u.rowVer() == null) {
                errors.row(u.rowKey()).add("convId", "REQUIRED");
            }
            cu.add(u);
            fu.add(validate(u, item, units, errors.row(u.rowKey())));
        }
        for (ConvItem c : created) {
            key(c, keys);
            cc.add(c);
            fc.add(validate(c, item, units, errors.row(c.rowKey())));
        }
        errors.throwIfAny();

        for (ConvItem d : deleted) {
            if (mapper.disableConv(itemId, d.convId(), d.rowVer(), me.loginId()) == 0) {
                throw rowConflict(d.rowKey());
            }
        }
        try {
            for (int i = 0; i < cu.size(); i++) {
                ConvItem u = cu.get(i);
                int n;
                try {
                    n = mapper.updateConv(itemId, u.convId(), u.rowVer(), u.fromUnit().strip(), fu.get(i), me.loginId());
                } catch (DuplicateKeyException e) {
                    // 사용 안 함으로 남은 같은 단위 행과 겹침 — 검증 집합은 사용 중 행만 본다 (QA 3a #6)
                    throw duplicate(u.rowKey());
                }
                if (n == 0) {
                    throw rowConflict(u.rowKey());
                }
            }
            List<CreatedKey> out = new ArrayList<>();
            for (int i = 0; i < cc.size(); i++) {
                ConvItem c = cc.get(i);
                Long id = mapper.insertConv(itemId, item.getUnitCd(), c.fromUnit().strip(), fc.get(i), me.loginId());
                if (id == null) {
                    throw duplicate(c.rowKey());
                }
                out.add(new CreatedKey(c.rowKey(), id));
            }
            return new BatchResult(out, cu.size(), deleted.size());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private BigDecimal validate(ConvItem c, ItemLookup item, Set<String> units, FieldErrors.RowScope row) {
        FieldErrors e = FieldErrors.create();
        String from = c.fromUnit() == null || c.fromUnit().isBlank() ? null : c.fromUnit().strip();
        if (from == null) {
            e.add("fromUnit", "REQUIRED");
        } else if (from.equals(item.getUnitCd())) {
            e.add("fromUnit", "CODE");
        } else if (!units.add(from)) {
            e.add("fromUnit", "DUPLICATE");
        } else if (!mapper.unitUsable(from)) {
            e.add("fromUnit", "CODE");
        }
        BigDecimal f = BomService.decimal(c.factor(), "factor", FACTOR_SCALE, FACTOR_INT_DIGITS, true, e);
        if (c.factor() == null || c.factor().isBlank()) {
            e.add("factor", "REQUIRED");
        }
        e.list().forEach(fe -> row.add(fe.field(), fe.code(), fe.args()));
        return f;
    }

    private ItemLookup item(long itemId) {
        ItemLookup it = mapper.findItemById(itemId);
        if (it == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return it;
    }

    private static void key(ConvItem c, Set<String> keys) {
        if (c.rowKey() == null || !BomService.ROW_KEY.matcher(c.rowKey()).matches() || !keys.add(c.rowKey())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    private static BusinessException duplicate(String rowKey) {
        return new BusinessException(HbErrorCode.INVALID_VALUE)
                .withFieldErrors(List.of(new FieldErrors.FieldError(rowKey, "fromUnit", "DUPLICATE")));
    }

    private static BusinessException rowConflict(String rowKey) {
        return new BusinessException(HbErrorCode.CONFLICT)
                .withFieldErrors(List.of(new FieldErrors.FieldError(rowKey, null, "CONFLICT")));
    }
}
