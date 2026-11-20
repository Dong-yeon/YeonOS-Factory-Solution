package io.smarthappy.module.pop;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.cms.link.BreakdownReport.Photo;
import io.smarthappy.module.cms.link.BreakdownReport.PhotoLimits;
import io.smarthappy.module.cms.link.BreakdownReport.PhotoResult;
import io.smarthappy.module.cms.link.BreakdownReport.Report;
import io.smarthappy.module.cms.link.BreakdownReport.ReportResult;
import io.smarthappy.module.cms.link.BreakdownReport.Symptom;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownEquip;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownForm;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownReply;
import io.smarthappy.module.pop.PopWorkDtos.EquipOption;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * POP 고장 신고 (docs/25 4.35, 계약 docs/33 11.16) — 화면·범위 확인은 MES, 기록(보전 작업지시 BM·REQ)은 CMS {@link BreakdownReport}.
 * 지금 작업자 필수, 설비는 단말 공장 + 작업장(라인이면 아래 셀 포함) 안만. CMS 도 공장을 다시 본다.
 * 신고는 이 트랜잭션 안에서 부른다(계약 MANDATORY) — 멱등(같은 clientReqId = 처음 번호, duplicate)·칸 오류는 CMS 것을 그대로 돌려준다.
 * 공정·설비 상태는 건드리지 않는다(계약 보강 7), 온라인만(오프라인 대기열 없음).
 * 신고 사진(docs/25 4.44, C25): 범위(신고한 단말만·REQ/OPEN)·형식·크기·개수·잠김 판정은 CMS — MES 는 작업자 확인과 파일 없음만 막고 오류를 그대로.
 */
@Service
@RequiredArgsConstructor
public class PopBreakdownService {

    private final PopWorkMapper mapper;
    private final ObjectProvider<BreakdownReport> reports;

    @Transactional(readOnly = true)
    public List<EquipOption> equips(TerminalPrincipal t, PopWorker w) {
        require(w);
        return mapper.breakdownEquips(t.plantCd(), t.nodeId()).stream()
                .map(e -> new EquipOption(e.getEquipId(), e.getEquipCd(), e.getEquipNm(), e.getNodeCd())).toList();
    }

    @Transactional(readOnly = true)
    public List<Symptom> symptoms(TerminalPrincipal t, PopWorker w, Long equipId) {
        require(w);
        return BreakdownReport.of(reports).symptoms(equip(t, equipId).getEquipId());
    }

    /** 신고 + 사진 한도(같은 트랜잭션에서 CMS photoLimits — 단말이 사진 단계를 열지·얼마나 줄일지 정한다) */
    @Transactional
    public BreakdownReply report(TerminalPrincipal t, PopWorker w, BreakdownForm in) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        BreakdownEquip e = equip(t, in.equipId());
        BreakdownReport cms = BreakdownReport.of(reports);
        ReportResult r = cms.report(new Report(e.getEquipId(), blankToNull(in.symptomCd()), blankToNull(in.symptomText()),
                me.workerId(), t.terminalId(), in.clientReqId()));
        PhotoLimits l = cms.photoLimits();
        return new BreakdownReply(r.reqNo(), r.duplicate(), l.maxCount(), l.maxBytes(), l.exts());
    }

    /**
     * 신고 사진 한 장(C25) — 계약 MANDATORY 라 이 트랜잭션 안. 붙이는 사람 = 지금 작업자(신고자와 달라도 됨 — 교대).
     * 같은 바이트 재전송 = 처음 파일(duplicate). 스트림은 여기서 닫는다(계약: 부르는 쪽이 닫음)
     */
    @Transactional
    public PhotoResult attachPhoto(TerminalPrincipal t, PopWorker w, String reqNo, String clientReqId, MultipartFile file) {
        PopWorker me = require(w);
        if (file == null) {
            throw OrderService.fieldError("file", "REQUIRED");
        }
        try (InputStream in = file.getInputStream()) {
            return BreakdownReport.of(reports).attachPhoto(new Photo(reqNo, me.workerId(), t.terminalId(), clientReqId,
                    file.getOriginalFilename(), file.getSize(), in));
        } catch (IOException ex) {
            throw new UncheckedIOException("POP 신고 사진 읽기 실패", ex);
        }
    }

    /** 설비 범위 — 없음(다른 회사 포함, RLS)·사용 중지·다른 공장·작업장 밖을 칸 오류로 나눈다 */
    private BreakdownEquip equip(TerminalPrincipal t, Long equipId) {
        if (equipId == null) {
            throw OrderService.fieldError("equipId", "REQUIRED");
        }
        BreakdownEquip e = mapper.findBreakdownEquip(equipId);
        if (e == null) {
            throw OrderService.fieldError("equipId", "NO_SUCH_CODE");
        }
        if (!"Y".equals(e.getUseYn())) {
            throw OrderService.fieldError("equipId", "INACTIVE");
        }
        if (!Objects.equals(t.plantCd(), e.getPlantCd())) {
            throw OrderService.fieldError("equipId", "PLANT");
        }
        if (t.nodeId() != null && !t.nodeId().equals(e.getNodeId()) && !t.nodeId().equals(e.getParentNodeId())) {
            throw OrderService.fieldError("equipId", "EQUIP_NODE");
        }
        return e;
    }

    private static PopWorker require(PopWorker w) {
        if (w == null) {
            throw new BusinessException(HbErrorCode.POP_NO_WORKER);
        }
        return w;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
