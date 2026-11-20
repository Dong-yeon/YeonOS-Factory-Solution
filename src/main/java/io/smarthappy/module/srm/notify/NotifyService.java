package io.smarthappy.module.srm.notify;

import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.claim.ClaimService;
import io.smarthappy.module.srm.notify.NotifyDtos.NoticeCrit;
import io.smarthappy.module.srm.notify.NotifyDtos.NoticePage;
import io.smarthappy.module.srm.notify.NotifyDtos.NoticeRow;
import io.smarthappy.module.srm.notify.NotifyDtos.NoticeSort;
import io.smarthappy.module.srm.po.PoService;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 발주 통지 이력 (srm-notify) — SRM S4, docs/31 D8. 이력 = sys_outbox 의 발주 토픽, 받는 주소는 가린다.
 * 다시 보내기 = 실패(FAIL)한 건만, 같은 아웃박스 행을 되살리지 않고 지금 발주 상태·주소로 새 메시지를 낸다(PoService.renotify).
 */
@Service
@RequiredArgsConstructor
public class NotifyService {

    public static final String PROG = "srm-notify";
    static final int SEARCH_MAX_DAYS = 366;
    static final Set<String> STATUS = Set.of("NEW", "SENDING", "SENT", "FAIL");

    private final NotifyMapper mapper;
    private final PoService pos;
    private final ClaimService claims;
    private final SrmMailbox mailbox;
    private final InboxService inbox;
    private final DateRanges ranges;
    private final BizClock clock;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public List<NoticeRow> list(String from, String to, String status, Long poId) {
        List<NoticeRow> rows = mapper.findNotices(criteria(from, to, status, poId));
        rows.forEach(r -> r.setToAddr(mask(r.getToAddr())));
        return rows;
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link NoticeSort}, 최대 3개, 모르는 열 400).
     * 받는 주소는 목록과 같이 가린다
     */
    @Transactional(readOnly = true)
    public NoticePage grid(String from, String to, String status, Long poId, Integer page, Integer size, List<String> sort,
            Boolean total) {
        NoticeCrit c = criteria(from, to, status, poId);
        PageQuery pq = PageQuery.parse(page, size, sort, total, NoticeSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("createdAt,desc"));
        List<NoticeRow> rows = mapper.findNoticePage(c, pq);
        rows.forEach(r -> r.setToAddr(mask(r.getToAddr())));
        return new NoticePage(rows, pq.isWithTotal() ? mapper.countNotices(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸, 개정·시도 횟수를 따로) — 정렬 가능 = {@link NoticeSort}. 종류·상태는 화면이 이름으로 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.of("createdAt", "srm.notify.col.at", ColumnType.DATETIME, 130).withSort().withPriority(1),
            ColumnSpec.text("topic", "srm.notify.col.kind", 80).withSort(),
            ColumnSpec.text("poNo", "srm.notify.col.po", 120).withSort().withPriority(1),
            ColumnSpec.of("rev", "srm.notify.col.rev", ColumnType.INT, 50).withPriority(3),
            ColumnSpec.text("custNm", "srm.notify.col.supplier", 130).withSort(),
            ColumnSpec.text("toAddrText", "srm.notify.col.to", 150).withPriority(3),
            ColumnSpec.text("status", "srm.notify.col.status", 90).withSort().withPriority(1),
            ColumnSpec.of("attempts", "srm.notify.col.attempts", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.text("lastError", "srm.notify.col.error", 200).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private NoticeCrit criteria(String from, String to, String status, Long poId) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String st = code(status);
        if (st != null && !STATUS.contains(st)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        OffsetDateTime start = f.atStartOfDay(clock.zone()).toOffsetDateTime();
        OffsetDateTime end = t.plusDays(1).atStartOfDay(clock.zone()).toOffsetDateTime();
        return new NoticeCrit(start, end, st, poId);
    }

    @Transactional
    public void resend(long outboxId) {
        inbox.claimRequestKey("SRM_NOTICE_RESEND");
        NoticeRow n = mapper.findNotice(outboxId);
        if (n == null || n.getPoId() == null && n.getClaimId() == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"FAIL".equals(n.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (n.getClaimId() != null) {
            claims.renotify(n.getClaimId());       // 클레임 통지(2부 R4d) — 보낸 뒤 상태에서만
        } else {
            pos.renotify(n.getPoId(), n.getTopic());
        }
    }

    /** 가짜 메일함(이 회사 것만) — 받는 주소도 가린다 */
    public List<SrmMailbox.Mail> mailbox() {
        return mailbox.list(TenantContext.requireCompCd()).stream()
                .map(m -> new SrmMailbox.Mail(m.messageId(), m.topic(), mask(m.to()), m.subject(), m.body(), m.receivedAt()))
                .toList();
    }

    /** a***@example.com — 화면·응답에 메일 주소를 그대로 내지 않는다 */
    static String mask(String addr) {
        if (addr == null || addr.isBlank()) {
            return null;
        }
        int at = addr.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return addr.charAt(0) + "***" + addr.substring(at);
    }
}
