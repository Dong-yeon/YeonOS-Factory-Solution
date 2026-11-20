package io.smarthappy.module.plm;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.code.CodeService;
import lombok.RequiredArgsConstructor;

/**
 * 고객 제출 문서(29장 통지서·31장 PPAP 요약서)의 글자 — 발신 회사·회사 기본 언어(누가 눌렀느냐로 바뀌지 않게, 29장 T3),
 * 문구 키, 코드 이름. 호출하는 쪽 트랜잭션 안에서(회사 = RLS)
 */
@Component
@RequiredArgsConstructor
class PlmDocs {

    private final PlmMapper mapper;
    private final CodeService codes;
    private final MessageSource messages;

    /** 발신 회사 이름 + 회사 기본 언어 */
    record Head(String compNm, Locale locale) {
    }

    Head head() {
        Map<String, Object> comp = mapper.compHead();
        return new Head((String) comp.get("comp_nm"),
                Locale.forLanguageTag(comp.get("locale_cd") == null ? "ko" : (String) comp.get("locale_cd")));
    }

    /** 코드 그룹 → {코드: 이름} */
    Map<String, String> names(String grp, Locale locale) {
        return codes.codes(grp, locale).stream().collect(Collectors.toMap(CodeService.CodeItem::code, CodeService.CodeItem::name, (a, b) -> a));
    }

    String msg(String key, Locale locale) {
        return messages.getMessage(key, null, locale);
    }

    String msg(String key, Locale locale, Object... args) {
        return messages.getMessage(key, args, locale);
    }

    /** 코드 이름(없는 코드는 코드 그대로, null 은 빈 칸) */
    static String name(Map<String, String> names, String code) {
        return code == null ? "" : names.getOrDefault(code, code);
    }
}
