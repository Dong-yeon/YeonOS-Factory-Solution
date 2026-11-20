package io.smarthappy.module.pms;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserRef;
import lombok.RequiredArgsConstructor;

/**
 * PMS 즉시 알림 보내기 (docs/32 4.14) — 업무 트랜잭션 안에서 부른다(롤백되면 알림도 없다).
 * 받는 사람에서 지금 누른 사람은 뺀다(자기가 한 일은 알리지 않음). 같은 일 다시 부름은 중복 키로 한 번만(플랫폼).
 * 알림 서비스·사용자 조회만 쓴다 — 결재 콜백(GateDecider·TaskChgApplier)에서 불려도 순환이 없다
 */
@Component
@RequiredArgsConstructor
class PmsNotifier {

    private final NotificationService notify;
    private final UserDirectory users;

    /** 작업 담당 지정 — ver = 저장 뒤 row_ver(같은 사람을 다시 지정해도 새 알림) */
    void taskAssigned(Long ownerId, long taskId, int ver, String projNo, String taskNm) {
        send(PmsNotifyEvents.PMS_TASK_ASSIGNED, recipients(ownerId), "T" + taskId + ":" + ownerId + ":" + ver,
                "notify.pms.taskAssigned", TaskService.PROG, Map.of("keyword", projNo), projNo, taskNm);
    }

    void delivRejected(Long ownerId, String submittedBy, long delivId, int ver, String projNo, String delivNm) {
        Set<Long> to = recipients(ownerId);
        if (to.isEmpty() && ownerId == null) {
            to = recipients(userId(submittedBy));
        }
        send(PmsNotifyEvents.PMS_DELIV_REJECTED, to, "D" + delivId + ":" + ver, "notify.pms.delivRejected",
                DeliverableService.PROG, Map.of("keyword", projNo), projNo, delivNm);
    }

    void issueAssigned(Long ownerId, long issueId, int ver, String issueNo, String title) {
        send(PmsNotifyEvents.PMS_ISSUE_ASSIGNED, recipients(ownerId), "I" + issueId + ":" + ownerId + ":" + ver,
                "notify.pms.issueAssigned", IssueService.PROG, Map.of("keyword", issueNo), issueNo, title);
    }

    /** 해결 — 올린 사람 + 프로젝트 PM */
    void issueResolved(String createdBy, long pmUserId, long issueId, int ver, String issueNo, String title) {
        send(PmsNotifyEvents.PMS_ISSUE_RESOLVED, recipients(userId(createdBy), pmUserId), "I" + issueId + ":R:" + ver,
                "notify.pms.issueResolved", IssueService.PROG, Map.of("keyword", issueNo), issueNo, title);
    }

    /** 게이트 결정 — PM + 게이트 작업 담당. decision = GO·CONDITIONAL·NO_GO(문구 키 끝) */
    void gateDecided(long pmUserId, Long gateOwnerId, long reviewId, String projNo, String gateNm, String decision) {
        String key = switch (decision) {
            case "GO" -> "notify.pms.gateGo";
            case "CONDITIONAL" -> "notify.pms.gateConditional";
            default -> "notify.pms.gateNoGo";
        };
        send(PmsNotifyEvents.PMS_GATE_DECIDED, recipients(pmUserId, gateOwnerId), "G" + reviewId, key, GateService.PROG,
                Map.of("keyword", projNo), projNo, gateNm);
    }

    void chgRequested(long pmUserId, long reqId, String projNo, String taskNm) {
        send(PmsNotifyEvents.PMS_CHG_REQUESTED, recipients(pmUserId), "C" + reqId, "notify.pms.chgRequested", TaskService.PROG,
                Map.of("chgReq", String.valueOf(reqId)), projNo, taskNm);
    }

    void chgResult(long requesterId, long reqId, boolean approved, String projNo, String taskNm) {
        send(PmsNotifyEvents.PMS_CHG_RESULT, recipients(requesterId), "C" + reqId + ":" + (approved ? "A" : "R"),
                approved ? "notify.pms.chgApproved" : "notify.pms.chgRejected", TaskService.PROG,
                Map.of("chgReq", String.valueOf(reqId)), projNo, taskNm);
    }

    // ------------------------------------------------------------------ 내부

    private void send(String event, Set<Long> to, String dedup, String titleKey, String prog, Map<String, String> params,
            String... args) {
        for (long u : to) {
            notify.send(NotifyMessage.of(event, u, dedup, titleKey, args).withLink(prog, params));
        }
    }

    /** 받는 사람 — null·중복·지금 누른 사람 빼고 */
    private static Set<Long> recipients(Long... ids) {
        Long me = CurrentUser.get().map(LoginUser::userId).orElse(null);
        Set<Long> out = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && !id.equals(me)) {
                out.add(id);
            }
        }
        return out;
    }

    private Long userId(String login) {
        UserRef r = login == null ? null : users.byLogin(login);
        return r == null ? null : r.userId();
    }
}
