package io.smarthappy.module.qms;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.OcapDtos.OcapForm;
import io.smarthappy.module.qms.OcapDtos.OcapPage;
import io.smarthappy.module.qms.OcapDtos.OcapRow;
import io.smarthappy.module.qms.OcapDtos.OcapSort;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * OCAP 표준 조치 목록 (qms-ocap) — docs/29_P3 26장 1단계.
 * - 규칙(WE1~WE4·R1)별 항목 + 모든 규칙 공통(rule null). 순서·문구·사용 여부를 고치고(row_ver), 지우지 않는다(사용 중지 — OC-6)
 * - SPC 위반 ACTION 에서 고를 때는 {@link #pick} 이 같은 회사·사용 중·규칙 일치를 서버에서 다시 보고 고른 순간 문구를 사본으로 남긴다(OC-2·4·5)
 */
@Service
@RequiredArgsConstructor
public class OcapService {

    static final String PROG = "qms-ocap";
    static final Set<String> RULES = Set.of("WE1", "WE2", "WE3", "WE4", "R1");
    static final int MAX_PICKS = 20;
    static final List<String> DEFAULT_SORT = List.of("ruleKey,asc", "seq,asc");
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("ruleKey", "qms.ocap.col.ruleCd", 150).withSort().withPriority(1),
            ColumnSpec.of("seq", "qms.ocap.col.seq", ColumnSpec.ColumnType.INT, 60).withSort().withPriority(2),
            ColumnSpec.text("actionText", "qms.ocap.col.actionText", 320).withSort().withPriority(1).withFlex().withMinWidth(160),
            ColumnSpec.of("useYn", "qms.ocap.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2),
            ColumnSpec.of("updatedAt", "mstChar.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    private final OcapMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;

    /** rule: 비우면 전체, "*" = 공통만, 규칙 코드 = 그 규칙만 / useYn: 비우면 전체 */
    @Transactional(readOnly = true)
    public OcapPage grid(String rule, String useYn, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, OcapSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String r = VisibleText.isBlank(rule) ? null : rule.strip();
        if (r != null && !"*".equals(r) && !RULES.contains(r)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String yn = VisibleText.isBlank(useYn) ? null : useYn.strip();
        if (yn != null && !"Y".equals(yn) && !"N".equals(yn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String kw = null;
        if (!VisibleText.isBlank(keyword)) {
            String k = keyword.strip();
            if (k.length() > 100) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            kw = "%" + BomService.likeEscape(k) + "%";
        }
        return new OcapPage(mapper.findPage(r, yn, kw, pq), pq.isWithTotal() ? mapper.count(r, yn, kw) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional
    public long create(OcapForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_OCAP_CREATE");
        OcapForm f = validate(in, false);
        try {
            return mapper.insert(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw SamplingService.fieldError("actionText", "DUPLICATE");
        }
    }

    /** 고치기 — 문구·순서·규칙·사용 여부. 옛 고른 기록은 사본이라 그대로(OC-6) */
    @Transactional
    public void update(long id, OcapForm in) {
        LoginUser me = SamplingService.me();
        OcapForm f = validate(in, true);
        if (mapper.findById(id) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        try {
            if (mapper.update(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException e) {
            throw SamplingService.fieldError("actionText", "DUPLICATE");
        }
    }

    /** SPC 위반 처리 폼의 고르기 목록 — 그 규칙 + 공통의 사용 중 항목 */
    @Transactional(readOnly = true)
    public List<OcapRow> options(String rule) {
        return mapper.findOptions(rule);
    }

    /**
     * ACTION 처리 행에 고른 항목 사본 — 처리 행 INSERT 전에 {@link #lockAndCheck} 로 확인한 항목을 그대로 넣는다.
     * 순서 = 고르기 목록과 같게(공통 먼저·순서)
     */
    public void pick(long actId, List<OcapRow> rows, String by) {
        if (!rows.isEmpty()) {
            mapper.insertPicks(actId, rows, by);
        }
    }

    /**
     * 고를 항목 확인(OC-4) — FOR SHARE 로 잠그고(동시 수정과 줄 세움, OC-5) 같은 회사·사용 중·규칙(그 이벤트 규칙 또는 공통)을 본다.
     * 하나라도 어긋나면 400 칸 오류 ocapIds[i](NOT_FOUND·INACTIVE·OCAP_RULE). 같은 id 두 번·null·20개 초과도 400
     */
    public List<OcapRow> lockAndCheck(List<Long> ids, String eventRule) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_PICKS) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("ocapIds", "TOO_MANY_ITEMS", MAX_PICKS).list());
        }
        FieldErrors e = FieldErrors.create();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            if (id == null) {
                e.add("ocapIds[" + i + "]", "REQUIRED");
            } else if (!seen.add(id)) {
                e.add("ocapIds[" + i + "]", "DUPLICATE");
            }
        }
        e.throwIfAny();
        Map<Long, OcapRow> found = mapper.lockForPick(List.copyOf(seen)).stream().collect(Collectors.toMap(OcapRow::getOcapId, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            OcapRow o = found.get(ids.get(i));
            if (o == null) {
                e.add("ocapIds[" + i + "]", "NOT_FOUND");
            } else if (!"Y".equals(o.getUseYn())) {
                e.add("ocapIds[" + i + "]", "INACTIVE");
            } else if (o.getRuleCd() != null && !o.getRuleCd().equals(eventRule)) {
                e.add("ocapIds[" + i + "]", "OCAP_RULE");
            }
        }
        e.throwIfAny();
        return found.values().stream().sorted(Comparator.comparing((OcapRow o) -> o.getRuleCd() == null ? "" : o.getRuleCd())
                .thenComparingInt(OcapRow::getSeq).thenComparingLong(OcapRow::getOcapId)).toList();
    }

    /** 이벤트의 처리 행별 고른 항목 사본 */
    public Map<Long, List<OcapDtos.PickRow>> picksOfEvent(long eventId) {
        return mapper.findPicksOfEvent(eventId).stream().collect(Collectors.groupingBy(OcapDtos.PickRow::getActId,
                java.util.LinkedHashMap::new, Collectors.toList()));
    }

    private static OcapForm validate(OcapForm in, boolean update) {
        if (in == null || (update && in.rowVer() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String rule = VisibleText.isBlank(in.ruleCd()) ? null : in.ruleCd().strip();
        if (rule != null && !RULES.contains(rule)) {
            e.add("ruleCd", "CODE");
        }
        int seq = in.seq() == null ? 0 : in.seq();
        if (seq < 0 || seq > 99999) {
            e.add("seq", "RANGE", 0, 99999);
        }
        String text = VisibleText.reason(in.actionText(), "actionText", 1, 500, e);
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        return new OcapForm(rule, seq, text, use, in.rowVer());
    }
}
