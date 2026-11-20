package io.smarthappy.module.home;

import java.util.List;
import java.util.Map;

/**
 * 카드 내용 (docs/37 4.1) — 행동이 필요한 건수 + 상태 + 보조 숫자(≤3) + 맨 앞 항목(≤5).
 * 글자(항목 제목·설명)는 화면이 textContent 로만 넣는다.
 * link(4단계, docs/37 16장) = 카드 숫자를 눌렀을 때 그 숫자의 목록을 여는 조회 조건(항목 링크와 같은 형식).
 * <b>화면이 그 조건을 모두 받을 수 있을 때만</b> 채운다 — 일부만 맞는 조건이면 숫자와 목록 건수가 달라져 null(화면만 열기)로 둔다
 */
public record HomeCard(long count, Tone tone, List<Fact> facts, List<Item> items, Map<String, String> link) {

    /** 상태 — 정상은 회색(NEUTRAL), 이상만 경고·알람색. 초록(성공) 톤은 두지 않는다(docs/12 2.1 ISA-101) */
    public enum Tone {
        NEUTRAL, INFO, WARN, ALERT
    }

    /** 보조 숫자 — 이름(번역된 글자)·값·상태 */
    public record Fact(String label, long value, Tone tone) {
    }

    /**
     * 항목 한 줄 — 제목(업무 번호 등)·설명·상태 + 링크 조건(2단계, docs/37 14.2): 카드 화면의 조회 조건 이름 → 값.
     * 화면을 열면 조회 패널이 그 조건으로 채우고 조회한다. null = 화면만 연다(카드의 "화면 열기" 와 같음)
     */
    public record Item(String title, String detail, Tone tone, Map<String, String> link) {

        public Item(String title, String detail, Tone tone) {
            this(title, detail, tone, null);
        }

        public Item {
            link = link == null ? null : Map.copyOf(link);
        }
    }

    /** 카드 수준 링크 없음(화면만 열기) — 1~3단계 카드 */
    public HomeCard(long count, Tone tone, List<Fact> facts, List<Item> items) {
        this(count, tone, facts, items, null);
    }

    public HomeCard {
        tone = tone == null ? Tone.NEUTRAL : tone;
        facts = facts == null ? List.of() : List.copyOf(facts);
        items = items == null ? List.of() : List.copyOf(items);
        link = link == null ? null : Map.copyOf(link);
    }

    /** 숫자가 있으면 그 상태, 0 이면 NEUTRAL */
    public static Tone toneIf(long n, Tone whenPositive) {
        return n > 0 ? whenPositive : Tone.NEUTRAL;
    }

    /** 가장 높은 상태 */
    public static Tone max(Tone... tones) {
        Tone m = Tone.NEUTRAL;
        for (Tone t : tones) {
            if (t != null && t.ordinal() > m.ordinal()) {
                m = t;
            }
        }
        return m;
    }
}
