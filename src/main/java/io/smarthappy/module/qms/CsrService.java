package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.CapaDtos.CsrDetail;
import io.smarthappy.module.qms.CapaDtos.CsrForm;
import io.smarthappy.module.qms.CapaDtos.CsrPage;
import io.smarthappy.module.qms.CapaDtos.CsrRow;
import io.smarthappy.module.qms.CapaDtos.CsrSort;
import io.smarthappy.module.qms.CapaDtos.CsrTargetForm;
import io.smarthappy.module.qms.CapaDtos.CsrTargetValue;
import io.smarthappy.module.qms.CapaDtos.CsrValue;
import io.smarthappy.module.qms.CapaDtos.Ref;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import io.smarthappy.platform.common.text.VisibleText;
import tools.jackson.databind.json.JsonMapper;

/**
 * 고객 요구사항(CSR) (qms-csr) — P3 QMS Q4, docs/29 D8.
 *
 * - 거래처 × 문서 번호 × 리비전, 시작일, override 는 정해진 키(CONTAIN_DAYS·CLOSE_DAYS·NOTIFY_HOURS·RETENTION_YEARS)만 양의 정수
 * - 적용 대상 = 품목 또는 품목 클래스(없으면 그 고객 전체). 시정조치 기한은 만들 때 스냅샷 — CSR 을 고쳐도 지난 8D 는 그대로
 * - 거래처는 만든 뒤 못 바꾼다(트리거가 최종)
 */
@Service
@RequiredArgsConstructor
public class CsrService {

