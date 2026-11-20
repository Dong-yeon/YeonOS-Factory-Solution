package io.smarthappy.module.apqp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FlowChar;
import io.smarthappy.module.apqp.ApqpDtos.FlowStep;
import io.smarthappy.module.apqp.ApqpDtos.FlowStepRow;
import io.smarthappy.module.apqp.ApqpDtos.FlowView;
import io.smarthappy.module.apqp.ApqpDtos.FmeaDetail;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.FuncRow;
import io.smarthappy.module.apqp.ApqpDtos.NodeRow;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import io.smarthappy.module.apqp.CpDtos.CpLineRow;
import io.smarthappy.module.apqp.CpDtos.CpRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import lombok.RequiredArgsConstructor;

/**
 * 공정 흐름도 PFD (docs/41 13장 A5-1, D1~D3) — 저장하지 않고 그때 만든다: PFMEA 의 라우팅(그 리비전) 단계 순서 + 우리 기호 4종 +
 * 단계별 특성(PFMEA 기능 특성 ∪ 관리계획서 행 특성, 특별특성은 마스터) + 관리계획서 행 수 + 위험행·AP 높음 수.
 * 원본이 바뀌면(라우팅 다시 잇기·위험행·관리계획서) 다음 조회에 바로 따라간다. 화면·엑셀 시트가 같은 결과를 쓴다
 */
@Service
@RequiredArgsConstructor
public class ApqpFlowService {

    static final String PROCESS = "PROCESS";
    static final String INSPECT = "INSPECT";
    static final String OUTSOURCE = "OUTSOURCE";
    static final String PACK = "PACK";

    private final FmeaService fmeas;
    private final ApqpRepository repo;
    private final CpRepository cps;
    private final PermissionService permission;

