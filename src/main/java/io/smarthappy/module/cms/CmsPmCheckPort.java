package io.smarthappy.module.cms;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.link.PmCheckPort;
import lombok.RequiredArgsConstructor;

/** 현장 단말 예방점검 계약 구현 (docs/25 4.58) — 판정·필수 검사·다음 예정은 점검 실행(ChkRunService) 규칙 그대로 */
@Component
@RequiredArgsConstructor
class CmsPmCheckPort implements PmCheckPort {

    private final ChkRunService runs;

    @Override
    public List<DueRun> due(String plantCd, Collection<Long> equipIds) {
        return runs.floorDue(plantCd, equipIds);
    }

    @Override
    public RunView detail(long runId, long terminalId) {
        return runs.floorDetail(runId, terminalId);
    }

    @Override
    public Done complete(Complete in) {
        return runs.floorComplete(in);
    }

    @Override
    public Done skip(Skip in) {
        return runs.floorSkip(in);
    }
}
