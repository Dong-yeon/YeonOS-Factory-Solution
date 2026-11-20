# 테스트 기록 — APQP (P6, docs/41)

> 실행 방법·이상 사용 체크리스트 A~K·데이터 규칙은 [TESTING.md](../../TESTING.md). 새 기록은 **이 파일 끝에** 덧붙인다(시간순).

## A1 PFMEA·AP 규칙표 (2026-10-23, V145·임시 V9408, 브랜치 `claude/pms-p5-work`, docs/41 4·4.1장)

자동: `./gradlew check`(JDK 21) **2,100건 통과(건너뜀 2), 실패 0**(main 501ec8fb 병합 뒤). 신규 `ApqpFmeaTest` 6. 화면: bootRun(8094, DEMO + 예시 규칙표 시드), Playwright 1366·390.

| 해 본 것 | 결과 | 조치 |
|---|---|---|
| AP 규칙표: S 9~10 만(빠짐 800) / 전체 L + S 9~10 H(겹침 200)를 사용(Y)으로 | 둘 다 400 `APQP_AP_INCOMPLETE`, 사용 안 함이면 저장(덮임 결과 겹침 200) | — |
| 규칙 행 오류: S 0 · O 5~2 · AP X | 행 키 1 + `sevFrom`·`occTo`·`ap` 칸 오류 | — |
| 같은 코드(소문자로) 다시 | `DUPLICATE` | — |
| 확정 FMEA 가 쓴 규칙표 규칙 바꾸기 / 이름만 바꾸기 / 직접 SQL 규칙 삭제 / 복사 | 409 `APQP_AP_SET_USED` / 200 / 트리거 거부 / 새 표(사용 안 함, 규칙 3) | — |
| 정상: 라우팅에서 단계 2 → 작업요소·기능(특성)·FE(S 9)·FM(불량 유형)·FC·사슬·위험(O 3·D 4) | S 9 · AP H(예시 규칙) | — |
| FE 심각도 9 → 5 / O 3 → 7 | AP L / AP M(서버 재계산) | — |
| 조치(조치 뒤 O 2) | 조치 뒤 AP L | — |
| 확정 → 목록 AP 높음 0·열린 조치 1, 확정본에 노드 추가 | 409 `APQP_FMEA_STATE` | — |
| 확정본 직접 SQL: 위험행 O·머리 제목 수정·노드 삭제 | 모두 트리거 거부 | — |
| 확정본 조치: 내용 바꾸며 완료 / 내용 그대로 완료·증빙 | `FIXED` / 200(P7) | 머리 row_ver 올리기를 트리거가 막아 409 → 상태 그대로 row_ver 만은 허용 |
| 새 리비전(복사) / 한 번 더 / 그동안 확정본 조치 | rev 2 = 노드 4·사슬 1·AP M·조치 완료 그대로 / 409 / 409 | — |
| rev 2 확정 | rev 1 폐기, 현행 목록 1건(rev 2)·전체 2건 | — |
| 확정 조건 미달: 사슬·원인 없는 형태 + O 없는 위험행 | 400 `APQP_RELEASE_CHECK`, 행 키 `FAIL:id`·`RISK:id`, `APQP_NO_CHAIN`·`APQP_NO_RISK`·`REQUIRED` | — |
| 작성 중 삭제: 낡은 rowVer / 맞는 rowVer | 409(하위 행 지움도 되돌림) / 하위 행까지 0, 상세 404 | — |
| 이상: 작업요소를 아이템 아래·단계를 단계 아래·4M 없음 | `APQP_LEVEL`·`APQP_LEVEL`·`m4` | — |
| 다른 라우팅의 단계(API·직접 SQL) | `APQP_STEP_ROUTING` / 트리거 거부 | — |
| 검사 공정 특성을 용접 단계에 / 다른 품목 제품 특성 | `APQP_CHAR_TARGET` | — |
| 아이템 기능에 FM 요청 / FE S 11 / 사슬 거꾸로 / 같은 사슬 | `APQP_LEVEL` / `RANGE` / `APQP_LEVEL` / `DUPLICATE` | — |
| 다른 단계 원인과 짝(API·직접 SQL) / 같은 짝 다시 | `APQP_PAIR` / 트리거 거부 / `DUPLICATE` | — |
| O 0·D 11·특별특성 ZZZ | 세 칸 오류 | — |
| 같은 rowVer 두 번 저장 | 두 번째 409 | — |
| 단계 삭제 / 아이템 삭제 | 아래 위험·사슬까지 / `APQP_LEVEL` | — |
| 권한: 조회만(만들기·확정·규칙표 저장) / SAVE 만(확정) | 403 / 403, 상세 editable false | — |
| **A사로 B사**: 목록·상세·규칙표 / A사 FMEA 에 노드·위험 수정·삭제 | 0건·404·404 / 404 | — |
| B사 FMEA 에 A사 규칙표·A사 라우팅·A사 제품 특성·A사 불량 유형 | 칸 오류(`apSetId`·`routingId`·`APQP_CHAR_TARGET`·`defectId`) | — |
| B사 범위 직접 SQL: A사 FMEA 노드 / A사 특성 / A사 규칙표 지정 / A사 머리 수정 / A사 규칙 삭제 | 트리거 거부 ×3 / 0행 / 0행 | — |
| 화면 1366: 규칙표(예시 배지·덮임 완전) → 확정 FMEA 가 쓰는 표는 잠금 안내·삭제 단추 없음 | 맞음 | — |
| 화면 1366: 신규(품번 입력 → 라우팅 고르기 → 규칙표) → 구조 5 → 기능·영향 S 9 → 단계 기능·형태 → 작업요소(대화상자) → 원인 → 영향 연결·원인 추가 → O 7·D 5 저장 | 위험행 S 9 · AP H 배지 | — |
| 조치 저장(조치 뒤 O 3) → 확정(대화상자) | 조치 1행, 확정 배지, 편집 단추 숨김·새 리비전 보임 | — |
| 390 | 화면 가로 스크롤 0 | — |
| 콘솔 | 오류 없음(favicon 404 제외) | 첫 시도에 `hb.searchPanel` 없음 → `hb.input.js`·`hb.dates.js` 싣기 / 트리 단추가 전역 파랑 → `hb-btn-ghost` 바탕 |

## 2026-10-23 데이터 접근 jOOQ 교체 (docs/41 4.2, 결정 docs/29 #52)

