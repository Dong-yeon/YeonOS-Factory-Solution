package io.smarthappy.module.pop;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.link.PmCheckPort;
import io.smarthappy.module.cms.link.PmCheckPort.Complete;
import io.smarthappy.module.cms.link.PmCheckPort.Done;
import io.smarthappy.module.cms.link.PmCheckPort.DueRun;
import io.smarthappy.module.cms.link.PmCheckPort.ItemResult;
import io.smarthappy.module.cms.link.PmCheckPort.RunView;
import io.smarthappy.module.cms.link.PmCheckPort.Skip;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownEquip;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.code.CodeService.CodeItem;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * POP 설비 점검 (docs/25 4.58) — 작업자 자주 점검(예방점검 CHECK 방식)을 단말에서 실행. 화면·범위 확인은 MES, 기록·판정·다음 예정은 CMS
 * {@link PmCheckPort}(이 트랜잭션 안에서 부른다 — 계약 MANDATORY).
 *
 * - 지금 작업자 필수(없으면 428), 설비는 단말 공장 + 작업장(라인이면 아래 셀 포함) 안만 — 고장 신고와 같은 설비 범위. 밖의 점검은 404
 * - 결과 입력 + 완료는 한 요청(clientReqId 멱등 — 같은 id 재전송 = 처음 결과). 건너뛰기는 사유 코드 필수
 * - 수치 판정·필수 항목·WO 방식 거부·칸 오류는 CMS 것을 그대로 돌려준다. 온라인만(오프라인 대기열 없음)
 */
@Service
@RequiredArgsConstructor
public class PopPmService {

    static final String SKIP_REASON_GRP = "CMS_SKIP_REASON";

    /** 점검 화면 — 항목 + 건너뛰기 사유 선택지(공통코드, 회사 확장 포함) */
    public record PmView(RunView view, List<CodeItem> skipReasons) {
    }

    public record CompleteForm(Integer rowVer, List<ItemResult> results, String clientReqId) {
    }

    public record SkipForm(Integer rowVer, String reasonCd, String memo, String clientReqId) {
    }

    private final PopWorkMapper mapper;
    private final ObjectProvider<PmCheckPort> ports;
    private final CodeService codes;

    /** 점검 버튼 — 생산 단말(POP)만, CMS 구현이 없으면(NONE) 숨김 */
    static boolean pmOn(String terminalType, ObjectProvider<PmCheckPort> provider) {
        return "POP".equals(terminalType) && PmCheckPort.of(provider) != PmCheckPort.NONE;
    }

    @Transactional(readOnly = true)
    public List<DueRun> due(TerminalPrincipal t, PopWorker w) {
        require(w);
        return PmCheckPort.of(ports).due(t.plantCd(), equipIds(t));
    }

    @Transactional(readOnly = true)
    public PmView view(TerminalPrincipal t, PopWorker w, long runId) {
        require(w);
        RunView v = PmCheckPort.of(ports).detail(runId, t.terminalId());
        inScope(t, v.run().equipId());
        return new PmView(v, codes.codes(SKIP_REASON_GRP, LocaleContextHolder.getLocale()));
    }

    @Transactional
    public Done complete(TerminalPrincipal t, PopWorker w, long runId, CompleteForm in) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PmCheckPort cms = PmCheckPort.of(ports);
        inScope(t, cms.detail(runId, t.terminalId()).run().equipId());
        return cms.complete(new Complete(runId, in.rowVer(), in.results(), me.workerId(), t.terminalId(), in.clientReqId()));
    }

    @Transactional
    public Done skip(TerminalPrincipal t, PopWorker w, long runId, SkipForm in) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PmCheckPort cms = PmCheckPort.of(ports);
        inScope(t, cms.detail(runId, t.terminalId()).run().equipId());
        return cms.skip(new Skip(runId, in.rowVer(), in.reasonCd(), in.memo(), me.workerId(), t.terminalId(), in.clientReqId()));
    }

    /** 단말 작업장 설비(고장 신고와 같은 범위 조회) */
    private Set<Long> equipIds(TerminalPrincipal t) {
        return mapper.breakdownEquips(t.plantCd(), t.nodeId()).stream().map(BreakdownEquip::getEquipId).collect(Collectors.toSet());
    }

    /** 작업장 밖 설비의 점검 = 없음(다른 작업장에 무엇이 있는지 흘리지 않는다) */
    private void inScope(TerminalPrincipal t, long equipId) {
        if (!equipIds(t).contains(equipId)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private static PopWorker require(PopWorker w) {
        if (w == null) {
            throw new BusinessException(HbErrorCode.POP_NO_WORKER);
        }
        return w;
    }
}
