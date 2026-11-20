package io.smarthappy.module.cms.edge;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;

/**
 * EDGE(SaaS) 보내기 — 서버는 공장 안으로 들어가지 않는다. 명령 행(REQUESTED) 자체가 "당기는 아웃박스" 이고,
 * 엣지가 `/ext/v1/edge/commands/claim` 으로 lease 를 잡아 가져간다(docs/65 16.1·6.2 와 같은 모양). 여기서 할 일은 없다.
 */
@Component
class EdgeCommandTransport implements EquipCommandTransport {

    @Override
    public String connMode() {
        return "EDGE";
    }

    @Override
    public String dispatch(CmdRow cmd, LinkRef link) {
        return null;
    }
}
