package io.smarthappy.module.production;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.LotHoldMapper.HoldLot;
import io.smarthappy.module.production.LotHoldMapper.HoldRow;
import lombok.RequiredArgsConstructor;

/**
 * LOT 보류 근거 (docs/25 4.19) — 화면(LotService)·POP 정정·계약(LotHoldPort)이 같이 쓰는 한 곳.
 * 잠금 순서: LOT 행 → 근거 행. lot_master 의 보류 칸은 lot_hold 트리거가 맞춘다.
 */
@Service
@RequiredArgsConstructor
public class LotHoldService {

    public static final String MANUAL = "MANUAL";
    public static final String MES_CORRECT = "MES_CORRECT";
    /** MES 화면에서 풀 수 있는 출처 — QMS 근거는 QMS 처분으로만(D34) */
    static final Set<String> SCREEN_SOURCES = Set.of(MANUAL, MES_CORRECT);

    private final LotHoldMapper mapper;

    /** LOT 행 잠금 — 없거나 다른 회사면 null */
    @Transactional(propagation = Propagation.MANDATORY)
    HoldLot lock(long lotId) {
        return mapper.lockLot(lotId);
    }

    /** 근거 추가(LOT 은 잠근 뒤) — 같은 근거가 살아 있으면 false */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean addLocked(long lotId, String source, Long refId, String reason, String by) {
        return addLocked(lotId, source, refId, null, reason, by);
    }

    /** 사유 코드(LOT_HOLD_REASON, docs/68 Q1)와 함께 — 코드는 부르는 쪽이 확인한다(DB 트리거도 본다) */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean addLocked(long lotId, String source, Long refId, String reasonCd, String reason, String by) {
        if (mapper.liveHold(lotId, source, refId) != null) {
            return false;
        }
        mapper.insertHold(lotId, source, refId, reasonCd, reason, by);
        return true;
    }

    /** 근거 해제(LOT 은 잠근 뒤) — 살아 있는 그 근거가 없으면 false */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean releaseLocked(long lotId, String source, Long refId, String reason, String by) {
        HoldRow h = mapper.liveHold(lotId, source, refId);
        return h != null && mapper.release(h.getHoldId(), reason, by) == 1;
    }

    /** POP 실적 정정 — 생산 LOT 을 그 실적 근거로 보류(이미 있으면 그대로) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holdForCorrection(long lotId, long resultId, String reason, String by) {
        if (mapper.lockLot(lotId) == null) {
            throw new IllegalStateException("lot " + lotId + " is not visible");
        }
        return addLocked(lotId, MES_CORRECT, resultId, reason, by);
    }
}
