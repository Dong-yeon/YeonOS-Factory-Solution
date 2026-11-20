package io.smarthappy.module.qms;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 부적합(NCR) 첨부 잠금 (docs/29_P3 24장, Q8a-5 특채 고객 승인 기록) — 처리 중(OPEN)일 때만 올리기·삭제.
 * 특채 결재 중(PENDING)·종결·취소면 잠김(반려·회수로 OPEN 이 되면 다시 풀림), 없는 대상(다른 회사 포함)도 잠김
 */
@Component
@RequiredArgsConstructor
public class NcrFileLock implements FileLockRule {

    private final NcrMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!NcrService.PROG.equals(targetType)) {
            return false;
        }
        try {
            return !"OPEN".equals(mapper.ncrStatus(Long.parseLong(targetId)));
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
