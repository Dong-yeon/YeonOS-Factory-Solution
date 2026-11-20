package io.smarthappy.module.wms.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.smarthappy.module.wms.label.PrinterAddress.AddressException;
import io.smarthappy.module.wms.label.PrinterAddress.Problem;
import io.smarthappy.module.wms.label.ZplRenderer.Issue;
import io.smarthappy.module.wms.label.ZplRenderer.RenderException;

/** ZPL 템플릿 검사·렌더 + 프린터 주소 검사 (docs/30 W2) — 순수 함수 */
class ZplRendererTest {

    static final Set<String> LOC = LabelFields.allowed("LOC");

    static List<ZplRenderer.Problem> problems(String body) {
        return ZplRenderer.validate(body, LOC).stream().map(Issue::problem).toList();
    }

    @Test
    void 견본_템플릿은_모든_유형이_검사를_통과() {
        for (String t : LabelFields.TYPES) {
            assertThat(ZplRenderer.validate(LabelFields.sampleBody(t), LabelFields.allowed(t))).as(t).isEmpty();
            assertThat(ZplRenderer.render(LabelFields.sampleBody(t), LabelFields.sampleValues(t), LabelFields.allowed(t)))
                    .startsWith("^XA").endsWith("^XZ");
        }
    }

    @Test
    void 렌더_값이_든_필드에만_FH_와_이스케이프() {
        String body = "^XA^FO10,10^FD{{locCd}}^FS^FO10,40^FDSTATIC_TEXT^FS^FO10,80^FDLOC_{{path}}^FS^XZ";
        Map<String, String> v = new LinkedHashMap<>();
        v.put("locCd", "A-01");
        v.put("path", "P1/W_1");
        String out = ZplRenderer.render(body, v, LOC);
        assertThat(out).isEqualTo("^XA^FO10,10^FH_^FDA-01^FS^FO10,40^FDSTATIC_TEXT^FS^FO10,80^FH_^FDLOC_5FP1/W_5F1^FS^XZ");
    }

    @Test
    void 값으로_ZPL_명령을_끼워_넣을_수_없다() {
        String body = "^XA^FO10,10^FD{{locNm}}^FS^XZ";
        String evil = "X^FS^XZ^XA~JR^JUS^FO0,0^GB800,800,800^FS";       // 필드 닫고 리셋·설정 저장·검은 사각형
        String out = ZplRenderer.render(body, Map.of("locNm", evil), LOC);
        assertThat(out).isEqualTo("^XA^FO10,10^FH_^FDX_5EFS_5EXZ_5EXA_7EJR_5EJUS_5EFO0,0_5EGB800,800,800_5EFS^FS^XZ");
        // 렌더 결과에 ^ 는 템플릿 것만 남는다
        assertThat(out.chars().filter(c -> c == '^').count()).isEqualTo(6);
        assertThat(out).doesNotContain("~");
    }

    @Test
    void 한글_값은_그대로_없는_값은_빈칸() {
        String out = ZplRenderer.render("^XA^CI28^FD{{locNm}}|{{path}}^FS^XZ", Map.of("locNm", "원자재 창고"), LOC);
        assertThat(out).isEqualTo("^XA^CI28^FH_^FD원자재 창고|^FS^XZ");
    }

