package io.smarthappy.module.cms;

import java.util.Set;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 보전 작업지시 첨부 잠금 (C13, docs/33 13장) — 대상 = 작업지시 id. 작업지시 첨부(cms-mwo)·고장 신고 사진(cms-breakdown) 모두
 * 없는 작업지시(다른 회사 포함)·취소·확인(CLOSED)이면 올리기·삭제가 잠긴다. 완료(DONE)는 열어 둔다 — 완료 보고서·사진을 뒤에 붙이는 일이
 * 흔하다(화면은 완료 뒤 삭제 버튼을 숨긴다). 신고 사진은 고장(BM) 작업지시에만 붙는다.
 */
@Component
@RequiredArgsConstructor
public class MwoFileLock implements FileLockRule {

    static final Set<String> LOCKED = Set.of("CANCELLED", "CLOSED");

    private final CmsMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        boolean breakdown = MwoService.PROG_BREAKDOWN.equals(targetType);
        if (!breakdown && !MwoService.PROG.equals(targetType)) {
            return false;
        }
        String typeStatus;
        try {
            typeStatus = mapper.mwoTypeStatus(Long.parseLong(targetId));
        } catch (NumberFormatException e) {
            return true;
        }
        if (typeStatus == null) {
            return true;
        }
        String[] ts = typeStatus.split(":", 2);
        return LOCKED.contains(ts[1]) || (breakdown && !"BM".equals(ts[0]));
    }
}
