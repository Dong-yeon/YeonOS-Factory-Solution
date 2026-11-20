package io.smarthappy.module.pms;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import lombok.RequiredArgsConstructor;

/**
 * 처리할 일정변경 요청 (docs/32 4.24) — 로그인 사용자가 PM 인 열린 프로젝트(작성·진행·보류)에서 담당자가 낸 PM 처리 대기(REQUESTED) 요청.
 * 결재 중(IN_APPROVAL)은 결재함 카드 몫, 내가 낸 요청은 빼고(내가 처리할 것이 아니다), 끝난 요청·닫힌 프로젝트는 세지 않는다(R8).
 * APPROVE 권한자도 처리할 수 있지만 그 사람에게 회사 전체 요청을 세어 주지 않는다 — 카드는 "내 프로젝트" 기준(R9: 사람별 집계 아님).
 * 항목 링크 = pms-project ?q.chgReq= (열린 탭이어도 그 요청을 연다, 4.23). 숫자 링크는 없다 — 요청만 모은 목록 화면이 없다(docs/37 16.2)
 */
@Component
@RequiredArgsConstructor
public class PmsChgTodoCard implements HomeCardProvider {

    private final TaskChgMapper mapper;

    @Override
    public String code() {
        return "pms-chg-todo";
    }

    @Override
    public String titleKey() {
        return "home.card.pmsChgTodo";
    }

    @Override
    public String progCd() {
        return TaskService.PROG;
    }

    @Override
    public int sortNo() {
        return 802;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        long user = ctx.userId();
        long cnt = mapper.countPmTodo(user);
        List<Item> items = mapper.findPmTodo(user, HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(r.getProjNo(), ctx.msg("home.card.pmsChgTodo.item", r.getTaskNm() == null ? "-" : r.getTaskNm(),
                        r.getNewStart().toString(), r.getNewEnd().toString(), r.getRequesterNm()), Tone.WARN,
                        Map.of("chgReq", String.valueOf(r.getReqId()))))
                .toList();
        Tone tone = HomeCard.toneIf(cnt, Tone.WARN);
        return new HomeCard(cnt, tone, List.of(new Fact(ctx.msg("home.card.pmsChgTodo.waiting"), cnt, tone)), items, null);
    }
}