    @Test
    void 값_검사() {
        assertThat(catchThrowableOfType(RenderException.class,
                () -> ZplRenderer.render("^XA^FD{{locCd}}^FS^XZ", Map.of("secret", "x"), LOC)).code()).isEqualTo("UNKNOWN_FIELD");
        assertThat(catchThrowableOfType(RenderException.class,
                () -> ZplRenderer.render("^XA^FD{{locCd}}^FS^XZ", Map.of("locCd", "a\nb"), LOC)).code()).isEqualTo("VALUE_CONTROL_CHAR");
        assertThat(catchThrowableOfType(RenderException.class,
                () -> ZplRenderer.render("^XA^FD{{locCd}}^FS^XZ", Map.of("locCd", "x".repeat(201)), LOC)).code()).isEqualTo("VALUE_TOO_LONG");
        assertThat(catchThrowableOfType(RenderException.class,
                () -> ZplRenderer.render("^XA^FD{{nope}}^FS^XZ", Map.of(), LOC)).code()).isEqualTo("TEMPLATE_UNKNOWN_FIELD");
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @CsvSource(delimiter = '|', value = {
            "^FD{{locCd}}^FS                         | NOT_ZPL",
            "^XA^CC+^FD{{locCd}}^FS^XZ               | FORBIDDEN_CMD",
            "^XA~CT~^XZ                              | FORBIDDEN_CMD",
            "^XA^JUS^XZ                              | FORBIDDEN_CMD",
            "^XA~JR^XZ                               | FORBIDDEN_CMD",
            "^XA^FH\\^FD{{locCd}}^FS^XZ              | FORBIDDEN_CMD",
            "^XA^FD{{password}}^FS^XZ                | UNKNOWN_FIELD",
            "^XA^BY{{locCd}}^FD1^FS^XZ               | OUTSIDE_FD",
            "^XA^FO{{locCd}},10^FDx^FS^XZ            | OUTSIDE_FD",
            "^XA^FD{{locCd}^FS^XZ                    | BAD_PLACEHOLDER",
            "^XA^FD{{ locCd }}^FS^XZ                 | BAD_PLACEHOLDER",
            "'   '                                   | EMPTY"
    })
    void 템플릿_검사(String body, ZplRenderer.Problem expected) {
        assertThat(problems(body)).contains(expected);
    }

    @Test
    void 템플릿_길이_대소문자_마지막_FS_없음() {
        assertThat(problems("^XA^FD" + "x".repeat(ZplRenderer.MAX_BODY) + "^FS^XZ")).containsExactly(ZplRenderer.Problem.TOO_LONG);
        assertThat(problems("^xa^fo1,1^fd{{locCd}}^fs^xz")).isEmpty();                 // 소문자 명령도 같은 규칙
        assertThat(problems("^XA^FD{{locCd}}^XZ")).isEmpty();                          // ^FS 빠짐 = 끝까지 필드
        assertThat(problems(null)).containsExactly(ZplRenderer.Problem.EMPTY);
    }

    // ------------------------------------------------------------------ 프린터 주소

    static final Set<Integer> PORTS = Set.of(9100);

    @ParameterizedTest(name = "[{index}] {0}:{1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "10.0.0.5         | 9100 | OK",
            "172.16.4.1       | 9100 | OK",
            "172.31.255.254   | 9100 | OK",
            "192.168.0.50     | 9100 | OK",
            "fd12:3456::7     | 9100 | OK",
            "zebra-01.factory.local | 9100 | OK",
            "172.32.0.1       | 9100 | NOT_PRIVATE",
            "8.8.8.8          | 9100 | NOT_PRIVATE",
            "169.254.169.254  | 9100 | NOT_PRIVATE",      // 클라우드 메타데이터
            "127.0.0.1        | 9100 | NOT_PRIVATE",
            "0.0.0.0          | 9100 | NOT_PRIVATE",
            "100.64.0.1       | 9100 | NOT_PRIVATE",      // CGNAT
            "224.0.0.1        | 9100 | NOT_PRIVATE",
            "::1              | 9100 | NOT_PRIVATE",
            "fe80::1          | 9100 | NOT_PRIVATE",
            "2001:4860::8888  | 9100 | NOT_PRIVATE",
            "localhost        | 9100 | NOT_PRIVATE",
            "300.1.1.1        | 9100 | FORMAT",
            "010.0.0.1        | 9100 | FORMAT",          // 8진수로 읽는 구현이 있다
            "http://10.0.0.1  | 9100 | FORMAT",
            "a b              | 9100 | FORMAT",
            "10.0.0.5         | 22   | PORT_NOT_ALLOWED",
            "10.0.0.5         | 0    | PORT_NOT_ALLOWED"
    })
    void 저장때_주소_검사(String host, int port, String expected) {
        Problem p = PrinterAddress.checkStatic(host, port, PORTS, false);
        assertThat(p == null ? "OK" : p.name()).isEqualTo(expected);
    }

    @Test
    void 루프백은_설정으로만() {
        assertThat(PrinterAddress.checkStatic("127.0.0.1", 9100, PORTS, true)).isNull();
        assertThat(PrinterAddress.checkStatic("localhost", 9100, PORTS, true)).isNull();
    }

    @Test
    void 보낼때_이름을_해석해_모든_주소를_검사() throws Exception {
        PrinterAddress.Resolver mixed = h -> new InetAddress[] {InetAddress.getByAddress(h, new byte[] {10, 0, 0, 1}),
                InetAddress.getByAddress(h, new byte[] {8, 8, 8, 8})};
        AddressException e = catchThrowableOfType(AddressException.class,
                () -> PrinterAddress.resolveChecked("printer.example", false, mixed));
        assertThat(e.problem()).isEqualTo(Problem.NOT_PRIVATE);                          // 하나라도 공인이면 거부(재바인딩)
        PrinterAddress.Resolver ok = h -> new InetAddress[] {InetAddress.getByAddress(h, new byte[] {(byte) 192, (byte) 168, 1, 9})};
        assertThat(PrinterAddress.resolveChecked("zebra", false, ok).getHostAddress()).isEqualTo("192.168.1.9");
        PrinterAddress.Resolver none = h -> {
            throw new UnknownHostException(h);
        };
        assertThat(catchThrowableOfType(AddressException.class,
                () -> PrinterAddress.resolveChecked("nowhere", false, none)).problem()).isEqualTo(Problem.UNRESOLVED);
        assertThat(catchThrowableOfType(AddressException.class,
                () -> PrinterAddress.resolveChecked("169.254.169.254", true, none)).problem()).isEqualTo(Problem.NOT_PRIVATE);
    }
}
