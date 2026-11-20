package io.smarthappy.module.qms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoQualityView;
import io.smarthappy.module.plm.link.EcoSubmitRule;
import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.module.qms.ChangeDtos.ChangeDetail;
import io.smarthappy.module.qms.ChangeDtos.ChangeForm;
import io.smarthappy.module.qms.ChangeDtos.ChangeHead;
import io.smarthappy.module.qms.ChangeDtos.ChangePage;
import io.smarthappy.module.qms.ChangeDtos.ChangeRow;
import io.smarthappy.module.qms.ChangeDtos.ChangeSort;
import io.smarthappy.module.qms.ChangeDtos.ChangeValue;
import io.smarthappy.module.qms.ChangeDtos.CharForm;
import io.smarthappy.module.qms.ChangeDtos.CharValue;
import io.smarthappy.module.qms.ChangeDtos.Choice;
import io.smarthappy.module.qms.ChangeDtos.DeleteForm;
import io.smarthappy.module.qms.ChangeDtos.EcoHead;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.ApiError.FieldErrorView;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/**
 * 4M 변경 (qms-change) — P3 QMS 2부 Q5, docs/29_P3 11장·12.1. PLM ECO 의 1:1 확장.
 *
 * - ECO 머리는 PLM 계약 {@link EcoLookup} 으로만 읽는다. plm_eco 직접 읽기는 저장 전 상태 잠금(FOR SHARE) 하나 — PLM 상신과 직렬화(11.4)
 * - 저장·삭제는 ECO 가 DRAFT 일 때만(트리거가 최종). 4M 유형 하나 이상, 임시 변경(TEMP)은 복귀 조건 필수
 * - ECO 상신 규칙 ④({@link EcoSubmitRule}) 와 ECO 상세 품질 요약 ③({@link EcoQualityView}, 인터페이스는 PLM) 도 이 클래스가 구현한다
 */
@Service
public class ChangeService implements EcoSubmitRule, EcoQualityView {

