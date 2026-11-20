package io.smarthappy.module.qms;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 관리문서 첨부 잠금 (docs/29 Q3) — 작성 중 리비전에만 올리고 지운다. 결재 중·배포·폐기 리비전과 없는 리비전(다른 회사 포함)은 잠김.
 * 배포 때는 DB 쪽에도 sys_file.locked_yn 을 찍는다(DocReleaser)
 */
@Component
@RequiredArgsConstructor
public class DocFileLock implements FileLockRule {

    private final DocMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!DocService.PROG.equals(targetType)) {
            return false;
        }
        long id;
        try {
            id = Long.parseLong(targetId);
        } catch (NumberFormatException e) {
            return true;
        }
        return !"DRAFT".equals(mapper.revStatus(id));
    }
}
