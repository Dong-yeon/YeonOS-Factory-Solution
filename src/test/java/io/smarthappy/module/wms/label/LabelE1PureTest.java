package io.smarthappy.module.wms.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.wms.label.ZebraStatus.Probe;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.module.wms.scan.ScanKeyResolver.BareAs;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Context;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Reason;
import io.smarthappy.module.wms.scan.ScanKeyResolver.Result;

/** 라벨 보강 E1 순수 규칙 (docs/65 18장) — 상태 전이표 · ~HQES 해석 · 배치·넘침 · 한글 글꼴 경고 · GS1 인코더·해석 */
class LabelE1PureTest {

    static final Context CTX = new Context("P1", BareAs.NONE, LocalDate.of(2026, 11, 9));
    static final char GS = '\u001d';

    @Test
    void 전이표_앞으로만_끝난_상태는_그대로() {
        assertThat(PrintStates.canMove("QUEUED", "SENT")).isTrue();
        assertThat(PrintStates.canMove("SENT", "PRINTED")).isTrue();
        assertThat(PrintStates.canMove("PRINTED", "VERIFIED")).isTrue();
        assertThat(PrintStates.canMove("PRINTER_ERROR", "PRINTED")).isTrue();
        assertThat(PrintStates.canMove("UNKNOWN", "PRINTED")).isTrue();            // 늦게 온 결과·사람 확정
        assertThat(PrintStates.canMove("PRINTED", "SENT")).isFalse();              // 되돌림 없음
        assertThat(PrintStates.canMove("PRINTED", "PRINTER_ERROR")).isFalse();
        assertThat(PrintStates.canMove("CANCELLED", "PRINTED")).isFalse();
        assertThat(PrintStates.canMove("VERIFIED", "VERIFIED")).isFalse();
        assertThat(PrintStates.canMove("QUEUED", "UNKNOWN")).isFalse();            // 가져가지도 않은 것은 불명이 아니다
        assertThat(PrintStates.fromOutbox("SENDING")).isEqualTo("QUEUED");
        assertThat(PrintStates.fromOutbox("FAIL")).isEqualTo("FAILED");
    }

    @Test
    void HQES_해석_비트와_odometer() {
        Probe ok = ZebraStatus.parse("\u0002\r\n PRINTER STATUS\r\n ERRORS: 0 00000000 00000000\r\n WARNINGS: 0 00000000 00000000\r\n\"12345\"");
        assertThat(ok.state()).isEqualTo("OK");
        assertThat(ok.odometer()).isEqualTo(12345L);
        Probe err = ZebraStatus.parse("ERRORS: 1 00000000 00000045\r\nWARNINGS: 1 00000000 00000008\r\n\"7\"");
        assertThat(err.errors()).containsExactly("MEDIA_OUT", "HEAD_OPEN", "BAD_HEAD_ELEMENT");
        assertThat(err.warnings()).containsExactly("PAPER_NEAR_END");
        assertThat(err.state()).isEqualTo("ERROR");
        // 모르는 비트는 버리지 않는다 · 높은 묶음
        Probe unknown = ZebraStatus.parse("ERRORS: 1 00000001 00000100\r\nWARNINGS: 0 00000000 00000000");
        assertThat(unknown.errors()).containsExactly("ERR_x100", "ERR_x100000000");
        assertThat(ZebraStatus.parse("garbage").state()).isEqualTo("UNKNOWN");
        assertThat(ZebraStatus.parse(null).state()).isEqualTo("UNKNOWN");
        assertThat(Probe.offline("ConnectException").state()).isEqualTo("OFFLINE");
        assertThat(ZebraStatus.cleanCodes(List.of("media_out", "x y", "MEDIA_OUT", "ok"))).containsExactly("MEDIA_OUT", "OK");
    }

    @Test
    void 장_수는_서식마다_PQ() {
        assertThat(ZplLayout.labelsPerCopy("^XA^FDx^FS^XZ")).isEqualTo(1);
        assertThat(ZplLayout.labelsPerCopy("^XA^PQ3^XZ^XA^FD^XZ")).isEqualTo(4);
        assertThat(ZplLayout.labelsPerCopy("^xa^pq2,0,1,Y^xz")).isEqualTo(2);
        assertThat(ZplLayout.labelsPerCopy(null)).isEqualTo(1);
    }