    static final String PROG = "qms-csr";
    /** override 키 → 상한 */
    static final Map<String, Integer> OVERRIDE_KEYS = Map.of("CONTAIN_DAYS", 365, "CLOSE_DAYS", 3650, "NOTIFY_HOURS", 720,
            "RETENTION_YEARS", 100);
    static final Set<String> TARGET_TYPES = Set.of("ITEM", "ITEM_CLASS");
    static final int MAX_TARGETS = 200;
    static final int LIST_LIMIT = 500;
    /** 기본 = 목록과 같은 순서(고객 → 문서 번호 → 시작일 최근) */
    static final List<String> GRID_DEFAULT_SORT = List.of("custCd,asc", "csrNo,asc", "effectiveFrom,desc");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("custCd", "qms.csr.col.custCd", 100).withSort().withPriority(1),
            ColumnSpec.text("custNm", "qms.csr.col.custNm", 140).withPriority(2),
            ColumnSpec.text("csrNo", "qms.csr.col.csrNo", 120).withSort().withPriority(1),
            ColumnSpec.text("csrRev", "qms.csr.col.csrRev", 60).withPriority(2),
            ColumnSpec.text("title", "qms.csr.col.title", 180).withFlex().withMinWidth(120).withPriority(1),
            ColumnSpec.of("effectiveFrom", "qms.csr.col.effectiveFrom", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.of("useYn", "qms.csr.col.useYn", ColumnSpec.ColumnType.YN, 60).withPriority(3),
            ColumnSpec.of("updatedAt", "qms.csr.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CapaMapper mapper;
    private final InboxService inbox;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public List<CsrRow> list(String keyword, Boolean withUnused) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        if (kw != null && kw.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<CsrRow> rows = mapper.findCsrs(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", Boolean.TRUE.equals(withUnused),
                LIST_LIMIT);
        rows.forEach(CsrService::parse);
        return rows;
    }

    /** 그리드(UI 단계 3, 결정 #45) — 목록과 같은 조건·검증 + 쪽·정렬 */
    @Transactional(readOnly = true)
    public CsrPage grid(String keyword, Boolean withUnused, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CsrSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                GRID_DEFAULT_SORT);
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        if (kw != null && kw.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = kw == null ? null : "%" + BomService.likeEscape(kw) + "%";
        boolean unused = Boolean.TRUE.equals(withUnused);
        List<CsrRow> rows = mapper.findCsrGrid(like, unused, pq);
        rows.forEach(CsrService::parse);
        return new CsrPage(rows, pq.isWithTotal() ? mapper.countCsrs(like, unused) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public CsrDetail detail(long id) {
        return new CsrDetail(find(id), mapper.findCsrTargets(id));
    }

    @Transactional
    public long create(CsrForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_CSR_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long custId = null;
        String custCd = VisibleText.isBlank(in.custCd()) ? null : in.custCd().strip().toUpperCase(Locale.ROOT);
        if (custCd == null) {
            e.add("custCd", "REQUIRED");
        } else {
            Ref r = mapper.findCust(custCd);
            if (r == null) {
                e.add("custCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(r.getUseYn())) {
                e.add("custCd", "INACTIVE");
            } else {
                custId = r.getId();
            }
        }
        CsrValue v = value(in, custId, e);
        List<CsrTargetValue> targets = targets(in.targets(), e);
        e.throwIfAny();
        long id = mapper.insertCsr(v, me.loginId());
        if (!targets.isEmpty()) {
            mapper.insertCsrTargets(id, targets, me.loginId());
        }
        return id;
    }

    /** 내용·override·적용 대상(targets 가 null 이면 그대로). 거래처는 고정 */
    @Transactional
    public void update(long id, CsrForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CsrRow cur = find(id);
        FieldErrors e = FieldErrors.create();
        if (!VisibleText.isBlank(in.custCd()) && !in.custCd().strip().equalsIgnoreCase(cur.getCustCd())) {
            e.add("custCd", "FIXED");
        }
        CsrValue v = value(in, cur.getCustId(), e);
        List<CsrTargetValue> targets = in.targets() == null ? null : targets(in.targets(), e);
        e.throwIfAny();
        if (mapper.updateCsr(id, v, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (targets != null) {
            mapper.deleteCsrTargets(id);
            if (!targets.isEmpty()) {
                mapper.insertCsrTargets(id, targets, me.loginId());
            }
        }
    }

    private CsrValue value(CsrForm in, Long custId, FieldErrors e) {
        String no = VisibleText.reason(in.csrNo(), "csrNo", 1, 50, e);
        String rev = VisibleText.reason(in.csrRev(), "csrRev", 1, 20, e);
        String title = VisibleText.reason(in.title(), "title", 1, 200, e);
        LocalDate from = BomService.date(in.effectiveFrom(), "effectiveFrom", e);
        if (VisibleText.isBlank(in.effectiveFrom())) {
            e.add("effectiveFrom", "REQUIRED");
        }
        String useYn = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!Set.of("Y", "N").contains(useYn)) {
            e.add("useYn", "CODE");
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        return new CsrValue(custId, no, rev, title, from, overrides(in.overrides(), e), useYn, remark, in.rowVer(), notifyReq(in.notifyReq(), e));
    }

    /** 정해진 키만, 1 ~ 상한 정수. 빈 값은 "override 없음" */
    static String overrides(Map<String, Object> raw, FieldErrors e) {
        Map<String, Integer> out = new TreeMap<>();
        if (raw != null) {
            for (Map.Entry<String, Object> en : raw.entrySet()) {
                String key = en.getKey();
                Integer max = key == null ? null : OVERRIDE_KEYS.get(key);
                String field = "overrides." + key;
                if (max == null) {
                    e.add("overrides", "CSR_OVERRIDE_KEY");
                    continue;
                }
                Object val = en.getValue();
                if (val == null || (val instanceof String s && s.isBlank())) {
                    continue;
                }
                Long n = null;
                if (val instanceof Integer || val instanceof Long || val instanceof Short) {
                    n = ((Number) val).longValue();
                } else if (val instanceof String s && s.strip().matches("\\d{1,9}")) {
                    n = Long.parseLong(s.strip());
                }
                if (n == null) {
                    e.add(field, "FORMAT");
                } else if (n < 1 || n > max) {
                    e.add(field, "RANGE", 1, max);
                } else {
                    out.put(key, n.intValue());
                }
            }
        }
        return JSON.writeValueAsString(out);
    }

    /**
     * 4M 유형별 고객 통지 요구(docs/29_P3 52장 CN-1·CN-2) — null 이면 그대로(null 돌려줌), NONE·빈 값 = 요구 없음(저장 안 함).
     * 키는 4M 유형, 값은 NOTIFY·APPROVE — 틀리면 칸 오류 notifyReq.{유형}
     */
    static String notifyReq(Map<String, String> raw, FieldErrors e) {
        if (raw == null) {
            return null;
        }
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> en : raw.entrySet()) {
            String key = en.getKey();
            if (key == null || !ChangeService.M4_TYPES.contains(key)) {
                e.add("notifyReq", "CODE");
                continue;
            }
            String v = VisibleText.isBlank(en.getValue()) ? "NONE" : en.getValue().strip();
            if ("NONE".equals(v)) {
                continue;
            }
            if (!CustNotifyJudge.LEVELS.contains(v)) {
                e.add("notifyReq." + key, "CODE");
            } else {
                out.put(key, v);
            }
        }
        return JSON.writeValueAsString(out);
    }

    private List<CsrTargetValue> targets(List<CsrTargetForm> raw, FieldErrors e) {
        List<CsrTargetForm> in = raw == null ? List.of() : raw;
        if (in.size() > MAX_TARGETS || in.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<CsrTargetValue> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            CsrTargetForm t = in.get(i);
            FieldErrors.RowScope row = e.row(String.valueOf(i));
            String type = VisibleText.isBlank(t.targetType()) ? null : t.targetType().strip();
            String cd = VisibleText.isBlank(t.targetCd()) ? null : t.targetCd().strip().toUpperCase(Locale.ROOT);
            if (type == null || !TARGET_TYPES.contains(type)) {
                row.add("targetType", type == null ? "REQUIRED" : "CODE");
                continue;
            }
            if (cd == null) {
                row.add("targetCd", "REQUIRED");
                continue;
            }
            Ref r = "ITEM".equals(type) ? mapper.findItem(cd) : mapper.findClass(cd);
            if (r == null) {
                row.add("targetCd", "NO_SUCH_CODE");
                continue;
            }
            if (!seen.add(type + ":" + r.getId())) {
                row.add("targetCd", "DUPLICATE");
                continue;
            }
            out.add("ITEM".equals(type) ? new CsrTargetValue(r.getId(), null) : new CsrTargetValue(null, r.getId()));
        }
        return out;
    }

    private CsrRow find(long id) {
        CsrRow r = mapper.findCsr(id);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        parse(r);
        return r;
    }

    private static void parse(CsrRow r) {
        String json = r.getOverridesJson();
        r.setOverrides(json == null ? Map.of() : JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { }));
        r.setOverridesJson(null);
        String nr = r.getNotifyReqJson();
        r.setNotifyReq(nr == null ? Map.of() : JSON.readValue(nr, new TypeReference<LinkedHashMap<String, String>>() { }));
        r.setNotifyReqJson(null);
    }
}
