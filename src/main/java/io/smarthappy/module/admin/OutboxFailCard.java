package io.smarthappy.module.admin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.admin.OutboxDtos.TopicCount;
import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCard.Fact;
import io.smarthappy.module.home.HomeCard.Item;
import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.home.HomeCardProvider;
import io.smarthappy.module.home.HomeService;
import lombok.RequiredArgsConstructor;

/**
 * 홈 카드 "연계 실패"(docs/37 507행 "아웃박스 실패", docs/36 19장 O9). 숫자 = FAIL 만 — 수동 종결(CLOSED_MANUAL)·취소(CANCELED)는
 * 세지 않는다(O7). 종결 결재가 진행 중인 FAIL 도 주 숫자·주제·목록에서 빼고 보조 숫자 "종결 결재 대기"로 따로(O-10 — 재시도·종결이
 * 막혀 있고 결재 카드와 겹쳐 세지 않게). 보조 숫자 = 많은 주제 3개, 목록 = 오래된 FAIL 5건. 숫자·항목 링크 없음 — 화면 기간 조건(기본 최근 7일, 최대 92일)이
 * 오래된 FAIL 과 맞지 않을 수 있다(H27: 일부만 맞는 조건이면 화면만 연다). 볼 수 있는 사람 = 화면 sys-outbox 조회 권한
 */
@Component
@RequiredArgsConstructor
public class OutboxFailCard implements HomeCardProvider {

    static final int MAX_FACTS = 3;

    private final OutboxMapper mapper;

    @Override
    public String code() {
        return "outbox-fail";
    }

    @Override
    public String titleKey() {
        return "home.card.outboxFail";
    }

    @Override
    public String progCd() {
        return OutboxAdminService.PROG;
    }

    @Override
    public int sortNo() {
        return 955;
    }

    @Override
    public HomeCard load(HomeCardContext ctx) {
        List<TopicCount> byTopic = mapper.failByTopic();
        long total = byTopic.stream().mapToLong(TopicCount::cnt).sum();
        List<Fact> facts = new ArrayList<>(byTopic.stream().limit(MAX_FACTS)
                .map(t -> new Fact(ctx.msg("outboxTopic." + t.topic()), t.cnt(), Tone.WARN)).toList());
        long closePending = mapper.failClosePending();
        if (closePending > 0) {
            facts.add(new Fact(ctx.msg("home.card.outboxFail.closePending"), closePending, Tone.INFO));
        }
        List<Item> items = mapper.failTop(HomeService.MAX_ITEMS).stream()
                .map(r -> new Item(ctx.msg("outboxTopic." + r.topic()) + (r.bizKey() == null ? "" : " · " + r.bizKey()),
                        ctx.msg("home.card.outboxFail.item", r.createdAt().atZoneSameInstant(ctx.now().getZone()).toLocalDate().toString(),
                                r.lastError() == null ? "-" : r.lastError()),
                        Tone.WARN))
                .toList();
        return new HomeCard(total, HomeCard.toneIf(total, Tone.WARN), facts, items);
    }
}
