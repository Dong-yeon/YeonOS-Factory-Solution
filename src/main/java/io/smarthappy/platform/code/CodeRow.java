package io.smarthappy.platform.code;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodeRow {
    private String code;
    private String codeNm;
    private String codeNmKey;
    private int sortNo;
    private boolean systemYn;
    /** jsonb 를 글자로 (attrs::text) */
    private String attrs;
}
