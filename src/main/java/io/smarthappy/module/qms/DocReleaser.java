package io.smarthappy.module.qms;

import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.DocDtos.RevRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 관리문서 리비전 배포 한 곳 (docs/29 Q3) — 결재 승인(DocApprovalDocType), 결재 없는 배포(정책 N), PLM ECO 적용(DocService.releaseFromChange)이
 * 같은 순서를 쓴다: 문서 행 잠금 → 이전 배포본 폐기 → 이 리비전 배포 → 첨부 잠금 표시. 서비스에 기대지 않는다(결재 핸들러 순환 방지)
 */
@Component
@RequiredArgsConstructor
class DocReleaser {

    private final DocMapper mapper;

    void release(long docRevId, String by) {
        RevRow r = mapper.findRev(docRevId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        mapper.lockDoc(r.getDocId());
        mapper.obsoleteReleased(r.getDocId(), by);
        if (mapper.releaseRev(docRevId, by) == 0) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
        mapper.lockFiles(docRevId, by);
    }
}
