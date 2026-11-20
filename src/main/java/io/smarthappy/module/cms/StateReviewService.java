package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.at;
import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsStateDtos.IntervalRef;
import io.smarthappy.module.cms.CmsStateDtos.LinkForm;
import io.smarthappy.module.cms.CmsStateDtos.LinkRow;
import io.smarthappy.module.cms.CmsStateDtos.MwoAlarmRow;
import io.smarthappy.module.cms.CmsStateDtos.MwoDownRow;
import io.smarthappy.module.cms.CmsStateDtos.MwoDowntime;
import io.smarthappy.module.cms.CmsStateDtos.MwoOption;
import io.smarthappy.module.cms.CmsStateDtos.NoteForm;
import io.smarthappy.module.cms.CmsStateDtos.NoteRow;
import io.smarthappy.module.cms.CmsStateDtos.ReasonRow;
import io.smarthappy.module.cms.CmsStateDtos.ReviewDetail;
import io.smarthappy.module.cms.CmsStateDtos.ReviewExport;
import io.smarthappy.module.cms.CmsStateDtos.ReviewPage;
import io.smarthappy.module.cms.CmsStateDtos.ReviewRow;
import io.smarthappy.module.cms.CmsStateDtos.ReviewSort;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

/**
 * 비가동 사유 검토 (cms-state-review) — docs/33 11.2 C7, D18. MES 상태 구간 원장은 읽기만 하고,
 * 사후 사유(cms_state_note)·고장 작업지시 연결(cms_mwo_down)을 덧붙임 표에 추가 + 무효로 남긴다.
 * 규칙(트리거가 최종): 사후 사유는 닫힌 구간만·UNKNOWN 제외·같은 분류(UDT ↔ SDT 재분류만 예외)·구간당 유효 하나,
 * 연결은 실효 분류 UDT·같은 설비의 취소 아닌 BM·구간당 유효 하나.
 */
@Service
@RequiredArgsConstructor
public class StateReviewService {

    static final String PROG = "cms-state-review";
    static final int MAX_ROWS = 2000;
    static final int MAX_DAYS = 31;
    static final int OPTION_LIMIT = 50;
    static final Set<String> CATS = Set.of("PRD", "SBY", "ENG", "SDT", "UDT", "NST", "UNKNOWN");
    /** 실효 사유 코드 거르기(대시보드 Pareto 드릴다운, docs/51 C4) — cms_state_reason.reason_cd 형식 */
    static final java.util.regex.Pattern REASON_CD = java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$");

    private final CmsStateMapper mapper;
    private final DateRanges ranges;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    private final ExcelExportService excel;

    /** 기간 = 공장 현지 날짜 [from 00:00, to 다음날 00:00) 과 겹치는 구간, 최대 31일 */
    @Transactional(readOnly = true)
    public List<ReviewRow> list(String plantCd, String from, String to, String keyword, String effCat, boolean pending, String reasonCd) {
        ReviewCrit c = criteria(plantCd, from, to, keyword, effCat, reasonCd);
        List<ReviewRow> rows = mapper.findReview(c.plant, c.from, c.to, c.keyword, c.effCat, pending, c.reasonCd, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        rows.forEach(r -> zoned(r, c.zone));
        return rows;
    }

    /** 목록·grid 공통 조건(공장·기간 필수, 기간은 공장 시간대의 [from 0시, to+1 0시)) */
    private record ReviewCrit(String plant, ZoneId zone, OffsetDateTime from, OffsetDateTime to, String keyword, String effCat, String reasonCd) {
    }

    private ReviewCrit criteria(String plantCd, String from, String to, String keyword, String effCat, String reasonCd) {
        FieldErrors e = FieldErrors.create();
        if (blank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        if (keyword != null && keyword.length() > 100 || !blank(effCat) && !CATS.contains(effCat)
                || !blank(reasonCd) && !REASON_CD.matcher(reasonCd.strip()).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ranges.check(f, t, MAX_DAYS);
        String plant = plantCd.strip();
        ZoneId z = clock.zone(plant);
        return new ReviewCrit(plant, z, f.atStartOfDay(z).toOffsetDateTime(), t.plusDays(1).atStartOfDay(z).toOffsetDateTime(),
                blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), blank(effCat) ? null : effCat,
                blank(reasonCd) ? null : reasonCd.strip().toUpperCase(Locale.ROOT));
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link ReviewSort}) */
    @Transactional(readOnly = true)
    public ReviewPage grid(String plantCd, String from, String to, String keyword, String effCat, boolean pending, Integer page, Integer size,
            List<String> sort, Boolean total, String reasonCd) {
        ReviewCrit c = criteria(plantCd, from, to, keyword, effCat, reasonCd);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ReviewSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("startAt,desc"));
        List<ReviewRow> rows = mapper.findReviewPage(c.plant, c.from, c.to, c.keyword, c.effCat, pending, c.reasonCd, pq);
        rows.forEach(r -> zoned(r, c.zone));
        return new ReviewPage(rows, pq.isWithTotal() ? mapper.countReview(c.plant, c.from, c.to, c.keyword, c.effCat, pending, c.reasonCd) : null);
    }