| 시험 | 기대 | 결과 |
|---|---|---|
| `ApqpFmeaTest` 무수정(main 과 diff 없음) — MyBatis 구현(main 599c288) / jOOQ 구현 | 6/6 / 6/6 | 같음 |
| 새 `ApqpLookupTest` — 품목 검색(대소문자 무시, `%` 는 글자 그대로)·라우팅·불량 유형·사용자·특성(제품 먼저, 라우팅 밖 공정·사용 안 함 제외)·구조 노드의 공정 표시 | MyBatis 구현 2/2 · jOOQ 구현 2/2 | 같음 |
| 같은 시험 — 목록 계산 칸 정렬(AP 높음 내림·오름, 제목, 2쪽)·라우팅 표시 글자·규칙표 코드 | 같은 순서·값 | 같음 |
| **A사로 B사**(조회 목록): 다른 회사 사용자에게 품목·라우팅·불량 유형 | 0건 | 맞음 |
| jOOQ 가드: 사용 위치·plain SQL·생성물 어긋남(INCLUDES 4표 추가 후 재생성)·판 | 통과 | 맞음 |
| 컴파일 실험(커밋 안 함): 생성물에서 `apqp_fmea.title` 이름 바꿈 / `risk.occ` 형 바꿈 | 컴파일 오류 | 5건 / 2건 |
| 전체 `./gradlew check`(JDK 21, main 599c288 병합 뒤) | 실패 0 | 2,155건 통과(건너뜀 2 — 실측 시험) |

## 2026-10-23 A2 관리계획서 (docs/41 5장, V153·임시 V9409) — `ApqpCpTest` 5건

| 시험 | 기대 | 결과 |
|---|---|---|
| 정상: 확정 PFMEA(용접 단계 FM 특성 = 용접 전류, 검출 관리, 특별특성 SC)로 만들기 + 끌어오기 | 라우팅 = FMEA 의 것, 행 1(단계 10·용접 전류·검사·SC·AP H) | 맞음 |
| 행을 SPC 로 고친 뒤 다시 끌어오기 | 더함 0·건너뜀 1, 고친 값 그대로 | 맞음 |
| 확정 시도: 반응계획·주기 없음 + 품목 제품 특성 CC 행 없음 | 400 `APQP_RELEASE_CHECK`, 행 키 `LINE:id`·`CHAR:id`, 상세에 빠짐 표시 | 맞음 |
| 채운 뒤 확정 → 스냅샷 | VARIABLE·상한 220, CC 행 특별특성 CC, 특성 마스터를 250 으로 바꿔도 220 | 맞음 |
| 확정본: 행 추가·끌어오기 / 직접 SQL 행·머리 수정·삭제 | 409 `APQP_CP_STATE` / 트리거 거부 | 맞음 |
| 새 리비전(복사·스냅샷 비움) / 한 번 더 | rev 2, 행 2 / 409 | 맞음 |
| rev 2 확정: 개정 사유 없음(API · 직접 SQL) | `changeReason` 칸 오류 / CHECK `ck_apqp_cp_reason` | 맞음 |
| 다음 단계: 시작품 / 양산 전 | `APQP_CP_PHASE_ORDER` / 새 번호·`prev_cp_id`·행 2 | 맞음 |
| 근거 PFMEA 새 리비전 확정 | 목록·상세 `fmeaStale`·`newerFmeaId`, 작성 중 CP 확정 거부(`APQP_CP_FMEA_STALE`), 근거 그대로 복사 거부 | 맞음 |
| 새 근거로 새 리비전 → 끌어오기 | 근거 위험행 비움, 같은 단계·특성·관리 방법 행이 있어 건너뜀 1 | 맞음 |
| 이상: 단계 없음·코드 밖 / 다른 품목 FMEA / 작성 중 FMEA 근거(API·직접 SQL) | `phase` / `fmeaId` / `APQP_CP_FMEA_STALE`·트리거 | 맞음 |
| 이상: 다른 품목 라우팅 단계 · 검사 공정 특성을 용접 단계에(API·직접 SQL) | `APQP_STEP_ROUTING`·`APQP_CHAR_TARGET` / 트리거 | 맞음 |
| 이상: 관리 방법·계측기·검사 유형 코드 밖, 시간 주기 200, 샘플 0 / 특성 없음 | 칸 오류 5개 / `charText` | 맞음 |
| 이상: 작성 중 FMEA 의 위험행을 근거로(API·직접 SQL) | `riskId` / 트리거 | 맞음 |
| 같은 단계 × 특성 × 관리 방법 두 번 / 순서 바꾸기 / 같은 rowVer 두 번·낡은 rowVer 삭제 | `DUPLICATE` / 위로 / 409·409 | 맞음 |
| 근거 위험행 행이 있는데 근거 FMEA 비우기 | `APQP_CP_FMEA_USED` | 맞음 |
| 권한: 조회만(만들기·끌어오기·확정) / SAVE 만(확정) / FMEA 화면 권한만(상세) | 403 / 403 / 403 | 맞음 |
| **A사로 B사**: 목록·상세·행 추가·행 수정·끌어오기·근거 FMEA 목록 | 0건·404·404·404·404·0건 | 맞음 |
| B사 관리계획서에 A사 FMEA·A사 라우팅 단계·A사 특성 | `fmeaId`·`stepId`·`charId` 칸 오류 | 맞음 |
| B사 범위 직접 SQL: A사 CP 에 행 / A사 특성 / A사 FMEA 지정 / A사 머리 수정·행 삭제 | 트리거 거부 ×3 / 0행·0행 | 맞음 |
| 화면 1366(DEMO 회사, 제목 ZT): 신규 → 품번 → 근거 PFMEA(라우팅 잠김) → 저장 = 끌어온 행 1·특별특성 빠짐 2 | 맞음 | — |
| 행 고르기 → 반응계획·주기 50 개마다·육안·공정검사 저장 → 확정 | 거부 사유에 빠진 특성 2개 이름 | — |
| 빠짐 "항목 추가" ×2 → 확정 | 확정·시작품, "다음 단계로" 보임, 행 편집 숨김 | 첫 시도에 작성 중에도 "다음 단계로" 가 보임(`.apqp-inline` flex 가 hidden 을 덮음) → `[hidden]` 규칙 / 확정본에서 행을 고르면 편집 칸이 보임 → 작성 중에만 |
| 390 | 가로 스크롤 0(표는 안에서 스크롤) | — |
| 콘솔 | 오류 없음(favicon 404, 의도한 확정 거부 400 제외) | — |
| 검사계획 대조: QMS 검사계획(공정검사·용접, 용접 전류 캘리퍼 50개마다·용접 강도 육안 LOT 마다)을 QMS API 로 확정, 양산 CP 행 A(육안)·B(출하검사)·글자 행 | A = DIFF(계획 계측기 CALIPER), B = MISSING_IN_PLAN, 계획의 용접 강도 = MISSING_IN_CP, 대조 안 함 1 | 맞음 |
| 양산 단계 확정 | 400 `APQP_CP_NOT_IN_PLAN`, 행 키 `LINE:B` 만(DIFF 는 막지 않음) | 맞음 |
| B 를 공정검사로 → 맞음 1, 남은 것 DIFF → 확정 | 200 | 맞음 |
| **A사로 B사**: B사 양산 CP(같은 특성 번호·공정) 대조 | A사 검사계획을 읽지 않음 → MISSING_IN_PLAN | 맞음 |
| `codeUsable` = `Routines.hbCodeUsable`(jOOQ 루틴) — 계측기·특별특성 코드 밖 칸 오류 | 기존 시험(ApqpFmeaTest·ApqpCpTest) 그대로 통과 | 맞음 |
| 화면 1366: 검사계획 대조 영역(데모에 해당 공정검사 계획 없음) | "검사계획에 없음" 1행, 관리계획서 / 검사계획 값 나란히 | 맞음 |
| 전체 `./gradlew check`(JDK 21, main 077dd74 병합 뒤) | 실패 0 | 2,208건 통과(건너뜀 2 — 실측 시험) |

