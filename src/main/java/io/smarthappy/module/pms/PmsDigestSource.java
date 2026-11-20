package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.pms.PortfolioDtos.DigestRow;
import io.smarthappy.platform.notify.NotifyDigestSource;
import lombok.RequiredArgsConstructor;

/**
 * PMS 하루 요약 줄 (docs/32 4.14, docs/48 R-4) — 기한 임박·지연은 즉시 알림 없이 여기만(매일 같은 알림이 즉시로 쌓이지 않게).
 * 내 할 일(PMS 홈)과 같은 조건: 내 잎 작업(시작 전·진행 중) + 내 산출물(시작 전·반려), 열린 프로젝트.
 * 지연 = 기한 &lt; 지연 기준일(PmsDays — 회사 휴일 반영), 임박 = 기준일 이상 ~ 오늘 + {@value #SOON_DAYS}일(달력일). 줄을 누르면 PMS 홈
 */
@Component
@RequiredArgsConstructor
public class PmsDigestSource implements NotifyDigestSource {

    /** 하루 요약의 임박 범위(달력일) — 사용자 결정 "2일 안"(R-4). 홈 화면 임박은 정책 PMS_DUE_SOON_DAYS 그대로 */
    static final int SOON_DAYS = 2;
    static final String LATE = "notify.digest.pmsLate";
    static final String SOON = "notify.digest.pmsDueSoon";

    private final PortfolioMapper pm;
    private final PmsDays days;

    @Override
    public List<Due> due(LocalDate today) {
        List<Due> out = new ArrayList<>();
        for (DigestRow r : pm.digestCounts(days.lateRef(today), today.plusDays(SOON_DAYS))) {
            if (r.getLateCnt() > 0) {
                out.add(new Due(r.getUserId(), LATE, r.getLateCnt(), PortfolioService.PROG));
            }
            if (r.getSoonCnt() > 0) {
                out.add(new Due(r.getUserId(), SOON, r.getSoonCnt(), PortfolioService.PROG));
            }
        }
        return out;
    }
}
