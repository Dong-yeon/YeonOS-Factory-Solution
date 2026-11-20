# mobile/pda — YeonOS 현장 단말 껍데기 (POP·PDA 공용 APK)

설계: [docs/50](../../docs/50_현장_단말_APK_배포.md) 5·6장. 서버의 `/pop` 웹을 감싸기만 합니다. 화면·인증·권한은 서버 그대로입니다.
브라우저(크롬)로 같은 주소를 열어도 같은 일을 끝낼 수 있습니다(6.1).

> **상태: 골격만 있습니다. 아직 빌드하지 않았고 실기기에서도 확인하지 않았습니다.**
> 빌드와 실기기 확인은 사용자 로컬에서 합니다(2026-10-27 결정). 확인한 결과는 아래 [실기기 확인 목록](#실기기-확인-목록-docs50-62)에 기기·OS·날짜와 함께 적고, `docs/testing/sys.md` 에도 옮깁니다.

## 구성

```
mobile/pda/
├─ package.json · package-lock.json   Capacitor 8.5.3 (정확한 버전 고정)
├─ capacitor.config.json              appId io.smarthappy.yeonos.terminal, webDir www, https 만
├─ scripts/licenses.mjs               의존성 라이선스 확인 (npm run licenses)
├─ www/                               껍데기 안 화면 2개뿐: 서버 주소 입력 · 업데이트 필요/연결 안 됨 (S2)
└─ android/                           Capacitor 안드로이드 템플릿(MIT)에서 시작한 프로젝트 틀
   └─ app/src/main/java/io/smarthappy/yeonos/terminal/
      ├─ MainActivity.java            허용 출처 = 저장한 서버 호스트 하나 (S3)
      ├─ YeonShellPlugin.java         다리 YeonShell: start · setServer (내장 화면) / kiosk · appInfo (서버 웹, K6)
      ├─ ServerConfig.java            서버 주소 한 번 저장 — 바꾸려면 재설치 (S5, Q5)
      └─ VersionGate.java             GET {서버}/pda/version.json → minVersion 비교 (V1·V2)
```

시작 흐름:

1. 앱을 열면 내장 화면이 뜨고 `YeonShell.start()` 를 부릅니다.
2. 결과에 따라 다음 중 하나로 갑니다.
   - **SETUP**: 서버 주소를 입력받습니다. https만 받고, `/pda/version.json` 이 응답해야 저장한 뒤 앱을 다시 시작합니다.
   - **OPEN**: `{서버}/pop` 을 엽니다. 서버가 단말 등록 상태를 보고 활성화 화면, 작업장 홈, `/pop/wh` 중 하나로 보냅니다.
   - **UPDATE**: 업데이트 필요 화면을 보입니다.
   - **OFFLINE**: 연결 안 됨 화면과 [다시 연결]을 보입니다.

## 준비물 (빌드 머신)

| 항목 | 버전 | 근거 |
|---|---|---|
| Node.js | 22 이상 | `@capacitor/cli` 8.5.3 `engines` |
| JDK | 21 (Temurin) | Capacitor 8 안드로이드 라이브러리 `JavaVersion.VERSION_21`. 서버 저장소와 같음 |
| Android SDK | compileSdk·targetSdk **36**, minSdk **24** | `android/variables.gradle` |
| Android Gradle Plugin | 8.13.0 | `android/build.gradle` |
| Gradle | 8.14.3 | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Studio | AGP 8.13 을 지원하는 판 | 처음 열 때 IDE 가 안내하는 업그레이드는 받지 않습니다. 버전을 바꾸면 이 표도 고칩니다 |

## 빌드 절차

```bash
cd mobile/pda
npm ci                      # package-lock.json 그대로
npm run licenses            # 라이선스 확인(아래 표 기준) — 실패하면 멈춘다
npx cap sync android        # capacitor.settings.gradle·app/capacitor.build.gradle·assets 생성 (커밋하지 않음)
```

이어서 Android Studio 로 `mobile/pda/android` 를 엽니다.
- Gradle wrapper 의 jar·스크립트(`gradlew`)는 저장소에 넣지 않았습니다. Studio 가 `gradle-wrapper.properties` 대로 받습니다.
- 명령줄로 빌드하려면 먼저 `gradle wrapper --gradle-version 8.14.3` 으로 wrapper 를 만듭니다.

**서명** — 서명 키는 저장소 밖에 둡니다(docs/50 F6·S7). 빌드 머신의 환경 변수로만 넘깁니다(이름만 적습니다).

| 환경 변수 | 뜻 |
|---|---|
| `YEONOS_KEYSTORE` | keystore 파일 경로(저장소 밖) |
| `YEONOS_KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `YEONOS_KEY_ALIAS` | 키 별칭 |
| `YEONOS_KEY_PASSWORD` | 키 비밀번호 |

```bash
cd android && ./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

**배포**:
1. 빌드한 APK를 서버의 `PDA_APK_DIR` 에 `yeonos-pda.apk` 라는 이름으로 놓습니다. 함께 `release.json` 을 놓습니다. 절차는 [deploy/README 4.1](../../deploy/README.md)에 있습니다.
2. `app/build.gradle` 의 `versionName` 과 release.json 의 `version` 을 같게 맞춥니다.
3. `minVersion` 은 다리 계약이 깨질 때만 올립니다(V4).

## 의존성·라이선스 (docs/50 S1)

| 구분 | 패키지 | 라이선스 |
|---|---|---|
| 앱에 들어가는 것 | `@capacitor/android` 8.5.3, `@capacitor/core` 8.5.3 | MIT |
| 〃 (core 의 의존) | `tslib` 2.8.1 | 0BSD |
| 빌드 도구 95개(`@capacitor/cli` 8.5.3 포함) | MIT 70 · ISC 11 · BlueOak-1.0.0 10 · Apache-2.0 2 · Unlicense 2(`big-integer`·`stream-buffers`) | permissive — APK 에 들어가지 않음 |
| 안드로이드 템플릿·기본 아이콘 | Capacitor `android-template` (8.5.3) | MIT — 아이콘은 자리표시(교체 대상) |

`npm run licenses` 는 앱에 들어가는 의존성이 MIT·Apache-2.0·0BSD 가 아니거나, 빌드 도구 의존성이 허용 목록 밖이면 실패합니다. 플러그인을 추가할 때도 같은 기준을 씁니다.

## 지키는 것 (보안)

- **열 수 있는 곳**: 저장한 서버 호스트 하나뿐입니다(`allowNavigation`). 그 밖의 링크는 시스템 브라우저로 넘어갑니다.
- **통신**: https만 씁니다(`network_security_config` `cleartextTrafficPermitted=false`).
  - 사내 인증서(사설 CA)를 위해 사용자 CA 도 믿습니다(S6). **개인 단말에는 쓰지 않습니다**(회사 지급·MDM 또는 키오스크 단말만). 사설 CA 가 없는 고객용 빌드는 `android/app/src/main/res/xml/network_security_config.xml` 에서 `<certificates src="user" />` 줄을 빼고 빌드합니다(docs/50 10장 2026-10-27).
  - 서버 주소가 첫 실행 때 정해지므로 도메인별 설정은 미리 쓸 수 없습니다.
- **권한**: 인터넷과 카메라(고장 신고 사진, K8)만 씁니다. 촬영 파일은 앱 캐시에만 둡니다(`file_paths.xml`).
- **다리**: `kiosk`·`appInfo`·`start`·`setServer` 넷만 있습니다.
  - `setServer` 는 처음 한 번만 받습니다.
  - 다리가 받는 값은 모두 입력으로 다룹니다. 단말 유형·권한은 서버가 정합니다.
- **화면 JS**: `YeonShell` 을 직접 부르지 않습니다. `hb.pop`·`hb.scan` 이 다리가 있는지 보고 고릅니다(6.1). 이 연결은 MES 계약 K5·C6 으로 따로 합니다.

## 추정 (실기기에서 확인할 것)

- `allowNavigation` 에 넣은 서버 페이지에도 Capacitor 다리(`window.Capacitor.Plugins.YeonShell`)가 주입된다.
- 파일 입력 `capture="environment"` 가 웹뷰에서 카메라 앱을 연다(K8). 권한을 거부하면 갤러리만 열린다.
- MDM 이 없는 기기에서 `startLockTask()` 를 부르면 OS 가 화면 고정 확인을 묻는다(K3).

## 실기기 확인 목록 (docs/50 6.2)

기기·OS 버전·날짜를 적고 결과를 ✅/❌ 로 표시합니다. 끝나면 `docs/testing/sys.md` 에 옮깁니다.

| # | 확인 | 기대 | 결과(기기·OS·날짜) |
|---|---|---|---|
| A1 | 첫 실행 → 서버 주소 입력 | `http://`·경로 포함·응답 없는 주소는 거절, 맞는 주소는 저장 뒤 `/pop` | |
| A2 | 저장 뒤 주소 바꾸기 | 화면에 바꾸는 길 없음, 앱 데이터 삭제(재설치) 뒤에만 다시 입력 | |
| A3 | 최소 버전 막힘 | 서버 `minVersion` > 앱 `versionName` → 웹을 열지 않고 "업데이트 필요(현재·필요)" | |
| A4 | 서버 끊김 | "연결 안 됨" + [다시 연결]. 마지막 최소 버전보다 낮으면 업데이트 필요 | |
| A5 | 등록 안 된 기기 | `/pop/activate` (등록 코드 입력) | |
| A6 | POP 으로 등록 | 작업장 홈 `/pop` | |
| A7 | PDA 로 등록 | 창고 홈 `/pop/wh` | |
| A8 | 폐기된 단말 | 다음 요청에서 바로 끊김(활성화 화면) | |
| A9 | 키오스크 켬(POP) | 홈이 열리면 화면 고정 + 꺼지지 않음(10분 그대로). PDA 로 등록한 기기는 고정 안 됨(Q7) — POP 홈에 `hb.kiosk.attach()` 가 붙은 뒤 | |
| A10 | 키오스크 빠져나오기 | 오른쪽 위 구석 5초 누름 → 대화상자 → 감독자 배지 스캔 + PIN 맞으면 풀림 / 감독자 아님·자격 만료·PIN 틀림·잠김·오프라인이면 안 풀림(고정 유지) | |
| A11 | 비활동 내려감 | 키오스크 중에도 `POP_WORKER_IDLE_MIN` 뒤 작업자만 내려감 | |
| A12 | 고장 신고 사진 | [사진 찍기] → 카메라 권한 묻기 → 찍기 → 줄이기 → "붙음". 권한 거부 → 갤러리만, 신고는 그대로 | |
| A13 | 외부 링크 | 서버 밖 주소는 시스템 브라우저로, 웹뷰 안에서 안 열림 | |
| A14 | 하드웨어 스캐너(웨지) | 배지·LOT 스캔이 포커스 칸에 새지 않고 한 번만 | |
| A15 | 사내 인증서 | 사용자 인증서 설치 전 연결 실패 → 설치 뒤 연결 | |
| A16 | 화면 회전·세로 800×1280 | 넘침 없음, 누르는 대상 48px 이상 | |
| A17 | 구석 길게 누르기 | 2초에 떼면 안 열림, 5초면 열림. 구석 밑 머리 단추(언어 등)는 짧게 누르면 그대로 눌림, 5초 누른 뒤 떼도 안 눌림 | |
| A18 | 빠져나온 뒤 다시 잠김(시간) | 빠져나온 뒤 10분이 지나면 승인 없이 다시 고정 | |
| A19 | 빠져나온 뒤 다시 잠김(작업자·홈) | 작업자가 바뀌거나 내려가면, 또는 홈을 다시 열면 10분 전이라도 바로 다시 고정 | |
| A20 | 화면 고정 확인 거절(MDM 없음) | OS 의 "화면 고정" 확인에서 취소해도 화면은 그대로 쓸 수 있음(오류 없음) | |
