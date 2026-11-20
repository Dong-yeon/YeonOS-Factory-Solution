package io.smarthappy.yeonos.terminal;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.view.WindowManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 다리 YeonShell (docs/50 K6) — 서버 웹이 부르는 것은 kiosk·appInfo 둘, 내장 시작 화면이 부르는 것은 start·setServer.
 * 다리를 부를 수 있는 출처는 저장한 서버 하나(allowNavigation)와 내장 화면뿐. 값은 모두 입력으로 다룬다.
 *
 * 화면 JS 는 이 다리를 직접 부르지 않는다 — hb.kiosk·hb.pop.keepAwake·hb.scan 이 있는지 보고 고른다(6.1, MES 계약 K5·C6).
 */
@CapacitorPlugin(name = "YeonShell")
public class YeonShellPlugin extends Plugin {

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 내장 시작 화면 → SETUP | OPEN | UPDATE | OFFLINE (V2) */
    @PluginMethod
    public void start(PluginCall call) {
        ServerConfig server = new ServerConfig(getContext());
        String url = server.url();
        if (url == null) {
            call.resolve(state("SETUP"));
            return;
        }
        io.execute(() -> {
            String min;
            boolean reached = true;
            try {
                min = VersionGate.fetchMinVersion(url);
                server.rememberMinVersion(min);
            } catch (IOException e) {
                min = server.lastMinVersion();   // 닿지 못하면 마지막 성공 값으로 판단
                reached = false;
            }
            String current = appVersion();
            if (min != null && !min.isEmpty() && VersionGate.compare(current, min) < 0) {
                JSObject r = state("UPDATE");
                r.put("current", current);
                r.put("min", min);
                call.resolve(r);
            } else if (!reached) {
                call.resolve(state("OFFLINE"));
            } else {
                call.resolve(state("OPEN"));
                getBridge().executeOnMainThread(() -> getBridge().getWebView().loadUrl(url + "/pop"));
            }
        });
    }

    /** 첫 실행 한 번 — https·형식·/pda/version.json 응답을 확인하고 저장, 액티비티를 다시 만들어 허용 출처를 바꾼다(S5) */
    @PluginMethod
    public void setServer(PluginCall call) {
        ServerConfig server = new ServerConfig(getContext());
        if (server.url() != null) {
            call.reject("서버 주소는 이미 정해져 있습니다. 바꾸려면 앱을 다시 설치하세요.");
            return;
        }
        String url = ServerConfig.normalize(call.getString("url"));
        if (url == null) {
            call.reject("https:// 로 시작하는 서버 주소만 넣습니다(경로 없이).");
            return;
        }
        io.execute(() -> {
            try {
                VersionGate.fetchMinVersion(url);   // 응답해야 저장 — 오타 주소를 저장하지 않게
            } catch (IOException e) {
                call.reject("그 주소의 서버에 연결할 수 없습니다. 주소와 네트워크를 확인하세요.");
                return;
            }
            if (!server.saveOnce(url)) {
                call.reject("서버 주소를 저장하지 못했습니다.");
                return;
            }
            call.resolve();
            getBridge().executeOnMainThread(() -> getActivity().recreate());
        });
    }

    /** 앱 정보 — 서버 웹이 참고로 부른다(판단에 쓰지 않는다) */
    @PluginMethod
    public void appInfo(PluginCall call) {
        JSObject r = new JSObject();
        r.put("version", appVersion());
        call.resolve(r);
    }

    /**
     * 키오스크 켬·끔 (K3·K7) — 화면 고정(lock task) + 화면 꺼짐 방지. 끄기는 서버가 감독자 배지 + PIN 을 확인한 뒤
     * 웹이 부른다(K4·K5, MES 계약). MDM 이 기기 소유자로 허용하지 않은 기기는 OS 가 화면 고정 확인을 묻는다
     */
    @PluginMethod
    public void kiosk(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getBridge().executeOnMainThread(() -> {
            Activity a = getActivity();
            if (on) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                a.startLockTask();
            } else {
                a.stopLockTask();
                a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            call.resolve();
        });
    }

    private String appVersion() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    private static JSObject state(String s) {
        JSObject r = new JSObject();
        r.put("state", s);
        return r;
    }
}
