# QA 2026-11-02 — 열린 탭 링크(#756) · PMS 선행 미완 실적 제약(C-3, #753) · COPQ 고객 반품 출처(#752)

> 독립 QA 세션(docs/29 8장). 기준 main `c6c8732a`. 상위 지시(2026-11-02) 회차.
> 근거 표시: [코드] = 코드로 확인, [재현] = 시험으로 실행해 확인, [관찰] = 실행 중 본 값. "추정" 은 확인 안 됨.
> 시험: `src/test/java/io/smarthappy/qa/PmsDepQaTest.java`(3) · `CopqRtnQaTest.java`(1) — 지금 main 에서 통과. 실패 재현(P-a)은 **부록 A**.
> 모듈 시험(`PmsDepEnforceTest`·`NcrPortTest`·`hb.input.link.test.js`)이 이미 덮은 것은 다시 하지 않았다.

## 1. 발견 요약

| ID | 무엇이 조용히 틀리나 | 분류 | 담당(제안) |
|---|---|---|---|
| **L-a** | 홈 "미처리 SPC 위반" 숫자 링크를 **이미 열린 SPC 탭**(관리도 단위 보기)에 적용하면 보기가 그대로 — 위반 목록이 아니라 **전체 관리도 단위 목록**(검색어는 reset 으로 비워짐). 처음 연 탭은 위반 목록 | 고칠 것(중) — 홈 감사 ② 그대로 남음 | QMS(`spc.js` 가 `onLink` 로 `view` 받기) |
| L-b | 열린 WMS 실사·출하 탭에 다른 공장 링크 → 목록은 맞지만 **공장별 보조 선택지(실사 범위 칸·출하 대기 칸·프린터)는 이전 공장 것** — 그대로 새 실사·새 출하를 누르면 서버가 400(`scopeNodeId NOT_FOUND`·`stageNodeId NOT_STAGING`) | 고칠 것(낮음, 화면) — 조용히 틀리지는 않음 | WMS(`onLink` 또는 링크 뒤 change 처리) |
| L-c | `prd-wo`·`cms-state-review` 는 JS 로 넣은 기본 기간을 `onReset` 에서 다시 넣지 않는다 — 지금 카드 링크는 기간을 늘 주므로 드러나지 않지만, [초기화] 를 누르면 처음 화면과 다른 기간(prd-wo 지난 30일, state-review 빈 기간 → 필수 칸으로 조회 막힘) | 정책 질의(낮음) | MES·CMS |
| **P-a** | PMS BLOCK 에서 PM 이 **같은 요청으로 유형을 GATE 로** 바꾸며 실적 시작 → 사유·통과 기록 없이 통과, 다음 요청에 TASK 로 되돌려도 됨 | 고칠 것(낮음) | PMS |
| P-b | 실적 시작을 넣은 채 작업을 만들고 나중에 미완 선행 의존을 건다 → 기록 없는 "선행 미완 시작" | 정책 질의(낮음) | PMS |
| — | COPQ 반품·일반 NCR 섞인 달: 합계·줄·엑셀 모두 손 계산과 같음 | 이상 없음 | — |

## 2. 열린 탭 링크 reset · onLink(#756)

[코드] `hb.input.js` `SearchPanel.link(q)` = `readyPromise` 뒤 `reset()`(일반 칸 = HTML defaultValue/defaultSelected/첫 option, dateRange = `data-default`, 그 뒤 화면 `onReset`) → `setValues(q)` → `onLink(q)`(true 면 조회 안 함) → `search()`. 처음 여는 탭은 주소 `?q.` 를 ready 전에 채우고 `onLink` 를 부르지 않는다.

조사 범위: 링크가 있는 홈 카드 19장 × 대상 화면 17개(카드 파일의 링크 키, 화면 JS 의 조회 칸 초기화·`onReset`·`onLink`). **화면 JS 어디에도 `onReset`·`onLink` 가 없다**(`static/js/views` 전체) — 열린 탭은 늘 HTML 기본값 위에 링크 값만 덮는다.

