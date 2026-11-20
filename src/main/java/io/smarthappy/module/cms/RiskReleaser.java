package io.smarthappy.module.cms;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.CmsRiskDtos.RaRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 위험성평가 확정 (docs/33 4.7) — 결재 없이 바로 확정(RiskService)과 결재 승인(RiskApprovalDocType)이 같은 함수를 쓴다.
 * 확정 = RELEASED + 확정 시각, 이전 확정본(prev_ra_id)은 같은 트랜잭션에서 OBSOLETE. 이 빈은 ApprovalService 에 기대지 않는다.
 */
@Component
@RequiredArgsConstructor
class RiskReleaser {

    private final CmsRiskMapper mapper;
    private final BizClock clock;

    /** 호출자가 평가 행을 잠근 트랜잭션 안에서. from = 지금 상태로 있어야 할 것(DRAFT 또는 IN_APPROVAL) */
    void release(long raId, String from, Integer rowVer, String by) {
        RaRow r = mapper.findRa(raId, clock.today());
        if (r == null || !from.equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_RA_STATE);
        }
        if (mapper.setStatus(raId, "RELEASED", r.getAprId(), true, rowVer, by) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (r.getPrevRaId() != null) {
            mapper.lockRa(r.getPrevRaId());
            RaRow prev = mapper.findRa(r.getPrevRaId(), clock.today());
            if ("RELEASED".equals(prev.getStatus())) {
                mapper.setStatus(prev.getRaId(), "OBSOLETE", prev.getAprId(), false, null, by);   // 새 확정본이 대신한다
            }
        }
    }
}
