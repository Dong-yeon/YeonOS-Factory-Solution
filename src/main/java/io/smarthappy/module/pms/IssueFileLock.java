package io.smarthappy.module.pms;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 이슈 첨부 잠금 (docs/32 4.15) — 대상 id "I" + 이슈 id. 닫힌 이슈(종료·기각)와 진행 중이 아닌 프로젝트의 이슈, 없는 이슈(다른 회사 포함)는 잠김.
 * 목록·내려받기는 잠겨도 된다(플랫폼: 잠금은 올리기·삭제만)
 */
@Component
@RequiredArgsConstructor
public class IssueFileLock implements FileLockRule {

    private final WorkMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!IssueService.FILE_TARGET.equals(targetType)) {
            return false;
        }
        if (targetId == null || !targetId.startsWith("I")) {
            return true;
        }
        long id;
        try {
            id = Long.parseLong(targetId.substring(1));
        } catch (NumberFormatException e) {
            return true;
        }
        WorkDtos.IssueLockRow r = mapper.findIssueLock(id);
        return r == null || "CLOSED".equals(r.getIssueStatus()) || !TaskService.OPEN.contains(r.getProjStatus());
    }
}
