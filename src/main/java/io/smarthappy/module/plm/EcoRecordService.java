package io.smarthappy.module.plm;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.PlmDtos.AprLineRow;
import io.smarthappy.module.plm.PlmDtos.EcoDetail;
import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.PcnRow;
import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.module.plm.PlmDtos.SignView;
import io.smarthappy.module.plm.PlmDtos.TargetRow;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 사내 지시 기록서 엑셀 (docs/39 34장) — 감사·품질 보관용: 머리·변경 내용·대상 → 결재선 → 전자서명 → 고객 승인·PPAP·PCN → 표시 LOT 수.
 * 화면 상세와 같은 조회(EcoService.detail)로 만들어 보이는 것과 기록이 같다. 사내 문서라 보는 사람 언어(29·31장 고객 문서와 다름).
 * 내려받기는 접속 로그 ACTION·EXCEL 에 남는다(ProgAuth) — detail 로 어떤 문서·지시인지
 */
@Service
@RequiredArgsConstructor
public class EcoRecordService {

    private final EcoService ecos;
    private final PlmPpapMapper ppaps;
    private final PlmDocs docs;
    private final BizClock clock;

    /** 내려받을 파일 */
    public record Record(String fileName, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Record record(long ecoId) throws IOException {
        EcoDetail d = ecos.detail(ecoId);
        PpapRow ppap = ppaps.findPpapOfEco(ecoId);
        List<PcnRow> pcns = ppaps.findPcns(ecoId, clock.today());
        return new Record(d.eco().getEcoNo() + "_record.xlsx", write(d, ppap, pcns, docs.head().compNm(), LocaleContextHolder.getLocale()));
    }

    private byte[] write(EcoDetail d, PpapRow ppap, List<PcnRow> pcns, String compNm, Locale l) throws IOException {
        EcoRow e = d.eco();
        Map<String, String> reason = docs.names("PLM_CHANGE_REASON", l);
        Map<String, String> kind = docs.names("PLM_CHANGE_KIND", l);
        Map<String, String> status = docs.names("PLM_ECO_STATUS", l);
        Map<String, String> type = docs.names("PLM_TARGET_TYPE", l);
        Map<String, String> custApr = docs.names("PLM_CUST_APR", l);
        Map<String, String> ppapSt = docs.names("PLM_PPAP_STATUS", l);
        Map<String, String> pcnSt = docs.names("PLM_PCN_STATUS", l);
        Map<String, String> meaning = docs.names("ESIGN_MEANING", l);
        try (PlmSheet sh = new PlmSheet("ECO")) {
            sh.title(msg("plm.record.title", l) + " — " + compNm);
            sh.info(new String[][] {
                    {msg("plm.notice.ecoNo", l), e.getEcoNo()},
                    {msg("plm.notice.subject", l), e.getTitle()},
                    {msg("plm.notice.reason", l), PlmDocs.name(reason, e.getReasonCd())},
                    {msg("plm.notice.kind", l), PlmDocs.name(kind, e.getChangeKind())},
                    {msg("plm.notice.status", l), PlmDocs.name(status, e.getStatus())},
                    {msg("plm.notice.effective", l), day(e.getEffectiveDate())},
                    {msg("plm.notice.owner", l), e.getOwnerNm()},
                    {msg("plm.record.createdAt", l), time(e.getCreatedAt())},
                    {msg("plm.record.submittedAt", l), time(e.getSubmittedAt())},
                    {msg("plm.record.approvedAt", l), time(e.getApprovedAt())},
                    {msg("plm.record.releasedAt", l), time(e.getReleasedAt()) + (e.getReleasedBy() == null ? "" : " · " + e.getReleasedBy())},
                    {msg("plm.record.cancelReason", l), e.getCancelReason()},
                    {msg("plm.record.markCnt", l), String.valueOf(e.getMarkCnt())}});
            sh.blank();
            sh.heading(msg("plm.notice.description", l));
            sh.paragraph(e.getDescription(), 6);
            sh.blank();
            sh.heading(msg("plm.record.targets", l));
            sh.tableHead(msg("plm.notice.col.seq", l), msg("plm.notice.col.type", l), msg("plm.notice.col.itemCd", l),
                    msg("plm.notice.col.itemNm", l), msg("plm.notice.col.ref", l), msg("plm.record.col.refStatus", l), msg("plm.notice.col.change", l));
            for (TargetRow t : d.targets()) {
                sh.tableRow(6, String.valueOf(t.getSeq()), PlmDocs.name(type, t.getTargetType()), t.getItemCd(), t.getItemNm(), ref(t),
                        (t.getRefStatus() == null ? "" : t.getRefStatus()) + (t.isChanged() ? " " + msg("plm.record.changedAfter", l) : ""),
                        t.getChangeDesc());
            }
            sh.blank();
            sh.heading(msg("plm.record.approval", l));
            sh.tableHead(msg("plm.notice.col.seq", l), msg("plm.record.col.approver", l), msg("plm.record.col.lineType", l),
                    msg("plm.record.col.decision", l), msg("plm.record.col.actedAt", l), msg("plm.record.col.comment", l));
            for (AprLineRow a : d.approval().lines()) {
                sh.tableRow(5, String.valueOf(a.getSeq()), a.getApproverNm(), msg("js.sysApproval.lt." + a.getLineType(), l),
                        msg("js.sysApproval.st." + a.getStatus(), l), time(a.getActedAt()), a.getComment());
            }
            sh.blank();
            sh.heading(msg("plm.record.signs", l));
            sh.tableHead(msg("plm.record.col.meaning", l), msg("plm.record.col.signer", l), msg("plm.record.col.method", l),
                    msg("plm.record.col.signedAt", l), msg("plm.record.col.stale", l));
            for (SignView s : d.signs()) {
                sh.tableRow(-1, PlmDocs.name(meaning, s.meaningCd()), s.signerNm(), s.authMethod(), time(s.signedAt()),
                        s.changed() ? msg("plm.record.staleYes", l) : "");
            }
            sh.blank();
            sh.heading(msg("plm.record.customer", l));
            boolean custReq = "Y".equals(e.getCustApprovalReqYn());
            sh.info(new String[][] {
                    {msg("plm.notice.customer", l), custReq ? e.getCustNm() + " (" + e.getCustCd() + ")" : ""},
                    {msg("plm.notice.custApproval", l), custReq ? PlmDocs.name(custApr, e.getCustApprovalStatus())
                            + (e.getCustApprovalRef() == null ? "" : " · " + e.getCustApprovalRef())
                            + (e.getCustApprovedOn() == null ? "" : " · " + e.getCustApprovedOn()) : ""},
                    {msg("plm.record.ppap", l), ppap == null ? "" : "L" + ppap.getSubmitLevel() + " · " + PlmDocs.name(ppapSt, ppap.getStatus())
                            + (ppap.getSubmittedOn() == null ? "" : " · " + ppap.getSubmittedOn())
                            + (ppap.getDecidedOn() == null ? "" : " → " + ppap.getDecidedOn())
                            + (ppap.getDecisionRef() == null ? "" : " · " + ppap.getDecisionRef())}});
            sh.tableHead(msg("plm.record.col.pcnNo", l), msg("plm.notice.customer", l), msg("plm.record.col.notifiedOn", l),
                    msg("plm.record.col.due", l), msg("plm.notice.status", l), msg("plm.record.col.respondedOn", l));
            for (PcnRow n : pcns) {
                sh.tableRow(-1, n.getPcnNo(), n.getCustCd(), day(n.getNotifiedOn()), day(n.getResponseDue()), PlmDocs.name(pcnSt, n.getStatus()),
                        day(n.getRespondedOn()));
            }
            sh.widths(12, 14, 16, 26, 22, 18, 40);
            return sh.bytes();
        }
    }

    /** 대상의 참조 — 통지서(29장)와 같은 글자 */
    private static String ref(TargetRow t) {
        return switch (t.getTargetType()) {
            case "BOM", "ROUTING" -> t.getRev() == null ? "" : "Rev " + t.getRev();
            case "EQUIP" -> join(t.getEquipCd(), t.getEquipNm());
            case "PROC" -> join(t.getProcCd(), t.getProcNm());
            case "DOC" -> join(t.getDocNo(), t.getDocNm()) + (t.getRev() == null ? "" : " Rev " + t.getRev());
            default -> "";
        };
    }

    private static String join(String cd, String nm) {
        return cd == null ? "" : nm == null ? cd : cd + " " + nm;
    }

    private static String day(java.time.LocalDate d) {
        return d == null ? "" : d.toString();
    }

    /** 일시 — 회사 시간대로 분까지 */
    private String time(OffsetDateTime t) {
        return t == null ? "" : t.atZoneSameInstant(clock.zone()).toLocalDateTime().withSecond(0).withNano(0).toString().replace('T', ' ');
    }

    private String msg(String key, Locale l) {
        return docs.msg(key, l);
    }
}
