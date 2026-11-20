package io.smarthappy.module.wms.ship;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 출하 사진·증빙 첨부 잠금 (docs/58, W-7·W-8) — 대상 = sys_file(target_type 'wms-ship', target_id = ship_id).
 * DRAFT·PICKING·SHIPPED 지시에만 올리고 지운다. 취소된 지시·없는 지시(다른 회사 포함 — RLS 로 안 보임)·숫자가 아닌 id 는 잠김(409 FILE_LOCKED).
 * 보존은 출하 기록과 같음(별도 규칙 없음 — 지운 첨부도 use_yn = 'N' 으로 남는다)
 */
@Component
@RequiredArgsConstructor
public class ShipFileLock implements FileLockRule {

    private final ShipTransportMapper mapper;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (!ShipService.PROG.equals(targetType)) {
            return false;
        }
        long id;
        try {
            id = Long.parseLong(targetId);
        } catch (NumberFormatException e) {
            return true;
        }
        String st = mapper.shipStatus(id);
        return st == null || "CANCELLED".equals(st);
    }
}
