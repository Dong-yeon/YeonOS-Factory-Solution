package io.smarthappy.module.qms;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.qms.NcrDtos.NcrRow;
import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;

/**
 * 결재 문서 유형 QMS_NCR — 부적합 특채(그대로 사용) 결재 (docs/29_P3 12.7 Q8a-5). bizKey = ncr_id.
 * 승인 = LOT 보류 해제(포트, LOT 먼저) → 종결 → WMS 처분 반영(B3 — 409 면 승인 자체가 거부, docs/29_P3 21.1 WB-2), 반려·회수 = OPEN. NcrService 는 ApprovalService 를 쓰므로 이 빈은 매퍼·포트·서명만 쓴다(순환 방지).
 * 결재자 서명(E-2, docs/29_P3 12.10): 승인 줄 = APPROVE, 합의 줄 = REVIEW 서명을 줄 처리 직전(beforeAct)에 검증·소비 — 정책 Y 면 필수, 반려는 서명 없음
 */
@Component
public class NcrApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "QMS_NCR";

    private final NcrMapper mapper;
    private final ObjectProvider<LotHoldPort> holdPort;
    private final QmsESign esign;
    private final ObjectProvider<QualityStockLink> stockLink;

    public NcrApprovalDocType(NcrMapper mapper, ObjectProvider<LotHoldPort> holdPort, QmsESign esign,
            ObjectProvider<QualityStockLink> stockLink) {
        this.mapper = mapper;
        this.holdPort = holdPort;
        this.esign = esign;
        this.stockLink = stockLink;
    }

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 OPEN·특채 있음·처분 수량 합이 맞는 NCR 만 — NcrService.submit 이 확인한 같은 트랜잭션에서 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        long id = id(bizKey);
        NcrRow n = mapper.findById(id);
        if (n == null || !"OPEN".equals(n.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_NCR_STATE);
        }
        NcrService.sumCheck(n, mapper.findDisps(id));
    }

    /**
     * 승인·합의 줄 = 전자서명(대상 = 이 NCR, 해시 = 지금 처분 내용 — 결재 중엔 처분을 못 고쳐 작성 서명과 같은 내용).
     * 위임으로 대신 처리하면 누른 사람(세션)이 서명한다. 토큰이 없으면 정책 Y 일 때만 403, 틀린 토큰은 403(ESignService)
     */
    @Override
    public void beforeAct(String bizKey, LineAct act) {
        if (!act.approve()) {
            return;
        }
        long id = id(bizKey);
        NcrRow n = mapper.findById(id);
        if (n == null || !"PENDING".equals(n.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_NCR_STATE);
        }
        String meaning = act.lineType() == ApprovalService.LineType.AGREE ? QmsESign.REVIEW : QmsESign.APPROVE;
        esign.sign(act.esignToken(), meaning, QmsESign.NCR, id, QmsESign.ncr(n, mapper.findDisps(id)));
    }

    /** 공통 결재함 안내 — 정책 Y 이고 승인·합의일 때 그 줄의 서명(검증은 beforeAct). 정책 N·반려·없는 NCR 은 null */
    @Override
    public ESignSpec esignFor(String bizKey, ApprovalService.LineType lineType, boolean approve) {
        if (!approve || lineType == ApprovalService.LineType.REF || !esign.required()) {
            return null;
        }
        NcrRow n = mapper.findById(id(bizKey));
        return n == null ? null : new ESignSpec(lineType == ApprovalService.LineType.AGREE ? QmsESign.REVIEW : QmsESign.APPROVE,
                QmsESign.NCR, n.getNcrId(), n.getNcrNo());
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = id(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        NcrRow n = mapper.findById(id);
        if (n == null || !"PENDING".equals(n.getStatus())) {
            throw new IllegalStateException("특채 결재 중이 아닌 부적합: " + id);
        }
        if (result == ApprovalStatus.APPROVED) {
            if ("Y".equals(n.getHoldYn())) {
                LotHoldPort.of(holdPort).release(n.getLotId(), LotHoldPort.QMS_NCR, id, NcrService.cut("부적합 특채 승인 " + n.getNcrNo()), by);
            }
            if (mapper.close(id, "PENDING", null, by) == 0) {
                throw new IllegalStateException("특채 결재 중이 아닌 부적합: " + id);
            }
            QualityStockLink.of(stockLink).onNcrClosed(id);
        } else if (mapper.reopen(id, by) == 0) {
            throw new IllegalStateException("특채 결재 중이 아닌 부적합: " + id);
        }
    }

    private static long id(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
