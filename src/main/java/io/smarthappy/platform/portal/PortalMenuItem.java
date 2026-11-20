package io.smarthappy.platform.portal;

import java.util.regex.Pattern;

/**
 * 협력사 포털 메뉴 한 줄 (docs/42 3장 — 포털 메뉴는 화면 권한 sys_prog 가 아니라 코드로 고정).
 * path 는 포털 화면 경로(/portal 또는 /portal/…)만 — 경계 필터가 협력사 사용자를 포털 밖으로 내보내지 않으므로 밖을 가리키면 쓸 수 없다.
 */
public record PortalMenuItem(String code, String labelKey, String path, int sortNo) {

    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,29}$");
    private static final Pattern PATH = Pattern.compile("^/portal(/[a-z0-9-]+)*$");

    public PortalMenuItem {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("포털 메뉴 코드 형식: " + code);
        }
        if (labelKey == null || !labelKey.startsWith("portal.")) {
            throw new IllegalArgumentException("포털 메뉴 문구 키는 portal. 로 시작: " + labelKey);
        }
        if (path == null || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("포털 메뉴 경로는 /portal 아래만: " + path);
        }
    }
}
