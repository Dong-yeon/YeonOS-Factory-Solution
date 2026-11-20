package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.PlmDtos.CustOption;
import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.PpapActForm;
import io.smarthappy.module.plm.PlmDtos.PpapCreateForm;
import io.smarthappy.module.plm.PlmDtos.PpapDecideForm;
import io.smarthappy.module.plm.PlmDtos.PpapDetail;
import io.smarthappy.module.plm.PlmDtos.PpapElemItem;
import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapForm;
import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.module.plm.PlmDtos.PpapSubmitForm;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * PPAP 패키지 (PLM 4단계, docs/39 14.4) — ECO 하나에 하나, 18요소(요구 S/R/N·상태·첨부).
 *
 * - 레벨별 요구 표는 넣지 않는다(AIAG 저작권·고객별 차이). 기본: 레벨 1 = PSW 만 S, 레벨 5 = 전부 R, 그 외 = 전부 S
 * - 제출 = S 요소 모두 DONE·WAIVED + PSW(18) DONE. 판정 승인 = ECO 고객 승인을 같은 트랜잭션에서 채운다(칸은 ECO 에)
 * - 내용(레벨·요소)은 작성 중에만 — 거절·임시 승인은 다시 작성 중으로(재제출)
 * - 증빙 파일은 plm-eco × PPAP-{id}(2단계) — 작성 중에만 올리고 지운다({@link PlmFileLock}), 승인 때 잠금 표시
 */
@Service
@RequiredArgsConstructor
public class PpapService {

    static final int PSW = 18;
    static final Set<String> REQS = Set.of("S", "R", "N");
    static final Set<String> ELEM_STATUSES = Set.of("OPEN", "DONE", "WAIVED");
    static final Set<String> DECISIONS = Set.of("APPROVED", "INTERIM", "REJECTED");

    private final PlmPpapMapper mapper;
    private final PlmMapper plm;
    private final BizClock clock;
    private final io.smarthappy.platform.esign.ESignService esign;
    private final io.smarthappy.platform.policy.PolicyService policies;
    private final io.smarthappy.platform.notify.MailService mail;
    private final PlmMailStatus mailStatus;
    private final PpapApqpService apqp;

    /** 기록 무결성 점검(docs/52 Y1): 서명 대상 PPAP 의 지금 내용 해시(판정 서명과 같은 정규 글자) — 없으면 빈 Optional */
    @Transactional(readOnly = true)
    public java.util.Optional<Set<String>> signHashes(long ppapId) {
        PpapRow p = mapper.findPpap(ppapId);
        if (p == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Set.of(PlmSignContent.ppapHash(p, mapper.findElems(ppapId))));
    }

    @Transactional(readOnly = true)
    public PpapDetail ofEco(long ecoId) {
        eco(ecoId);
        PpapRow p = mapper.findPpapOfEco(ecoId);
        List<String> apqpKinds = apqp.kinds();
        if (p == null) {
            return new PpapDetail(null, List.of(), List.of(), List.of(), mail.enabled(), apqpKinds);
        }
        List<io.smarthappy.module.plm.PlmDtos.PpapElemRow> elems = mapper.findElems(p.getPpapId());
        // 요소 6·7 APQP 근거의 지금 상태(38장 Q5) — 해시는 상태 없이 근거(label)만
        apqp.fillStatus(elems, apqpKinds);
        // 제출 메일 이력 + 발송 결과(37장 P6·P7)
        List<io.smarthappy.module.plm.PlmDtos.PpapMailRow> mails = mapper.findPpapMails(p.getPpapId());
        mailStatus.fill(mails);
        return new PpapDetail(p, elems,
                EcoService.signs(esign, PlmSignContent.PPAP, p.getPpapId(), PlmSignContent.ppapHash(p, elems)), mails, mail.enabled(),
                apqpKinds);
    }

