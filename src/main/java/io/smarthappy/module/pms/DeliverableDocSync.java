package io.smarthappy.module.pms;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.PpapView;
import io.smarthappy.module.pms.GateDtos.DelivRow;
import io.smarthappy.module.apqp.link.ApqpLookup;
import io.smarthappy.module.qms.link.DocLookup;
import io.smarthappy.module.qms.link.DocLookup.DocRevView;
import lombok.RequiredArgsConstructor;

/**
 * QMS 문서 연결 산출물의 "배포 = 승인", PPAP 연결 산출물의 "고객 승인 = 승인", PFMEA·관리계획서 연결 산출물의 "한 번이라도 확정 = 승인"
 * 반영 (docs/32 PM6·PM7, D6·D15·D18·D19, docs/41 A3-1·L2). APQP 는 계약 ApqpLookup 으로만.
 * 리비전은 QMS 계약 DocLookup.rev, PPAP 는 PLM 계약 EcoLookup.ppap 으로만 다시 읽는다(둘 다 Optional — 예외 없음).
 * 배포(RELEASED)·고객 승인(APPROVED)된 것에 연결된 제출 중 칸을 APPROVED 로. 남의 표에는 쓰지 않는다.
 * 쓰기 트랜잭션 안에서만 부른다: [QMS 상태 반영] 버튼, 게이트 상신·결정(바로·결재 끝) 직전 — 조회는 상태를 보여 주기만 한다.
 * 계약 빈은 ObjectProvider 로 늦게 받는다 — 결재 서비스 → 게이트 결재 문서 → GateDecider → 이 빈 → 다른 모듈 → 결재 서비스 순환을 만들지 않게
 */
@Component
@RequiredArgsConstructor
public class DeliverableDocSync {

    private final GateMapper gm;
    private final ObjectProvider<DocLookup> docs;
    private final ObjectProvider<EcoLookup> ecos;
    private final ObjectProvider<ApqpLookup> apqps;

    /** 반환 = 승인으로 바뀐 칸 수 */
    int sync(long projId, String by) {
        int n = 0;
        PpapView ppap = null;
        boolean ppapRead = false;
        for (DelivRow d : gm.findDocPending(projId)) {
            if (DeliverableService.PPAP_REF.equals(d.getRefType())) {
                if (!ppapRead) {
                    ppap = ppapOf(gm.findProjectEcoId(projId));
                    ppapRead = true;
                }
                // 잠정 승인(INTERIM)은 승인이 아니다(D19)
                if (ppap != null && ppap.ppapId() == d.getRefId() && "APPROVED".equals(ppap.status())
                        && gm.approveFromDoc(d.getDelivId(), approvedNote(ppap), by) == 1) {
                    gm.insertHist(projId, d.getDelivId(), "APPROVE", null, approvedNote(ppap), by);
                    n++;
                }
                continue;
            }
            // PFMEA·관리계획서 — 한 번이라도 확정 = 승인(docs/41 L2)
            if (DeliverableService.FMEA_REF.equals(d.getRefType()) || DeliverableService.CP_REF.equals(d.getRefType())) {
                String kind = DeliverableService.FMEA_REF.equals(d.getRefType()) ? ApqpLookup.FMEA : ApqpLookup.CP;
                ApqpLookup.RevView a = ApqpLookup.of(apqps).rev(kind, d.getRefId()).orElse(null);
                if (a != null && a.everReleased() && gm.approveFromDoc(d.getDelivId(), releasedNote(a), by) == 1) {
                    gm.insertHist(projId, d.getDelivId(), "APPROVE", null, releasedNote(a), by);
                    n++;
                }
                continue;
            }
            DocRevView r = DocLookup.of(docs).rev(d.getRefId()).orElse(null);
            if (r != null && "RELEASED".equals(r.status()) && gm.approveFromDoc(d.getDelivId(), releasedNote(r), by) == 1) {
                gm.insertHist(projId, d.getDelivId(), "APPROVE", null, releasedNote(r), by);
                n++;
            }
        }
        return n;
    }

    /** 프로젝트 ECO 의 PPAP 패키지 — ECO·패키지가 없거나 다른 회사면 null(계약이 빈 값) */
    PpapView ppapOf(Long ecoId) {
        return ecoId == null ? null : EcoLookup.of(ecos).ppap(ecoId).orElse(null);
    }

    static String approvedNote(PpapView p) {
        return "PPAP APPROVED" + (p.decidedOn() == null ? "" : " " + p.decidedOn());
    }

    static String releasedNote(ApqpLookup.RevView r) {
        return "APQP RELEASED " + (ApqpLookup.FMEA.equals(r.kind()) ? "PFMEA " : "CP ") + r.no() + " rev " + r.rev();
    }

    static String releasedNote(DocRevView r) {
        return "QMS RELEASED " + r.docNo() + " " + r.rev();
    }
}