    @Test
    void 배치_넘침_FB_줄수_라벨밖() {
        String zpl = "^XA^PW400^LL240^FO20,10^A0N,30,30^FD\u0001itemNm\u0002" + "가".repeat(15) + "^FS"
                + "^FO20,60^A0N,20,20^FB100,2,0,L^FH_^FD\u0001spec\u0002" + "W".repeat(30) + "^FS"
                + "^FO10,230^GB100,20,2^FS^FO300,100^BQN,2,4^FDQA,\u0001barcode\u0002LOT:1^FS^XZ";
        ZplLayout.Layout l = ZplLayout.layout(zpl, 0, 0);
        assertThat(l.widthDots()).isEqualTo(400);
        // 한글 15자 × 높이 30 = 450 > 400 · 30자 × 12 = 360 / 100 = 4줄 > 2 · 상자가 240 아래로 · QR 은 안
        assertThat(l.warnings()).containsExactly("OVERFLOW_X:itemNm", "FB_LINES:spec", "OFF_LABEL:box");
        assertThat(l.elements()).extracting(ZplLayout.Element::kind).containsExactly("TEXT", "TEXT", "BOX", "QR");
        assertThat(l.elements().get(0).text()).isEqualTo("가".repeat(15));       // 칸 표시는 글자에서 빠진다
        // 기본 크기: 50×30mm 203dpi = 400×240 점
        assertThat(ZplLayout.layout("^XA^XZ", BigDecimal.valueOf(50), BigDecimal.valueOf(30), 203).heightDots()).isEqualTo(240);
        assertThat(ZplLayout.unescapeFh("A_5FB_5E")).isEqualTo("A_B^");
    }

    @Test
    void 한글_글꼴_경고() {
        assertThat(ZplLint.warnings("^XA^FO1,1^A0N,20,20^FD{{itemNm}}^FS^XZ")).containsExactly("CJK_FONT_MISSING:itemNm", "CI28_MISSING");
        assertThat(ZplLint.warnings("^XA^CI28^CWJ,E:NANUM.TTF^FO1,1^AJN,20,20^FD{{itemNm}}^FS^XZ")).isEmpty();
        assertThat(ZplLint.warnings("^XA^CI28^FO1,1^AJN,20,20^FD{{itemNm}}^FS^XZ")).containsExactly("CJK_FONT_ALIAS:J");
        assertThat(ZplLint.warnings("^XA^CI28^FO1,1^A@N,20,20,E:NANUM.TTF^FD{{itemNm}}^FS^XZ")).isEmpty();
        // ^CF 기본 글꼴이 앞에서 정해지면 그것을 본다 · 한글 칸이 아니면 경고 없음
        assertThat(ZplLint.warnings("^XA^CI28^CWK,E:N.TTF^CFK,20^FO1,1^FD{{custNm}}^FS^XZ")).isEmpty();
        assertThat(ZplLint.warnings("^XA^FO1,1^A0N,20,20^FD{{itemCd}}^FS^XZ")).isEmpty();
        for (String t : LabelFields.TYPES) {
            assertThat(ZplLint.warnings(LabelFields.sampleBody(t))).as(t).isEmpty();
        }
    }

