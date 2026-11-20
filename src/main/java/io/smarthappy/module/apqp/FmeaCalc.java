package io.smarthappy.module.apqp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.apqp.ApqpDtos.ActionRow;
import io.smarthappy.module.apqp.ApqpDtos.ChainRow;
import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import lombok.RequiredArgsConstructor;

/**
 * 위험행의 파생 값 — S = 고장사슬로 연결된 FE 심각도의 최댓값, AP = 머리 AP 규칙표로 (S, O, D) 찾기.
 * 조치 뒤 AP = (S, 조치 뒤 O 또는 원래 O, 조치 뒤 D 또는 원래 D) — 조치 뒤 값이 하나도 없으면 비운다.
 * 사슬·심각도·O·D·규칙표·머리 규칙표가 바뀔 때마다 부른다(호출하는 트랜잭션 안). 파생 값은 row_ver 를 올리지 않는다
 */
@Component
@RequiredArgsConstructor
class FmeaCalc {

    private final ApqpRepository repo;

    void recalc(long fmeaId) {
        FmeaRow f = repo.findFmea(fmeaId);
        if (f == null || "OBSOLETE".equals(f.getStatus())) {
            return;
        }
        ApRules rules = ApRules.of(f.getApSetId() == null ? List.of() : repo.findApRules(f.getApSetId()));
        Map<Long, Integer> sevOfFail = new HashMap<>();
        for (FailRow x : repo.findFails(fmeaId)) {
            if (x.getSeverity() != null) {
                sevOfFail.put(x.getFailId(), x.getSeverity());
            }
        }
        Map<Long, Integer> sevOfFm = new HashMap<>();
        for (ChainRow c : repo.findChains(fmeaId)) {
            Integer s = sevOfFail.get(c.getFeId());
            if (s != null) {
                sevOfFm.merge(c.getFmId(), s, Math::max);
            }
        }
        boolean draft = "DRAFT".equals(f.getStatus());
        Map<Long, RiskRow> risks = new HashMap<>();
        for (RiskRow r : repo.findRisks(fmeaId)) {
            Integer sev = sevOfFm.get(r.getFmId());
            String ap = rules.ap(sev, r.getOcc(), r.getDet());
            if (draft) {
                repo.updateRiskDerived(r.getRiskId(), sev, ap);
                r.setSev(sev);
            }
            risks.put(r.getRiskId(), r);
        }
        for (ActionRow a : repo.findActions(fmeaId)) {
            RiskRow r = risks.get(a.getRiskId());
            String ap = null;
            if (r != null && (a.getNewOcc() != null || a.getNewDet() != null)) {
                ap = rules.ap(r.getSev(), a.getNewOcc() != null ? a.getNewOcc() : r.getOcc(),
                        a.getNewDet() != null ? a.getNewDet() : r.getDet());
            }
            if (!java.util.Objects.equals(ap, a.getNewAp())) {
                repo.updateActionDerived(a.getActionId(), ap);
            }
        }
    }
}