| 카드 → 화면 | 링크 키(숫자 / 항목) | JS 가 넣는 조회 칸 기본값 | 처음 연 탭 vs 열린 탭 |
|---|---|---|---|
| 납기 임박 → `sal-order` | dueFrom·dueTo·status·from·to / keyword·from·to | 없음(고객 select 는 빈 option 만 추가) | 같음 |
| PMS 내 작업·포트폴리오 → `pms-home` | — / keyword | 없음 | 같음 |
| **SPC 미처리 → `qms-spc`** | view·status / — | 없음 — 단 **보기 라디오가 폼 밖**(`spc.html` `#viewSeg` 가 `</form>` 뒤) | **다름 → L-a** |
| 검사·CAPA·봉쇄·NCR → `qms-insp`·`qms-capa`·`qms-contain`·`qms-ncr` | status / keyword·status 등 | 없음 | 같음 |
| ECO 승인·고객 대기 → `plm-eco` | status / keyword | 없음 | 같음 |
| 점검 예정 → `cms-chk-run`, 고장 대기 → `cms-mwo` | status·from·to(·mwoType·scope)(·plantCd) | 공장 select = 빈 "전체" + API 목록(선택 없음) | 같음(공장 안 고르면 둘 다 전체) |
| 상태 확인 대기 → `cms-state-review` | plantCd·from·to·pending(·keyword) | 공장 = 첫 공장, 기간 오늘-6 ~ 오늘 — `onReset` 복원 없음 | 같음(링크가 세 칸 모두 덮음) — L-c |
| 실사 진행 → `wms-count`, 출하 진행 → `wms-ship` | — / plantCd·status | 공장 = 첫 공장(빈 option 없음) | 목록 같음, 보조 선택지 다름 → L-b |
| 정위치 점검 → `wms-loc-check` | — / plantCd·openOnly·status·from·to | 공장 = 첫 공장 | 같음 |
| 오늘 지시 → `prd-wo` | status·from·to(·plantCd) / keyword·from·to | 기간 지난 7일 ~ 앞으로 30일 — reset 은 `data-default="last30"` | 같음(링크가 from·to 덮음) — L-c |
| 보류 LOT → `prd-lot`, 늦은 ASN → `srm-asn` | holdOnly·from·to / keyword·from·to 등 | 없음 | 같음 |

- "로그인 사용자 기본 공장" 을 JS 로 넣는 화면은 없었다 — 공장 select 는 빈 "전체" 를 앞에 두거나(=처음과 같음) 첫 공장을 고르며(=reset 의 첫 option 과 같음), 첫 공장 화면의 카드는 링크에 plantCd 를 늘 넣는다. **링크에 공장 칸이 없는 카드**(SPC·QMS·PLM·영업·PMS·MES 보류 LOT·SRM)의 대상 화면에는 공장 조회 칸이 없거나 빈 "전체" 가 기본이다 → 처음과 같음.
- L-a [코드]: 처음 연 탭은 `spc.js` 가 주소 `q.view === 'events'` 를 보고 위반 보기로 연다. 열린 탭은 `setValues` 가 폼 밖 `view` 를 무시하고, 관리도 단위 보기면 `onSearch` 가 단위 grid 를 다시 읽는다 — 단위 grid API 는 status 를 받지 않아 링크의 `status=OPEN_ALL` 도 안 먹고 reset 된 검색어(빈 값)로 **전체 단위**. 카드 숫자(미종결 위반 수)와 목록이 다르다. docs/37 23장 표 ② 가 "QMS 진행 예정 — 공통 훅 `onLink` 로" 라고 적은 그 건으로, main `c6c8732a` 에서 아직 고쳐지지 않았다.
- L-b [코드]: 링크·reset 은 `plantSel.value` 를 코드로 바꿔 `change` 가 나지 않는다 → `count.js` `loadNodes`·`ship.js` `loadPlant` 가 다시 불리지 않는다. 처음 연 탭은 `sp.ready` 뒤에 부른다. 서버는 다른 공장 칸을 거부한다(`CountService` `scopeNodeId NOT_FOUND`, `ShipService` `stageNodeId NOT_STAGING`·프린터 `NOT_FOUND`) — 잘못 저장되지는 않고 오류로 끝난다.
- 미실행: 브라우저에서 두 탭을 실제로 여는 확인(Playwright)은 하지 않았다 — 위 판단은 [코드]. L-a 는 고칠 때 브라우저 시험 권장.

## 3. PMS 선행 미완 실적 제약(C-3, #753, V225)

