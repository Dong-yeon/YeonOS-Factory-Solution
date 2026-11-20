package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.PlmDtos.CustOption;
import io.smarthappy.module.plm.PlmDtos.PcnContact;
import io.smarthappy.module.plm.PlmDtos.PcnMailForm;
import io.smarthappy.module.plm.PlmDtos.PcnMailRow;
import io.smarthappy.module.plm.PlmDtos.PcnMailSource;
import io.smarthappy.module.plm.PlmDtos.PcnMailTargets;
import io.smarthappy.module.plm.PlmDtos.PcnForm;
import io.smarthappy.module.plm.PlmDtos.PcnResponseForm;
import io.smarthappy.module.plm.PlmDtos.PcnRow;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 고객 변경 통지 PCN (PLM 4단계, docs/39 14.5) — ECO 가 취소가 아니면 언제나 통지 기록,
 * 응답(확인·승인·거절)은 통지 상태에서 한 번. 같은 ECO·고객의 열린 통지는 하나. 게이트가 아니다(게이트는 ECO 고객 승인).
 * 메일 발송(23장)은 기록과 분리 — 열린 통지에서 고객 담당자를 골라 플랫폼 MailService 로, 매번 발송 이력 한 줄.
 */
@Service
@RequiredArgsConstructor
public class PcnService {

    static final String RULE = "PLM_PCN";
    static final Set<String> RESPONSES = Set.of("ACK", "APPROVED", "REJECTED");

    private final PlmPpapMapper mapper;
    private final PlmMapper plm;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final MailService mail;
    private final CodeService codes;
    private final MessageSource messages;
    private final EcoNoticeService notices;
    private final PlmMailStatus mailStatus;
    private final io.smarthappy.platform.file.FileService files;

    /** 한 번에 보낼 수 있는 담당자 수 */
    static final int MAIL_MAX = 10;

