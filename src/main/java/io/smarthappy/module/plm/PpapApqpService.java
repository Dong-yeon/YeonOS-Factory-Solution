package io.smarthappy.module.plm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.link.ApqpLookup;
import io.smarthappy.module.plm.PlmDtos.PpapApqpCand;
import io.smarthappy.module.plm.PlmDtos.PpapApqpCandidates;
import io.smarthappy.module.plm.PlmDtos.PpapApqpForm;
import io.smarthappy.module.plm.PlmDtos.PpapApqpItem;
import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * PPAP 요소 6(공정 FMEA)·7(관리계획서) ← APQP 현행 확정본 (docs/39 38장 Q1~Q7, PMS A3-6 docs/41 11.11).
 * APQP 는 계약 {@link ApqpLookup} 으로만 읽는다(표·서비스 직접 안 부름, 예외 없음 — APQP 없는 설치본은 후보 빈 목록).
 * 근거는 리비전 id 를 FK 없이 + 연결한 때의 label 스냅샷. 후보·연결은 그 APQP 화면 SEARCH 가 있어야(Q4), 근거는 폐기돼도 바꾸지 않는다(Q5).
 */
@Service
@RequiredArgsConstructor
public class PpapApqpService {

    static final String FMEA_PROG = "apqp-fmea";
    static final String CP_PROG = "apqp-cp";
    /** 요소 번호 ↔ APQP 종류(V194 CHECK 와 같다) */
    static final Map<Integer, String> ELEM_KIND = Map.of(6, ApqpLookup.FMEA, 7, ApqpLookup.CP);
    /** 관리계획서 단계 표시 순서 — 요소 7 은 보통 양산 단계(Q1, 고르는 것은 사용자) */
    private static final List<String> CP_PHASE_ORDER = List.of("PRODUCTION", "PRE_LAUNCH", "PROTO");

    private final PlmPpapMapper mapper;
    private final PermissionService permission;
    private final ObjectProvider<ApqpLookup> apqps;

    /** 이 사람이 근거로 다룰 수 있는 종류 — 그 APQP 화면 SEARCH(Q4) */
    List<String> kinds() {
        List<String> out = new ArrayList<>();
        if (permission.actions(FMEA_PROG).contains(Action.SEARCH)) {
            out.add(ApqpLookup.FMEA);
        }
        if (permission.actions(CP_PROG).contains(Action.SEARCH)) {
            out.add(ApqpLookup.CP);
        }
        return out;
    }

    /** 상세 응답의 요소에 지금 상태 — 조회 권한이 있는 종류만, 못 읽으면 null(label 만 보인다) */
    void fillStatus(List<PpapElemRow> elems, List<String> kinds) {
        ApqpLookup lookup = ApqpLookup.of(apqps);
        for (PpapElemRow el : elems) {
            if (el.getApqpRevId() != null && kinds.contains(el.getApqpKind())) {
                el.setApqpStatus(lookup.rev(el.getApqpKind(), el.getApqpRevId()).map(ApqpLookup.RevView::status).orElse(null));
            }
        }
    }

    /** 후보 — 지시 대상 품목들의 현행 확정 PFMEA·관리계획서. 다른 회사·없는 패키지 404, 권한 없는 종류는 빈 목록 */
    @Transactional(readOnly = true)
    public PpapApqpCandidates candidates(long ppapId) {
        PpapRow p = mapper.findPpap(ppapId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<String> kinds = kinds();
        if (kinds.isEmpty()) {
            return new PpapApqpCandidates(List.of(), List.of());
        }
        List<PpapApqpCand> all = all(p.getEcoId());
        List<PpapApqpCand> fmea = kinds.contains(ApqpLookup.FMEA) ? all.stream().filter(c -> ApqpLookup.FMEA.equals(c.kind())).toList() : List.of();
        List<PpapApqpCand> cp = kinds.contains(ApqpLookup.CP) ? all.stream().filter(c -> ApqpLookup.CP.equals(c.kind()))
                .sorted(Comparator.comparingInt((PpapApqpCand c) -> phaseOrder(c.phase())).thenComparing(PpapApqpCand::itemCd)
                        .thenComparing(PpapApqpCand::no))
                .toList() : List.of();
        return new PpapApqpCandidates(fmea, cp);
    }

    /**
     * 요소 6·7 근거 연결·끊기 — 작성 중 패키지만(제출 뒤 409, 가드도 막음), 패키지 row_ver 를 올린다(동시 저장과 충돌).
     * 고른 리비전은 <b>그 순간 후보 안</b>(대상 품목의 현행 확정본, 같은 종류)이어야 — 아니면 400 NOT_CANDIDATE. label 은 서버가 만든다
     */
    @Transactional
    public void link(long ppapId, PpapApqpForm in) {
        LoginUser me = EcoService.me();
        int ver = EcoService.rowVer(in == null ? null : in.rowVer());
        PpapRow cur = mapper.lockPpap(ppapId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_PPAP_STATE);
        }
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String kind = in.elemNo() == null ? null : ELEM_KIND.get(in.elemNo());
        if (kind == null) {
            FieldErrors e = FieldErrors.create();
            e.add("elemNo", "CODE");
            e.throwIfAny();
        }
        if (!kinds().contains(kind)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        String label = null;
        if (in.revId() != null) {
            PpapApqpCand c = all(cur.getEcoId()).stream().filter(x -> kind.equals(x.kind()) && x.revId() == in.revId()).findFirst().orElse(null);
            if (c == null) {
                // 다른 종류·다른 품목·작성 중·결재 중·폐기·다른 회사·없는 id — 구분하지 않는다
                FieldErrors e = FieldErrors.create();
                e.add("apqpRevId", "NOT_CANDIDATE");
                e.throwIfAny();
            }
            label = label(c);
        }
        if (mapper.updatePpap(ppapId, ver, cur.getSubmitLevel(), cur.getRemark(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.updateElemApqp(ppapId, in.elemNo(), in.revId() == null ? null : kind, in.revId(), label, me.loginId());
    }

    /** "번호 rev N[ 단계]" — 표시 스냅샷(V194 apqp_label 100자) */
    static String label(PpapApqpCand c) {
        String v = c.no() + " rev " + c.rev() + (c.phase() == null ? "" : " " + c.phase());
        return v.length() > 100 ? v.substring(0, 100) : v;
    }

    private List<PpapApqpCand> all(long ecoId) {
        ApqpLookup lookup = ApqpLookup.of(apqps);
        List<PpapApqpCand> out = new ArrayList<>();
        for (PpapApqpItem it : mapper.ecoTargetItems(ecoId)) {
            for (ApqpLookup.CurrentView v : lookup.current(it.getItemId())) {
                ApqpLookup.RevView r = v.rev();
                out.add(new PpapApqpCand(r.kind(), r.id(), r.no(), r.rev(), r.title(), v.phase(), it.getItemCd(), it.getItemNm(), r.releasedAt()));
            }
        }
        return out;
    }

    private static int phaseOrder(String phase) {
        int i = CP_PHASE_ORDER.indexOf(phase);
        return i < 0 ? CP_PHASE_ORDER.size() : i;
    }
}
