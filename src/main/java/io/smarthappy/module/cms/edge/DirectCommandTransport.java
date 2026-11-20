package io.smarthappy.module.cms.edge;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxService;
import lombok.RequiredArgsConstructor;

/**
 * DIRECT(설치형) 보내기 — 같은 트랜잭션에 아웃박스 EQUIP_CMD 발행 → 릴레이가 트랜잭션 밖에서 어댑터로 POST({@link EquipCmdHandler}).
 * 업무가 롤백되면 발행도 없다. 결과는 어댑터가 EDGE 와 같은 수신 API 로 보고한다.
 */
@Component
@RequiredArgsConstructor
class DirectCommandTransport implements EquipCommandTransport {

    private final OutboxService outbox;

    @Override
    public String connMode() {
        return "DIRECT";
    }

    @Override
    public String dispatch(CmdRow cmd, LinkRef link) {
        if (link.getAdapterUrl() == null) {
            throw new BusinessException(HbErrorCode.EQUIP_NO_ADAPTER);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("url", link.getAdapterUrl());
        p.put("cmdKey", cmd.getCmdKey().toString());
        p.put("equipCd", link.getEquipCd());
        p.put("tagCd", cmd.getTagCd());
        p.put("dataType", cmd.getDataType());
        p.put("value", cmd.getCmdValue());
        p.put("expiresAt", cmd.getExpiresAt().toString());
        outbox.publish(EquipCmdHandler.TOPIC, bizKey(cmd.getCmdId()), p);
        return "outbox";
    }

    static String bizKey(long cmdId) {
        return "cmd:" + cmdId;
    }
}
