package io.smarthappy.module.cms.edge;

import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;

/**
 * 명령 보내기 — 연결 방식마다 하나 (docs/65 16.1). 명령 서비스는 방식을 모르고 이것만 부른다.
 * 업무 트랜잭션 안에서 불린다(MANDATORY) — 실제 송신은 트랜잭션 밖(아웃박스 릴레이) 또는 엣지가 당겨 갈 때.
 *
 * @return 이 단계에서 명령이 갈 상태(REQUESTED 그대로 / SENT)와 단계 원장 SENT 를 남길지
 */
public interface EquipCommandTransport {

    String connMode();

    /** @return 단계 SENT 의 detail(남기지 않으면 null) */
    String dispatch(CmdRow cmd, LinkRef link);
}