    @Transactional(readOnly = true)
    public List<PcnRow> ofEco(long ecoId) {
        if (plm.ecoStatus(ecoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<PcnRow> rows = mapper.findPcns(ecoId, clock.today());
        java.util.Map<Long, List<PcnMailRow>> mails = new java.util.HashMap<>();
        List<PcnMailRow> all = mapper.findPcnMails(ecoId);
        mailStatus.fill(all);
        for (PcnMailRow m : all) {
            mails.computeIfAbsent(m.getPcnId(), k -> new java.util.ArrayList<>()).add(m);
        }
        boolean on = mail.enabled();
        rows.forEach(r -> {
            r.setMails(mails.getOrDefault(r.getPcnId(), List.of()));
            r.setMailEnabled(on);
        });
        return rows;
    }


    // ------------------------------------------------------------------ 메일 (docs/39 23장)

    /** 받는 사람 후보(통지 고객의 담당자 중 메일 있는 사람) + 메일 설정 켜짐 + 미리보기 — 다른 회사·없는 통지는 404 */
    @Transactional(readOnly = true)
    public PcnMailTargets mailTargets(long pcnId) {
        PcnMailSource src = mapper.pcnMailSource(pcnId);
        if (src == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!mail.enabled()) {
            return new PcnMailTargets(false, List.of(), null, null);
        }
        Locale locale = locale(src);
        return new PcnMailTargets(true, mapper.findPcnContacts(pcnId), subject(src, locale),
                body(src, msg("plm.pcn.mail.previewName", locale), EcoService.me().userNm(), locale));
    }

    /**
     * 고른 담당자에게 메일 한 통씩 — 같은 트랜잭션에서 아웃박스 MAIL 발행 + 발송 이력. 응답 끝난 통지는 409,
     * 메일 꺼짐은 플랫폼 409 MAIL_DISABLED. 받는 사람은 그 통지 고객의 사용 중 담당자만(주소 자유 입력 없음 — E1).
     * 중복 발송은 요청 멱등 키로 막는다(같은 요청 두 번 = 한 번). 다시 보내기는 새 요청으로 허용(E2) → 보낸 메일 수
     */
    @Transactional
    public int mail(long pcnId, PcnMailForm in) {
        LoginUser me = EcoService.me();
        inbox.claimRequestKey("PLM_PCN_MAIL");
        PcnRow cur = mapper.lockPcn(pcnId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!mail.enabled()) {
            throw new BusinessException(HbErrorCode.MAIL_DISABLED);
        }
        if (!"SENT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PCN_STATE);
        }
        List<Long> ids = in == null || in.contactIds() == null ? List.of() : in.contactIds();
        FieldErrors e = FieldErrors.create();
        java.util.Map<Long, PcnContact> allowed = new java.util.HashMap<>();
        mapper.findPcnContacts(pcnId).forEach(c -> allowed.put(c.getContactId(), c));
        if (ids.isEmpty()) {
            e.add("contactIds", "REQUIRED");
        } else if (ids.size() > MAIL_MAX) {
            e.add("contactIds", "TOO_MANY_ITEMS", MAIL_MAX);
        } else if (ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            e.add("contactIds", "DUPLICATE");
        } else if (!allowed.keySet().containsAll(ids)) {
            // 다른 고객·다른 회사·사용 안 함·메일 없는 담당자 — 구분하지 않는다
            e.add("contactIds", "NOT_PCN_CONTACT");
        }
        e.throwIfAny();
        PcnMailSource src = mapper.pcnMailSource(pcnId);
        Locale locale = locale(src);
        String subject = subject(src, locale);
        Long noticeFileId = in.attachNotice() != null && in.attachNotice() ? noticeCopy(src, me) : null;
        MailService.MailAttach attach = noticeFileId == null ? null
                : new MailService.MailAttach(EcoService.PROG, noticeTarget(pcnId), List.of(noticeFileId));
        for (Long id : ids) {
            PcnContact c = allowed.get(id);
            String name = clip(oneLine(c.getContactNm()).replaceAll("[\"<>,;\\\\]", "").strip(), 100);   // 플랫폼 이름 금지 글자를 미리 뺀다
            if (name.isEmpty()) {
                name = c.getEmail().strip();   // 이름이 금지 글자뿐이면 주소로(이력 이름 칸은 비울 수 없다)
            }
            String body = body(src, name, me.userNm(), locale);
            java.util.UUID outboxId = mail.publish("PCN:" + pcnId + ":" + id,   // 업무 키는 id 로(번호 글자 모양에 기대지 않음)
                    new MailService.Recipient(c.getEmail().strip(), name), subject, body, attach);
            mapper.insertPcnMail(pcnId, id, c.getEmail().strip(), name, subject, outboxId, noticeFileId, me.loginId());
        }
        return ids.size();
    }

    /** 통지서 사본 대상(33장 F2) — 사용자는 올리기·지우기 못 함(PlmFileLock), 서버가 이 트랜잭션에서만 넣는다 */
    static String noticeTarget(long pcnId) {
        return "PCN-" + pcnId;
    }

    /**
     * 그 시점 변경 통지서(29장, 회사 기본 언어·결재 전이면 초안 표시)를 통지 대상에 사본으로 — 같은 내용이면 있던 사본(F2).
     * 메일 발행과 같은 트랜잭션: 실패하면 사본도 남지 않는다
     */
    private long noticeCopy(PcnMailSource src, LoginUser me) {
        EcoNoticeService.Notice n;
        try {
            n = notices.notice(src.getEcoId());
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        String target = noticeTarget(src.getPcnId());
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(PlmFileLock.SERVER_ATTACHING, target);
        try {
            long fileId = files.attachAs(EcoService.PROG, target, n.fileName(), n.bytes().length, new java.io.ByteArrayInputStream(n.bytes()),
                    me.loginId(), new io.smarthappy.platform.file.FileService.AttachOptions(null, null, true)).file().fileId();
            // 보낸 사본은 근거 — DB 쪽에서도 굳힌다(sys_file.locked_yn, QMS 배포 문서와 같다 — QA P-1). 앱 잠금(PlmFileLock)과 이중 방어
            plm.lockFiles(target, me.loginId());
            return fileId;
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(PlmFileLock.SERVER_ATTACHING);
        }
    }

    private static Locale locale(PcnMailSource s) {
        return Locale.forLanguageTag(s.getLocaleCd() == null ? "ko" : s.getLocaleCd());
    }

    private String subject(PcnMailSource s, Locale locale) {
        return clip(oneLine(msg("plm.pcn.mail.subject", locale, s.getCompNm(), s.getPcnNo(), s.getEcoTitle())), 200);
    }

    /** 글자 메일 본문 — 회사 기본 언어, 사내 화면 주소 없음(E4). 첨부는 변경 통지서 사본만(33장), 결재 전이면 초안 안내 한 줄 */
    private String body(PcnMailSource s, String toName, String senderName, Locale locale) {
        String reason = codes.codes("PLM_CHANGE_REASON", locale).stream().filter(c -> c.code().equals(s.getReasonCd()))
                .map(CodeService.CodeItem::name).findFirst().orElse(s.getReasonCd());
        StringBuilder b = new StringBuilder();
        b.append(msg("plm.pcn.mail.greeting", locale, toName)).append("\n\n");
        b.append(msg("plm.pcn.mail.intro", locale, s.getCompNm())).append("\n\n");
        if ("DRAFT".equals(s.getEcoStatus()) || "PENDING".equals(s.getEcoStatus())) {
            b.append(msg("plm.pcn.mail.draft", locale)).append("\n\n");   // 확정본으로 오해하지 않게(33장 F5)
        }
        b.append(msg("plm.pcn.mail.pcnNo", locale, s.getPcnNo())).append('\n');
        b.append(msg("plm.pcn.mail.eco", locale, s.getEcoNo(), oneLine(s.getEcoTitle()))).append('\n');
        if (reason != null) {
            b.append(msg("plm.pcn.mail.reason", locale, oneLine(reason))).append('\n');
        }
        if (s.getEffectiveDate() != null) {
            b.append(msg("plm.pcn.mail.effective", locale, s.getEffectiveDate().toString())).append('\n');
        }
        b.append(msg("plm.pcn.mail.due", locale, s.getResponseDue().toString())).append('\n');
        if (s.getNote() != null && !s.getNote().isBlank()) {
            b.append('\n').append(msg("plm.pcn.mail.note", locale)).append('\n').append(multiLine(s.getNote())).append('\n');
        }
        b.append('\n').append(msg("plm.pcn.mail.footer", locale, oneLine(senderName), s.getCompNm()));
        return clip(b.toString(), 20000);
    }

    private String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }

    /** 제어·양방향 문자 → 공백(한 줄 칸: 제목·이름) */
    static String oneLine(String v) {
        return v == null ? "" : v.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
    }

    /** 여러 줄 칸(비고): 줄바꿈·탭만 남기고 나머지 제어·양방향 문자 제거 */
    static String multiLine(String v) {
        return v.replace("\r\n", "\n").replaceAll("[\\p{Cf}\\x00-\\x08\\x0B-\\x1F\\x7F]", "");
    }

    private static String clip(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }

    @Transactional
    public long create(long ecoId, PcnForm in) {
        LoginUser me = EcoService.me();
        inbox.claimRequestKey("PLM_PCN_CREATE");
        String status = plm.ecoStatus(ecoId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("CANCELLED".equals(status)) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long custId = null;
        if (EcoService.blank(in.custCd())) {
            e.add("custCd", "REQUIRED");
        } else {
            CustOption c = plm.findSalesCust(in.custCd().strip().toUpperCase(Locale.ROOT));
            if (c == null) {
                e.add("custCd", "NOT_SALES");
            } else {
                custId = c.getCustId();
            }
        }
        LocalDate on = OrderService.date(in.notifiedOn(), "notifiedOn", true, e);
        LocalDate due = OrderService.date(in.responseDue(), "responseDue", true, e);
        String note = OrderService.text(in.note(), "note", 1000, e);
        if (on != null && on.isAfter(clock.today())) {
            e.add("notifiedOn", "FUTURE");
        }
        if (on != null && due != null && due.isBefore(on)) {
            e.add("responseDue", "BEFORE_NOTIFIED");
        }
        e.throwIfAny();
        String no = numbering.nextOrDefault(RULE, "고객 변경 통지 번호", "PCN{yyMM}-{seq:4}", "MONTH");
        try {
            return mapper.insertPcn(no, ecoId, custId, on, due, note, me.loginId());
        } catch (DuplicateKeyException ex) {
            // uq_plm_pcn_open — 같은 고객에 응답 안 받은 통지가 이미 있다
            throw OrderService.fieldError("custCd", "PCN_OPEN");
        }
    }

    @Transactional
    public void respond(long pcnId, PcnResponseForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        PcnRow cur = mapper.lockPcn(pcnId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"SENT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PCN_STATE);
        }
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        if (in.status() == null || !RESPONSES.contains(in.status())) {
            e.add("status", "CODE");
        }
        LocalDate on = OrderService.date(in.respondedOn(), "respondedOn", true, e);
        String note = OrderService.text(in.note(), "responseNote", 1000, e);
        if (on != null && on.isAfter(clock.today())) {
            e.add("respondedOn", "FUTURE");
        } else if (on != null && on.isBefore(cur.getNotifiedOn())) {
            e.add("respondedOn", "BEFORE_NOTIFIED");
        }
        e.throwIfAny();
        if (mapper.respondPcn(pcnId, ver, in.status(), on, note, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }
}
