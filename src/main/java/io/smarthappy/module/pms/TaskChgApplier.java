package io.smarthappy.module.pms;

import org.springframework.stereotype.Component;

import io.smarthappy.module.pms.PmsDtos.BaselineRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.TaskChgDtos.ChgRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 일정변경 요청 결정 적용 — 결재 끝(같은 트랜잭션, {@link TaskChgApprovalDocType})과 PM 바로 처리(TaskChgService)가 같은 함수를 쓴다.
 * 매퍼만 쓴다: 문서 유형 빈 → 서비스 → ApprovalService → 문서 유형 빈 순환을 만들지 않는다(docs/29_P3 13.5-10 교훈).
 * 승인 = 기존 "사유 있는 계획 변경" 경로(계획일 + 기준선 있으면 이력 REQ, 기준선 불변) + 요약 다시 계산
 */
@Component
@RequiredArgsConstructor
class TaskChgApplier {

    private final PmsMapper mapper;
    private final TaskChgMapper cm;

    /** 잠금 순서는 어디서나 프로젝트 머리 → 요청 행(작업 편집·게이트와 같게 — 교착 방지). 잠근 뒤 다시 읽은 요청 */
    ChgRow lock(long reqId) {
        ChgRow r = cm.findReq(reqId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        mapper.lockProject(r.getProjId());
        cm.lockReq(reqId);
        return cm.findReq(reqId);
    }

    /**
     * lock() 뒤에 부른다. 프로젝트가 닫혔으면 409 PMS_PROJECT_CLOSED, 작업이 없어졌거나 요청 뒤 바뀌었으면(row_ver) 409 CONFLICT
     */
    void approve(ChgRow r, String note, String by) {
        String projStatus = mapper.lockProject(r.getProjId());
        if (projStatus == null || !TaskService.OPEN.contains(projStatus)) {
            throw new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
        }
        TaskRow t = mapper.findTask(r.getTaskId());
        if (t == null || t.getProjId() != r.getProjId() || "Y".equals(t.getIsSummary()) || t.getRowVer() != r.getTaskRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.updateTaskPlan(t.getTaskId(), r.getNewStart(), r.getNewEnd(), by);
        BaselineRow bl = mapper.findLatestBaseline(r.getProjId());
        if (bl != null) {
            mapper.insertPlanChange(r.getProjId(), t.getTaskId(), bl.getBaselineNo(), t, r.getNewStart(), r.getNewEnd(), "REQ",
                    r.getReason(), by);
        }
        TaskService.rollup(mapper, r.getProjId(), by);
        close(r.getReqId(), "APPROVED", note, by);
    }

    /** 열린 요청을 끝낸다 — 이미 끝났으면 409 */
    void close(long reqId, String status, String note, String by) {
        if (cm.finish(reqId, status, note, by) == 0) {
            throw new BusinessException(HbErrorCode.PMS_CHG_STATE);
        }
    }
}
