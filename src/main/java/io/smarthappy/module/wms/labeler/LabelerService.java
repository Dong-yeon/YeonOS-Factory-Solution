package io.smarthappy.module.wms.labeler;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.module.wms.label.LabelMapper;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxForm;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxQuery;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxSearch;
import io.smarthappy.module.wms.labeler.LabelerDtos.DevLogRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.EventRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerForm;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/** 오토라벨러 화면(wms-labeler) — 라벨러 마스터·박스 사전 등록·박스 목록·원장 (docs/65 21.5) */
@Service
public class LabelerService {

    static final String PROG = "wms-labeler";
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Pattern BOX_CD = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/#:-]{0,59}$");
    static final Set<String> STATUSES = Set.of("REGISTERED", "PREFETCHED", "REQUESTED", "VERIFIED", "REJECTED", "SKIPPED");
    static final int MAX_QUERY_DAYS = 92;
    static final List<String> BOX_SORT = List.of("createdAt,desc");

    static final List<ColumnSpec> BOX_COLUMNS = List.of(
            ColumnSpec.of("createdAt", "wmsLabeler.col.createdAt", ColumnSpec.ColumnType.DATETIME, 140).withSort(),
            ColumnSpec.text("boxCd", "wmsLabeler.col.boxCd", 150).withSort(),
            ColumnSpec.code("status", "wmsLabeler.col.status", "WMS_LABELER_BOX_STATUS", 100).withSort(),
            ColumnSpec.text("sscc", "wmsLabeler.col.sscc", 160),
            ColumnSpec.text("itemCd", "wmsLabeler.col.itemCd", 120),
            ColumnSpec.text("itemNm", "wmsLabeler.col.itemNm", 160).withFlex().withMinWidth(110),
            ColumnSpec.dec("qty", "wmsLabeler.col.qty", 6, 80).withMinScale(0),
            ColumnSpec.of("attemptCnt", "wmsLabeler.col.attemptCnt", ColumnSpec.ColumnType.INT, 60),
            ColumnSpec.code("lastReasonCd", "wmsLabeler.col.lastReasonCd", "WMS_LABELER_REASON", 120),
            ColumnSpec.text("refNo", "wmsLabeler.col.refNo", 110).withPriority(3),
            ColumnSpec.text("labelSkipYn", "wmsLabeler.col.labelSkipYn", 60).withPriority(3));

    private final LabelerMapper mapper;
    private final LabelMapper labels;
    private final InboxService inbox;
    private final PolicyService policy;
    private final DateRanges ranges;
    private final BizClock clock;

    public LabelerService(LabelerMapper mapper, LabelMapper labels, InboxService inbox, PolicyService policy, DateRanges ranges,
            BizClock clock) {
        this.mapper = mapper;
        this.labels = labels;
        this.inbox = inbox;
        this.policy = policy;
        this.ranges = ranges;
        this.clock = clock;
    }

    // ================================================================== 라벨러

    @Transactional(readOnly = true)
    public List<LabelerRow> labelers(String plantCd) {
        return mapper.findLabelers(VisibleText.isBlank(plantCd) ? null : plantCd.strip());
    }