## 2026-10-23 A3 1차 (docs/41 11장, V158·임시 V9410) — `ApqpA3Test` 3건

| 시험 | 기대 | 결과 |
|---|---|---|
| 산출물 PFMEA ← 작성 중 PFMEA(번호만, 리비전 비움) | 제출 중·문서 칸 = 번호·DRAFT, 반영 0건 | 맞음 |
| 같은 리비전 다시 연결 | `PMS_APQP_SAME` | 맞음 |
| PFMEA 확정 → [상태 반영] | 1건 승인, 이력 LINK 표시 "번호 rev 1" | 맞음 |
| 새 리비전 확정(rev 1 폐기) | 산출물 승인 유지, 문서 칸 상태 OBSOLETE(L2) | 맞음 |
| 관리계획서 확정본 연결 | 바로 승인 | 맞음 |
| 폐기본 지정 / 없는 번호 / 없는 리비전 / 종류 XX | `PMS_APQP_OBSOLETE` / `PMS_APQP_NOT_FOUND` / `PMS_APQP_REV_NOT_FOUND` / `kind` | 맞음 |
| PM 이지만 APQP 화면 조회 권한 없음 | 403 | 맞음 |
| **A사로 B사**: B사 프로젝트에서 A사 PFMEA 번호 / 직접 SQL 로 A사 리비전 지정 | `PMS_APQP_NOT_FOUND` / 트리거 거부 | 맞음 |
| 라우팅 새 리비전(같은 품목·공장·대안, 더 늦은 시작일) 확정 | 확정 PFMEA `routingStale` 참(상세·목록), 상태 그대로, 그 FMEA 로 만든 CP 도 참, 라우팅 없는 FMEA 는 거짓 | 맞음 |
| 결재 정책 N 에서 상신 / Y 에서 바로 확정 | 409 `APQP_APPROVAL_POLICY` ×2 | 맞음 |
| 상신 → 결재 중: 편집 API / 직접 SQL 머리·위험행 수정 | 409 `APQP_FMEA_STATE` / 트리거 거부 ×2, 목록 현행에 결재 중 | 맞음 |
| 결재함 승인 | 확정·확정 시각 | 맞음 |
| 관리계획서 상신(스냅샷 찍힘) → 행 추가 / 반려 | 409 `APQP_CP_STATE` / 작성 중·편집 가능 | 맞음 |
| 확정 조건 미달(반응계획 없는 행) 상신 | 400 `APQP_RELEASE_CHECK`, 결재 요청 생기지 않음 | 맞음 |
| 화면 1366(DEMO 회사, ZT 제목·결재자 ZT 사용자 추가): PFMEA 결재선 고르기 → 상신 | 확정 단추 숨김·결재 상신 영역, 상신 뒤 "결재 중" 배지·안내 | 맞음 |
| PMS 산출물 화면: 연결한 PFMEA 칸 | "제출됨 — PFMEA 번호 rev 1 (결재 중) 연결", 연결 영역 보임 | 맞음 |
| 390 | 가로 스크롤 0 | 맞음 |
| 콘솔 | 오류 없음(favicon 404 제외) | 첫 시도에 상신 400 — `IN_APPROVAL` 이 상태 칸 varchar(10) 를 넘음 → V9410 에서 varchar(12) |

전체 `./gradlew check`(JDK 21): 2,229건 통과(건너뜀 2 — 실측 플래그), 실패 0.

## 2026-10-23 A3 2차 — 재검토 표시 ②③ (docs/41 11.6, 마이그레이션 없음) — `ApqpA3Test` 1건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| 작성 중 PFMEA(적용된 ECO 있음) | `changes` 빈 값(M1) | 맞음 |
| 확정 뒤: 확정 5일 전 적용 ECO / 확정일 적용 설계 ECO / 4M 승인(적용 전) / 설계 승인만 / 4M 승인 뒤 취소 / 작성 중 4M / 다른 품목 적용 ECO | 확정일 적용·4M 승인 둘만, 순서 적용일→승인일→eco_id, 4M 은 유형(MACHINE·METHOD), 설계 ECO 는 빈 유형 | 맞음 |
| 4M ECO 가 나중에 적용됨 | 한 줄 그대로(②③ 겹침 한 번), 적용일 채워짐 | 맞음 |
| 표시만 | 확정본 상태·조치 편집 가능 그대로 | 맞음 |
| 관리계획서 | 작성 중 빈 값 → 확정 뒤 같은 목록 | 맞음 |
| 새 리비전 확정 | 옛 리비전(폐기) 빈 값, 새 리비전은 자기 확정일 기준 | 맞음 |
| **A사로 B사**: B사 같은 품번에 적용된 ECO / B사 사용자로 A사 PFMEA 상세 | A사 목록에 없음 / 404 | 맞음 |
| 화면 1366·390(DEMO 회사, ZT ECO 2건 — 확정일 적용 설계 변경·4M 승인): PFMEA 상세 | 안내 "2건" + ECO 한 줄씩(번호·제목·종류·적용/승인일·4M 이름), 390 가로 스크롤 0, 콘솔 오류 없음(favicon 404 제외) | 맞음 |

전체 `./gradlew check`(JDK 21): 2,278건 통과(건너뜀 2 — 실측 플래그), 실패 0.

## 2026-10-24 A4 — PFMEA·관리계획서 엑셀 양식 (docs/41 12장, V168·임시 V9411) — `ApqpA4Test` 2건