[코드] `TaskService.update` — 프로젝트 잠금(`lockProject`) → 다시 읽기 → rowVer → 담당자/PM 판정 → **`cur.actStart == null && v.actStart != null && 요약 아님 && v.taskType ≠ GATE && 정책 BLOCK`** 이면 `openPreds`(완료·취소 아닌 선행) → 담당자 409 `PMS_DEP_NOT_DONE`, PM 은 사유(`text(…, 500)`, 빈 값·공백 → `PMS_DEP_REASON`) → 저장 뒤 `pms_task_dep_waive` 한 행. 내 작업 줄(`/actual`)은 같은 `update` 를 부른다. 정책은 캐시 없이 요청마다 읽는다(`PolicyService.value` → `findValue`).

| 상위 지정 | 결과 | 근거 |
|---|---|---|
| BLOCK 에서 담당자가 상세 폼·내 작업 줄·API 직접 | 모두 409(사유를 보내도) | 모듈 시험 `BLOCK_담당자_거부_상세폼과_줄편집_둘다_PM은_사유로_통과_기록` · [코드] 줄 편집도 같은 `update` |
| 담당자의 다른 길(상태만 IN_PROGRESS/DONE, 진척만) | 막힘 — 상태 IN_PROGRESS·DONE 은 실적 시작 필수(`validate`), NOT_STARTED 는 진척 0 이어야 → 결국 실적 시작 검사로 간다. 유형 바꾸기는 PM 만 | [코드] `validate` 상태 규칙, `update` 담당자 범위 |
| **PM 이 같은 요청에서 유형을 GATE 로** | **사유·기록 없이 통과** → P-a | [재현] 부록 A-1 |
| **이미 시작한 작업에 나중에 미완 선행 의존을 건다**(PM, 작업 만들 때 실적 시작 포함) | 200 — 작업 IN_PROGRESS, 기록 0 | [재현] P-b(정책 질의) |
| PM 통과 사유 빈 값·공백 | 칸 오류 `PMS_DEP_REASON`, 기록 0 | 모듈 시험 |
| 같은 요청 동시(PM 사유 통과 2번, 같은 rowVer) | 200/409, 기록 1 | [재현] `PmsDepQaTest` 1 |
| 담당자 줄 편집 ∥ PM 사유 통과 동시 | 담당자 409(거부 또는 버전), PM 200, 기록 1(pm1) | [재현] `PmsDepQaTest` 2 |
| 정책을 바꾸는 사이의 경쟁 | 정책 읽기는 프로젝트 잠금 **뒤**, 요청마다(캐시 없음). 정책 저장과 작업 저장 사이 잠금은 없다 → BLOCK 저장이 커밋되기 직전에 읽은 요청은 WARN 으로 끝난다(한 요청 폭). 정책 변경은 "그 뒤 요청부터" 로 본다 | [코드] — 범위 밖(설계상 허용, 기록만) |
| 통과 기록 UPDATE·DELETE | 앱 계정 권한 거부(REVOKE), 다른 프로젝트 작업으로 INSERT 트리거 거부 | 모듈 시험 `직접SQL_…` |
| A사로 B사 작업 id | 상세 폼 404·기록 조회 404(모듈 시험), **내 작업 줄 `/actual` 404**·A사 작업 그대로 | [재현] `PmsDepQaTest` 3 |

### P-a PM 이 유형을 GATE 로 바꾸며 실적 시작 — 사유·기록 없이 통과 (고칠 것, 낮음)

- [재현] BLOCK, 선행 미완. PM 이 한 요청으로 `taskType: GATE`(계획 하루) + `actStart` → **200**. 다음 요청으로 `TASK`·원래 계획으로 되돌림 → 200. [관찰] 작업 TASK/IN_PROGRESS, `pms_task_dep_waive` **0행**.
- [코드] 제외 조건이 **새 값** `!"GATE".equals(v.taskType())` 을 본다(게이트 규칙 PMS_GATE_ENFORCE 도 같은 줄 모양). 지금 유형(`cur`)이 GATE 가 아니면 검사해야 한다.
- 영향: PM 은 어차피 사유로 통과할 수 있으므로 "막힘" 은 아니고 **통과 기록(감사 근거)이 빠진다** — P-12 의 목적이 기록.
- 고칠 방향: `!"GATE".equals(cur.getTaskType()) && !"GATE".equals(v.taskType())` 가 아니라, **둘 다 GATE 일 때만** 제외(`"GATE".equals(cur) && "GATE".equals(v)`).

### P-b 시작된 작업에 미완 선행 의존 추가 (정책 질의, 낮음)

