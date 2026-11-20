package io.smarthappy.module.plm;

import java.util.Comparator;
import java.util.List;

import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.module.plm.PlmDtos.TargetRow;
import io.smarthappy.platform.esign.ESignService;

/**
 * 전자서명 정규 글자 (docs/39 19.2) — 서명한 "내용" 이 무엇인지 고정한다. 칸 순서·형식을 바꾸면 이전 서명이 모두 "서명 뒤 바뀜" 으로
 * 보이므로 바꿀 때는 머리의 판(v1)을 올리고 문서에 남긴다.
 * 형식: 머리 줄 + 칸마다 한 줄 "길이:값"(null 은 "-") — 구분자가 값 안에 있어도 모호하지 않다.
 */
final class PlmSignContent {

    static final String ECO = "plm_eco";
    static final String PPAP = "plm_ppap";

    private PlmSignContent() {
    }

    /** ECO — 결재받는 내용(상신 뒤 고정되는 칸) + 대상(순서·유형·참조·상신 때 고정한 리비전 row_ver) */
    static String eco(EcoRow e, List<TargetRow> targets) {
        StringBuilder b = new StringBuilder("PLM_ECO v1\n");
        f(b, e.getEcoNo()).f(b, e.getTitle()).f(b, e.getReasonCd()).f(b, e.getDescription())
                .f(b, str(e.getEffectiveDate())).f(b, str(e.getOwnerId())).f(b, e.getCustApprovalReqYn()).f(b, e.getCustCd())
                .f(b, e.getChangeKind()).f(b, str(e.getMarkLots())).f(b, str(e.getMarkUntil()))
                .f(b, e.getApplyCond()).f(b, e.getStockItemCd()).f(b, e.getStockPlantCd());
        targets.stream().sorted(Comparator.comparingInt(TargetRow::getSeq)).forEach(t -> {
            b.append("T\n");
            new PlmSignContent.Line().f(b, str(t.getSeq())).f(b, t.getTargetType()).f(b, str(t.getItemId()))
                    .f(b, str(t.getBomId())).f(b, str(t.getRoutingId())).f(b, str(t.getEquipId())).f(b, str(t.getProcId()))
                    .f(b, str(t.getDocRevId())).f(b, str(t.getBaseRowVer())).f(b, t.getChangeDesc());
        });
        return b.toString();
    }

    /** PPAP — 판정까지 포함한 패키지(고객·레벨·상태·제출일·판정) + 18요소(번호·요구·상태·파일 + 있으면 APQP 근거) */
    static String ppap(PpapRow p, List<PpapElemRow> elems) {
        StringBuilder b = new StringBuilder("PLM_PPAP v1\n");
        f(b, str(p.getPpapId())).f(b, str(p.getEcoId())).f(b, p.getCustCd()).f(b, str(p.getSubmitLevel())).f(b, p.getStatus())
                .f(b, str(p.getSubmittedOn())).f(b, str(p.getDecidedOn())).f(b, p.getDecisionRef()).f(b, p.getDecisionNote());
        elems.stream().sorted(Comparator.comparingInt(PpapElemRow::getElemNo)).forEach(el -> {
            b.append("E\n");
            new PlmSignContent.Line().f(b, str(el.getElemNo())).f(b, el.getReqCd()).f(b, el.getStatus()).f(b, str(el.getFileId()));
            // APQP 근거(38장 Q6) — 연결이 있을 때만 덧붙여 연결 없는 기존 패키지의 해시(서명)는 그대로
            if (el.getApqpRevId() != null) {
                b.append("A\n");
                new PlmSignContent.Line().f(b, el.getApqpKind()).f(b, str(el.getApqpRevId())).f(b, el.getApqpLabel());
            }
        });
        return b.toString();
    }

    static String ecoHash(EcoRow e, List<TargetRow> targets) {
        return ESignService.hash(eco(e, targets));
    }

    static String ppapHash(PpapRow p, List<PpapElemRow> elems) {
        return ESignService.hash(ppap(p, elems));
    }

    private static Line f(StringBuilder b, String v) {
        return new Line().f(b, v);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 칸 한 줄 — 연쇄 호출용 */
    static final class Line {
        Line f(StringBuilder b, String v) {
            if (v == null) {
                b.append("-\n");
            } else {
                b.append(v.length()).append(':').append(v).append('\n');
            }
            return this;
        }
    }
}
