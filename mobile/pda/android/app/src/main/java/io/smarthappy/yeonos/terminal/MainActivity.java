package io.smarthappy.yeonos.terminal;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.CapConfig;

/**
 * YeonOS 현장 단말 껍데기 (docs/50 5·6장). 웹뷰는 늘 내장 시작 화면(www/index.html)으로 열고,
 * 시작 화면이 다리 {@link YeonShellPlugin#start} 로 서버 확인 → {서버}/pop 으로 옮긴다.
 *
 * - 열 수 있는 곳 = 저장한 서버 호스트 하나(allowNavigation). 그 밖 링크는 Capacitor 가 시스템 브라우저로 넘긴다(S3)
 * - 서버 주소가 아직 없으면(첫 실행) 허용 출처 없음 — 시작 화면의 주소 입력만
 */
public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(YeonShellPlugin.class);
        ServerConfig server = new ServerConfig(this);
        CapConfig.Builder b = new CapConfig.Builder(this)
                .setAndroidScheme("https")
                .setAllowMixedContent(false)
                .setWebContentsDebuggingEnabled(false);
        String host = server.host();
        if (host != null) {
            b.setAllowNavigation(new String[] {host});
        }
        this.config = b.create();
        super.onCreate(savedInstanceState);
    }
}
