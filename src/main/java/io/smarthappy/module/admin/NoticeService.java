package io.smarthappy.module.admin;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.NoticeDtos.NoticeForm;
import io.smarthappy.module.admin.NoticeDtos.NoticeItem;
import io.smarthappy.module.admin.NoticeDtos.NoticeRow;
import io.smarthappy.module.admin.NoticeDtos.NoticeSearch;
import io.smarthappy.module.admin.NoticeDtos.NoticeSort;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.file.FileReadGrant;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.notify.InboxBadgeSource;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 공지·자료실 (docs/36 11.2·11.4). 관리(sys-notice) + 읽기(내 알림 화면 — 모든 사용자, 게시 중만).
 * 본문은 글자만(Q16) — 화면은 textContent. 삭제 없음(사용 중지). 게시 기간은 회사 날짜.
 * 첨부는 FileService(대상 sys-notice): 올리기·삭제 = sys-notice SAVE, 읽기 = 게시 중 공지면 누구나({@link FileReadGrant}).
 * 안 읽은 공지 수는 셸 종 버튼에 더한다({@link InboxBadgeSource}) — 팬아웃 없음(Q14).
 */
@Service
@RequiredArgsConstructor
public class NoticeService implements FileReadGrant, InboxBadgeSource {

    static final String PROG = "sys-notice";
    static final String FILE_TARGET = "sys-notice";
    static final int MAX_BODY = 20000;
    /** 종 버튼에 세는 기간 — 게시 시작이 이보다 오래된 공지는 목록에만 */
    static final int BADGE_DAYS = 30;
    static final int READER_LIMIT = 200;
    /** 분류 = 공통코드 NOTICE_CAT(시스템 + 회사 코드, docs/68) */
    static final String CAT_GRP = "NOTICE_CAT";
    static final Set<String> STATES = Set.of("POSTED", "SCHEDULED", "ENDED", "OFF");
    static final List<String> DEFAULT_SORT = List.of("postFrom,desc");

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.code("catCd", "sysNotice.col.catCd", "NOTICE_CAT", 80).withPriority(2),
            ColumnSpec.text("title", "sysNotice.col.title", 280).withSort().withFlex().withPriority(1).withMinWidth(160),
            ColumnSpec.code("stateCd", "sysNotice.col.stateCd", "NOTICE_STATE", 90).withPriority(1),
            ColumnSpec.of("postFrom", "sysNotice.col.postFrom", ColumnSpec.ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("postTo", "sysNotice.col.postTo", ColumnSpec.ColumnType.DATE, 100),
            ColumnSpec.of("pinYn", "sysNotice.col.pinYn", ColumnSpec.ColumnType.YN, 60).withPriority(3),
            ColumnSpec.of("fileCnt", "sysNotice.col.fileCnt", ColumnSpec.ColumnType.INT, 60).withPriority(3),
            ColumnSpec.text("updatedBy", "sysNotice.col.updatedBy", 100).withPriority(3),
            ColumnSpec.of("updatedAt", "sysNotice.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    public record Page(List<NoticeRow> list, Long total) {
    }

    private final NoticeMapper mapper;
    private final BizClock clock;
    private final PolicyService policies;
    private final CodeService codes;

    // ------------------------------------------------------------------ 관리

    @Transactional(readOnly = true)
    public Page list(String keyword, String catCd, String stateCd, Integer page, Integer size, List<String> sort,
            Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, NoticeSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String kw = AdminInputs.blank(keyword) ? null : keyword.strip();
        if ((kw != null && kw.length() > 100) || (!AdminInputs.blank(catCd) && !CodeService.isCodeFormat(catCd))
                || (!AdminInputs.blank(stateCd) && !STATES.contains(stateCd))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        NoticeSearch q = new NoticeSearch(kw == null ? null : "%" + likeEscape(kw) + "%",
                AdminInputs.blank(catCd) ? null : catCd, AdminInputs.blank(stateCd) ? null : stateCd, clock.today(), pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public NoticeRow detail(long noticeId) {
        NoticeRow r = mapper.find(noticeId, clock.today());
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    @Transactional
    public long create(NoticeForm form) {
        return mapper.insert(validate(form, true), me().loginId());
    }

    @Transactional
    public void update(long noticeId, NoticeForm form) {
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.find(noticeId, clock.today()) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.update(noticeId, validate(form, false), me().loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 새 공지는 게시 시작이 오늘 이후(지난 날짜로 "새 공지" 를 만들어 안 읽은 수를 속이지 않게), 고칠 때는 그대로 둘 수 있다 */
    NoticeForm validate(NoticeForm in, boolean isNew) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cat = AdminInputs.blank(in.catCd()) ? "NOTICE" : in.catCd();
        if (!codes.usable(CAT_GRP, cat)) {
            e.add("catCd", "CODE");
        }
        String title = AdminInputs.text(in.title(), "title", 200, true, e);
        String body = in.body() == null ? "" : in.body().replace("\r\n", "\n").strip();
        if (body.length() > MAX_BODY) {
            e.add("body", "TOO_LONG", MAX_BODY);
        } else if (body.chars().anyMatch(c -> (c < 0x20 && c != '\n' && c != '\t') || c == 0x7F
                || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069))) {
            e.add("body", "FORMAT");
        }
        String pin = AdminInputs.blank(in.pinYn()) ? "N" : in.pinYn();
        if (!pin.equals("Y") && !pin.equals("N")) {
            e.add("pinYn", "CODE");
        }
        LocalDate from = in.postFrom();
        if (from == null) {
            e.add("postFrom", "REQUIRED");
        } else if (isNew && from.isBefore(clock.today())) {
            e.add("postFrom", "PAST");
        }
        if (from != null && in.postTo() != null && in.postTo().isBefore(from)) {
            e.add("postTo", "INVALID_RANGE");
        }
        String use = AdminInputs.yn(in.useYn(), "useYn", e);
        e.throwIfAny();
        return new NoticeForm(cat, title, body, pin, from, in.postTo(), use, in.rowVer());
    }

    // ------------------------------------------------------------------ 읽는 사람

    @Transactional(readOnly = true)
    public List<NoticeItem> posted(String catCd) {
        if (!AdminInputs.blank(catCd) && !CodeService.isCodeFormat(catCd)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findPosted(me().userId(), clock.today(), AdminInputs.blank(catCd) ? null : catCd, READER_LIMIT);
    }

    /** 게시 중인 공지 하나 + 읽음 기록. 게시 전·끝·사용 중지·다른 회사는 404 */
    @Transactional
    public NoticeRow read(long noticeId) {
        NoticeRow r = mapper.findPostedOne(noticeId, clock.today());
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        mapper.markRead(noticeId, me().userId());
        return r;
    }

    @Override
    public boolean allows(String targetType, String targetId) {
        if (!FILE_TARGET.equals(targetType) || targetId == null || !targetId.matches("^[1-9][0-9]{0,17}$")) {
            return false;
        }
        return mapper.findPostedOne(Long.parseLong(targetId), clock.today()) != null;
    }

    @Override
    public String code() {
        return "notice";
    }

    @Override
    public long unread(long userId) {
        LocalDate today = clock.today();
        return mapper.countUnread(userId, today, today.minusDays(BADGE_DAYS));
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
