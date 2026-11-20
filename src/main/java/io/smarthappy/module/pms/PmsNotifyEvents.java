package io.smarthappy.module.pms;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.notify.NotifyEventDef;
import io.smarthappy.platform.notify.NotifyEventSource;

/**
 * PMS 알림 이벤트 (docs/48 R-4, docs/32 4.14) — 모두 즉시·기본 켜짐. 사람마다 끄기는 플랫폼 알림 설정(이벤트별 알림함·메일)이 한다.
 * 기한 임박·지연은 즉시가 아니라 하루 요약({@link PmsDigestSource}, DUE_DIGEST)에만 — 매일 같은 알림이 쌓이지 않게
 */
@Component
public class PmsNotifyEvents implements NotifyEventSource {

    /** 작업 담당이 됨(추가·담당 바뀜, 본인이 지정하면 없음) */
    public static final String PMS_TASK_ASSIGNED = "PMS_TASK_ASSIGNED";
    /** 내 산출물이 반려됨 */
    public static final String PMS_DELIV_REJECTED = "PMS_DELIV_REJECTED";
    /** 이슈 담당이 됨 */
    public static final String PMS_ISSUE_ASSIGNED = "PMS_ISSUE_ASSIGNED";
    /** 내가 올린(또는 내가 PM 인) 이슈가 해결됨 */
    public static final String PMS_ISSUE_RESOLVED = "PMS_ISSUE_RESOLVED";
    /** 게이트 결정(PM·게이트 담당) */
    public static final String PMS_GATE_DECIDED = "PMS_GATE_DECIDED";
    /** 일정변경 요청이 들어옴(PM — 결재로 가면 결재자가 아닌 PM 에게만) */
    public static final String PMS_CHG_REQUESTED = "PMS_CHG_REQUESTED";
    /** 내 일정변경 요청 결과(PM 바로 처리 — 결재로 간 요청은 결재 결과 알림 APR_DONE 이 대신) */
    public static final String PMS_CHG_RESULT = "PMS_CHG_RESULT";

    @Override
    public List<NotifyEventDef> events() {
        return List.of(new NotifyEventDef(PMS_TASK_ASSIGNED, true, 300), new NotifyEventDef(PMS_DELIV_REJECTED, true, 310),
                new NotifyEventDef(PMS_ISSUE_ASSIGNED, true, 320), new NotifyEventDef(PMS_ISSUE_RESOLVED, true, 330),
                new NotifyEventDef(PMS_GATE_DECIDED, true, 340), new NotifyEventDef(PMS_CHG_REQUESTED, true, 350),
                new NotifyEventDef(PMS_CHG_RESULT, true, 360));
    }
}
