package io.smarthappy.module.aps;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsDtos.PinForm;
import io.smarthappy.module.aps.ApsDtos.PinMoveForm;
import io.smarthappy.module.aps.ApsDtos.PinOp;
import io.smarthappy.module.aps.ApsDtos.PinRow;
import io.smarthappy.module.aps.ApsDtos.PinSaved;
import io.smarthappy.module.aps.FiniteScheduler.Res;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 간트 수동 고정 (aps-plan) — APS 3단계, docs/38 12장.
 *
 * - 고정 = (지시, 공정 순번) 하나에 사용 중 하나, 자원 + 시작 시각. 다음 계산이 먼저 자리 잡는다(계획 결과·지시는 그대로)
 * - 자원은 그 공정의 **지금** 후보만(계산과 같은 {@link ApsService#resources}), 외주 공정은 외주만
 * - 동시 새 고정 → 부분 유일 → 409 APS_PIN_EXISTS, 옮기기·해제는 rowVer
 */
@Service
@RequiredArgsConstructor
public class ApsPinService {

    static final String OUTSRC = "OUTSRC";
    private static final Instant MIN = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("2999-12-01T00:00:00Z");
    private static final List<String> OPEN = List.of("PLANNED", "RELEASED", "IN_PROGRESS");

    private final ApsMapper mapper;
    private final ApsService plans;

    @Transactional(readOnly = true)
    public List<PinRow> list(String plantCd) {
        return mapper.findPinList(plantCd == null || plantCd.isBlank() ? null : plantCd.strip().toUpperCase(Locale.ROOT));
    }

    @Transactional
    public PinSaved create(PinForm in) {
        LoginUser me = me();
        if (in == null || in.planOpId() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PinOp op = mapper.findPinOp(in.planOpId());
        if (op == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String remark = OrderService.text(in.remark(), "remark", 500, e);
        Res res = resource(op, in.resKey(), e);
        OffsetDateTime start = start(in.startAt(), e);
        e.throwIfAny();
        try {
            long id = mapper.insertPin(op, res.kind(), res.equipId(), "NODE".equals(res.kind()) ? res.nodeId() : null, start, remark,
                    me.loginId());
            return new PinSaved(id, 0);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.APS_PIN_EXISTS);   // 그 사이 다른 사람이 같은 공정을 고정
        }
    }

    @Transactional
    public PinSaved move(long pinId, PinMoveForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PinRow pin = pin(pinId);
        PinOp op = mapper.findPinOpOf(pin.getSrcPlanId(), pin.getWoId(), pin.getOpSeq());
        if (op == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        Res res = resource(op, in.resKey(), e);
        OffsetDateTime start = start(in.startAt(), e);
        e.throwIfAny();
        if (mapper.movePin(pinId, res.kind(), res.equipId(), "NODE".equals(res.kind()) ? res.nodeId() : null, start, in.rowVer(),
                me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return new PinSaved(pinId, in.rowVer() + 1);
    }

    @Transactional
    public void release(long pinId, PinMoveForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        pin(pinId);
        if (mapper.releasePin(pinId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 지시가 열려 있고 공정이 끝나지 않았으며, 자원이 그 공정의 지금 후보인지 */
    private Res resource(PinOp op, String key, FieldErrors e) {
        if (!OPEN.contains(op.getWoStatus()) || "DONE".equals(op.getProcStatus())) {
            e.add("planOpId", "APS_PIN_CLOSED");
            return null;
        }
        if (key == null || key.isBlank()) {
            e.add("resKey", "REQUIRED");
            return null;
        }
        boolean outsrc = "Y".equals(op.getOutsrcYn());
        if (outsrc) {
            if (!OUTSRC.equals(key)) {
                e.add("resKey", "APS_PIN_RES");
                return null;
            }
            return new Res(OUTSRC, null, null, "");
        }
        if (op.getNodeId() == null) {
            e.add("resKey", "APS_PIN_RES");
            return null;
        }
        FiniteScheduler.OpIn in = new FiniteScheduler.OpIn();
        in.nodeId = op.getNodeId();
        in.equipId = op.getCurEquipId();
        Res hit = plans.resources(op.getPlantCd()).apply(in).stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
        if (hit == null) {
            e.add("resKey", "APS_PIN_RES");
        }
        return hit;
    }

    /** ISO 순간(오프셋 필수) → 분 단위로 자른 UTC */
    private static OffsetDateTime start(String v, FieldErrors e) {
        if (v == null || v.isBlank()) {
            e.add("startAt", "REQUIRED");
            return null;
        }
        Instant t;
        try {
            t = OffsetDateTime.parse(v.strip()).toInstant();
        } catch (DateTimeParseException ex) {
            e.add("startAt", "FORMAT");
            return null;
        }
        if (t.isBefore(MIN) || !t.isBefore(MAX)) {
            e.add("startAt", "FORMAT");
            return null;
        }
        return OffsetDateTime.ofInstant(t.truncatedTo(ChronoUnit.MINUTES), ZoneOffset.UTC);
    }

    private PinRow pin(long id) {
        PinRow p = mapper.findPin(id);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