    @Transactional
    public long createLabeler(LabelerForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("WMS_LABELER_CREATE");
        LabelerForm f = validate(in, null);
        try {
            return mapper.insertLabeler(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void updateLabeler(long id, LabelerForm in) {
        LoginUser me = me();
        LabelerRow cur = mapper.findLabeler(id);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LabelerForm f = validate(in, cur);
        try {
            if (mapper.updateLabeler(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private LabelerForm validate(LabelerForm in, LabelerRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = cur != null ? cur.getPlantCd() : VisibleText.isBlank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        String cd = cur != null ? cur.getLabelerCd() : VisibleText.isBlank(in.labelerCd()) ? null : in.labelerCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (in.plantCd() != null && !in.plantCd().strip().equalsIgnoreCase(cur.getPlantCd())) {
                e.add("plantCd", "FIXED");
            }
            if (in.labelerCd() != null && !in.labelerCd().strip().equalsIgnoreCase(cur.getLabelerCd())) {
                e.add("labelerCd", "FIXED");
            }
        } else {
            if (plant == null) {
                e.add("plantCd", "REQUIRED");
            } else if (!labels.plantActive(plant)) {
                e.add("plantCd", "NOT_FOUND");
            }
            if (cd == null) {
                e.add("labelerCd", "REQUIRED");
            } else if (!CODE.matcher(cd).matches()) {
                e.add("labelerCd", "FORMAT");
            }
        }
        String nm = VisibleText.isBlank(in.labelerNm()) ? null : in.labelerNm().strip();
        if (nm == null) {
            e.add("labelerNm", "REQUIRED");
        } else if (nm.length() > 100) {
            e.add("labelerNm", "TOO_LONG", 100);
        }
        // 장치 = 시스템관리에서 키를 발급한 수집 장치(COLLECTOR, 폐기 안 됨) — 다른 회사 장치는 RLS 로 안 보인다
        if (in.terminalId() == null) {
            e.add("terminalId", "REQUIRED");
        } else if (!labels.agentActive(in.terminalId())) {
            e.add("terminalId", "NOT_FOUND");
        }
        String conn = VisibleText.isBlank(in.connMode()) ? "DIRECT" : in.connMode().strip();
        if (!"DIRECT".equals(conn) && !"EDGE".equals(conn)) {
            e.add("connMode", "CODE");
        }
        if (in.tplId() == null) {
            e.add("tplId", "REQUIRED");
        } else {
            TplRow t = labels.findTpl(in.tplId());
            if (t == null) {
                e.add("tplId", "NOT_FOUND");
            } else if (!"PALLET".equals(t.getLabelType())) {
                e.add("tplId", "LABEL_TYPE_MISMATCH");                       // 물류 단위 SSCC 라벨 = PALLET 유형 칸
            } else if (!"Y".equals(t.getUseYn())) {
                e.add("tplId", "INACTIVE");
            }
        }
        int deadline = in.deadlineMs() == null ? 2000 : in.deadlineMs();
        if (deadline < 200 || deadline > 60000) {
            e.add("deadlineMs", "RANGE", 200, 60000);
        }
        String use = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = VisibleText.isBlank(in.remark()) ? null : in.remark().strip();
        if (remark != null && remark.length() > 500) {
            e.add("remark", "TOO_LONG", 500);
        }
        e.throwIfAny();
        return new LabelerForm(plant, cd, nm, in.terminalId(), conn, in.tplId(), deadline, use, remark, in.rowVer());
    }

    private RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        if ("uq_wms_labeler_cd".equals(c)) {
            return fieldError("labelerCd", "DUPLICATE");
        }
        if ("uq_wms_labeler_terminal".equals(c)) {
            return fieldError("terminalId", "DUPLICATE");                     // 한 장치 = 한 라벨러
        }
        if ("uq_wms_labeler_box_cd".equals(c)) {
            return fieldError("boxCd", "DUPLICATE");
        }
        return e;
    }

    /** 화면 선택지: 공장 · 장치(시스템관리에서 키 발급한 COLLECTOR) · PALLET 유형 템플릿(사용 중) */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> options() {
        return java.util.Map.of("plants", mapper.plants(),
                "devices", labels.agentOptions(policy.intValue("WMS_PRINT_AGENT_OFFLINE_SEC")),
                "templates", labels.findTpls("PALLET", "Y").stream()
                        .map(t -> java.util.Map.<String, Object>of("tplId", t.getTplId(), "tplCd", t.getTplCd(), "tplNm", t.getTplNm(),
                                "sizeCd", t.getSizeCd() == null ? "" : t.getSizeCd()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<DevLogRow> devLogs(long labelerId) {
        if (mapper.findLabeler(labelerId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.devLogs(labelerId, 100);
    }

    // ================================================================== 박스

    public record Page(List<BoxRow> list, Long total) {
    }

    @Transactional(readOnly = true)
    public Page boxes(BoxQuery q, Integer page, Integer size, List<String> sort, Boolean total) {
        if (q == null || q.from() == null || q.to() == null) {
            throw fieldError("from", "REQUIRED");
        }
        ranges.check(q.from(), q.to(), MAX_QUERY_DAYS);
        String status = VisibleText.isBlank(q.status()) ? null : q.status().strip();
        if (status != null && !STATUSES.contains(status)) {
            throw fieldError("status", "CODE");
        }
        String like = VisibleText.isBlank(q.boxCd()) ? null : q.boxCd().strip();
        if (like != null && like.length() > 60) {
            throw fieldError("boxCd", "TOO_LONG", 60);
        }
        PageQuery pq = PageQuery.parse(page, size, sort, total, LabelerDtos.BoxSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                BOX_SORT);
        ZoneId zone = clock.zone();
        BoxSearch s = new BoxSearch(VisibleText.isBlank(q.plantCd()) ? null : q.plantCd().strip(), status,
                like == null ? null : "%" + like.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
                q.from().atStartOfDay(zone).toOffsetDateTime(), q.to().plusDays(1).atStartOfDay(zone).toOffsetDateTime(), pq);
        return new Page(mapper.findBoxPage(s), pq.isWithTotal() ? mapper.countBoxes(s) : null);
    }

    public List<ColumnSpec> boxColumns() {
        return BOX_COLUMNS;
    }

    @Transactional(readOnly = true)
    public BoxRow box(long id) {
        BoxRow b = mapper.findBox(id);
        if (b == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return b;
    }

    @Transactional(readOnly = true)
    public List<EventRow> events(long boxId) {
        box(boxId);
        return mapper.eventsOfBox(boxId);
    }

    /** 사전 등록 — 미등록 박스는 라벨러가 리젝트한다(7.1) */
    @Transactional
    public long registerBox(BoxForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("WMS_LABELER_BOX");
        FieldErrors e = FieldErrors.create();
        String plant = in == null || VisibleText.isBlank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else if (!labels.plantActive(plant)) {
            e.add("plantCd", "NOT_FOUND");
        }
        String boxCd = in == null || VisibleText.isBlank(in.boxCd()) ? null : in.boxCd().strip();
        if (boxCd == null) {
            e.add("boxCd", "REQUIRED");
        } else if (!BOX_CD.matcher(boxCd).matches()) {
            e.add("boxCd", "FORMAT");
        }
        Long itemId = null;
        if (in == null || VisibleText.isBlank(in.itemCd())) {
            e.add("itemCd", "REQUIRED");
        } else {
            itemId = mapper.itemIdByCd(in.itemCd().strip().toUpperCase(Locale.ROOT));
            if (itemId == null) {
                e.add("itemCd", "NOT_FOUND");
            }
        }
        BoxForm f = checkInfo(in, e);
        e.throwIfAny();
        try {
            return mapper.insertBox(plant, new BoxForm(plant, boxCd, in.itemCd(), f.qty(), f.lotNo(), f.refNo(), f.labelSkipYn(), null),
                    itemId, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 고치기 — 아직 라벨러가 손대지 않은(REGISTERED) 박스의 수량·LOT·참조·라벨 생략만 */
    @Transactional
    public void updateBox(long id, BoxForm in) {
        LoginUser me = me();
        BoxRow cur = box(id);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (!"REGISTERED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        if ((in.boxCd() != null && !in.boxCd().strip().equals(cur.getBoxCd()))
                || (in.itemCd() != null && !in.itemCd().strip().equalsIgnoreCase(cur.getItemCd()))) {
            e.add("boxCd", "FIXED");
        }
        BoxForm f = checkInfo(in, e);
        e.throwIfAny();
        if (mapper.updateBoxInfo(id, new BoxForm(null, null, null, f.qty(), f.lotNo(), f.refNo(), f.labelSkipYn(), in.rowVer()),
                me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static BoxForm checkInfo(BoxForm in, FieldErrors e) {
        BigDecimal qty = in == null ? null : in.qty();
        if (qty == null) {
            e.add("qty", "REQUIRED");
        } else if (qty.signum() <= 0) {
            e.add("qty", "RANGE", ">0", "");
        } else if (qty.stripTrailingZeros().scale() > 6 || qty.precision() - qty.scale() > 12) {
            e.add("qty", "SCALE", 6);
        }
        String lot = in == null || VisibleText.isBlank(in.lotNo()) ? null : in.lotNo().strip();
        if (lot != null && lot.length() > 60) {
            e.add("lotNo", "TOO_LONG", 60);
        }
        String ref = in == null || VisibleText.isBlank(in.refNo()) ? null : in.refNo().strip();
        if (ref != null && ref.length() > 60) {
            e.add("refNo", "TOO_LONG", 60);
        }
        String skip = in == null || VisibleText.isBlank(in.labelSkipYn()) ? "N" : in.labelSkipYn().strip();
        if (!"Y".equals(skip) && !"N".equals(skip)) {
            e.add("labelSkipYn", "CODE");
        }
        return new BoxForm(null, null, null, qty, lot, ref, skip, null);
    }

    // ================================================================== 공통

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }


    static BusinessException fieldError(String field, String code, Object... args) {
        return LabelerDeviceService.fieldError(field, code, args);
    }

}
