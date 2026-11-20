package io.smarthappy.yeonos.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * 서버 주소 — 첫 실행 때 한 번 저장, 바꾸려면 앱 데이터 삭제(재설치)뿐 (docs/50 S5, Q5 결정).
 * 형식: https://호스트[:포트] — 경로·쿼리·조각·사용자 정보 없음.
 */
final class ServerConfig {

    private static final String PREFS = "yeonos_shell";
    private static final String KEY_URL = "server_url";
    private static final String KEY_MIN = "last_min_version";

    private final SharedPreferences prefs;

    ServerConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 저장된 서버 주소(끝 / 없음) 또는 null */
    String url() {
        return prefs.getString(KEY_URL, null);
    }

    String host() {
        String u = url();
        return u == null ? null : Uri.parse(u).getHost();
    }

    /** 형식이 맞으면 정규화한 주소, 아니면 null */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        Uri u = Uri.parse(raw.trim());
        String path = u.getPath();
        if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getHost().isEmpty() || u.getUserInfo() != null
                || u.getQuery() != null || u.getFragment() != null || (path != null && !path.isEmpty() && !"/".equals(path))) {
            return null;
        }
        return "https://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /** 한 번만 — 이미 있으면 false (현장에서 다른 서버로 바꾸지 못하게) */
    boolean saveOnce(String normalized) {
        if (url() != null) {
            return false;
        }
        return prefs.edit().putString(KEY_URL, normalized).commit();
    }

    /** 서버에 닿지 않을 때 판단에 쓰는 마지막 최소 버전 (V2) */
    String lastMinVersion() {
        return prefs.getString(KEY_MIN, null);
    }

    void rememberMinVersion(String min) {
        prefs.edit().putString(KEY_MIN, min).apply();
    }
}
