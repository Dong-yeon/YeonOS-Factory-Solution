package io.smarthappy.module.home;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.TodoView;
import lombok.RequiredArgsConstructor;

/**
 * 결재 대기 — 개인 카드(R2): 내 차례인 결재선만(ApprovalService.todo). 결재는 플랫폼 기능이라 플랫폼이 모듈에 의존하지 않게
 * 홈 패키지에 둔다(docs/37 H6). 결재함 화면이 생기면 progCd 만 바꾼다(H3).
 */
@Component
@RequiredArgsConstructor
public class ApprovalTodoCard implements HomeCardProvider {

    /** 이만큼 넘게 기다린 결재는 따로 센다 */
    static final Duration LONG_WAIT = Duration.ofDays(3);

    private final ApprovalService approvals;

    @Override
    public String code() {
        return "apr-todo";
    }

    @Override
    public String titleKey() {
        return "home.card.aprTodo";
    }

    @Override
    /**
     * 결재함(시스템관리 3단계, 개인 화면 self_service_yn = 'Y' → 로그인 사용자 모두 조회 권한). 5단계 H19 — 링크만 생기고 보이는 범위는 그대로,
     * 숫자는 여전히 내 차례(R2). 숫자 링크는 없다: 결재함 기본 탭이 "내 차례"(기간 없음)라 화면 열기 = 카드 목록
     */
    public String progCd() {
        return "sys-approval";
    }

    @Override
    public int sortNo() {
        return 10;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        List<TodoView> todo = approvals.todo();
        long longWait = todo.stream().filter(t -> t.requestedAt().toInstant().isBefore(ctx.now().toInstant().minus(LONG_WAIT)))
                .count();
        List<Item> items = todo.stream().limit(HomeService.MAX_ITEMS).map(t -> new Item(t.title(),
                ctx.msg("home.card.aprTodo.item", t.requesterNm(),
                        t.requestedAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString()),
                Tone.NEUTRAL)).toList();
        return new HomeCard(todo.size(), HomeCard.toneIf(todo.size(), Tone.WARN),
                List.of(new Fact(ctx.msg("home.card.aprTodo.longWait", LONG_WAIT.toDays()), longWait, HomeCard.toneIf(longWait, Tone.WARN))),
                items);
    }
}
