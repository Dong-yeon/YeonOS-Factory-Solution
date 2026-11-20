package io.smarthappy.module.pms;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 산출물 첨부 잠금 (docs/32 PM3) — 진행 중(작성·진행·보류) 프로젝트의 산출물에만 올린다. 종료·취소 프로젝트와 없는 산출물(다른 회사 포함)은 잠김.
 * 제출한 파일 자체는 제출 때 sys_file.locked_yn 을 찍어 공통 트리거가 삭제·변경을 막는다(DeliverableService.submit)
 */
@Component
@RequiredArgsConstructor
public class DeliverableFileLock implements FileLockRule {

    private final GateMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!DeliverableService.FILE_TARGET.equals(targetType)) {
            return false;
        }
        if (targetId == null || !targetId.startsWith("D")) {
            return true;
        }
        long id;
        try {
            id = Long.parseLong(targetId.substring(1));
        } catch (NumberFormatException e) {
            return true;
        }
        String status = mapper.findDeliverableProjStatus(id);
        return status == null || !TaskService.OPEN.contains(status);
    }
}
