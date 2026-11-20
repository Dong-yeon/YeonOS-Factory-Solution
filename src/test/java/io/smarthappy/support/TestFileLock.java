package io.smarthappy.support;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;

/** 시험 전용 첨부 잠금 — 대상 id 가 "LOCKED-" 로 시작하면 잠김(FileAttachAsTest). 업무 대상 id 와 겹치지 않는 접두어 */
@Component
public class TestFileLock implements FileLockRule {

    @Override
    public boolean locked(String targetType, String targetId) {
        return targetId != null && targetId.startsWith("LOCKED-");
    }
}
