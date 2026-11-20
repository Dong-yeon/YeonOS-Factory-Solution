package io.smarthappy.platform.importer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.smarthappy.platform.code.CodeService;

/**
 * 엑셀 올리기의 코드 열 해석: 코드(대소문자 무시) 또는 이름(한국어·영어) → 코드.
 * 같은 이름이 둘 이상이면 이름으로는 정하지 않는다(원문 그대로 → 템플릿 검증이 CODE 오류). 트랜잭션 안에서 만든다.
 */
public final class ImportCodes {

    private final Map<String, String> byText = new HashMap<>();
    private final Set<String> codes = new HashSet<>();

    public ImportCodes(CodeService service, String grp) {
        Set<String> ambiguous = new HashSet<>();
        for (Locale locale : List.of(Locale.KOREAN, Locale.ENGLISH)) {
            for (CodeService.CodeItem c : service.codes(grp, locale)) {
                codes.add(c.code());
                byText.put(c.code().toLowerCase(Locale.ROOT), c.code());
                String name = c.name() == null ? null : c.name().strip().toLowerCase(Locale.ROOT);
                if (name != null && !name.isEmpty()) {
                    String prev = byText.putIfAbsent(name, c.code());
                    if (prev != null && !prev.equals(c.code())) {
                        ambiguous.add(name);
                    }
                }
            }
        }
        ambiguous.forEach(byText::remove);
    }

    /** 코드·이름 → 코드. 모르면 원문(검증에서 CODE 오류), 빈 칸은 null */
    public String resolve(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return byText.getOrDefault(text.strip().toLowerCase(Locale.ROOT), text.strip());
    }

    /** 사용 중인 코드인지 */
    public boolean contains(String code) {
        return code != null && codes.contains(code);
    }

    /** Y/N 열: Y·N·사용·미사용·예·아니오·Yes·No·True·False → Y/N, 모르면 원문, 빈 칸은 null */
    public static String yn(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "y", "yes", "true", "사용", "예", "o" -> "Y";
            case "n", "no", "false", "미사용", "아니오", "x" -> "N";
            default -> text.strip();
        };
    }
}
