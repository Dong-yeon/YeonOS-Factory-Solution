package io.smarthappy.platform.notify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.time.BizClock;
import lombok.extern.slf4j.Slf4j;

/**
 * 하루 요약 DUE_DIGEST (docs/36 11.3). 회사 시간대 {@value #SEND_HOUR}시 뒤 첫 실행에 사람마다 한 행 —
 * 중복 키 = 회사 날짜라 매시 다시 돌아도 하루 한 번. 원천({@link NotifyDigestSource}) 하나가 실패해도 나머지는 모은다.
 */
@Slf4j
@Service
public class NotifyDigestService {

    static final int SEND_HOUR = 7;
    static final String TITLE = "notify.digest.title";

    private final NotificationService notify;
    private final BizClock clock;
    private final ObjectProvider<NotifyDigestSource> sources;

    public NotifyDigestService(NotificationService notify, BizClock clock, ObjectProvider<NotifyDigestSource> sources) {
        this.notify = notify;
        this.clock = clock;
        this.sources = sources;
    }

    /** 회사 범위 트랜잭션 안(정기 작업). 보낸 사람 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int runForCompany() {
        if (clock.now().getHour() < SEND_HOUR) {
            return 0;
        }
        LocalDate today = clock.today();
        Map<Long, List<NotifyMessage.Line>> byUser = new LinkedHashMap<>();
        sources.orderedStream().forEach(s -> {
            for (NotifyDigestSource.Due d : s.due(today)) {
                if (d.count() > 0) {
                    byUser.computeIfAbsent(d.userId(), k -> new ArrayList<>())
                            .add(new NotifyMessage.Line(d.labelKey(), d.count(), d.progCd()));
                }
            }
        });
        int sent = 0;
        for (Map.Entry<Long, List<NotifyMessage.Line>> e : byUser.entrySet()) {
            List<NotifyMessage.Line> lines = e.getValue().size() > NotificationService.MAX_LINES
                    ? e.getValue().subList(0, NotificationService.MAX_LINES) : e.getValue();
            long total = lines.stream().mapToLong(NotifyMessage.Line::count).sum();
            NotifyMessage m = NotifyMessage.of(PlatformNotifyEvents.DUE_DIGEST, e.getKey(), today.toString(), TITLE,
                    String.valueOf(total)).withLines(List.copyOf(lines));
            if (notify.send(m) == NotificationService.Result.SENT) {
                sent++;
            }
        }
        return sent;
    }
}
