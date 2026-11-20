package io.smarthappy.module.wms.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import io.smarthappy.module.wms.scan.ScanKeyResolver.BareAs;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Context;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Reason;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Result;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Type;

/** 스캔 키 규칙 (docs/30 W1, D7) — 순수 함수 표 기반 시험. GS1 예제 번호는 손으로 검사 숫자를 계산한 값 */
class ScanKeyResolverTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    static final Context P1 = new Context("P1", BareAs.LOC, TODAY);
    static final String GS = "\u001d";
    static final String GTIN = "09501101530003";       // 검사 숫자 3
    static final String SSCC = "106141411234567897";   // 검사 숫자 7

    static Result r(String text) {
        return ScanKeyResolver.resolve(text, P1);
    }

    @ParameterizedTest(name = "[{index}] {0} → {1} {2}:{3}")
    @CsvSource(delimiter = '|', value = {
            "LOC:P1:A-01-03       | LOC  | P1 | A-01-03",
            "loc:p1:a-01-03       | LOC  | P1 | A-01-03",       // 소문자 스캐너 설정
            "ITEM:84910-2S000     | ITEM |    | 84910-2S000",
            "ITEM:ab.123/4        | ITEM |    | AB.123/4",
            "LOT:R2610-0001a      | LOT  |    | R2610-0001a",   // LOT 은 대소문자 유지
            "PLT:PL#0001          | PLT  |    | PL#0001",
            "A-01-03              | LOC  | P1 | A-01-03",       // 접두어 없음 → 정책 LOC, 지금 공장
            "0112345              | LOC  | P1 | 0112345"        // 숫자만 있는 코드는 GS1 로 읽지 않는다
    })
    void 정상_판별(String text, Type type, String plant, String key) {
        Result res = r(text);
        assertThat(res.type()).isEqualTo(type);
        assertThat(res.key()).isEqualTo(key);
        assertThat(res.plantCd()).isEqualTo(plant == null || plant.isBlank() ? null : plant);
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "LOC:P2:A-01          | OTHER_PLANT",     // 다른 공장 로케이션 라벨
            "LOC:A-01             | FORMAT",          // 공장 빠짐
            "LOC:P1:              | FORMAT",
            "LOC:P1:A 01          | FORMAT",          // 공백
            "LOC:P1:-A01          | FORMAT",          // 첫 글자 기호
            "ITEM:                | FORMAT",
            "LOT:로트1             | FORMAT",          // 한글
            "가나다                | FORMAT",          // 접두어 없음 + 형식 밖
            "LOC:P1:A01:extra     | FORMAT"
    })
    void 형식_오류(String text, Reason reason) {
        Result res = r(text);
        assertThat(res.type()).isEqualTo(Type.UNKNOWN);
        assertThat(res.reason()).isEqualTo(reason);
    }

    @Test
    void 빈값_길이_제어문자() {
        assertThat(r(null).reason()).isEqualTo(Reason.EMPTY);
        assertThat(r("   \r\n").reason()).isEqualTo(Reason.EMPTY);
        assertThat(r("A".repeat(ScanKeyResolver.MAX_LEN + 1)).reason()).isEqualTo(Reason.TOO_LONG);
        assertThat(r("A".repeat(30)).ok()).isTrue();                               // 노드 코드 최대 30자
        assertThat(r("A".repeat(31)).reason()).isEqualTo(Reason.FORMAT);
        assertThat(r("LOC:P1:A\u0000B").reason()).isEqualTo(Reason.BAD_CHAR);       // NUL 섞인 스캔
        assertThat(r("LOC:P1:A\u0007B").reason()).isEqualTo(Reason.BAD_CHAR);
        assertThat(r("  LOC:P1:A-01 \t\r\n").key()).isEqualTo("A-01");                   // 웨지가 붙인 공백·줄바꿈
        assertThat(r(" LOC:P1:A-01　").key()).isEqualTo("A-01");          // 붙여넣기 공백
    }

    @Test
    void 접두어_없는_코드는_정책대로() {
        assertThat(ScanKeyResolver.resolve("84910-2S000", new Context("P1", BareAs.ITEM, TODAY)).type()).isEqualTo(Type.ITEM);
        Result none = ScanKeyResolver.resolve("A-01", new Context("P1", BareAs.NONE, TODAY));
        assertThat(none.reason()).isEqualTo(Reason.BARE_NOT_ALLOWED);
        assertThat(ScanKeyResolver.resolve("A-01", new Context(null, BareAs.LOC, TODAY)).reason()).isEqualTo(Reason.NO_PLANT);
        assertThat(r("A-01").bare()).isTrue();
        assertThat(r("LOC:P1:A-01").bare()).isFalse();
        // 접두어가 있으면 정책과 무관
        assertThat(ScanKeyResolver.resolve("LOC:P1:A-01", new Context("P1", BareAs.NONE, TODAY)).type()).isEqualTo(Type.LOC);
    }

    @Test
    void GS1_128_원문_고정길이_가변길이_GS구분() {
        Result res = r("]C1" + "01" + GTIN + "17" + "271231" + "10" + "AB-12" + GS + "37" + "120");
        assertThat(res.type()).isEqualTo(Type.GS1);
        assertThat(res.key()).isEqualTo(GTIN);
        assertThat(res.gs1().gtin()).isEqualTo(GTIN);
        assertThat(res.gs1().expiry()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(res.gs1().batch()).isEqualTo("AB-12");
        assertThat(res.gs1().qty()).isEqualByComparingTo(new BigDecimal("120"));
        // 심볼 식별자 없이 GS 만 있어도 GS1, 마지막 가변 값은 끝까지
        Result noSym = r("01" + GTIN + "10" + "LOT9" + GS + "21" + "SN001");
        assertThat(noSym.gs1().batch()).isEqualTo("LOT9");
        assertThat(noSym.gs1().serial()).isEqualTo("SN001");
        // SSCC 만 → 키는 SSCC
        Result sscc = r("]C1" + "00" + SSCC);
        assertThat(sscc.key()).isEqualTo(SSCC);
        assertThat(sscc.gs1().sscc()).isEqualTo(SSCC);
    }

    @Test
    void GS1_괄호_형식() {
        Result res = r("(01)" + GTIN + "(17)270200(10)A1(30)5");
        assertThat(res.type()).isEqualTo(Type.GS1);
        assertThat(res.gs1().expiry()).isEqualTo(LocalDate.of(2027, 2, 28));   // DD=00 → 그 달 마지막 날
        assertThat(res.gs1().qty()).isEqualByComparingTo("5");
        assertThat(r("(01)" + GTIN + "x(10)A1").reason()).isEqualTo(Reason.GS1_LENGTH);   // 괄호 사이 잡글자 → 01 길이
        assertThat(r("(10)A1)").reason()).isEqualTo(Reason.FORMAT);
    }

    @Test
    void GS1_오류() {
        assertThat(r("]C1" + "01" + "09501101530004").reason()).isEqualTo(Reason.GS1_CHECK_DIGIT);
        assertThat(r("]C1" + "00" + "106141411234567890").reason()).isEqualTo(Reason.GS1_CHECK_DIGIT);
        assertThat(r("]C1" + "01" + "0950110153").reason()).isEqualTo(Reason.GS1_LENGTH);          // 잘린 바코드
        assertThat(r("]C1" + "99" + "ABC").reason()).isEqualTo(Reason.GS1_UNKNOWN_AI);
        assertThat(r("]C1" + "10" + "A".repeat(21)).reason()).isEqualTo(Reason.GS1_LENGTH);        // 배치 20자 초과
        assertThat(r("]C1" + "17" + "271332").reason()).isEqualTo(Reason.GS1_DATE);                // 13월
        assertThat(r("]C1" + "17" + "270230").reason()).isEqualTo(Reason.GS1_DATE);                // 2월 30일
        assertThat(r("]C1" + "17" + "27AB01").reason()).isEqualTo(Reason.GS1_NUMERIC);
        assertThat(r("]C1" + "30" + "1.5").reason()).isEqualTo(Reason.GS1_NUMERIC);
        assertThat(r("]C1" + "10" + "A" + GS + "10" + "B").reason()).isEqualTo(Reason.GS1_DUPLICATE_AI);
        assertThat(r("]C1" + "30" + "5" + GS + "37" + "6").reason()).isEqualTo(Reason.GS1_DUPLICATE_AI);  // 수량 둘이 다름
        assertThat(r("]C1" + "10" + "가나").reason()).isEqualTo(Reason.BAD_CHAR);
        assertThat(r("]C1").reason()).isEqualTo(Reason.EMPTY);
        assertThat(r("]C1" + "0").reason()).isEqualTo(Reason.GS1_LENGTH);
        assertThat(r("]C1" + "10" + GS).reason()).isEqualTo(Reason.GS1_LENGTH);                  // 빈 가변 값
    }

    @Test
    void GS1_날짜_세기_창() {
        // 오늘 2026 기준 -49 ~ +50: 76 → 2076, 77 → 1977
        assertThat(ScanKeyResolver.gs1Date("761231", TODAY).getYear()).isEqualTo(2076);
        assertThat(ScanKeyResolver.gs1Date("770101", TODAY).getYear()).isEqualTo(1977);
        assertThatThrownBy(() -> ScanKeyResolver.gs1Date("260229", TODAY)).isInstanceOf(java.time.DateTimeException.class);
        assertThat(ScanKeyResolver.gs1Date("280229", TODAY)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(ScanKeyResolver.gs1Date("280229", TODAY)).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void 검사숫자() {
        assertThat(ScanKeyResolver.checkDigitOk(GTIN)).isTrue();
        assertThat(ScanKeyResolver.checkDigitOk(SSCC)).isTrue();
        assertThat(ScanKeyResolver.checkDigitOk("00000000000000")).isTrue();
    }
}
