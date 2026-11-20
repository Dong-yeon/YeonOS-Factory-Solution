package io.smarthappy.yeonos.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 최소 버전 확인 (docs/50 V1·V2) — GET {서버}/pda/version.json(로그인 없음, 세 칸). 메인 스레드에서 부르지 않는다.
 * 서버가 {} 를 주면(배포 파일 없음·틀림) 확인 없이 연다.
 */
final class VersionGate {

    static final int TIMEOUT_MS = 5000;
    static final int MAX_BYTES = 4096;
    private static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})$");

    /** 서버의 minVersion — 없으면 "" , 닿지 못하면 IOException */
    static String fetchMinVersion(String serverUrl) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(serverUrl + "/pda/version.json").openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);   // 다른 곳으로 보내는 응답은 믿지 않는다
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                for (int n; (n = in.read(buf)) > 0;) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) {
                        throw new IOException("too large");
                    }
                }
                JSONObject j = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                String min = j.optString("minVersion", "");
                return SEMVER.matcher(min).matches() ? min : "";
            } catch (JSONException e) {
                throw new IOException("not json", e);
            }
        } finally {
            c.disconnect();
        }
    }

    /** a < b 면 음수. 형식이 틀리면 0(막지 않음) */
    static int compare(String a, String b) {
        if (a == null || b == null || !SEMVER.matcher(a).matches() || !SEMVER.matcher(b).matches()) {
            return 0;
        }
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int r = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (r != 0) {
                return r;
            }
        }
        return 0;
    }

    private VersionGate() {
    }
}
