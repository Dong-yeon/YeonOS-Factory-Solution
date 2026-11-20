package io.smarthappy.module.cms;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 위험성평가 증빙 첨부 잠금 (C12, docs/33 11.14) — 대상 = 화면 cms-risk × 유해위험요인 id. 작성·결재 중·확정본은 올리기·삭제
 * (개선 증빙은 확정 뒤에 붙는다, D17), 폐기된 평가와 없는 요인(다른 회사 포함)은 잠김 — QMS 시정조치 첨부와 같은 방식
 */
@Component
@RequiredArgsConstructor
public class RiskFileLock implements FileLockRule {

    private final CmsRiskMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!RiskService.PROG.equals(targetType)) {
            return false;
        }
        try {
            String st = mapper.itemRaStatus(Long.parseLong(targetId));
            return st == null || "OBSOLETE".equals(st);
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
