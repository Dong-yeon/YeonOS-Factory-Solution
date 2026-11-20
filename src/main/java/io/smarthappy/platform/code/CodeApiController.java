package io.smarthappy.platform.code;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CodeApiController {

    private final CodeService codes;

    @GetMapping("/api/platform/codes")
    @ProgAuthExempt(reason = "드롭다운용 기준 코드 — 자기 회사 코드만(RLS), 화면 권한과 무관한 참조 데이터")
    public ApiResponse<List<CodeService.CodeItem>> codes(@RequestParam String grp, Locale locale) {
        return ApiResponse.ok(codes.codes(grp, locale));
    }

    /** 표시용 이름표 — 사용 중지된 코드도(docs/36 18.9 X4). 그리드·트리 CODE 칸이 쓴다. 선택 목록은 위 codes */
    @GetMapping("/api/platform/codes/names")
    @ProgAuthExempt(reason = "표시용 기준 코드 이름 — 자기 회사 코드만(RLS), 화면 권한과 무관한 참조 데이터")
    public ApiResponse<java.util.Map<String, String>> names(@RequestParam String grp, Locale locale) {
        return ApiResponse.ok(codes.names(grp, locale));
    }
}