    /** 패키지 만들기 — 취소되지 않은 ECO, 고객 비우면 ECO 고객 */
    @Transactional
    public long create(long ecoId, PpapCreateForm in) {
        LoginUser me = EcoService.me();
        EcoRow eco = plm.lockEco(ecoId);
        if (eco == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("CANCELLED".equals(eco.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        if (mapper.findPpapOfEco(ecoId) != null) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_STATE);
        }
        FieldErrors e = FieldErrors.create();
        Long custId = eco.getCustId();
        if (in != null && !EcoService.blank(in.custCd())) {
            CustOption c = plm.findSalesCust(in.custCd().strip().toUpperCase(Locale.ROOT));
            if (c == null) {
                e.add("custCd", "NOT_SALES");
            } else {
                custId = c.getCustId();
            }
        } else if (custId == null) {
            e.add("custCd", "REQUIRED");
        }
        int level = level(in == null ? null : in.submitLevel(), e);
        String remark = OrderService.text(in == null ? null : in.remark(), "remark", 1000, e);
        e.throwIfAny();
        long id = mapper.insertPpap(ecoId, custId, level, remark, me.loginId());
        for (int no = 1; no <= PSW; no++) {
            mapper.insertElem(id, no, defaultReq(level, no), me.loginId());
        }
        return id;
    }

    /** 기본 요구 — 고객 요구(CSR)에 맞춰 화면에서 바꾼다 */
    static String defaultReq(int level, int elemNo) {
        if (level == 1) {
            return elemNo == PSW ? "S" : "R";
        }
        return level == 5 ? "R" : "S";
    }

    /** 작성 중 저장 — 레벨·비고 + 보낸 요소만(번호로 찾음). 파일은 그 ECO 첨부만(트리거) */
    @Transactional
    public void save(long ppapId, PpapForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        PpapRow cur = lockDraft(ppapId);
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        int level = in.submitLevel() == null ? cur.getSubmitLevel() : level(in.submitLevel(), e);
        String remark = OrderService.text(in.remark(), "remark", 1000, e);
        List<PpapElemItem> items = in.elems() == null ? List.of() : in.elems();
        Map<Integer, PpapElemRow> current = mapper.findElems(ppapId).stream()
                .collect(Collectors.toMap(PpapElemRow::getElemNo, Function.identity()));
        Set<Integer> seen = new HashSet<>();
        for (PpapElemItem it : items) {
            if (it == null || it.elemNo() == null || !current.containsKey(it.elemNo()) || !seen.add(it.elemNo())) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            FieldErrors.RowScope row = e.row(elemKey(it.elemNo()));
            if (it.reqCd() == null || !REQS.contains(it.reqCd())) {
                row.add("reqCd", "CODE");
            }
            if (it.status() == null || !ELEM_STATUSES.contains(it.status())) {
                row.add("status", "CODE");
            }
            if (it.note() != null && it.note().strip().length() > 1000) {
                row.add("note", "TOO_LONG", 1000);
            }
        }
        e.throwIfAny();
        if (mapper.updatePpap(ppapId, ver, level, remark, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        for (PpapElemItem it : items) {
            String note = it.note() == null || it.note().isBlank() ? null : it.note().strip();
            try {
                mapper.updateElem(ppapId, it.elemNo(), it.reqCd(), it.status(), it.fileId(), note, me.loginId());
            } catch (DataIntegrityViolationException ex) {
                if ("fk_plm_ppap_elem_comp".equals(PgErrors.constraint(ex).orElse(""))) {
                    // 다른 ECO·다른 회사·사용 중지 파일
                    FieldErrors f = FieldErrors.create();
                    f.row(elemKey(it.elemNo())).add("fileId", "NOT_FOUND");
                    throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(f.list());
                }
                throw ex;
            }
        }
    }

    /** 제출 — S 요소 모두 DONE·WAIVED, PSW(18) DONE. 제출일 ≤ 오늘 */
    @Transactional
    public void submit(long ppapId, PpapSubmitForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        PpapRow cur = lockDraft(ppapId);
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate on = OrderService.date(in.submittedOn(), "submittedOn", false, e);
        e.throwIfAny();
        LocalDate today = clock.today();
        if (on == null) {
            on = today;
        } else if (on.isAfter(today)) {
            throw OrderService.fieldError("submittedOn", "FUTURE");
        }
        FieldErrors missing = FieldErrors.create();
        for (PpapElemRow el : mapper.findElems(ppapId)) {
            boolean psw = el.getElemNo() == PSW;
            if (psw && !"DONE".equals(el.getStatus())) {
                missing.row(elemKey(el.getElemNo())).add("status", "PSW_REQUIRED");
            } else if (!psw && "S".equals(el.getReqCd()) && "OPEN".equals(el.getStatus())) {
                missing.row(elemKey(el.getElemNo())).add("status", "ELEM_OPEN");
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_INCOMPLETE).withFieldErrors(missing.list());
        }
        if (mapper.submitPpap(ppapId, ver, on, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 고객 판정 — 제출된 패키지만. 승인이면 ECO 고객 승인(고객 승인 필요·같은 고객·적용 전)을 같이 기록 */
    @Transactional
    public void decide(long ppapId, PpapDecideForm in, jakarta.servlet.http.HttpSession session) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        PpapRow cur = lock(ppapId);
        if (!"SUBMITTED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_STATE);
        }
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        if (in.decision() == null || !DECISIONS.contains(in.decision())) {
            e.add("decision", "CODE");
        }
        LocalDate on = OrderService.date(in.decidedOn(), "decidedOn", true, e);
        String ref = OrderService.text(in.ref(), "decisionRef", 100, e);
        String note = OrderService.text(in.note(), "decisionNote", 1000, e);
        if ("APPROVED".equals(in.decision()) && ref == null) {
            e.add("decisionRef", "REQUIRED");
        }
        if (on != null && on.isAfter(clock.today())) {
            e.add("decidedOn", "FUTURE");
        } else if (on != null && on.isBefore(cur.getSubmittedOn())) {
            e.add("decidedOn", "BEFORE_SUBMITTED");
        }
        e.throwIfAny();
        String token = in.esignToken();
        boolean sign = token != null && !token.isBlank();
        if (!sign && EcoService.esignRequired(policies)) {
            throw new BusinessException(HbErrorCode.PLM_ESIGN_REQUIRED);
        }
        if (mapper.decidePpap(ppapId, ver, in.decision(), on, ref, note, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if ("APPROVED".equals(in.decision())) {
            // 칸은 ECO 에 — 고객 승인 게이트(docs/02 2.3)가 그대로 이 값을 본다
            plm.lockEco(cur.getEcoId());
            mapper.syncEcoCustApproval(cur.getEcoId(), cur.getCustId(), ref, on, me.loginId());
            // D7(2단계): 승인된 패키지 증빙은 근거로 굳힌다(한 방향)
            plm.lockFiles(evidenceId(ppapId), me.loginId());
        }
        // 전자서명(19장): 판정을 기록한 사람의 서명(의미 AUTHOR) — 판정까지 들어간 내용으로 해시
        if (sign) {
            PpapRow after = mapper.findPpapOfEco(cur.getEcoId());
            esign.consume(session, token, "AUTHOR", PlmSignContent.PPAP, ppapId,
                    PlmSignContent.ppapHash(after, mapper.findElems(ppapId)));
        }
    }

    /** 다시 작성 중으로 — 임시 승인·거절 뒤 재제출 */
    @Transactional
    public void reopen(long ppapId, PpapActForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        PpapRow cur = lock(ppapId);
        if (!"INTERIM".equals(cur.getStatus()) && !"REJECTED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_STATE);
        }
        if (mapper.reopenPpap(ppapId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static int level(Integer raw, FieldErrors e) {
        if (raw == null) {
            return 3;
        }
        if (raw < 1 || raw > 5) {
            e.add("submitLevel", "RANGE", 1, 5);
            return 3;
        }
        return raw;
    }

    /** PPAP 증빙 첨부 대상 id — plm-eco × PPAP-{id} (ECO 첨부는 상신 뒤 고정이라 따로, docs/39 15.2) */
    public static String evidenceId(long ppapId) {
        return "PPAP-" + ppapId;
    }

    static String elemKey(int elemNo) {
        return "e" + elemNo;
    }

    private void eco(long ecoId) {
        if (plm.ecoStatus(ecoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private PpapRow lock(long ppapId) {
        PpapRow cur = mapper.lockPpap(ppapId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return cur;
    }

    private PpapRow lockDraft(long ppapId) {
        PpapRow cur = lock(ppapId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_STATE);
        }
        return cur;
    }
}