    static final String PROG = "qms-change";
    static final Set<String> M4_TYPES = new LinkedHashSet<>(List.of("MAN", "MACHINE", "MATERIAL", "METHOD", "MEASURE", "ENV", "SUPPLIER", "SITE"));
    static final Set<String> REQUIRED_KINDS = Set.of("PROCESS_4M", "TEMP");
    static final int LIST_LIMIT = 200;
    static final int MAX_CHARS = 200;
    /** ECO 상신 칸 오류의 행 키 (docs/29_P3 12.1 Q5-3) */
    static final String RULE_ROW = "qms4m";
    static final List<String> GRID_DEFAULT_SORT = List.of("updatedAt,desc");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("ecoNo", "qms.change.col.ecoNo", 120).withSort().withPriority(1),
            ColumnSpec.code("ecoStatus", "qms.change.col.ecoStatus", "PLM_ECO_STATUS", 90).withPriority(1),
            ColumnSpec.text("ecoTitle", "qms.change.col.title", 180).withFlex().withMinWidth(120).withPriority(2),
            ColumnSpec.text("m4Text", "qms.change.col.m4Types", 140).withPriority(1),
            ColumnSpec.of("charCnt", "qms.change.col.impactCnt", ColumnSpec.ColumnType.INT, 70).withPriority(2),
            ColumnSpec.of("updatedAt", "qms.change.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2));

    private final ChangeMapper mapper;
    private final PolicyService policies;
    private final ObjectProvider<EcoLookup> ecoLookup;
    private final MessageSource messages;
    private final SetupMapper setupMapper;
    private final ObjectProvider<EcoTrace> ecoTrace;
    private final ChangeRecordService records;
    private final BizClock clock;
    private final CustNotifyService custNotify;

    public ChangeService(ChangeMapper mapper, PolicyService policies, ObjectProvider<EcoLookup> ecoLookup, MessageSource messages,
            SetupMapper setupMapper, ObjectProvider<EcoTrace> ecoTrace, ChangeRecordService records, BizClock clock,
            CustNotifyService custNotify) {
        this.mapper = mapper;
        this.policies = policies;
        this.ecoLookup = ecoLookup;
        this.messages = messages;
        this.setupMapper = setupMapper;
        this.ecoTrace = ecoTrace;
        this.records = records;
        this.clock = clock;
        this.custNotify = custNotify;
    }

    // ------------------------------------------------------------------ 조회

    /** 복귀 기한 조건(docs/29_P3 51.2 RV-5) — null = 조건 없음, OVERDUE = 홈 카드와 같은 함수로 고른 ECO id(비면 0건) */
    private List<Long> dueIds(String revertDue) {
        String d = VisibleText.isBlank(revertDue) ? null : revertDue.strip();
        if (d == null) {
            return null;
        }
        if (!"OVERDUE".equals(d)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return records.overdueTemps(clock.today()).stream().map(EcoView::ecoId).toList();
    }

    @Transactional(readOnly = true)
    public List<ChangeRow> list(String keyword, String m4Type, String revertDue) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String t = VisibleText.isBlank(m4Type) ? null : m4Type.strip();
        if ((kw != null && kw.length() > 100) || (t != null && !M4_TYPES.contains(t))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return withEco(mapper.findPage(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", t, dueIds(revertDue), LIST_LIMIT));
    }

    /** 그리드(UI 단계 3, 결정 #45) — 목록과 같은 조건·검증 + 쪽·정렬, 기본 = 고친 시각 최근 */
    @Transactional(readOnly = true)
    public ChangePage grid(String keyword, String m4Type, String revertDue, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ChangeSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                GRID_DEFAULT_SORT);
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String t = VisibleText.isBlank(m4Type) ? null : m4Type.strip();
        if ((kw != null && kw.length() > 100) || (t != null && !M4_TYPES.contains(t))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String like = kw == null ? null : "%" + BomService.likeEscape(kw) + "%";
        List<Long> ids = dueIds(revertDue);
        return new ChangePage(withEco(mapper.findGrid(like, t, ids, pq)), pq.isWithTotal() ? mapper.countList(like, t, ids) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** ECO 머리는 계약으로 한 번에(상한 EcoLookup.MAX_IDS ≥ 목록 상한·쪽 크기 상한) */
    private List<ChangeRow> withEco(List<ChangeRow> rows) {
        Map<Long, EcoView> ecos = EcoLookup.of(ecoLookup).ecos(rows.stream().map(ChangeRow::getEcoId).toList()).stream()
                .collect(Collectors.toMap(EcoView::ecoId, Function.identity()));
        for (ChangeRow r : rows) {
            EcoView v = ecos.get(r.getEcoId());
            if (v != null) {
                r.setEcoTitle(v.title());
                r.setEcoStatus(v.status());
                r.setChangeKind(v.changeKind());
                r.setEffectiveDate(v.effectiveDate());
            }
        }
        return rows;
    }

    /** ECO 번호로 열기 — 없거나 다른 회사면 404 */
    @Transactional(readOnly = true)
    public long ecoIdByNo(String ecoNo) {
        String no = VisibleText.isBlank(ecoNo) ? null : ecoNo.strip();
        if (no == null || no.length() > 50) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return EcoLookup.of(ecoLookup).byNo(no).map(EcoView::ecoId).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public ChangeDetail detail(long ecoId) {
        EcoView v = eco(ecoId);
        ChangeHead head = mapper.findHead(ecoId);
        return new ChangeDetail(head(v), head, head == null ? List.of() : mapper.findChars(ecoId), "DRAFT".equals(v.status()),
                required(v), issues(v, head != null).stream()
                        .map(f -> new FieldErrorView(f.rowKey(), f.field(), f.code(),
                                messages.getMessage("valid." + f.code(), f.args(), f.code(), LocaleContextHolder.getLocale())))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<Choice> choices(long ecoId) {
        EcoView v = eco(ecoId);
        List<Choice> out = new ArrayList<>(mapper.charChoices(v.itemIds(), v.procIds()));
        out.addAll(mapper.planChoices(v.itemIds(), v.procIds()));
        return out;
    }

    // ------------------------------------------------------------------ 저장

    /** 4M 세부 + 영향 행 한 벌(chars 가 null 이면 그대로). 처음이면 만들고, 있으면 rowVer 로 고친다 */
    @Transactional
    public void save(long ecoId, ChangeForm in) {
        LoginUser me = SamplingService.me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        EcoView v = draftLocked(ecoId);
        ChangeHead cur = mapper.findHead(ecoId);
        if (cur != null && in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ChangeValue val = value(in, v, e);
        List<CharValue> chars = in.chars() == null ? null : chars(in.chars(), e);
        e.throwIfAny();
        if (cur == null) {
            mapper.insertChange(ecoId, val, me.loginId());
        } else if (mapper.updateChange(ecoId, val, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (chars != null) {
            mapper.deleteChars(ecoId);
            if (!chars.isEmpty()) {
                mapper.insertChars(ecoId, chars, me.loginId());
            }
        }
    }

    /** 4M 세부 지우기(ECO 작성 중일 때만) — 잘못 붙인 ECO 정리용 */
    @Transactional
    public void delete(long ecoId, DeleteForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        draftLocked(ecoId);
        if (mapper.findHead(ecoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        mapper.deleteChars(ecoId);
        if (mapper.deleteChange(ecoId, in.rowVer()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 계약 ④ 상신 규칙 · ③ 요약

    /** PLM EcoService.submit 이 ECO 행 잠금 뒤 부른다(같은 트랜잭션, 읽기만) */
    @Override
    public List<FieldError> check(long ecoId) {
        Optional<EcoView> v = EcoLookup.of(ecoLookup).eco(ecoId);
        return v.isEmpty() ? List.of() : issues(v.get(), mapper.exists(ecoId));
    }

    /**
     * PLM ECO 상세가 부른다(그 조회 트랜잭션 안). 초품 수 = 첫 LOT 표시 중 살아 있는 판정 OK·NG 와 나머지(미판정) (Q7-5).
     * 4M 이 필요 없고 4M 세부·첫 LOT 표시도 없으면 빈 값 — ECO 화면의 품질 영역을 띄우지 않는다
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Summary> summary(long ecoId) {
        Optional<EcoView> v = EcoLookup.of(ecoLookup).eco(ecoId);
        if (v.isEmpty()) {
            return Optional.empty();
        }
        ChangeHead head = mapper.findHead(ecoId);
        long firsts = EcoTrace.of(ecoTrace).markedLots(ecoId).stream().filter(m -> SetupService.FIRST_LOT.equals(m.reason())).count();
        if (head == null && !required(v.get()) && firsts == 0) {
            return Optional.empty();
        }
        Integer ok = null;
        Integer ng = null;
        Integer open = null;
        if (firsts > 0) {
            SetupDtos.Counts c = setupMapper.counts(ecoId);
            ok = c.getOk();
            ng = c.getNg();
            open = (int) firsts - ok - ng;
        }
        return Optional.of(new Summary(head != null, head == null ? List.of() : head.getM4Types(), head == null ? 0 : mapper.charCount(ecoId),
                ok, ng, open));
    }

    /** 상신 때 나올 칸 오류 — 정책이 켜져 있고 PROCESS_4M·TEMP 일 때만 (docs/29_P3 12.1 Q5-3, docs/39 16.1) */
    List<FieldError> issues(EcoView v, boolean has4m) {
        List<FieldError> out = new ArrayList<>();
        if (required(v)) {
            if (!has4m) {
                out.add(new FieldError(RULE_ROW, "qmsChange", "QMS_4M_MISSING"));
            }
            if (v.equipIds().isEmpty() && v.procIds().isEmpty()) {
                out.add(new FieldError(RULE_ROW, "targets", "QMS_4M_NO_MARK_TARGET"));
            }
        }
        // 고객 통지 — 정책 BLOCK 일 때만 상신을 막는다(docs/29_P3 52장 CN-7)
        out.addAll(custNotify.gateIssues(v));
        return out;
    }

    private boolean required(EcoView v) {
        return REQUIRED_KINDS.contains(v.changeKind()) && policies.bool(Policies.QMS_4M_REQUIRED);
    }

    // ------------------------------------------------------------------ 검증

    private ChangeValue value(ChangeForm in, EcoView v, FieldErrors e) {
        List<String> raw = in.m4Types() == null ? List.of() : in.m4Types();
        Set<String> types = new LinkedHashSet<>();
        for (String t : raw) {
            String s = t == null ? "" : t.strip();
            if (!M4_TYPES.contains(s)) {
                e.add("m4Types", "CODE");
                break;
            }
            if (!types.add(s)) {
                e.add("m4Types", "DUPLICATE");
                break;
            }
        }
        if (raw.isEmpty()) {
            e.add("m4Types", "REQUIRED");
        }
        String revert = "TEMP".equals(v.changeKind()) ? VisibleText.reason(in.revertCond(), "revertCond", 1, 1000, e)
                : VisibleText.optional(in.revertCond(), "revertCond", 1000, e);
        // 정해진 순서로 저장(목록 표시·비교가 같게)
        String[] ordered = M4_TYPES.stream().filter(types::contains).toArray(String[]::new);
        return new ChangeValue(ordered, VisibleText.reason(in.beforeDesc(), "beforeDesc", 1, 2000, e),
                VisibleText.reason(in.afterDesc(), "afterDesc", 1, 2000, e), VisibleText.optional(in.reasonDesc(), "reasonDesc", 2000, e),
                VisibleText.optional(in.impactDesc(), "impactDesc", 4000, e), revert, in.rowVer());
    }

    /** 영향 행 한 벌 — 행 키 = 배열 순번, 순번은 1부터 다시 */
    private List<CharValue> chars(List<CharForm> in, FieldErrors e) {
        if (in.size() > MAX_CHARS || in.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<CharValue> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            CharForm c = in.get(i);
            FieldErrors.RowScope row = e.row(String.valueOf(i));
            String type = VisibleText.isBlank(c.refType()) ? null : c.refType().strip();
            if (!"CHAR".equals(type) && !"PLAN".equals(type)) {
                row.add("refType", type == null ? "REQUIRED" : "CODE");
                continue;
            }
            if (c.refId() == null) {
                row.add("refId", "REQUIRED");
                continue;
            }
            Long found = "CHAR".equals(type) ? mapper.charOfComp(c.refId()) : mapper.planOfComp(c.refId());
            if (found == null) {
                row.add("refId", "NOT_FOUND");
                continue;
            }
            if (!seen.add(type + ":" + found)) {
                row.add("refId", "DUPLICATE");
                continue;
            }
            String note = VisibleText.strip(c.impactDesc());
            if (note != null && note.length() > 1000) {
                row.add("impactDesc", "TOO_LONG", 1000);
                continue;
            }
            out.add(new CharValue(out.size() + 1, "CHAR".equals(type) ? found : null, "PLAN".equals(type) ? found : null, note));
        }
        return out;
    }

    // ------------------------------------------------------------------ ECO

    private EcoView eco(long ecoId) {
        return EcoLookup.of(ecoLookup).eco(ecoId).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
    }

    /** ECO 상태를 FOR SHARE 로 잡고 작성 중인지 — 없으면 404, 작성 중이 아니면 409 */
    private EcoView draftLocked(long ecoId) {
        String status = mapper.lockEcoStatus(ecoId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DRAFT".equals(status)) {
            throw new BusinessException(HbErrorCode.QMS_CHANGE_STATE);
        }
        return eco(ecoId);
    }

    private static EcoHead head(EcoView v) {
        return new EcoHead(v.ecoId(), v.ecoNo(), v.title(), v.changeKind(), v.status(), v.effectiveDate(), v.markUntil(),
                v.custApprovalStatus(), v.equipIds().size(), v.procIds().size());
    }
}
