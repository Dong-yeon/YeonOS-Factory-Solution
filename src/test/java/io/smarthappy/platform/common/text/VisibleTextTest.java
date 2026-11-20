package io.smarthappy.platform.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.FieldErrors.FieldError;

/**
 * 보이지 않는 글자 판정 (QA R4-a, docs/qa/2026-11-02_invisible-reason-proposal.md 6장 1단계) — DB 없이.
 * 이상 사용 B(경계값·형식): 형식 문자·구분자·제어·한글 채움 문자만으로 된 사유, "2자 이상" 을 보이지 않는 글자로 채우기, 이모지·ZWJ 가 낀 정상 글
 */
class VisibleTextTest {

    /** 화면에 아무것도 그리지 않는 글자 — 하나씩, 그리고 섞어서 */
    @ParameterizedTest
    @ValueSource(strings = {"\u200B", "\uFEFF", "\u00AD", "\u00A0", "\u3000", "\u2060", "\u200E", "\u202E", "\u180E", "\u2028",
            "\t", "\n", "\r", " ", "\u0085", "\u3164", "\u115F", "\u1160", "\uFFA0", "\u2800",
            "\u200B\u200B", "\u00A0\u00A0", " \u200B\u3000\uFEFF\u2060 "})
    void 보이지_않는_글자뿐이면_빈_값이다(String s) {
        assertThat(VisibleText.isBlank(s)).as(hex(s)).isTrue();
        assertThat(VisibleText.visibleLength(s)).isZero();
        assertThat(VisibleText.strip(s)).isNull();
        FieldErrors e = FieldErrors.create();
        VisibleText.reason(s, "reason", 1, 500, e);
        assertThat(codes(e)).containsExactly("REQUIRED");
        FieldErrors o = FieldErrors.create();
        assertThat(VisibleText.optional(s, "note", 500, o)).isNull();
        assertThat(o.isEmpty()).isTrue();
    }

    @Test
    void null_과_빈_글자() {
        assertThat(VisibleText.isBlank(null)).isTrue();
        assertThat(VisibleText.isBlank("")).isTrue();
        assertThat(VisibleText.strip(null)).isNull();
        assertThat(VisibleText.visibleLength(null)).isZero();
    }

    @Test
    void 보이는_글자_수는_코드_포인트로_센다() {
        assertThat(VisibleText.visibleLength("가")).isEqualTo(1);
        assertThat(VisibleText.visibleLength("오류")).isEqualTo(2);
        assertThat(VisibleText.visibleLength("\u200B오\u00A0류\uFEFF")).isEqualTo(2);
        assertThat(VisibleText.visibleLength("\uD83D\uDE00")).isEqualTo(1);              // 보조 평면 — UTF-16 길이 2
        assertThat(VisibleText.visibleLength("\uD842\uDFB7")).isEqualTo(1);    // 보조 평면 한자(U+20BB7)
        assertThat(VisibleText.visibleLength("\uD83D\uDC68\u200D\uD83D\uDC69")).isEqualTo(2);       // ZWJ 는 세지 않는다
    }

    @Test
    void 앞뒤만_걷어내고_가운데는_그대로_둔다() {
        assertThat(VisibleText.strip("\u200B\u00A0 설비 고장 \u3000\uFEFF")).isEqualTo("설비 고장");
        assertThat(VisibleText.strip("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67")).isEqualTo("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67");   // 가운데 ZWJ — 지우면 다른 그림
        assertThat(VisibleText.strip("a\u200Bb")).isEqualTo("a\u200Bb");
        assertThat(VisibleText.strip("첫 줄\n둘째 줄")).isEqualTo("첫 줄\n둘째 줄");
        assertThat(VisibleText.strip("\uD83D\uDE00\u200B")).isEqualTo("\uD83D\uDE00");                         // 끝 대리 쌍을 쪼개지 않는다
    }

    @Test
    void 두_글자_규칙은_보이는_글자_수로() {
        FieldErrors e = FieldErrors.create();
        assertThat(VisibleText.reason("오류", "reason", 2, 500, e)).isEqualTo("오류");
        assertThat(e.isEmpty()).isTrue();
        for (String bad : List.of("오", "\u200B\u200B", "\u00A0\u00A0", "오\u200B", "\u200B오\u00A0")) {
            FieldErrors b = FieldErrors.create();
            VisibleText.reason(bad, "reason", 2, 500, b);
            assertThat(codes(b)).as(hex(bad)).containsExactly("REQUIRED");
        }
    }

    @Test
    void 최소가_0_이하여도_보이는_글자_하나는_필요하다() {
        FieldErrors e = FieldErrors.create();
        VisibleText.reason("\u200B", "reason", 0, 500, e);
        assertThat(codes(e)).containsExactly("REQUIRED");
    }

    @Test
    void 제어_문자는_FORMAT_줄바꿈과_탭은_허용() {
        FieldErrors ok = FieldErrors.create();
        assertThat(VisibleText.reason("가\t나\r\n다", "reason", 1, 500, ok)).isEqualTo("가\t나\r\n다");
        assertThat(ok.isEmpty()).isTrue();
        for (String bad : List.of("가\u0000나", "가\u001B나", "가\u007F나", "가\u0085나")) {
            FieldErrors b = FieldErrors.create();
            VisibleText.reason(bad, "reason", 1, 500, b);
            assertThat(codes(b)).as(hex(bad)).containsExactly("FORMAT");
            FieldErrors o = FieldErrors.create();
            VisibleText.optional(bad, "note", 500, o);
            assertThat(codes(o)).containsExactly("FORMAT");
        }
    }

    @Test
    void 최대_길이는_정리한_뒤_코드_포인트로_경계_500_501() {
        FieldErrors e = FieldErrors.create();
        VisibleText.reason("\u200B" + "가".repeat(500) + " ", "reason", 1, 500, e);
        assertThat(e.isEmpty()).isTrue();
        FieldErrors emoji = FieldErrors.create();
        VisibleText.reason("\uD83D\uDE00".repeat(500), "reason", 1, 500, emoji);   // UTF-16 1000 — varchar(500) 에는 들어간다
        assertThat(emoji.isEmpty()).isTrue();
        FieldErrors over = FieldErrors.create();
        VisibleText.reason("가".repeat(501), "reason", 1, 500, over);
        assertThat(over.list()).singleElement().satisfies(x -> {
            assertThat(x.code()).isEqualTo("TOO_LONG");
            assertThat(x.args()).containsExactly(500);
        });
    }

    private static List<String> codes(FieldErrors e) {
        return e.list().stream().map(FieldError::code).toList();
    }

    private static String hex(String s) {
        return s.codePoints().mapToObj(c -> String.format("U+%04X", c)).toList().toString();
    }
}
