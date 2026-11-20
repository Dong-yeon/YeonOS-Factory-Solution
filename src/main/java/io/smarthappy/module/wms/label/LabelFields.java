package io.smarthappy.module.wms.label;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 라벨 유형별 자리표시자 허용 목록과 견본 (docs/30 W2). 공통 칸 printedAt·printedBy 는 서버가 채운다.
 * LOT·PALLET·SHIP 값은 2부(P2 LOT·출하)가 채운다 — 1부는 템플릿을 미리 만들어 둘 수 있게 이름만 정한다.
 * {@code gs1}(LOT·PALLET·SHIP)은 사람이 읽는 괄호 형식 GS1 요소 문자열 — ^BX GS1 DataMatrix 필드에만(docs/65 18.5).
 */
public final class LabelFields {

    public static final List<String> TYPES = List.of("LOC", "ITEM", "LOT", "PALLET", "SHIP");
    static final List<String> COMMON = List.of("printedAt", "printedBy");

    private static final Map<String, List<String>> FIELDS = Map.of(
            "LOC", List.of("plantCd", "locCd", "locNm", "path", "usage", "barcode"),
            "ITEM", List.of("itemCd", "itemNm", "spec", "unitCd", "barcode"),
            "LOT", List.of("lotNo", "itemCd", "itemNm", "qty", "unitCd", "prodDate", "expiry", "supplierLotNo", "barcode", "gs1"),
            "PALLET", List.of("palletNo", "sscc", "itemCd", "itemNm", "qty", "unitCd", "lotCount", "barcode", "gs1"),
            "SHIP", List.of("shipNo", "custNm", "custItemCd", "itemCd", "itemNm", "qty", "unitCd", "dueDate", "palletCount", "barcode",
                    "gs1"));

    /**
     * "긴 값" 견본 — 칸마다 현실적 최대 길이(docs/65 18.3). 템플릿 저장·미리보기 때 넘침 경고를 두 벌로 본다.
     * 마스터 칸 길이(품목명 200자 등)보다 짧다: 실제 라벨에서 문제 되는 길이를 본다(품목명 한글 40자).
     */
    private static final Map<String, String> LONG = Map.ofEntries(
            Map.entry("itemNm", "가".repeat(40)), Map.entry("locNm", "나".repeat(30)), Map.entry("custNm", "다".repeat(30)),
            Map.entry("spec", "W".repeat(40)), Map.entry("path", "P1/" + "W".repeat(20) + "/" + "A".repeat(20) + "/" + "B".repeat(20)),
            Map.entry("itemCd", "W".repeat(40)), Map.entry("custItemCd", "W".repeat(40)), Map.entry("locCd", "W".repeat(30)),
            Map.entry("lotNo", "W".repeat(30)), Map.entry("supplierLotNo", "W".repeat(30)), Map.entry("palletNo", "W".repeat(30)),
            Map.entry("shipNo", "W".repeat(30)), Map.entry("qty", "9999999.999999"), Map.entry("barcode", "LOT:" + "W".repeat(40)));

    private LabelFields() {
    }

    /** 유형의 허용 이름(공통 포함). 없는 유형이면 빈 집합 */
    public static Set<String> allowed(String labelType) {
        List<String> f = FIELDS.get(labelType);
        if (f == null) {
            return Set.of();
        }
        Set<String> s = new LinkedHashSet<>(f);
        s.addAll(COMMON);
        return s;
    }

    /** 견본 값 — 미리보기·시험 인쇄용 */
    public static Map<String, String> sampleValues(String labelType) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String n : allowed(labelType)) {
            m.put(n, switch (n) {
                case "plantCd" -> "P1";
                case "locCd" -> "A-01-03";
                case "locNm" -> "A랙 1열 3단";
                case "path" -> "P1/RM-WH/A-01/A-01-03";
                case "usage" -> "STORAGE";
                case "barcode" -> "LOC".equals(labelType) ? "LOC:P1:A-01-03" : labelType + ":SAMPLE-0001";
                case "itemCd", "custItemCd" -> "84910-2S000";
                case "itemNm" -> "브래킷 ASSY";
                case "qty" -> "120";
                case "unitCd" -> "EA";
                case "printedAt" -> "2026-10-20 09:30";
                case "printedBy" -> "admin";
                case "sscc" -> "088012340000000017";
                case "gs1" -> "PALLET".equals(labelType) ? "(00)088012340000000017"
                        : "(01)08801234567893(10)SAMPLE-0001(17)271231(37)120";
                default -> "SAMPLE";
            });
        }
        return m;
    }

    /** "긴 값" 견본 — 견본 값을 칸마다 긴 값으로 바꾼 것 */
    public static Map<String, String> longValues(String labelType) {
        Map<String, String> m = sampleValues(labelType);
        m.replaceAll((k, v) -> LONG.getOrDefault(k, v));
        return m;
    }

    /**
     * 견본 템플릿 — 화면의 "견본 불러오기"(50×30mm, 203dpi). 한글 칸은 글꼴 별칭 J(^CW 로 프린터에 올린 한글 TTF —
     * docs/65 18.2). 글꼴 파일은 설치 때 프린터에 올린다(~DY, 프린터 도구).
     */
    public static String sampleBody(String labelType) {
        if ("LOC".equals(labelType)) {
            return """
                    ^XA
                    ^CI28
                    ^CWJ,E:NANUMGOTHIC.TTF
                    ^PW400^LL240
                    ^FO20,16^A0N,44,44^FD{{locCd}}^FS
                    ^FO20,66^AJN,22,22^FD{{path}}^FS
                    ^FO20,100^BY2^BCN,80,Y,N,N^FD{{barcode}}^FS
                    ^XZ""";
        }
        String key = switch (labelType) {
            case "ITEM" -> "itemCd";
            case "LOT" -> "lotNo";
            case "PALLET" -> "palletNo";
            default -> "shipNo";
        };
        return """
                ^XA
                ^CI28
                ^CWJ,E:NANUMGOTHIC.TTF
                ^PW400^LL240
                ^FO20,16^A0N,36,36^FD{{%s}}^FS
                ^FO20,58^AJN,22,22^FB220,2,0,L^FD{{itemNm}}^FS
                ^FO250,90^BQN,2,4^FDQA,{{barcode}}^FS
                ^FO20,200^A0N,18,18^FD{{printedAt}} {{printedBy}}^FS
                ^XZ""".formatted(key);
    }
}