    /** grid 열(화면 구간 표와 같은 칸). 시각은 공장 현지 글자, 분은 계산 값, 사유는 원장·사후 두 칸 — 정렬 밖 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("equipCd", "cms.stateReview.col.equip", 110).withSort(),
            ColumnSpec.text("equipNm", "cms.col.equipNm", 150).withPriority(3),
            ColumnSpec.code("effCat", "cms.stateReview.col.effCat", "STATE_CAT", 90).withSort(),
            ColumnSpec.text("startAt", "cms.stateReview.col.start", 130).withSort(),
            ColumnSpec.text("endAt", "cms.stateReview.col.end", 130).withSort(),
            ColumnSpec.of("minutes", "cms.stateReview.col.minutes", ColumnType.INT, 60),
            ColumnSpec.text("logReasonNm", "cms.stateReview.col.reason", 140),
            ColumnSpec.text("noteReasonNm", "cms.stateReview.col.noteReason", 140),
            ColumnSpec.text("mwoNo", "cms.stateReview.col.mwo", 120).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀 열 바꿈 — 시작·끝 = 공장 현지 글자 */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "startAt", List.of(ColumnSpec.text("startAtText", "cms.stateReview.col.start", 130)),
            "endAt", List.of(ColumnSpec.text("endAtText", "cms.stateReview.col.end", 130)));

    /** 엑셀(C21) — grid 와 같은 조건(공장·기간 필수)·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long export(ReviewExport req, HttpServletResponse response) throws IOException {
        var r = req == null ? new ReviewExport(null, null, null, null, null, null, null, null, null) : req;
        ReviewCrit c = criteria(r.plantCd(), r.from(), r.to(), r.keyword(), r.effCat(), r.reasonCd());
        boolean pending = "Y".equals(r.pending());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        PageQuery pq = PageQuery.sortOnly(r.sort(), ReviewSort.values(), List.of("startAt,desc"));
        List<String> keys = new ArrayList<>(List.of("plantCd", "from", "to"));
        if (c.keyword() != null) {
            keys.add("keyword");
        }
        if (c.effCat() != null) {
            keys.add("effCat");
        }
        if (pending) {
            keys.add("pending");
        }
        if (c.reasonCd() != null) {
            keys.add("reasonCd");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsStateReview", cols,
                mapper.countReview(c.plant(), c.from(), c.to(), c.keyword(), c.effCat(), pending, c.reasonCd()), keys),
                w -> mapper.findReviewPage(c.plant(), c.from(), c.to(), c.keyword(), c.effCat(), pending, c.reasonCd(), pq).forEach(row -> {
                    zoned(row, c.zone());
                    w.accept(row);
                }));
    }

    @Transactional(readOnly = true)
    public ReviewDetail get(long stateLogId) {
        ReviewRow r = mapper.findReviewRow(stateLogId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ZoneId z = clock.zone(r.getPlantCd());
        zoned(r, z);
        List<NoteRow> notes = mapper.findNotes(stateLogId);
        notes.forEach(n -> {
            n.setRecordedAt(at(n.getRecordedAt(), z));
            n.setVoidedAt(at(n.getVoidedAt(), z));
        });
        List<LinkRow> links = mapper.findLinks(stateLogId);
        links.forEach(l -> {
            l.setDownStartAt(at(l.getDownStartAt(), z));
            l.setDownEndAt(at(l.getDownEndAt(), z));
            l.setRecordedAt(at(l.getRecordedAt(), z));
            l.setVoidedAt(at(l.getVoidedAt(), z));
        });
        return new ReviewDetail(r, z.getId(), notes, links);
    }

    /** 사유 후보(사용 중) — 화면이 구간 분류로 거른다 */
    @Transactional(readOnly = true)
    public List<ReasonRow> reasons() {
        return mapper.findReasons(null, null, "Y");
    }

    /** 연결 후보: 같은 설비의 BM(취소 제외), 구간 시작 하루 전 ~ 구간 끝(열렸으면 지금) 하루 뒤에 접수 */
    @Transactional(readOnly = true)
    public List<MwoOption> mwoOptions(long stateLogId) {
        IntervalRef iv = interval(stateLogId);
        ZoneId z = clock.zone(iv.getPlantCd());
        OffsetDateTime end = iv.getEndAt() != null ? iv.getEndAt() : CmsSupport.now(clock, z);
        List<MwoOption> rows = mapper.mwoOptions(iv.getEquipId(), iv.getStartAt().minusDays(1), end.plusDays(1), OPTION_LIMIT);
        rows.forEach(o -> {
            o.setReportedAt(at(o.getReportedAt(), z));
            o.setDownStartAt(at(o.getDownStartAt(), z));
            o.setDownEndAt(at(o.getDownEndAt(), z));
        });
        return rows;
    }

    @Transactional
    public long addNote(long stateLogId, NoteForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_STATE_NOTE_ADD");
        interval(stateLogId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ReasonRow reason = null;
        if (blank(in.reasonCd())) {
            e.add("reasonCd", "REQUIRED");
        } else {
            String cd = in.reasonCd().strip().toUpperCase(Locale.ROOT);
            reason = cd.length() > 30 ? null : mapper.findReasonByCd(cd);
            if (reason == null) {
                e.add("reasonCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(reason.getUseYn())) {
                e.add("reasonCd", "INACTIVE");
            }
        }
        String note = text(in.noteText(), "noteText", 500, false, e);
        e.throwIfAny();
        try {
            return mapper.insertNote(stateLogId, reason.getReasonId(), note, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("reasonCd", "STATE_NOTED");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void voidNote(long stateLogId, long noteId, String reason) {
        LoginUser me = me();
        interval(stateLogId);
        FieldErrors e = FieldErrors.create();
        String why = text(reason, "reason", 500, true, e);
        e.throwIfAny();
        try {
            if (mapper.voidNote(stateLogId, noteId, why, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            if ("ck_cms_state_note_linked".equals(PgErrors.constraint(ex).orElse(""))) {
                throw fieldError("reason", "STATE_UNLINK_FIRST");
            }
            throw translate(ex);
        }
    }

    @Transactional
    public long link(long stateLogId, LinkForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_MWO_DOWN_ADD");
        interval(stateLogId);
        if (in == null || in.mwoId() == null) {
            throw fieldError("mwoId", "REQUIRED");
        }
        try {
            return mapper.insertLink(stateLogId, in.mwoId(), me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("mwoId", "STATE_LINKED");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void unlink(long stateLogId, long linkId, String reason) {
        LoginUser me = me();
        interval(stateLogId);
        FieldErrors e = FieldErrors.create();
        String why = text(reason, "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.voidLink(stateLogId, linkId, why, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 보전 작업지시 상세(C11): 연결된 수집 정지 구간(C7)과 이 작업지시를 만든 알람(C8). 수집 정지 합계(닫힌 구간)는 작업지시 수기 정지 구간과 대조용.
     * 작업지시는 호출 쪽(MwoService.get)이 이 회사 것으로 확인했다
     */
    @Transactional(readOnly = true)
    public MwoDowntime mwoDowntime(long mwoId, String plantCd) {
        ZoneId z = plantCd == null ? clock.zone() : clock.zone(plantCd);
        List<MwoDownRow> downs = mapper.mwoDowns(mwoId);
        long total = 0;
        boolean anyClosed = false;
        for (MwoDownRow d : downs) {
            if (d.getMinutes() != null) {
                total += d.getMinutes();
                anyClosed = true;
            }
            d.setStartAt(at(d.getStartAt(), z));
            d.setEndAt(at(d.getEndAt(), z));
        }
        List<MwoAlarmRow> alarms = mapper.mwoAlarms(mwoId);
        alarms.forEach(a -> {
            a.setRaisedAt(at(a.getRaisedAt(), z));
            a.setClearedAt(at(a.getClearedAt(), z));
        });
        return new MwoDowntime(z.getId(), downs, alarms, anyClosed ? total : null);
    }

    private IntervalRef interval(long stateLogId) {
        IntervalRef iv = mapper.findInterval(stateLogId);
        if (iv == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return iv;
    }

    private static void zoned(ReviewRow r, ZoneId z) {
        r.setStartAt(at(r.getStartAt(), z));
        r.setEndAt(at(r.getEndAt(), z));
        r.setNoteAt(at(r.getNoteAt(), z));
        r.setMwoDownStartAt(at(r.getMwoDownStartAt(), z));
        r.setMwoDownEndAt(at(r.getMwoDownEndAt(), z));
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_state_note_ctx_comp" -> fieldError("reasonCd", "NO_SUCH_CODE");
            case "ck_cms_state_note_closed" -> fieldError("reasonCd", "STATE_OPEN");
            case "ck_cms_state_note_cat" -> fieldError("reasonCd", "STATE_CAT");
            case "ck_cms_state_note_linked" -> fieldError("reasonCd", "STATE_UNLINK_FIRST");
            case "fk_cms_mwo_down_ctx_comp", "fk_cms_mwo_down_mwo" -> fieldError("mwoId", "NO_SUCH_CODE");
            case "ck_cms_mwo_down_mwo" -> fieldError("mwoId", "MWO_NOT_BREAKDOWN");
            case "ck_cms_mwo_down_cat" -> fieldError("mwoId", "STATE_NOT_UDT");
            default -> e;
        };
    }
}
