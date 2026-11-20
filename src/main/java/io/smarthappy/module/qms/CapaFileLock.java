package io.smarthappy.module.qms;

import java.util.Set;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/** 8D·CSR 첨부 잠금 (docs/29 Q4) — 시정조치는 종결 결재 중·종결·취소면 잠김, 없는 대상(다른 회사 포함)도 잠김. CSR 은 잠그지 않는다 */
@Component
@RequiredArgsConstructor
public class CapaFileLock implements FileLockRule {

    static final Set<String> LOCKED = Set.of("CLOSING", "CLOSED", "CANCELLED");

    private final CapaMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!CapaService.PROG.equals(targetType)) {
            return false;
        }
        try {
            String st = mapper.capaStatus(Long.parseLong(targetId));
            return st == null || LOCKED.contains(st);
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