| 시험 | 기대 | 결과 |
|---|---|---|
| 작성 중 PFMEA 내려받기 | 파일명 `번호_rev1_DRAFT.xlsx`, 제목 아래 "확정본 아님" | 맞음 |
| 확정 뒤 | 파일명 `번호_rev1.xlsx`, 표시 없음, 시트 2개(위험 분석·조치), 머리(번호·품목·확정일), 칸 제목 14개, 위험행 한 줄(단계 `10 ZT 용접`·기능+특성·FE(S9)·FM·작업요소(설비)·FC·O/D·AP H) | 맞음 |
| AP H 칸 색·인쇄 설정 | 색 있음, 가로·A3·머리 행 반복·바닥글 `번호 rev 1` | 맞음 |
| 조치 표 | 위험행 번호·구분 이름(검출)·담당 이름, `=HYPERLINK(…)` 로 시작하는 조치 글 → 앞에 `'`(글자 칸) | 맞음 |
| 영어 사용자 | 칸 제목·제목 영어 | 맞음 |
| 관리계획서 확정본 | 제목 "관리계획서 — 단계", 머리(번호·근거 PFMEA), 칸 제목 12개, 행(단계·특성·관리 방법 코드명·평가 방법·샘플·반응계획·근거 AP H) | 맞음 |
| PRINT 없음(SEARCH 만) | 상세 조회는 200, 엑셀 PFMEA·CP 403, `sys_prog` 에 PRINT | 맞음 |
| 행 상한(정책 100, 위험행 1 + 조치 100) | 400 `TOO_MANY_ROWS`, 관리계획서(1행)는 200 | 맞음 |
| **A사로 B사**: B사 사용자(PRINT 있음)로 A사 PFMEA·CP id / 없는 id | 404 / 404 | 맞음 |
| 화면 1366·390(DEMO 회사, ZT 확정 PFMEA): 상세 [엑셀 양식] | 단추 보임(관리자), 내려받은 `번호_rev1.xlsx` — 위험 분석·조치 시트, A3 가로(용지 8)·머리 행 반복·바닥글, 위험행 1줄(AP H), 390 가로 스크롤 0, 콘솔 오류 없음(favicon 404 제외) | 맞음 |

전체 `./gradlew check`(JDK 21): 2,321건 통과(건너뜀 2 — 실측 플래그), 실패 0.

## 2026-10-24 A3-5 — PFMEA 조치 → PMS 이슈 (docs/41 11.7, V171·임시 V9412) — `ApqpA3Test` 1건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| 프로젝트 있는 PFMEA 의 열린 조치 → [이슈로] | 이슈 = 제목 `[번호 rev 1] 조치`, 심각도 HIGH(AP H), 출처 APQP·근거 조치 id, 프로젝트·품목·담당·기한 복사, OPEN. 화면 표시 API 에 1건 | 맞음 |
| 같은 조치 다시 | 409 `PMS_APQP_ISSUE_EXISTS` | 맞음 |
| 직접 SQL 로 근거 조치 바꾸기 | 트리거 거부(고정) | 맞음 |
| 이슈 해결 | 조치는 OPEN 그대로(상태 따로 — L7) | 맞음 |
| 프로젝트 규칙: FMEA 프로젝트와 다른 projId / FMEA 프로젝트 없음 + projId 없음 / 없는 projId / 맞는 projId | `PMS_APQP_PROJ_MISMATCH` / projId REQUIRED / projId NOT_FOUND / 만들어짐 | 맞음 |
| 끝난(DONE) 조치 | `PMS_APQP_ACTION_CLOSED` | 맞음 |
| 권한: pms-issue SAVE 만(PFMEA 조회 없음) / PFMEA·이슈 조회만 / 없는 조치 | 403 / 403(화면 issueSave 거짓) / 404 | 맞음 |
| **A사로 B사**: B사 사용자(B사 프로젝트)로 A사 조치 / B사 범위 연결 이슈 조회 / 직접 SQL 로 B사 이슈에 A사 조치 | 404 / 0건 / 트리거 "is not of company" | 맞음 |
| 화면 1366·390(DEMO 회사, ZT PFMEA·프로젝트 있음): 공정 단계 → 위험행 → 조치 → [이슈로] → 확인 | "이슈를 만들었습니다", 조치 아래 "PMS 이슈 번호 (열림) · 프로젝트 번호", [이슈로] 사라짐, 390 가로 스크롤 0, 콘솔 오류 없음(favicon 404 제외) | 맞음 |

전체 `./gradlew check`(JDK 21): 2,335건 통과(건너뜀 2 — 실측 플래그), 실패 0.

## 2026-10-24 A3-3 — 확정 PFMEA·관리계획서 → QMS 관리문서 (docs/41 11.8, V176·임시 V9413) — `ApqpA3Test` 1건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| 작성 중 PFMEA | 화면 canPublish 거짓, 올리기 409 `APQP_FMEA_STATE` | 맞음 |
| 확정 → 올리기 | 새 QMS 문서(유형 PFMEA·이름 = 제목·대상 품목 ZT-BRKT), 리비전 DRAFT, 첨부 `번호_rev1.xlsx` 1개, `apqp_qms_doc` 1행 | 맞음 |
| 다시 올리기 | 같은 리비전, 첨부 그대로 1개, 화면 = 연결 표시(DRAFT)·단추 없음 | 맞음 |
| 새 리비전 확정 → 올리기(QMS 에 작성 중 남음) | 409 `QMS_DOC_OPEN_EXISTS`, APQP 연결 행 없음(롤백) | 맞음 |
| QMS 에서 rev 1 배포 뒤 다시 | 같은 QMS 문서 번호의 리비전 2개(PFMEA 문서는 1개) | 맞음 |
| 폐기된 rev 1 | canPublish 거짓 | 맞음 |
| 관리계획서 확정 → 올리기 | QMS 문서 유형 CP 1개 | 맞음 |
| 권한: PRINT 있고 QMS 문서 SAVE 없음 / QMS 문서 SAVE 있고 PRINT 없음 | 화면 canPublish 거짓·403 / 403, 연결 행 없음 | 맞음 |
| 직접 SQL 로 연결 행 고치기·지우기 | 트리거 거부 | 맞음 |
| **A사로 B사**: B사 사용자(권한 다 있음)로 A사 FMEA 올리기·조회 / B사 범위 연결 행 / 직접 SQL 로 A사 FMEA 를 원본으로 | 404·404 / 0건 / 트리거 거부 | 맞음 |
| 화면 1366·390(DEMO 회사, ZT 확정 PFMEA): [QMS 문서로 올리기] → 확인 | "QMS 문서 DOC… 에 올렸습니다", 도구 줄 "QMS 문서 번호 rev A0 (작성 중)"·단추 사라짐, QMS 관리문서 목록에 보임, 390 가로 스크롤 0, 콘솔 오류 없음(favicon 404 제외) | 맞음 |