    @Transactional(readOnly = true)
    public FlowView flow(long fmeaId) {
        FmeaDetail d = fmeas.get(fmeaId);
        FmeaRow f = d.fmea();
        List<FlowStepRow> steps = f.getRoutingId() == null ? List.of() : repo.findFlowSteps(f.getRoutingId());
        // 관리계획서 — 이 PFMEA 를 가리키는 최신(폐기 빼고). 화면 권한이 없으면 있다는 것만
        Long cpId = cps.findLatestCpOfFmea(fmeaId);
        boolean cpVisible = cpId != null && permission.actions(CpService.PROG).contains(Action.SEARCH);
        CpRow cp = cpVisible ? cps.findCp(cpId) : null;
        List<CpLineRow> lines = cp == null ? List.of() : cps.findLines(cp.getCpId());

        Map<Long, NodeRow> nodes = d.nodes().stream().collect(Collectors.toMap(NodeRow::getNodeId, Function.identity()));
        Map<Long, FuncRow> funcs = d.funcs().stream().collect(Collectors.toMap(FuncRow::getFuncId, Function.identity()));
        Map<Long, FailRow> fails = d.fails().stream().collect(Collectors.toMap(FailRow::getFailId, Function.identity()));

        Map<Long, Map<Long, FlowChar>> charsOf = new HashMap<>();
        for (FuncRow fn : d.funcs()) {
            Long step = stepOf(nodes, fn.getNodeId());
            if (step != null && fn.getCharId() != null) {
                charsOf.computeIfAbsent(step, k -> new LinkedHashMap<>()).merge(fn.getCharId(),
                        chr(fn.getCharId(), fn.getCharNo(), fn.getCharNm(), null, fn.getCharSpecial(), true, false), ApqpFlowService::both);
            }
        }
        Map<Long, Integer> cpCnt = new HashMap<>();
        for (CpLineRow l : lines) {
            if (l.getStepId() == null) {
                continue;
            }
            cpCnt.merge(l.getStepId(), 1, Integer::sum);
            if (l.getCharId() != null) {
                charsOf.computeIfAbsent(l.getStepId(), k -> new LinkedHashMap<>()).merge(l.getCharId(),
                        chr(l.getCharId(), l.getCharNo(), l.getCharNm(), l.getCharType(), l.getCharSpecial(), false, true), ApqpFlowService::both);
            }
        }
        Map<Long, int[]> riskCnt = new HashMap<>();
        int unplaced = 0;
        for (RiskRow r : d.risks()) {
            FailRow fm = fails.get(r.getFmId());
            FuncRow fn = fm == null ? null : funcs.get(fm.getFuncId());
            Long step = fn == null ? null : stepOf(nodes, fn.getNodeId());
            if (step == null) {
                unplaced++;
                continue;
            }
            int[] c = riskCnt.computeIfAbsent(step, k -> new int[2]);
            c[0]++;
            if ("H".equals(r.getAp())) {
                c[1]++;
            }
        }
        List<FlowStep> out = new ArrayList<>();
        for (FlowStepRow s : steps) {
            int[] rc = riskCnt.getOrDefault(s.getStepId(), new int[2]);
            out.add(new FlowStep(s.getStepId(), s.getOpSeq(), s.getProcCd(), s.getProcNm(), s.getProcCat(), symbol(s), s.getStdCtSec(),
                    List.copyOf(charsOf.getOrDefault(s.getStepId(), Map.of()).values()), cpCnt.getOrDefault(s.getStepId(), 0), rc[0], rc[1]));
        }
        // 라우팅에 없는 단계(다시 잇기 전 옛 단계 등)에 놓인 위험행도 "놓이지 않음" 으로 센다
        for (Map.Entry<Long, int[]> e : riskCnt.entrySet()) {
            if (steps.stream().noneMatch(s -> s.getStepId() == e.getKey())) {
                unplaced += e.getValue()[0];
            }
        }
        return new FlowView(f.getFmeaId(), f.getFmeaNo(), f.getRev(), f.getStatus(), f.getItemCd(), f.getItemNm(), f.getRoutingId(),
                f.getRoutingText(), cp == null ? null : cp.getCpId(), cp == null ? null : cp.getCpNo(), cp == null ? null : cp.getRev(),
                cp == null ? null : cp.getStatus(), cpId != null && !cpVisible, out, unplaced);
    }

    /** 기호 — 외주가 먼저(어디서 하느냐), 그다음 공정 분류 검사·포장, 나머지 가공 */
    static String symbol(FlowStepRow s) {
        if ("Y".equals(s.getOutsrcYn())) {
            return OUTSOURCE;
        }
        if ("INSPECT".equals(s.getProcCat())) {
            return INSPECT;
        }
        return "PACK".equals(s.getProcCat()) ? PACK : PROCESS;
    }

    /** 노드 → 위로 올라가며 라우팅 단계 id(STEP 노드의 step_id, 작업요소는 부모 단계) */
    private static Long stepOf(Map<Long, NodeRow> nodes, Long nodeId) {
        NodeRow n = nodeId == null ? null : nodes.get(nodeId);
        for (int guard = 0; n != null && guard < 64; guard++) {
            if (n.getStepId() != null) {
                return n.getStepId();
            }
            n = n.getParentId() == null ? null : nodes.get(n.getParentId());
        }
        return null;
    }

    private static FlowChar chr(long id, String no, String nm, String type, String specialCls, boolean fmea, boolean cp) {
        boolean special = specialCls != null && !specialCls.isBlank() && !"NONE".equals(specialCls);
        return new FlowChar(id, no, nm, type, specialCls, special, fmea, cp);
    }

    private static FlowChar both(FlowChar a, FlowChar b) {
        return new FlowChar(a.charId(), a.charNo(), a.charNm(), a.charType() != null ? a.charType() : b.charType(),
                a.specialCls() != null ? a.specialCls() : b.specialCls(), a.special() || b.special(), a.inFmea() || b.inFmea(),
                a.inCp() || b.inCp());
    }
}
