package io.smarthappy.platform.code;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 공통코드 조회 (드롭다운 등). 시스템 코드 이름은 사용자 언어로, 회사 코드는 입력한 이름 그대로 */
@Service
@RequiredArgsConstructor
public class CodeService {

    private static final Pattern GRP_CD = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CodeMapper mapper;
    private final MessageSource messages;

    /** attrs = 코드 속성(예: UNIT 의 scale). 속성이 없는 코드는 빈 객체 */
    public record CodeItem(String code, String name, boolean system, Map<String, Object> attrs) {
    }

    @Transactional(readOnly = true)
    public List<CodeItem> codes(String grpCd, Locale locale) {
        if (grpCd == null || !GRP_CD.matcher(grpCd).matches() || !mapper.groupExists(grpCd)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        // 같은 코드가 시스템·회사 둘 다면(회사가 먼저 만든 코드와 같은 시스템 코드가 나중에 생긴 경우) 시스템 것만 —
        // 정렬(sort_no)에 맡기지 않는다. 자리는 처음 나온 자리
        Map<String, CodeItem> byCode = new java.util.LinkedHashMap<>();
        for (CodeRow r : mapper.findCodes(grpCd)) {
            CodeItem item = new CodeItem(r.getCode(),
                    r.isSystemYn() ? messages.getMessage(r.getCodeNmKey(), null, r.getCode(), locale) : r.getCodeNm(),
                    r.isSystemYn(), attrs(r.getAttrs()));
            CodeItem prev = byCode.get(r.getCode());
            if (prev == null || (item.system() && !prev.system())) {
                byCode.put(r.getCode(), item);
            }
        }
        return List.copyOf(byCode.values());
    }

    /**
     * 표시용 이름표 코드 → 이름(보는 사람 언어) — **사용 중지된 코드도** 담는다(docs/36 18.9 X4: 코드를 끈 뒤에도 옛 행이 날 코드로 보이지
     * 않게). 선택 목록은 {@link #codes}(사용 중만). 그룹이 없거나 형식이 틀리면 빈 표(표시는 날 코드로 — 내려받기를 깨지 않는다)
     */
    @Transactional(readOnly = true)
    public Map<String, String> names(String grpCd, Locale locale) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (grpCd == null || !GRP_CD.matcher(grpCd).matches()) {
            return out;
        }
        for (CodeRow r : mapper.findCodeNames(grpCd)) {
            // 같은 코드가 시스템·회사 둘 다면 시스템 이름 — 시스템 행은 덮어쓰고 회사 행은 없을 때만(정렬에 맡기지 않는다)
            if (r.isSystemYn()) {
                out.put(r.getCode(), messages.getMessage(r.getCodeNmKey(), null, r.getCode(), locale));
            } else {
                out.putIfAbsent(r.getCode(), r.getCodeNm());
            }
        }
        return out;
    }

    /** 코드 칸 형식(시스템·회사 코드 공통) — 조회 조건은 이것만 본다(사용 중지된 코드로도 옛 행을 찾게) */
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");

    /** 조회 조건 코드 형식 확인 — 비었으면 true(조건 없음), 존재는 보지 않는다 */
    public static boolean isCodeFormat(String code) {
        return code == null || code.isEmpty() || CODE.matcher(code).matches();
    }

    /**
     * 저장할 코드 확인(docs/68) — 시스템 코드 또는 <b>이 회사</b> 코드 중 사용 중인 것. 다른 회사 코드·사용 중지·없는 코드는 false.
     * 업무 표 트리거(hb_check_code_col)와 같은 DB 함수라 판정이 어긋나지 않는다. 업무 트랜잭션 안에서 부른다(밖이면 회사 범위가 없다)
     */
    @Transactional(readOnly = true)
    public boolean usable(String grpCd, String code) {
        return code != null && CODE.matcher(code).matches() && mapper.usable(grpCd, code);
    }

    /** 코드 칸 + 이름 사본 칸 한 쌍(docs/68 Q2) — code 가 없으면 목록 밖 글자(옛 행)거나 둘 다 비었음 */
    public record CodeAndName(String code, String name) {
    }

    /**
     * 코드 칸 + 이름 사본 칸 짝 정하기(docs/68 Q2 — 측정 단위처럼 다른 표가 이름 글자를 복사해 가는 칸).
     * <ul>
     * <li>code 가 있으면: 쓸 수 있는 코드여야 하고(아니면 codeField CODE) 이름 사본은 그 코드 이름(보는 사람 언어). 이름이 nameMax 를 넘으면
     * codeField CODE_NAME_LONG — 사본 칸에 잘리지 않게</li>
     * <li>code 가 없고 글자만 있으면(옛 행·엑셀·직접 API): 글자 길이 확인(nameField TOO_LONG) 뒤 같은 이름의 코드가 하나면 그 코드로, 없으면 글자 그대로
     * (조용히 지우지 않는다)</li>
     * </ul>
     * curCode·curName = 고치는 행의 지금 값(새 행이면 null) — 코드가 그대로면 검사·이름을 다시 하지 않는다.
     * 오류는 e 에 쌓고 null. 업무 트랜잭션 안에서 부른다
     */
    @Transactional(readOnly = true)
    public CodeAndName codeAndName(String grpCd, String code, String text, String codeField, String nameField, int nameMax,
            String curCode, String curName, io.smarthappy.platform.common.web.FieldErrors e) {
        Locale locale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        String c = code == null || code.isBlank() ? null : code.strip();
        if (c != null && c.equals(curCode)) {
            return new CodeAndName(c, curName);   // 그대로인 코드는 다시 보지 않는다(사용 중지 뒤에도 다른 칸 수정 — 트리거와 같은 규칙)
        }
        if (c != null) {
            if (!usable(grpCd, c)) {
                e.add(codeField, "CODE");
                return null;
            }
            String nm = names(grpCd, locale).getOrDefault(c, c);
            if (nm.length() > nameMax) {
                e.add(codeField, "CODE_NAME_LONG", nameMax);
                return null;
            }
            return new CodeAndName(c, nm);
        }
        String t = text == null || text.isBlank() ? null : text.strip();
        if (t == null) {
            return new CodeAndName(null, null);
        }
        if (t.length() > nameMax) {
            e.add(nameField, "TOO_LONG", nameMax);
            return null;
        }
        List<String> hit = codes(grpCd, locale).stream().filter(x -> x.name().equals(t)).map(CodeItem::code).toList();
        return hit.size() == 1 ? new CodeAndName(hit.get(0), t) : new CodeAndName(null, t);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(String json) {
        return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, Map.class);
    }
}
