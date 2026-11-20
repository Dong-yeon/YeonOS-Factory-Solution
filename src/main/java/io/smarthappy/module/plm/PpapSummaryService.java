package io.smarthappy.module.plm;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * PPAP 제출 요약서 엑셀 (docs/39 31장) — 고객에게 패키지와 같이 내는 "무엇을 냈나" 한 장: 머리(발신 회사·지시·고객·레벨·상태·제출일·판정) →
 * 요소 18행(이름·요구·상태·첨부 파일 이름) → 고객 판정란(빈 칸). 이 저장소 자체 서식 — AIAG PSW 양식을 흉내 내지 않는다(S1).
 * 요소 메모·패키지 비고, 파일 경로·id·올린 사람은 넣지 않는다(사내 정보, S3). 제출 전이면 초안, 취소된 지시면 그 표시
 */
@Service
@RequiredArgsConstructor
public class PpapSummaryService {

    private final PlmPpapMapper ppaps;
    private final PlmMapper mapper;
    private final PlmDocs docs;

    /** 내려받을 파일 이름 */
    public record Summary(String fileName, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public Summary summary(long ppapId) throws IOException {
        PpapRow p = ppaps.findPpap(ppapId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<EcoRow> ecos = mapper.findEcos(null, null, null, p.getEcoId(), null, 1);
        if (ecos.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        EcoRow e = ecos.get(0);
        PlmDocs.Head comp = docs.head();
        return new Summary(e.getEcoNo() + "_ppap.xlsx", write(p, ppaps.findElems(ppapId), e, comp.compNm(), comp.locale()));
    }

    private byte[] write(PpapRow p, List<PpapElemRow> elems, EcoRow e, String compNm, Locale locale) throws IOException {
        Map<String, String> status = docs.names("PLM_PPAP_STATUS", locale);
        Map<String, String> elemNm = docs.names("PLM_PPAP_ELEM", locale);
        Map<String, String> req = docs.names("PLM_PPAP_REQ", locale);
        Map<String, String> elemSt = docs.names("PLM_PPAP_ELEM_STATUS", locale);
        String title = msg("plm.ppapSummary.title", locale)
                + ("DRAFT".equals(p.getStatus()) ? " " + msg("plm.ppapSummary.draft", locale) : "")
                + ("CANCELLED".equals(e.getStatus()) ? " " + msg("plm.ppapSummary.cancelled", locale) : "");
        try (PlmSheet sh = new PlmSheet("PPAP")) {
            sh.title(title);
            sh.info(new String[][] {
                    {msg("plm.notice.from", locale), compNm},
                    {msg("plm.notice.ecoNo", locale), e.getEcoNo()},
                    {msg("plm.notice.subject", locale), e.getTitle()},
                    {msg("plm.notice.customer", locale), p.getCustNm() + " (" + p.getCustCd() + ")"},
                    {msg("plm.ppapSummary.level", locale), String.valueOf(p.getSubmitLevel())},
                    {msg("plm.ppapSummary.status", locale), PlmDocs.name(status, p.getStatus())},
                    {msg("plm.ppapSummary.submittedOn", locale), day(p.getSubmittedOn())},
                    {msg("plm.ppapSummary.decidedOn", locale), day(p.getDecidedOn())},
                    {msg("plm.ppapSummary.decisionRef", locale), p.getDecisionRef()}});
            sh.blank();
            sh.tableHead(msg("plm.ppapSummary.col.no", locale), msg("plm.ppapSummary.col.elem", locale), msg("plm.ppapSummary.col.req", locale),
                    msg("plm.ppapSummary.col.status", locale), msg("plm.ppapSummary.col.file", locale));
            for (PpapElemRow el : elems) {
                String no = String.valueOf(el.getElemNo());
                // 첨부는 파일 이름만 — 경로·id·올린 사람은 사내 정보. 파일 없으면 APQP 근거 label(38장 Q6)
                sh.tableRow(-1, no, PlmDocs.name(elemNm, no), PlmDocs.name(req, el.getReqCd()), PlmDocs.name(elemSt, el.getStatus()),
                        el.getFileName() != null ? el.getFileName() : el.getApqpLabel());
            }
            sh.blank();
            // 고객 판정란 — 고객이 판정·일자·번호·서명을 적어 돌려준다(우리 쪽은 비워 둔다)
            sh.replyBox(msg("plm.ppapSummary.reply", locale), 4, msg("plm.ppapSummary.reply.decision", locale),
                    msg("plm.notice.reply.date", locale), msg("plm.ppapSummary.reply.ref", locale), msg("plm.notice.reply.by", locale),
                    msg("plm.notice.reply.note", locale));
            sh.widths(14, 44, 14, 12, 40);
            return sh.bytes();
        }
    }

    private static String day(LocalDate d) {
        return d == null ? "" : d.toString();
    }

    private String msg(String key, Locale locale) {
        return docs.msg(key, locale);
    }
}
