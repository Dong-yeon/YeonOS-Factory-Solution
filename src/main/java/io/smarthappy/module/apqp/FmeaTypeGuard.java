package io.smarthappy.module.apqp;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 기록 유형 = 화면 확인 (docs/41 13장 A5-2, D8) — PFMEA·DFMEA 는 같은 표·같은 서비스, 화면 권한만 다르다.
 * 화면(API 경로)과 기록 유형이 다르면 없는 것처럼 404 — PFMEA 권한만 있는 사람이 PFMEA 경로로 DFMEA 를 읽거나 고치지 못하게.
 * 하위 행(노드·기능·고장·사슬·위험·조치)은 그 행이 속한 FMEA 의 유형으로 본다
 */
@Component
@RequiredArgsConstructor
class FmeaTypeGuard {

    private final ApqpRepository repo;

    @Transactional(readOnly = true)
    public void fmea(String type, long fmeaId) {
        if (!type.equals(repo.fmeaType(fmeaId))) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /** kind = NODE·FUNC·FAIL·CHAIN·RISK·ACTION */
    @Transactional(readOnly = true)
    public void row(String type, String kind, long id) {
        Long f = repo.fmeaOf(kind, id);
        if (f == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        fmea(type, f);
    }
}
