package io.smarthappy.module.plm;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.TargetRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 변경 통지서 엑셀 (docs/39 29장) — 고객 제출용 서식 고정 한 장: 머리(발신 회사·지시 번호·제목·사유·종류·유효일·상태·고객 승인) →
 * 변경 내용 → 대상 표 → 고객 확인란(빈 칸). 회사 기본 언어, 코드는 이름으로, 셀 글자는 수식 주입 방지(safeText — {@link PlmSheet}).
 * 사내 전용 정보(결재선·서명·표시 LOT·첨부·재고)는 넣지 않는다. 결재 전이면 제목에 "초안" 표시
 */
@Service
@RequiredArgsConstructor
public class EcoNoticeService {

    private final PlmMapper mapper;
    private final PlmDocs docs;

    /** 내려받을 파일 이름 */
    public record Notice(String fileName, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Notice notice(long ecoId) throws IOException {
        List<EcoRow> rows = mapper.findEcos(null, null, null, ecoId, null, 1);
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        EcoRow e = rows.get(0);
        List<TargetRow> targets = mapper.findTargets(ecoId);
        PlmDocs.Head comp = docs.head();
        return new Notice(e.getEcoNo() + "_notice.xlsx", write(e, targets, comp.compNm(), comp.locale()));
    }

    private byte[] write(EcoRow e, List<TargetRow> targets, String compNm, Locale locale) throws IOException {
        Map<String, String> reason = docs.names("PLM_CHANGE_REASON", locale);
        Map<String, String> kind = docs.names("PLM_CHANGE_KIND", locale);
        Map<String, String> status = docs.names("PLM_ECO_STATUS", locale);
        Map<String, String> cust = docs.names("PLM_CUST_APR", locale);
        Map<String, String> type = docs.names("PLM_TARGET_TYPE", locale);
        boolean draft = "DRAFT".equals(e.getStatus()) || "PENDING".equals(e.getStatus());
        try (PlmSheet sh = new PlmSheet("ECN")) {
            sh.title(msg("plm.notice.title", locale) + (draft ? " " + msg("plm.notice.draft", locale) : ""));
            boolean custReq = "Y".equals(e.getCustApprovalReqYn());
            sh.info(new String[][] {
                    {msg("plm.notice.from", locale), compNm},
                    {msg("plm.notice.ecoNo", locale), e.getEcoNo()},
                    {msg("plm.notice.subject", locale), e.getTitle()},
                    {msg("plm.notice.reason", locale), PlmDocs.name(reason, e.getReasonCd())},
                    {msg("plm.notice.kind", locale), PlmDocs.name(kind, e.getChangeKind())},
                    {msg("plm.notice.effective", locale), e.getEffectiveDate() == null ? "" : e.getEffectiveDate().toString()},
                    {msg("plm.notice.status", locale), PlmDocs.name(status, e.getStatus())},
                    {msg("plm.notice.customer", locale), custReq ? e.getCustNm() + " (" + e.getCustCd() + ")" : ""},
                    {msg("plm.notice.custApproval", locale), custReq ? PlmDocs.name(cust, e.getCustApprovalStatus())
                            + (e.getCustApprovalRef() == null ? "" : " · " + e.getCustApprovalRef())
                            + (e.getCustApprovedOn() == null ? "" : " · " + e.getCustApprovedOn()) : ""},   // "-" 는 수식 방지로 '- 가 되어 빈 칸
                    {msg("plm.notice.owner", locale), e.getOwnerNm()}});
            sh.blank();
            sh.heading(msg("plm.notice.description", locale));
            sh.paragraph(e.getDescription(), 5);
            sh.blank();
            sh.tableHead(msg("plm.notice.col.seq", locale), msg("plm.notice.col.type", locale), msg("plm.notice.col.itemCd", locale),
                    msg("plm.notice.col.itemNm", locale), msg("plm.notice.col.ref", locale), msg("plm.notice.col.change", locale));
            for (TargetRow t : targets) {
                sh.tableRow(5, String.valueOf(t.getSeq()), PlmDocs.name(type, t.getTargetType()), t.getItemCd(), t.getItemNm(), ref(t),
                        t.getChangeDesc());
            }
            sh.blank();
            // 고객 확인란 — 고객이 판정·일자·서명을 적어 돌려준다(우리 쪽은 비워 둔다)
            sh.replyBox(msg("plm.notice.reply", locale), 5, msg("plm.notice.reply.decision", locale), msg("plm.notice.reply.date", locale),
                    msg("plm.notice.reply.by", locale), msg("plm.notice.reply.note", locale));
            sh.widths(14, 12, 16, 26, 22, 40);
            return sh.bytes();
        }
    }

    /** 대상의 참조 — 리비전(BOM·라우팅)·설비·공정·문서 번호 */
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

    private String msg(String key, Locale locale) {
        return docs.msg(key, locale);
    }
}
