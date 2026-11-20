package io.smarthappy.module.wms.ship;

import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.ship.ShipDtos.ShipHead;
import io.smarthappy.module.wms.ship.ShipDtos.TransportForm;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 출하 운송 정보 (docs/58, 사용자 결정 W-7) — 운송사·차량 번호·송장 번호. 지시 1 : 0..1(wms_ship_transport).
 *
 * 고칠 수 있는 상태 = DRAFT·PICKING·SHIPPED(송장 번호는 보통 출하 뒤에 나온다), CANCELLED 는 409 {@code WMS_SHIP_STATE}.
 * 머리를 FOR UPDATE 로 잡아 지시 취소와 줄 세우지만 머리 row_ver 는 올리지 않는다 — 사무가 송장 번호를 적는 사이 PDA 피킹이
 * 409 로 튕기지 않게. 운송 정보끼리는 운송 행의 row_ver 로 겨룬다(행이 없으면 rowVer = null 로 보내야 함 — 둘이 동시에 처음 쓰면 늦은 쪽 409).
 * 빈 값은 지움(null), 제어문자는 400 FORMAT. 다른 회사 지시는 RLS 로 안 보여 404.
 */
@Service
@RequiredArgsConstructor
public class ShipTransportService {

    static final int CARRIER_MAX = 100;
    static final int VEHICLE_MAX = 30;
    static final int TRACKING_MAX = 60;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    private final ShipMapper ships;
    private final ShipTransportMapper mapper;

    @Transactional
    public void save(long shipId, TransportForm f) {
        LoginUser me = ShipService.me();
        if (f == null) {
            throw ShipService.fieldError("carrierNm", "REQUIRED");
        }
        FieldErrors e = FieldErrors.create();
        String carrier = text(f.carrierNm(), "carrierNm", CARRIER_MAX, e);
        String vehicle = text(f.vehicleNo(), "vehicleNo", VEHICLE_MAX, e);
        String tracking = text(f.trackingNo(), "trackingNo", TRACKING_MAX, e);
        e.throwIfAny();
        ShipHead h = ships.lockShip(shipId);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("CANCELLED".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        Integer cur = mapper.transportRowVer(shipId);
        if (!Objects.equals(cur, f.rowVer())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        int n = cur == null
                ? mapper.insert(shipId, carrier, vehicle, tracking, me.loginId())
                : mapper.update(shipId, cur, carrier, vehicle, tracking, me.loginId());
        if (n != 1) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static String text(String v, String field, int max, FieldErrors e) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String r = v.strip();
        if (CONTROL.matcher(r).find()) {
            e.add(field, "FORMAT");
        } else if (r.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return r;
    }
}