- [재현] PM 이 `actStart` 를 넣어 작업을 만들고(만들기는 검사 없음 — 그때는 의존이 없다) 그 뒤 미완 선행 → 이 작업 의존을 추가 → 200, 기록 0. 결과 상태는 "선행 미완인데 시작" 으로 P-a 와 같다.
- 질의: BLOCK 에서 (가) 시작된 후행에 미완 선행 의존 추가를 막을지 / (나) 의존 추가 때도 사유·기록을 받을지 / (다) 지금처럼(의존 추가는 계획 행위라 대상 아님). 화면 경고(위반 표시)는 그대로 나온다.

## 4. QMS COPQ 고객 반품 출처(#752)

[코드] `CopqMapper.findNcrLines` 출처 = `src_type = 'WMS_CUST_RTN'` 이면 `RTN_` + 처분, 아니면 `NCR_` + 처분. 구분(CLAIM → 외부)은 출처와 따로라 손 클레임 NCR 과 반품 NCR 이 같은 외부 실패에 들어가되 줄은 갈린다. 엑셀 `SOURCES` 에 RTN_ 3개, 화면 `SOURCE_KEYS`·ko/en 문구 있음.

| 확인 | 결과 | 근거 |
|---|---|---|
| 한 달에 공정 NCR(폐기 3) · 손 클레임 NCR(폐기 2) · 반품 NCR 셋(폐기·선별·RETURN 섞음) · 취소된 반품 NCR, 단가 1000 | 내부 3,000 · 외부 7,000 · 합계 10,000 = 손 계산. 줄 4개 `INTERNAL:NCR_SCRAP:3` · `EXTERNAL:NCR_SCRAP:2` · `EXTERNAL:RTN_SCRAP:4`(반품 셋이 한 줄) · `EXTERNAL:RTN_SORT:1`, 줄 합 = 합계 | [재현] `CopqRtnQaTest` |
| 반품 줄의 NCR 번호 | 종결된 반품 NCR 셋만, 취소된 반품 NCR 없음, 일반 줄에 반품 NCR 없음 | 같은 시험 |
| RETURN 처분·취소 NCR | 비용에 안 들어감 | 같은 시험 |
| 엑셀 = 화면 | 원장 행 4, 금액 열 합 10,000 = 화면 합계, 출처는 문구(코드 아님), (구분·출처) 중복 없음 — 반품 폐기 ≠ 클레임 폐기 | 같은 시험 |
| 이상 | 없음 | |

## 5. 다음 회차

- L-a(SPC `onLink`)·L-b 고친 뒤 브라우저로 "처음 연 탭 = 열린 탭" 확인(카드 숫자 = 목록 건수)
- P-a 고친 PR 에서 부록 A 시험이 켜졌는지
- 서버 쪽 쉼표 두 곳(금형 수명 타수 #754 반영됨 · AQL 엑셀 정수 칸) 회귀

## 부록 A. 지금 main 에서 실패하는 재현 시험 (PmsDepQaTest 에 붙여 넣어 실행)

고칠 PR 에 켠 채로 넣는다(@Disabled 금지). `PmsDepQaTest` 의 도우미(`row`·`send`·`waives`)를 쓴다. [관찰] main 결과: 첫 요청 200, 되돌림 200, 작업 TASK/IN_PROGRESS, 통과 기록 0.

```java
    /** P-a: PM 이 같은 요청에서 유형을 GATE 로 바꾸며 실적 시작 — 게이트 제외 조건이 새 유형(v)을 봐서 사유·기록 없이 통과, 그 뒤 TASK 로 되돌림 */
    @Test
    void 결함Pa_PM_이_유형을_GATE_로_바꿔_시작해도_사유가_필요하다() throws Exception {
        Map<String, Object> r = row(b);
        int code = send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"taskType\":\"GATE\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today
                + "\",\"planEnd\":\"" + today + "\",\"actStart\":\"" + today + "\",\"rowVer\":" + r.get("rowVer") + "}", pm).andReturn().getResponse().getStatus();
        Map<String, Object> r2 = row(b);
        send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"taskType\":\"TASK\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + r.get("planStart")
                + "\",\"planEnd\":\"" + r.get("planEnd") + "\",\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + r2.get("rowVer") + "}", pm)
                .andReturn();
        assertThat(code).as("사유 없는 GATE 전환 시작").isNotEqualTo(200);
    }
```
