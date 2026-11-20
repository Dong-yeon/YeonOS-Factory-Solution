package io.smarthappy.module.plm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.smarthappy.module.plm.PlmDtos.PcnContact;
import io.smarthappy.module.plm.PlmDtos.PcnMailTargets;
import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapMailForm;
import io.smarthappy.module.plm.PlmDtos.PpapMailSource;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * PPAP 제출 메일 (docs/39 37장 P1~P9) — 제출(SUBMITTED)한 패키지를 그 고객의 담당자에게 알리고 그 시점 제출 요약서(31장)를 첨부한다.
 * 받는 사람은 패키지 고객의 담당자 표(mst_cust_contact)에서만 — 주소 직접 입력 없음. 송신은 플랫폼 MailService → 아웃박스 MAIL,
 * 같은 트랜잭션에서 요약서 사본(plm-eco × PPAPMAIL-{id}, DB 잠금) + 발송 이력. 하나라도 실패하면 이력·아웃박스·사본이 함께 롤백된다.
 */
@Service
@RequiredArgsConstructor
public class PpapMailService {

    /** 한 번에 보낼 수 있는 담당자 수(23장과 같다) */
    static final int MAIL_MAX = 10;

    private final PlmPpapMapper mapper;
    private final PlmMapper plm;
    private final InboxService inbox;
    private final MailService mail;
    private final FileService files;
    private final PpapSummaryService summaries;
    private final PlmDocs docs;

    /** 요약서 사본 대상 — 사용자는 올리기·지우기 못 함(PlmFileLock), 서버가 이 트랜잭션에서만 넣는다(P5) */
    static String summaryTarget(long ppapId) {
        return "PPAPMAIL-" + ppapId;
    }

    /** 받는 사람 후보 + 메일 켜짐 + 미리보기 — 다른 회사·없는 패키지 404. 담당자 메일은 개인정보라 SAVE 권한(P9) */
    @Transactional(readOnly = true)
    public PcnMailTargets mailTargets(long ppapId) {
        PpapMailSource src = mapper.ppapMailSource(ppapId);
        if (src == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!mail.enabled()) {
            return new PcnMailTargets(false, List.of(), null, null);
        }
        Locale locale = locale(src);
        return new PcnMailTargets(true, mapper.findPpapContacts(ppapId), subject(src, locale),
                body(src, msg("plm.pcn.mail.previewName", locale), EcoService.me().userNm(), locale));
    }

    /**
     * 고른 담당자에게 한 통씩 — 제출(SUBMITTED)·취소 아닌 지시의 패키지만(P3), 멱등 키 필수(같은 요청 두 번 = 한 번).
     * 받는 사람은 패키지 고객의 사용 중·메일 있는 담당자만(P1, 다른 고객·회사·사용 안 함·메일 없음 → NOT_PPAP_CONTACT) → 보낸 메일 수
     */
    @Transactional
    public int mail(long ppapId, PpapMailForm in) {
        LoginUser me = EcoService.me();
        inbox.claimRequestKey("PLM_PPAP_MAIL");
        PlmDtos.PpapRow cur = mapper.lockPpap(ppapId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!mail.enabled()) {
            throw new BusinessException(HbErrorCode.MAIL_DISABLED);
        }
        PpapMailSource src = mapper.ppapMailSource(ppapId);
        if (!"SUBMITTED".equals(cur.getStatus()) || "CANCELLED".equals(src.getEcoStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_STATE);
        }
        List<Long> ids = in == null || in.contactIds() == null ? List.of() : in.contactIds();
        FieldErrors e = FieldErrors.create();
        Map<Long, PcnContact> allowed = new HashMap<>();
        mapper.findPpapContacts(ppapId).forEach(c -> allowed.put(c.getContactId(), c));
        if (ids.isEmpty()) {
            e.add("contactIds", "REQUIRED");
        } else if (ids.size() > MAIL_MAX) {
            e.add("contactIds", "TOO_MANY_ITEMS", MAIL_MAX);
        } else if (ids.stream().anyMatch(Objects::isNull) || ids.stream().distinct().count() != ids.size()) {
            e.add("contactIds", "DUPLICATE");
        } else if (!allowed.keySet().containsAll(ids)) {
            // 다른 고객·다른 회사·사용 안 함·메일 없는 담당자 — 구분하지 않는다
            e.add("contactIds", "NOT_PPAP_CONTACT");
        }
        e.throwIfAny();
        Locale locale = locale(src);
        String subject = subject(src, locale);
        long summaryId = summaryCopy(ppapId, me);
        MailService.MailAttach attach = new MailService.MailAttach(EcoService.PROG, summaryTarget(ppapId), List.of(summaryId));
        for (Long id : ids) {
            PcnContact c = allowed.get(id);
            String name = PcnService.oneLine(c.getContactNm()).replaceAll("[\"<>,;\\\\]", "").strip();   // 플랫폼 이름 금지 글자를 미리 뺀다
            name = name.length() > 100 ? name.substring(0, 100) : name;
            if (name.isEmpty()) {
                name = c.getEmail().strip();
            }
            UUID outboxId = mail.publish("PPAP:" + ppapId + ":" + id, new MailService.Recipient(c.getEmail().strip(), name), subject,
                    body(src, name, me.userNm(), locale), attach);
            mapper.insertPpapMail(ppapId, id, c.getEmail().strip(), name, subject, outboxId, summaryId, me.loginId());
        }
        return ids.size();
    }