전체 `./gradlew check`(JDK 21, main #478 병합 뒤): 2,393건 통과(건너뜀 2 — 실측 플래그), 실패 0.

## 2026-10-24 11.9 — 라우팅 바꾸기·단계 다시 잇기 (docs/41 11.9, 마이그레이션 없음) — `ApqpA3Test` 1건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| 라우팅 A0(10 용접·20 도장) PFMEA·관리계획서 확정 → A1(10 용접·20 조립) 확정, 새 PFMEA 리비전 | 재검토 표시 참 | 맞음 |
| 미리보기(A1) | 잇기 1(10 → A1 10)·글자 1("20 ZT 도장"), 머리 라우팅 그대로 | 맞음 |
| 고르기 규칙: 작성 중 A2 / 지금 A0 / 없는 id / **A사로 B사** B사 라우팅 | `APQP_ROUTING_NOT_RELEASED` / `APQP_ROUTING_SAME` / NOT_FOUND / NOT_FOUND | 맞음 |
| 적용 rowVer 다름 | 409 | 맞음 |
| 적용 | 머리 라우팅 A1, 재검토 표시 거짓, 단계 노드 = [A1 10, 글자 "20 ZT 도장"], 위험행 수 그대로 | 맞음 |
| 감사 기록 | 글자로 바뀐 노드 UPDATE 1건 — 전 값에 옛 step_id | 맞음 |
| 확정 뒤 바꾸기 | 409 `APQP_FMEA_STATE` | 맞음 |
| 관리계획서 새 리비전 + 근거를 새 PFMEA 로 | 라우팅 A1, 행 단계 = [A1 10, 글자 "20 ZT 도장"], 원본 리비전 행은 옛 단계 그대로 | 맞음 |
| 화면 1366·390(DEMO 회사, ZT PFMEA 작성 중, 같은 품목 대안 2 라우팅 확정 — 단계 20 없음): 라우팅 바꾸기 → 미리보기 → 바꾸기 | 미리보기 "잇기 3건, 글자 단계로 1건"·단계별 줄, 적용 뒤 머리 라우팅 = 대안 2, 고르기 목록 = 대안 1(지금 것 빠짐 — 처음엔 방금 고른 값을 되살리다 빈칸이 돼 고침), 되돌려 바꾸기도 됨, 390 가로 스크롤 0, 콘솔 오류 없음(favicon 404 제외) | 맞음 |

전체 `./gradlew check`(JDK 21): 2,408건 통과(건너뜀 2 — 실측 플래그), 실패 0.

## 2026-10-24 QA 관찰 반영 — `ApqpA3Test` 1단계 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| (QA #495 a) 리비전마다 sourceKey 가 다름 | `APQP:{종류}:{원본 id}` — 리비전 = 새 행 id(기존 A3-3 시험에서 rev 2 가 같은 QMS 문서의 새 리비전으로 따로 생김) | 맞음(코드 변경 없음) |
| (QA #495 b) 연결된 QMS 문서 사용 중지 뒤 새 확정본 올리기 | 409 `APQP_QMS_DOC_INACTIVE`, 문구에 문서 번호, 연결 행 없음(막기는 QMS 정책 그대로) | 맞음 |
| (QA #514) 포트폴리오 grid 정렬 — NOT NULL 키(번호·이름·PM·진척률)에서 nulls last 뺌 | `SortNullsLastGuardTest` 통과(KNOWN 줄 지움), PMS 시험 그대로 | 맞음 |

전체 `./gradlew check`(JDK 21): 2,490건 통과(건너뜀 11 — 실측 플래그), 실패 0.

## 2026-10-24 M4 특성 겹침 · M2 목록 재검토 칸 · E-3 자기 배포 ECO (docs/41 11.6.1, 마이그레이션 없음) — `ApqpA3Test` 1건 추가·1건 보강

| 시험 | 기대 | 결과 |
|---|---|---|
| 확정 PFMEA(기능 특성 ZP1) + 4M ECO 3건: 영향 특성 ZP1·ZP9 / ZP9 만 / 설계 변경(세부 없음) | 겹침 [ZP1 "ZT 용접 전류"] / 빈 목록 / null(문서에 없는 ZP9 는 빠짐) | 맞음 |
| 확정 뒤 마스터 ZP1 을 특별특성(SC)으로 | 겹침 special = 참(마스터 원본) | 맞음 |
| 관리계획서 확정 → QMS 문서 → 그 리비전 + 품목을 DOC 대상으로 담은 ECO 적용 | 그 관리계획서: 재검토에서 빠지고 `own` = [그 ECO], 겹침은 행 특성으로 같은 규칙 | 맞음 |
| 같은 품목의 PFMEA(그 ECO 가 담지 않은 문서) | 그 ECO 가 재검토에 그대로, `own` 빈 목록 | 맞음 |
| **A사로 B사**: B사 ECO 에 A사 문서 리비전을 DOC 대상으로 / B사 사용자로 A사 관리계획서 상세 | PLM 트리거 거부(not of company), A사 `own` 그대로 / 404 | 맞음 |
| 목록 칸(M2): 라우팅 새 리비전 확정 → PFMEA·관리계획서 목록 / 근거 PFMEA 새 리비전 확정 / 폐기본(status=ALL) | `review` = ROUTING / 관리계획서 ROUTING,FMEA / 빈 칸. 열 정의 `review` 정렬 불가·제목 "재검토" | 맞음 |
| 화면 1366·390(DEMO 회사, ZT 확정 PFMEA + 4M ECO 2건 + 관리계획서 배포 ECO): PFMEA·관리계획서 상세, 두 목록 | ECO 줄 끝 "겹치는 특성 1 용접 너트 인발 강도(특별특성)" / "특성 겹침 없음(다른 영향은 상세에서 확인)", 관리계획서만 "이 리비전을 배포한 ECO: …", 목록 머리에 "재검토", 390 가로 스크롤 0, 콘솔 오류 없음(favicon 404 제외) | 맞음 — 처음엔 특성 번호만 보여 DEMO 번호 "1" 이 뜻이 없어 이름을 붙임 |

전체 `./gradlew check`(JDK 21): 2,494건 통과(건너뜀 12 — 실측 플래그), 실패 0.

## 2026-10-24 목록 엑셀 + 재검토 글자 (docs/41 11.6.1 X-1~X-3, V188·임시 V9414) — `ApqpA3Test` 1건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| grid JSON `reviewNm`(ko / en) | "라우팅 새 리비전" / "New routing revision", 관리계획서 "라우팅 새 리비전, 근거 PFMEA 새 리비전" — 엑셀과 같은 값(`ReviewText` 한 곳, docs/36 18.9 ⓑ) | 맞음 |
| 확정 PFMEA·작성 중 관리계획서, 재검토 없음 → PFMEA 목록 엑셀 | 재검토 칸 빈 칸 | 맞음 |
| 라우팅 새 리비전 확정 + PFMEA 새 리비전 확정 → PFMEA 엑셀(status ALL) / 관리계획서 엑셀 | "확정 | 라우팅 새 리비전", "폐기 | (빈 칸)" / "라우팅 새 리비전, 근거 PFMEA 새 리비전" — **코드값(ROUTING·FMEA) 아님, 화면과 같은 글자** | 맞음 |
| 요청 로케일 en + 열 고르기 [fmeaNo, reviewNm] | 열 2개, 머리 "Review", 값 "New routing revision" | 맞음 |
| 모르는 열 / 재검토로 정렬 / EXCEL 없는 사용자(SEARCH 만) | 400 / 400 / 403 | 맞음 |
| **A사로 B사**: B사 사용자(EXCEL 있음)가 status=ALL 로 내려받기 | 데이터 행 0(A사 행 없음) | 맞음 |
| 화면 1366(DEMO, admin): PFMEA·관리계획서 목록 오른쪽 클릭 → "엑셀로 내보내기" / 근거 PFMEA 새 리비전 확정 뒤 관리계획서 목록 | 메뉴 보임, `PFMEA_….xlsx`·`관리계획서_….xlsx` 내려받음(머리 "재검토", 코드값 없음) / 재검토 칸 "근거 PFMEA 새 리비전"(서버 글자, 경고 배지), 오류 없음 | 맞음 |

전체 `./gradlew check`(JDK 21, main d5a6e33f 병합 뒤): 2,526건 통과(건너뜀 14 — 실측 플래그), 실패 0.

## 2026-10-26 A3-6 준비 — `ApqpLookup.current` (docs/41 11.11, 마이그레이션 없음) — `ApqpA3Test` 1건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| 작성 중 PFMEA 만 | 빈 목록 | 맞음 |
| PFMEA 확정 + 관리계획서 양산(작성 중)·시작품(확정, QMS 문서 올림) | [FMEA 번호 rev1 (단계 없음, 문서 없음), CP 번호 rev1 PROTO (문서 있음)] — 작성 중 양산 빠짐 | 맞음 |
| PFMEA 새 리비전 작성 중 / 확정 | rev 1 그대로 / rev 2 만(폐기 rev 1 빠짐 — 번호마다 하나) | 맞음 |
| 없는 품목 id | 빈 목록(예외 없음) | 맞음 |
| **A사로 B사**: B사 범위에서 A사 품목 id | 빈 목록(RLS) | 맞음 |

전체 `./gradlew check`(JDK 21): 2,533건 통과(건너뜀 15 — 실측 플래그), 실패 0.

## 2026-10-26 재검토 칸 → 플랫폼 LABEL 열(X2) (docs/41 11.6.1 X-2, 마이그레이션 없음) — `ApqpA3Test` 보강

| 시험 | 기대 | 결과 |
|---|---|---|
| 열 정의 `review` (ko / en) | type LABEL·정렬 불가·제목 "재검토", 문구 표 ROUTING = "라우팅 새 리비전" / "New routing revision", 관리계획서 FMEA = "근거 PFMEA 새 리비전" | 맞음 |
| grid JSON | `review` 코드 그대로(ROUTING, 관리계획서 ROUTING,FMEA) — `reviewNm` 칸 없음 | 맞음 |
| 목록 엑셀(앞 절 시험 그대로 — 열 이름 `review`) | 화면과 같은 문구(코드값 아님), en 은 영어 | 맞음 |
| 화면 1366·390(DB 초기화 뒤 DEMO, admin — 확정 PFMEA·관리계획서 만든 뒤 PFMEA 새 리비전 확정) | 관리계획서 목록 재검토 칸 "근거 PFMEA 새 리비전"(경고 배지), 두 목록 엑셀 — 머리 "재검토", 문구 있음·코드값 없음, 390 가로 스크롤 0, 오류 없음 | 맞음 |
| (관찰·범위 밖) 헤드리스 Playwright 내려받기 이름 | 관리계획서(한글 화면 이름)가 가끔 "download" — 서버 헤더 `filename*=UTF-8''관리계획서_…` 는 정상, 거래처 목록도 같은 현상. 공통 `hb.download`·브라우저 쪽, 실제 브라우저 미검증 | 범위 밖 |

전체 `./gradlew check`(JDK 21): 2,560건 통과(건너뜀 15 — 실측 플래그), 실패 0.

## 2026-10-26 M4 확장 — 4M 영향 검사계획 겹침, B안 (docs/41 11.6.2, 마이그레이션 없음) — `ApqpA3Test` 1건 추가

| 시험 | 기대(B안 — A안에서 바뀌는 줄 표시) | 결과 |
|---|---|---|
| 관리계획서 대조가 본 검사계획 | `compare.plans` = [공정검사·용접 확정본] | 맞음 |
| 4M → 그 확정본 | hits [ZWELD PROCESS rev] | 맞음 |
| 4M → 같은 계획의 작성 중 새 리비전 | unknown 1 (A: hits) | 맞음 |
| 4M → 다른 계획(출하검사) | unknown 1 (A: unrelated 1) | 맞음 |
| 4M → 출하검사 + 그 확정본 | hits 1 + unknown 1 (A: hits 1 + unrelated 1) | 맞음 |
| 4M 에 검사계획 없음 | planOverlap null(표시 없음) | 맞음 |
| 같은 ECO 의 PFMEA 재검토 줄 | planOverlap null | 맞음 |
| (QMS 규칙) 한 ECO 에 같은 검사계획 두 번 | `uq_qms_change_char_ref` 거부 — APQP 는 중복을 신경 쓰지 않아도 됨 | 맞음(QMS) |
| **A사로 B사**: B사 4M / B사 사용자로 A사 관리계획서 | A사 재검토 목록에 없음 / 404 | 맞음 |
| 화면 1366·390(DEMO, 관리계획서와 무관한 검사계획을 가리킨 4M): 관리계획서·PFMEA 상세 | 관리계획서 줄 끝 "검사계획 비교 불가 1건(같은 계획의 다른 리비전일 수 있음 — 상세에서 확인)", PFMEA 줄엔 없음, 390 가로 스크롤 0, 오류 없음 | 맞음 |

전체 `./gradlew check`(JDK 21): 2,571건 통과(건너뜀 15 — 실측 플래그), 실패 0.

## 2026-10-26 M4 확장 A안 전환 — QMS `InspPlanLookup.keys` (docs/41 11.6.2, 마이그레이션 없음) — `ApqpA3Test` 기대값 3줄

| 시험 | 기대(A안) — B안 때 값 | 결과 |
|---|---|---|
| 4M → 대조가 본 확정본 | hits [ZWELD PROCESS rev] — 같음 | 맞음 |
| 4M → 같은 계획의 작성 중 새 리비전 | hits [ZWELD PROCESS 새 rev] — B: unknown 1 | 맞음 |
| 4M → 다른 계획(출하검사) | unrelated 1 — B: unknown 1 | 맞음 |
| 4M → 출하검사 + 확정본 | hits 1 + unrelated 1 — B: hits 1 + unknown 1 | 맞음 |
| 4M 검사계획 없음 / PFMEA / **A사로 B사** | none / null / B사 4M 안 보임·404 — 같음 | 맞음 |
| 화면 1366·390(DEMO, 관리계획서와 무관한 검사계획을 가리킨 4M) | 관리계획서 줄 "검사계획 겹침 없음(다른 영향은 상세에서 확인)"(B 때 "비교 불가 1건"), PFMEA 줄엔 없음, 390 가로 스크롤 0, 오류 없음 | 맞음 |

전체 `./gradlew check`(JDK 21): 2,580건 통과(건너뜀 15 — 실측 플래그), 실패 0.

## 2026-10-29 A5-1 공정 흐름도 PFD (docs/41 13.6, 마이그레이션 없음) — `ApqpFlowTest` 4건 추가, `ApqpA4Test` 시트 수 2 → 3

| 시험 | 기대 | 결과 |
|---|---|---|
| 라우팅 10 용접(CT 45.5) / 20 검사(INSPECT) / 30 도금(외주) / 40 포장(PACK) + 위험행 1(S9 → AP H) | 순서 10·20·30·40, 기호 가공·검사·외주·포장, CT 45.5, 위험 1/0/0/0, AP H 1/0/0/0, 특성 ZP1 특별 CC·PFMEA 출처, 관리계획서 없음 | 맞음 |
| PFMEA 확정 → 관리계획서 끌어오기(단계 10 행) | cpId, 행 수 1/0/0/0, 특성 출처 PFMEA·CP 둘 다 | 맞음 |
| 관리계획서 화면 권한 없는 사람 | cpId 없음·"있음(볼 권한 없음)", 행 수 0 | 맞음 |
| **원본이 바뀌면 바로**: 단계 15 추가 / 용접 공정 외주 전환 / 특성 특별특성 해제 | 10·15·20·30·40, 10 = 외주, 특별 false | 맞음 |
| 라우팅 없는 PFMEA | 단계 없음 | 맞음 |
| 엑셀 "공정 흐름" 시트 | 머리 8칸, 10행 = "10 / ZWELD ZT 용접 / 가공 / 45.5 / ZP1 ZT 용접 전류 [CC(안전·법규)] / (CP 없음 빈칸) / 1 / 1", 30행 = 외주 | 맞음 |
| 권한: apqp-fmea 없음(apqp-cp 만) / **A사로 B사** 흐름·엑셀 | 403 / 404·404 | 맞음 |
| 화면 1366·390(DEMO, 품목 64310-DM100 확정 라우팅 4단계로 ZT PFMEA 작성 + 단계 10 기능 특성·위험행 1) → [공정 흐름] | 10 가공·CT 45s·특성 "1 용접 너트 인발 강도 [CC]"·위험행 1·AP 높음 1 / 20 가공 / 30 외주(점선 칸) / 40 검사(각진 칸), "관리계획서 없음", 390 가로 스크롤 0, 화면 오류·4xx 없음 | 맞음 |

전체 `./gradlew check`(JDK 21): 2,772건 통과(건너뜀 15 — 실측 플래그), 실패 0.

## 2026-10-29 A5-2a DFMEA (docs/41 13.7, V211·임시 V9417) — `ApqpDfmeaTest` 6건 추가

| 시험 | 기대 | 결과 |
|---|---|---|
| 신규 DFMEA(ZT-ASSY · 확정 BOM A · 도면 ZT-DWG rev A) + [BOM 1~2단계로 시작] | 번호 DF-yyyy-nnnn, 라우팅 없음, 시스템 1 · 하위 시스템 2(ZT-SUB1·SUB2, 자품목 연결) · 구성품 2(각 자품목의 오늘 유효 확정 BOM 줄), 도면 글자 "ZT-DWG rev A …" | 맞음 |
| 손으로 노드: 하위 시스템(시스템 아래) / STEP / 시스템 아래 구성품 | 저장 / CODE / APQP_LEVEL | 맞음 |
| 뿌리(SYSTEM) 삭제 · 자품목 노드가 있을 때 BOM 비우기 | APQP_LEVEL · APQP_BOM_USED | 맞음 |
| 특성 후보 | 제품 특성 ZD1(머리)·ZD2(자품목)만, 공정 특성 없음 | 맞음 |
| 볼트 특성(ZD2)을 커버 하위 시스템에 / 공정 특성을 시스템에 — API · 직접 SQL | APQP_CHAR_TARGET ×2 / 트리거 "does not belong to this item/component" ×2 | 맞음 |
| 위험행(S9·O3·D4) → 확정 → 엑셀 | AP H, 시트 2개(공정 흐름 없음), 제목 "설계 FMEA (DFMEA)", 머리 BOM "1 · A", 칸 하위 시스템·구성품(공정 단계·작업요소 없음) | 맞음 |
| 새 리비전 | 같은 번호, 자품목 연결 그대로 | 맞음 |
| 관리계획서 근거로 확정 DFMEA — API · 직접 SQL | NOT_FOUND / "non-PFMEA" | 맞음 |
| 대상 검사: 라우팅 / 다른 품목 BOM / WI 문서 리비전 / PFMEA 에 BOM | APQP_DFMEA_NO_ROUTING / NOT_FOUND / docRevId APQP_NOT_DRAWING / APQP_PFMEA_NO_BOM | 맞음 |
| 직접 SQL: 유형 바꾸기 / PFMEA 에 bom_id / DFMEA 에 routing_id / WI 리비전 / DFMEA 에 STEP / 시스템 아래 COMPONENT / 하위 시스템 기능에 FE | type is fixed / ck_apqp_fmea_target ×2 / not a drawing / is not for DFMEA / breaks the 3 levels / does not belong to a SUBSYSTEM node | 맞음 |
| **권한 나눔**: PFMEA 만 가진 사람 → DFMEA 목록 / PFMEA 경로로 DFMEA 상세·흐름·엑셀·노드 고치기·기능 추가 | 403 / 404 ×5(이름 안 바뀜) | 맞음 |
| DFMEA 권한으로 PFMEA 상세·기능 추가·노드 고치기 | 404 ×3 | 맞음 |
| 목록 유형별 · DFMEA 상세 issueView | DFMEA 목록 = DFMEA 만, PFMEA 목록 = PFMEA 만 · false | 맞음 |
| **A사로 B사**: B 가 A 의 DFMEA 상세·엑셀 / A 품목 BOM 목록 / 목록 | 404·404 / 빈 / 빈 | 맞음 |
| **A사로 B사**: B 품목에 A 의 BOM·도면 리비전(API) / 직접 SQL(B 범위) A 의 BOM·도면·자품목 | NOT_FOUND ×2 / "is not of item"·"not a drawing"·"is not of company" | 맞음 |
| 화면 1366·390(DEMO 64310-DM100 확정 BOM, 화면에서 신규 → BOM 고르기 → 저장) | DF-2026-0001, 하위 시스템 5 · 구성품 3, 하위 시스템 고르면 라우팅 단계·4M 칸 숨김, 공정 흐름·라우팅 바꾸기·QMS 문서 단추 없음, 목록 칸 BOM, 가로 넘침 없음(1366·390) | 맞음 |

전체 `./gradlew check`(JDK 21): 2,788건 통과(건너뜀 15 — 실측 플래그), 실패 0.

## 2026-10-29 A5-2b DFMEA 재검토·BOM 다시 잇기·PFMEA 연결 (docs/41 13.8, V212·임시 V9418) — `ApqpDfmeaTest` 4건 추가(모두 10건)

| 시험 | 기대 | 결과 |
|---|---|---|
| ZT-ASSY BOM 새 리비전 B 확정(A 는 오늘로 끝) | 상세 bomStale, 목록 재검토 "BOM" | 맞음 |
| 도면: 지금 리비전 A 확정 → 작성 중 B → A 폐기·B 확정 | false → false → true, 목록 "BOM,DRAWING", 목록 엑셀 같은 문구 | 맞음 |
| 머리에서 BOM 바꾸기 / 직접 SQL 로 짝 없는 채 BOM 바꾸기 | APQP_BOM_USED / "subsystems not in bom" | 맞음 |
| 미리보기: 같은 BOM / 다른 품목 BOM / B | APQP_BOM_SAME / NOT_FOUND / 이어짐 1(SUB1)·끊김 1(SUB2)·더함 ZT-PART, 머리 BOM 그대로 | 맞음 |
| 적용: 낡은 rowVer / 맞는 rowVer | 409 / 머리 BOM = B, bomStale false, SUB2 이름 그대로·자품목 연결 없음, ZT-PART 하위 시스템 생김, 구성품 그대로 | 맞음 |
| PFMEA id 를 DFMEA 경로로 BOM 미리보기 | 404 | 맞음 |
| PFMEA: DFMEA 확정 전 / 확정 뒤 제안 | 없음 / DFMEA 고장 형태 "체결력 부족" 1건, 특성 ZD1 inDfmea | 맞음 |
| PFMEA 고장 영향 + 출처(DFMEA FM) | 저장, srcText "DF-… rev 1" | 맞음 |
| 출처 = DFMEA 고장 원인(FC) — API · 직접 SQL | NOT_FOUND / "not a released DFMEA failure mode" | 맞음 |
| DFMEA 쪽 고장 영향에 출처 | APQP_LEVEL | 맞음 |
| DFMEA 새 리비전 확정(rev1 폐기) 뒤 이미 붙은 출처 그대로 고치기 | 200 | 맞음 |
| **A사로 B사**: B PFMEA 의 제안 / A 의 DFMEA FM 을 출처로(API · 직접 SQL) | 빈 / NOT_FOUND · "not a released DFMEA failure mode" | 맞음 |
| **A사로 B사**: B DFMEA 를 A 의 BOM 으로 다시 잇기(미리보기·적용) / B 가 A 의 DFMEA 다시 잇기 | NOT_FOUND ×2 / 404 | 맞음 |
| 화면 1366·390(DEMO 64310-DM100 BOM 새 리비전 B0 — 와이어 빠지고 냉연강판 더함) | DFMEA 알림 줄 "BOM 새 리비전…", [BOM 바꾸기] 미리보기 이어짐 4·끊김 1·더함 1 → 적용 뒤 알림 없어짐·하위 시스템 6. PFMEA 품목 기능의 고장 영향 칸에 "DFMEA 고장 형태에서 제안" → 고르면 글자 복사, 저장 뒤 칩 "출처 DF-2026-0001 rev 1". 390 에서 제안 고르기가 칸 밖으로 넘쳐 `.apqp-inline select { max-width: 100% }` 로 고침, 가로 넘침 없음 | 맞음 |

전체 `./gradlew check`(JDK 21): 2,805건 통과(건너뜀 15 — 실측 플래그), 실패 0. (CSS 한 줄은 그 뒤 — 화면으로 확인)

## 2026-10-29 확정 DFMEA → QMS 관리문서 (docs/41 13.9, 마이그레이션 없음 — QMS V213 뒤) — `ApqpDfmeaTest` 1건 추가(모두 11건)

| 시험 | 기대 | 결과 |
|---|---|---|
| 작성 중 DFMEA / 확정 뒤 QMS 문서 저장 권한 없음 | canPublish false / false, 올리기 403 | 맞음 |
| qms-doc SAVE 받고 올리기 | 새 문서 유형 **DFMEA**, 변경 요약 "DFMEA DF-… rev 1" | 맞음 |
| 다시 올리기 | 같은 리비전(멱등), canPublish false, linked = 그 문서 | 맞음 |
| QMS 리비전 배포 → DFMEA 새 리비전 확정 → 올리기 | 같은 문서 번호의 새 리비전 | 맞음 |
| PFMEA 경로(`/api/apqp/fmeas/{dfmea}/qms-doc`) 조회·올리기 | 404·404 | 맞음 |
| apqp-fmea 권한만(qms-doc SAVE 있음)으로 DFMEA 올리기 | 403 | 맞음 |
| **A사로 B사** 조회·올리기 | 404·404 | 맞음 |
| 기존 PFMEA·CP 올리기(`ApqpA3Test` 11건) | 그대로 통과 | 맞음 |
| 화면 1366·390(DEMO 64310-DM100 DFMEA 확정 → [QMS 문서로 올리기]) | "QMS 문서 DOC2610-0001 에 올렸습니다", 단추 숨김·"QMS 문서 … rev A0 (작성 중)" 표시, 문서 유형 DFMEA, 가로 넘침 없음 | 맞음 |

전체 `./gradlew check`(JDK 21): 2,807건 통과(건너뜀 15 — 실측 플래그), 실패 0.
