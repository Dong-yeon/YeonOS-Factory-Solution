package io.smarthappy.module.pms;

import static io.smarthappy.module.pms.PmsInputs.fieldError;

import java.util.List;

import io.smarthappy.module.pms.PmsDtos.TaskRow;

/**
 * 프로젝트 종료 조건(11 D13) — 열린 잎 작업 없음, 필수 산출물 승인·면제, 이슈 모두 종료. 바로 종료·종료 상신·종료 승인(docs/32 4.16 P-4)이 같이 쓴다.
 * 결재 콜백(ProjApprovalDocType)도 부르므로 서비스에 기대지 않고 매퍼만 받는다(순환 방지). 미달이면 칸 오류 status
 */
final class ProjClose {

    private ProjClose() {
    }

    static void check(PmsMapper mapper, GateMapper gm, long projId) {
        List<TaskRow> all = mapper.findTasks(projId);
        if (all.isEmpty()) {
            throw fieldError("status", "PMS_NO_TASKS");
        }
        long open = all.stream().filter(t -> "N".equals(t.getIsSummary()) && !"DONE".equals(t.getStatus())
                && !"CANCELLED".equals(t.getStatus())).count();
        if (open > 0) {
            throw fieldError("status", "PMS_OPEN_TASKS", open);
        }
        int deliv = gm.countOpenRequired(projId);
        if (deliv > 0) {
            throw fieldError("status", "PMS_OPEN_DELIVERABLES", deliv);
        }
        int issues = gm.countOpenIssues(projId);
        if (issues > 0) {
            throw fieldError("status", "PMS_OPEN_ISSUES", issues);
        }
    }
}