    /** 그 시점 요약서(31장)를 사본으로 — 같은 내용이면 있던 사본(바이트 고정), 넣은 뒤 DB 잠금(36.1). 실패하면 트랜잭션과 함께 사라진다 */
    private long summaryCopy(long ppapId, LoginUser me) {
        PpapSummaryService.Summary s;
        try {
            s = summaries.summary(ppapId);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String target = summaryTarget(ppapId);
        TransactionSynchronizationManager.bindResource(PlmFileLock.SERVER_ATTACHING, target);
        try {
            long fileId = files.attachAs(EcoService.PROG, target, s.fileName(), s.bytes().length, new ByteArrayInputStream(s.bytes()),
                    me.loginId(), new FileService.AttachOptions(null, null, true)).file().fileId();
            plm.lockFiles(target, me.loginId());
            return fileId;
        } finally {
            TransactionSynchronizationManager.unbindResource(PlmFileLock.SERVER_ATTACHING);
        }
    }

    private static Locale locale(PpapMailSource s) {
        return Locale.forLanguageTag(s.getLocaleCd() == null ? "ko" : s.getLocaleCd());
    }

    private String subject(PpapMailSource s, Locale locale) {
        String v = PcnService.oneLine(msg("plm.ppap.mail.subject", locale, s.getCompNm(), s.getEcoNo(), s.getEcoTitle()));
        return v.length() > 200 ? v.substring(0, 200) : v;
    }

    /** 글자 메일 본문(P8) — 회사 기본 언어, 제출 요소 이름 목록, 요소 메모·패키지 비고·사내 화면 주소 없음 */
    private String body(PpapMailSource s, String toName, String senderName, Locale locale) {
        Map<String, String> elemNm = docs.names("PLM_PPAP_ELEM", locale);
        List<String> submitted = mapper.findElems(s.getPpapId()).stream().filter(el -> "S".equals(el.getReqCd()))
                .map(PpapElemRow::getElemNo).map(n -> n + ". " + PlmDocs.name(elemNm, String.valueOf(n))).toList();
        StringBuilder b = new StringBuilder();
        b.append(msg("plm.pcn.mail.greeting", locale, toName)).append("\n\n");
        b.append(msg("plm.ppap.mail.intro", locale, s.getCompNm())).append("\n\n");
        b.append(msg("plm.pcn.mail.eco", locale, s.getEcoNo(), PcnService.oneLine(s.getEcoTitle()))).append('\n');
        b.append(msg("plm.ppap.mail.level", locale, String.valueOf(s.getSubmitLevel()))).append('\n');
        if (s.getSubmittedOn() != null) {
            b.append(msg("plm.ppap.mail.submittedOn", locale, s.getSubmittedOn().toString())).append('\n');
        }
        if (!submitted.isEmpty()) {
            b.append('\n').append(msg("plm.ppap.mail.elements", locale)).append('\n');
            submitted.forEach(x -> b.append(" - ").append(x).append('\n'));
        }
        b.append('\n').append(msg("plm.ppap.mail.footer", locale, PcnService.oneLine(senderName), s.getCompNm()));
        String v = b.toString();
        return v.length() > 20000 ? v.substring(0, 20000) : v;
    }

    private String msg(String key, Locale locale, Object... args) {
        return docs.msg(key, locale, args);
    }
}
