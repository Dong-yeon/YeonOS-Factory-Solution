package io.smarthappy.module.plm;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 기한 지남 알림 (docs/39 26장) — 홈 카드와 같은 조건으로 지시 담당에게 사내 알림(구독에 메일을 켰으면 메일도, docs/36 18.3 M1).
 * 처음 지났을 때 한 번: 중복 키 = 대상 + 기준 날짜(유효일·응답 기한) — 반려 → 재상신으로 유효일이 바뀌어 다시 지나면 다시 알린다
 * (승인 뒤·통지 뒤에는 날짜가 고정이라 그 사이 바뀌지 않는다). 회사 범위 안(TenantRunner)에서만 부른다.
 */
@Service
@RequiredArgsConstructor
public class PlmOverdueNotifier {

    /** 한 번에 보는 대상 수 — 넘는 것은 다음 실행(매시)에 */
    static final int LIMIT = 500;

    private final PlmHomeMapper mapper;
    private final NotificationService notify;
    private final BizClock clock;

    /** 응답 기한 임박 = 오늘부터 며칠 뒤까지(35장 — 상수로 시작, 정책으로 빼려면 그때 마이그레이션) */
    static final int DUE_SOON_DAYS = 2;

    /** soon = 응답 기한 임박 알림 수(35장) */
    public record Sent(int ecos, int pcns, int soon) {
    }

    @Transactional
    public Sent run() {
        java.time.LocalDate today = clock.today();
        int ecos = 0;
        for (PlmHomeMapper.OverdueEco e : mapper.overdueEcos(today, LIMIT)) {
            NotificationService.Result r = notify.send(NotifyMessage.of(PlmNotifyEvents.PLM_ECO_OVERDUE, e.ownerId(),
                    "ECO:" + e.ecoId() + ":" + e.effectiveDate(), "notify.plm.ecoOverdue", e.ecoNo(), e.title(), e.effectiveDate().toString())
                    .withLink(EcoService.PROG, Map.of("keyword", e.ecoNo())));
            ecos += r == NotificationService.Result.SENT ? 1 : 0;
        }
        int pcns = 0;
        for (PlmHomeMapper.OverduePcn n : mapper.overduePcns(today, LIMIT)) {
            NotificationService.Result r = notify.send(NotifyMessage.of(PlmNotifyEvents.PLM_PCN_OVERDUE, n.ownerId(),
                    "PCN:" + n.pcnId() + ":" + n.responseDue(), "notify.plm.pcnOverdue", n.pcnNo(), n.custCd(), n.responseDue().toString(),
                    n.ecoNo()).withLink(EcoService.PROG, Map.of("keyword", n.ecoNo())));
            pcns += r == NotificationService.Result.SENT ? 1 : 0;
        }
        // 기한 임박(35장) — 지남 알림 앞 단계, 같은 통지·기한에 한 번(중복 키는 지남과 따로)
        int soon = 0;
        for (PlmHomeMapper.OverduePcn n : mapper.dueSoonPcns(today, today.plusDays(DUE_SOON_DAYS), LIMIT)) {
            NotificationService.Result r = notify.send(NotifyMessage.of(PlmNotifyEvents.PLM_PCN_DUE_SOON, n.ownerId(),
                    "PCN-SOON:" + n.pcnId() + ":" + n.responseDue(), "notify.plm.pcnDueSoon", n.pcnNo(), n.custCd(), n.responseDue().toString(),
                    n.ecoNo()).withLink(EcoService.PROG, Map.of("keyword", n.ecoNo())));
            soon += r == NotificationService.Result.SENT ? 1 : 0;
        }
        return new Sent(ecos, pcns, soon);
    }
}
