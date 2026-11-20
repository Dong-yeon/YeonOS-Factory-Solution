package io.smarthappy.module.qms.preship;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 출하 전 검수 사진 잠금 (docs/78 6장) — 대상 qms-preship · 판정 줄 id.
 * 검수 OPEN = 열림, DONE = 열린 미결이 있는 줄만 열림(출하 뒤 조치 사진), 취소·없는 줄(다른 회사 포함) = 잠김
 */
@Component
@RequiredArgsConstructor
public class PreshipFileLock implements FileLockRule {

    private final PreshipMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!PreshipService.PROG.equals(targetType)) {
            return false;
        }
        long id;
        try {
            id = Long.parseLong(targetId);
        } catch (NumberFormatException e) {
            return true;
        }
        String s = mapper.fileLockState(id);
        return !("OPEN".equals(s) || "DONE:Y".equals(s));
    }
}