    @Test
    void GS1_인코더_FNC1_은_가변_AI_뒤에만() {
        assertThat(Gs1Encoder.encode("(01)08801234567893(17)271231(10)LOT1", '@')).isEqualTo("@10108801234567893" + "17271231" + "10LOT1");
        assertThat(Gs1Encoder.encode("(10)A(21)B(37)12", '@')).isEqualTo("@110A@121B@13712");
        assertThat(Gs1Encoder.encode("(00)088012340000000017", '@')).isEqualTo("@100088012340000000017");
        assertThat(catchThrowableOfType(Gs1Encoder.Gs1Exception.class, () -> Gs1Encoder.encode("(10)A", '_')).code()).isEqualTo("GS1_ESCAPE");
        assertThat(catchThrowableOfType(Gs1Encoder.Gs1Exception.class, () -> Gs1Encoder.encode("(10)A~JR", '@')).code())
                .isEqualTo("VALUE_CHARSET");
        assertThat(catchThrowableOfType(Gs1Encoder.Gs1Exception.class, () -> Gs1Encoder.encode("(10)한글", '@')).code())
                .isEqualTo("GS1_BAD_CHAR");
        assertThat(catchThrowableOfType(Gs1Encoder.Gs1Exception.class, () -> Gs1Encoder.encode(" ", '@')).code()).isEqualTo("GS1_EMPTY");
        // 인코더가 낸 값을 스캐너(FNC1 → GS)로 다시 읽으면 같은 값
        String enc = Gs1Encoder.encode("(01)08801234567893(10)LOT1(17)271231", '@');
        Result back = ScanKeyResolver.resolve("]d2" + enc.substring(2).replace("@1", String.valueOf(GS)), CTX);
        assertThat(back.gs1().batch()).isEqualTo("LOT1");
        assertThat(back.gs1().expiry()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void GS1_310n_15_해석() {
        Result r = ScanKeyResolver.resolve("(01)08801234567893(3102)001250(15)270630", CTX);
        assertThat(r.ok()).isTrue();
        assertThat(r.gs1().netWeightKg()).isEqualByComparingTo("12.50");
        assertThat(r.gs1().bestBefore()).isEqualTo(LocalDate.of(2027, 6, 30));
        Result raw = ScanKeyResolver.resolve("]C1" + "0108801234567893" + "3103012345" + "15270600", CTX);
        assertThat(raw.gs1().netWeightKg()).isEqualByComparingTo("12.345");
        assertThat(raw.gs1().bestBefore()).isEqualTo(LocalDate.of(2027, 6, 30));        // DD 00 = 그 달 마지막 날
        assertThat(ScanKeyResolver.resolve("(3101)000010(3102)000010", CTX).reason()).isEqualTo(Reason.GS1_DUPLICATE_AI);
        assertThat(ScanKeyResolver.resolve("(3109)000010", CTX).reason()).isEqualTo(Reason.GS1_UNKNOWN_AI);
    }

    @Test
    void GS1_두_바코드_결합_섞이면_거부() {
        Result id = ScanKeyResolver.resolve("(01)08801234567893(21)S1", CTX);
        Result attr = ScanKeyResolver.resolve("(01)08801234567893(10)LOT1(3102)001250", CTX);
        Result both = ScanKeyResolver.combine(id, attr, CTX);
        assertThat(both.ok()).isTrue();
        assertThat(both.gs1().serial()).isEqualTo("S1");
        assertThat(both.gs1().batch()).isEqualTo("LOT1");
        assertThat(both.gs1().netWeightKg()).isEqualByComparingTo("12.5");
        Result other = ScanKeyResolver.resolve("(01)08801234567817(10)LOT9", CTX);
        Result mixed = ScanKeyResolver.combine(id, other, CTX);
        assertThat(mixed.reason()).isEqualTo(Reason.GS1_MISMATCH);
        assertThat(mixed.detail()).isEqualTo("01");
        assertThat(ScanKeyResolver.combine(id, ScanKeyResolver.resolve("LOT:L1", CTX), CTX).reason()).isEqualTo(Reason.GS1_NOT_GS1);
    }

    @Test
    void FNC1_대체_글자와_빠짐_의심() {
        Result sub = ScanKeyResolver.resolve("]C1" + "10LOT1<GS>17271231", CTX);
        assertThat(sub.ok()).isTrue();
        assertThat(sub.gs1().batch()).isEqualTo("LOT1");
        assertThat(sub.gs1().warnings()).containsExactly(ScanKeyResolver.WARN_FNC1_SUBSTITUTED);
        // GS 를 빼먹음: 길이로 확실하면 거부, 길이가 맞으면 받되 경고
        assertThat(ScanKeyResolver.resolve("]C1" + "10LOT-ABCDEFGHIJKLMN" + "17271231", CTX).reason()).isEqualTo(Reason.GS1_FNC1_MISSING);
        Result suspect = ScanKeyResolver.resolve("]C1" + "10LOT1" + "17271231", CTX);
        assertThat(suspect.ok()).isTrue();
        assertThat(suspect.gs1().batch()).isEqualTo("LOT117271231");                   // 조용히 틀릴 뻔한 값 — 경고로 알린다
        assertThat(suspect.gs1().warnings()).containsExactly(ScanKeyResolver.WARN_FNC1_MISSING_SUSPECT);
        assertThat(ScanKeyResolver.resolve("]C1" + "10LOT1" + GS + "17271231", CTX).gs1().warnings()).isEmpty();
    }

    @Test
    void 렌더_GS1_필드는_FH_없이_다른_칸은_FH() {
        String body = "^XA^BXN,6,200,,,,@^FD{{gs1}}^FS^FO1,1^A0N,10,10^FD{{lotNo}}_x^FS^XZ";
        String out = ZplRenderer.render(body, java.util.Map.of("gs1", "(10)L_1", "lotNo", "L_1"), LabelFields.allowed("LOT"));
        assertThat(out).isEqualTo("^XA^BXN,6,200,,,,@^FD@110L_1^FS^FO1,1^A0N,10,10^FH_^FDL_5F1_5Fx^FS^XZ");
        assertThat(ZplRenderer.bxEscape(body, body.indexOf("^FD"))).isEqualTo('@');
        assertThat(ZplRenderer.bxEscape(body, body.lastIndexOf("^FD"))).isNull();
    }
}
