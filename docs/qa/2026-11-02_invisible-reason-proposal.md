# 제안 — 필수 사유 칸의 "보이지 않는 글자" 막기 (QA R3-a·R4-a, 2026-11-02)

> 독립 QA 세션 제안 문서. 상위 요청(2026-11-02): 공통 도우미 API 안 · 적용 대상(모듈별) · 감사 성격 칸 우선순위 · "2자 이상" 규칙과의 관계.
> **결정 전 제안** — 코드 변경 없음. 근거·재현은 `docs/qa/2026-11-02_round3.md`(R3-a)·`docs/qa/2026-11-02_round4.md`(R4-a, 부록 A).
> 근거 표시: [코드] = 코드로 확인, [재현] = 실행해 확인, "추정" = 확인 안 됨. 파일:줄은 main `9335009f` 기준(경로는 `src/main/java/io/smarthappy/` 생략).

## 1. 문제 한 줄

사람이 이유를 적어야 하는 필수 칸이 **눈에 보이지 않는 글자만으로 통과**한다. 판정이 `String.isBlank()`/`strip()`(Java `Character.isWhitespace` 기준)이라 U+200B(제로폭 공백)·U+FEFF·U+00AD(형식 문자 Cf)와 U+00A0(NBSP, Zs 지만 isWhitespace 아님)이 "글자" 로 셈해진다. DB 검사 `length(btrim(x)) > 0` 도 공백만 지운다. → 감사·정정 기록에 **빈 사유**가 남는다(조용히 틀림).

| [재현] | 보낸 값 | 결과 |
|---|---|---|
| PMS 선행 미완 통과 사유 | U+200B | 200, 기록 `e2808b` |
| WMS 고객 반품 취소 사유("2자 이상") | U+200B ×2 | 200, `e2808be2808b` |
| WMS 수불 원장 역분개 사유("2자 이상" + DB `btrim > 0`) | U+00A0 ×2 | 200, 역분개 행 remark `c2a0c2a0` |

## 2. 공통 도우미 API 안

### 2.1 Java — `io.smarthappy.platform.common.text.VisibleText`(새 파일, 플랫폼 = 시스템관리 소유)

```java
public final class VisibleText {
    /** 보이지 않는 글자: 공백(\s)·구분자(\p{Z} — NBSP·U+3000 포함)·형식 문자(\p{Cf} — U+200B~200F·U+2060~2064·U+FEFF·U+00AD 등)·제어(\p{Cc}) */
    public static final Pattern INVISIBLE = Pattern.compile("[\\s\\p{Z}\\p{Cf}\\p{Cc}]+");

    /** null 이거나 보이는 글자가 하나도 없으면 true — 기존 각 모듈 blank() 를 대신한다 */
    public static boolean isBlank(String s);

    /** 보이는 글자 수(코드 포인트 기준, INVISIBLE 을 뺀 나머지) — "2자 이상" 규칙은 이것으로 */
    public static int visibleLength(String s);

    /**
     * 사유 칸 표준 정리·검사: 앞뒤의 보이지 않는 글자 제거, 가운데 제어 문자(\p{Cc})는 공백으로(지금 text() 와 같음),
     * 가운데 형식 문자는 **그대로 둔다**(문장 안 ZWJ·ZWNJ 는 일부 문자·이모지에 필요 — 지우면 내용이 바뀐다).
     * visibleLength < minVisible 이면 field 에 REQUIRED(지금 코드와 같은 오류 코드 — 화면 문구 변경 없음), 길이 > max 면 TOO_LONG.
     * 돌려줌 = 정리된 값(빈 경우 null)
     */
    public static String reason(String raw, String field, int minVisible, int max, FieldErrors e);
}
```

- 본보기: `module/master/classprop/ClassPropService.java:72` `INVISIBLE = [\s\p{Z}\p{Cf}]+`(분류 속성 값용, private) — 같은 정의를 플랫폼으로 올린다(+ `\p{Cc}`).
- **NFKC 정규화는 하지 않는다**(사유는 사람이 읽는 글 — 전각·호환 문자를 바꾸면 원문이 달라진다. 검색 키가 아니다).
- 기존 오류 코드(`REQUIRED`, WMS 의 `REQUIRED`, PMS 의 `PMS_DEP_REASON` 등)는 **그대로 쓴다** — 화면 문구·시험 기대값이 바뀌지 않게. 도우미는 "빈지" 판정만 바꾼다.

### 2.2 DB(선택, 3단계) — `hb_visible_len(text)`

서비스가 막으면 앱 경로는 충분하다. 직접 SQL(앱 계정)까지 막으려면 CHECK 를 바꾼다. PostgreSQL 정규식은 `\p{Cf}` 를 모르므로 범위를 적는다 — [재현] 아래 식으로 `E'​  　﻿⁠가'` → 1:

```sql
CREATE FUNCTION hb_visible_len(t text) RETURNS integer IMMUTABLE LANGUAGE sql AS $$
  SELECT length(regexp_replace(coalesce(t, ''),
         '[[:space:][:cntrl:] ­ ᠎ -‏ -  -⁤　﻿]', '', 'g'))
$$;
-- 예: CHECK (hb_visible_len(reason) > 0)
```

- 적용된 V 파일은 고칠 수 없으므로 **새 V** 로 CHECK 를 바꿔야 하고 대상이 약 60곳 — 규모가 커서 3단계(선택)로 둔다. 기존 행에 이미 보이지 않는 사유가 있으면 `NOT VALID` 로 추가 뒤 정리.

## 3. "2자 이상" 규칙과의 관계

- 지금 WMS 7곳은 `strip()` 뒤 `length() < 2`(UTF-16 길이) — U+200B 둘, NBSP 둘로 통과(재현). DB 도 `length(btrim(x)) >= 2`(V074·V108).
- 제안: **최소 길이는 "보이는 글자 수"로 센다** — `VisibleText.reason(raw, "reason", 2, 500, e)`. 한글 한 음절 = 1(코드 포인트), "오류" = 2 → 지금 통과하던 정상 사유는 그대로 통과.
- 1자 규칙 칸(대부분)은 `minVisible = 1`. 2자 규칙을 다른 모듈로 넓힐지는 별도 질의(이번 제안 범위 아님).
- 주의: `length()`(UTF-16)와 코드 포인트 수는 이모지·보조 평면 한자에서 다르다 — 최소 길이는 코드 포인트, **최대 길이는 지금처럼 DB 열 길이(varchar = 문자 수)에 맞춘다**(PG varchar(n) 은 코드 포인트 수).

## 4. 적용 대상(모듈별) — 필수 사유·설명 칸

[코드] 조사(필수인 칸만, 코드 목록 칸·선택 칸 제외). "A" = `isBlank`/`strip` 형(1글자로 통과), "C" = 2자 형(2글자로 통과). 기록 = 그 사유가 남는 곳.

| 모듈 | 위치 | 칸 | 형 | 기록 | 등급 |
|---|---|---|---|---|---|
| 플랫폼 | `platform/approval/ApprovalService.java:275` reject | comment | A | 결재 반려 이력 | **1** |
| 플랫폼 | `platform/approval/ApprovalDelegateService.java:144` create | reason | A | 결재 위임 | **1** |
| 플랫폼 | `platform/ops/SupportGrantService.java:57` grant | reason | A | 운영자 지원 권한(감사) | **1** |
| 관리 | `module/admin/OutboxAdminService.java:149` close | reason | A | 아웃박스 수동 종결(결재) | **1** |
| WMS | `module/wms/inv/InventoryService.java:238` reverseAll·reverseAt | reason | C | 재고 원장 역분개 | **1** |
| WMS | `module/wms/inv/CustRtnService.java:223` cancel | reason | C | 고객 반품 취소(+NCR) | **1** |
| WMS | `module/wms/count/PeriodService.java:151` reopen | reason | C | 마감 재개 | **1** |
| WMS | `module/wms/ship/ShipService.java:1054` reason | reason | C | 출하·피킹 취소 | 2 |
| WMS | `module/wms/count/CountService.java:449` cancel | reason | C | 실사 취소 | 2 |
| WMS | `module/wms/label/LabelPrintService.java:141` reprint | reason | C | 라벨 재발행 | 2 |
| WMS | `module/wms/inv/MroIssuePosting.java:131` onPartReturn | reason | C | MRO 반납 원장 | 2 |
| WMS | `module/wms/loccheck/LocCheckService.java:310` confirm | reason | A | 정위치 점검 확정 | 2 |
| MES | `module/production/ReceiptService.java:291` correct | reason | A | 입고 정정 역분개 | **1** |
| MES | `module/production/LotService.java:604` reverse | reason | A | 계보 정정 역분개 | **1** |
| MES | `module/production/LotService.java:478·495·509` hold·releaseHold | reason | A | LOT 보류·해제 | **1** |
| MES | `module/production/LotService.java:412·446` relabel·rework | reason | A | LOT 재라벨·재작업 | 2 |
| MES | `module/production/MesLotHoldPort.java:59` | reason(QMS 에서) | A | LOT 보류(QMS 출처) | 2 |
| MES | `module/production/WoService.java:349` cancel | reason | A | 작업지시 취소 | 3 |
| POP | `module/pop/PopWorkService.java:443·560` correct·correctParam | reason | A | 실적·파라미터 정정(+감독자 승인) | **1** |
| POP | `module/pop/TerminalService.java:203` revoke | reason | A | 단말 폐기 | 3 |
| QMS | `module/qms/InspService.java:411` voidInsp | reason | A | 검사 무효(→ WMS 역분개) | **1** |
| QMS | `module/qms/NcrService.java:218·241·361·423` | description·reason | A | NCR·취소 | **1** |
| QMS | `module/qms/SpcService.java:159` voidLimit · `SetupService.java:135` voidVerdict · `GaugeCalibService.java:270` voidCalib | reason | A | 관리한계·초품 판정·교정 무효 | **1** |
| QMS | `module/qms/CapaService.java:316` · `ContainService.java:151·194` | reason | A | CAPA 취소·봉쇄·해제 | 2 |
| QMS | `module/qms/SpcEventService.java:274` · `ChangeService.java:291` · `GaugeCalibService.java:235` · `OcapService.java:184` | note·revertCond·limitNote·actionText | A | SPC 조치·4M·교정 제한·OCAP | 2 |
| SRM | `module/srm/claim/ClaimService.java:321·348·382` | answerCause·answerAction·reason | A | 클레임 원인·대책·취소 | 2 |
| SRM | `module/srm/ack/AckService.java:260` · `supplier/SupplierService.java:234` | rejectReason·statusReason | A | 발주 수락 거부·협력사 상태 | 2 |
| SRM | `module/srm/pr/PrService.java:356·372·410` · `po/PoService.java:578` | reason | A | 구매요청 반려·취소·발주 취소 | 2 |
| SRM | `module/srm/asn/AsnService.java:378` · `claim/ReturnService.java:359` · `eval/EvalService.java:418` | reason | A | ASN·반품·평가 취소 | 3 |
| PLM | `module/plm/EcrService.java:226` reject · `EcoService.java:955` cancel | comment·reason | A | ECR 반려·ECO 취소 | 2 |
| APQP | `module/apqp/CpService.java:315` · `FmeaService.java:1232` | changeReason·actionText | A | 관리계획서 개정·FMEA 조치 | 2 |
| CMS | `module/cms/CounterService.java:402` · `StateReviewService.java:258·294` · `SpareService.java:424` | reason | A | 카운터 정정·상태 검토·예비품 반납 | 2 |
| CMS | `module/cms/MwoService.java:708` · `ChkRunService.java:393` | reason | A | 보전 지시 취소·점검 건너뜀 | 3 |
| PMS | `module/pms/TaskService.java:309·324·369` | depReason·planReason | A | 선행 면제(R3-a)·계획 변경 이력 | **1** |
| PMS | `module/pms/ScheduleService.java:141·197` · `DeliverableService.java:361` | reason·note | A | 기준선·산출물 반려/면제 | 2 |
| PMS | `ProjectService.java:464` · `TaskChgService.java:120` · `IssueService.java:368` · `WorkLogService.java:288` | reason·resolution | A | 프로젝트 보류/취소·변경 요청·이슈 해결·일지 재개 | 3 |
| 마스터 | `module/master/equip/AssetService.java:491` voidInstall | reason | A | 설치 이력 무효 | 2 |
| 마스터 | `module/master/workforce/WorkCalService.java:225` · `WorkCalImportTemplate.java:134` | reason | A | 근무달력 예외 | 3 |
| 영업·APS | `module/sales/OrderService.java:616` · `module/aps/ApsService.java:552` | reason | A | 수주 행 마감·취소·계획 폐기 | 3 |

판정 함수(각 모듈 `blank()`/`text()`) 위치: `sales/OrderService.java:598·640`, `qms/SamplingService.java:373·395`, `srm/SrmChecks.java:28·41`, `cms/CmsSupport.java:32·49`, `apqp/ApqpInputs.java:27·36`, `pms/PmsInputs.java:28·37`, `plm/EcoService.java:1143`, `master/equip/AssetService.java:681` 외 — 이 함수들을 `VisibleText` 로 바꾸면 위 표 대부분이 한 번에 덮인다(추정 — 칸마다 그 함수를 쓰는지 확인하며 적용).

## 5. 우선순위(감사 성격)

| 등급 | 기준 | 칸(4장 표 **1**) |
|---|---|---|
| **1** | 정정·무효·역분개·권한·결재 — 나중에 "왜" 를 감사가 묻는 기록, 되돌릴 수 없음 | 결재 반려·위임, 운영자 지원 권한, 아웃박스 종결, 원장 역분개·반품 취소·마감 재개, MES 입고/계보 정정·LOT 보류, POP 정정, QMS 검사/관리한계/초품/교정 무효·NCR, PMS 선행 면제·계획 변경 |
| 2 | 업무 취소·반려·상태 변경 — 기록은 남지만 정정 경로가 있음 | WMS 출하·실사·라벨, QMS CAPA·봉쇄·조치, SRM 클레임·거부·취소, PLM·APQP, CMS 정정, PMS 기준선·산출물 |
| 3 | 운영 메모 성격 | 작업지시·보전·점검·단말·수주·APS·근무달력·이슈·일지 |

## 6. 진행 안

1. **1단계(플랫폼, 1 PR)**: `VisibleText` + 단위 시험(U+200B·U+FEFF·U+00AD·U+00A0·U+3000·탭·줄바꿈·"가"·"오류"·이모지·ZWJ 가 가운데인 글) + **가드 시험**(등급 1 칸 API 에 `"​"`·`"  "` → 400 — 새 사유 칸이 생겨도 같은 규칙). 등급 1 의 플랫폼 칸(결재·운영자 권한·아웃박스) 적용.
2. **2단계(모듈별)**: 각 모듈 세션이 자기 `blank()`/`text()`·WMS `length() < 2` 를 `VisibleText` 로 — 등급 1 → 2 → 3. 기존 오류 코드 유지라 화면 변경 없음. QA 재현(R3-a·R4-a 부록)을 켠 채로.
3. **3단계(선택)**: DB `hb_visible_len` + 등급 1 표의 CHECK 를 새 V 로(`NOT VALID` 뒤 정리). 직접 SQL 까지 막을지 결정 뒤.

## 7. 결정이 필요한 것

| # | 질문 | 추천 |
|---|---|---|
| V-1 | 공통 도우미를 플랫폼에 둘지(시스템관리 소유) / 모듈마다 고칠지 | 플랫폼 하나(정의가 갈라지지 않게) |
| V-2 | 보이지 않는 글자의 정의 = `[\s\p{Z}\p{Cf}\p{Cc}]`, NFKC 안 함 | 예 |
| V-3 | 문장 **가운데** 형식 문자(ZWJ 등)는 남길지 | 남긴다(지우면 내용이 바뀜 — 빈지 판정만) |
| V-4 | "2자 이상" 은 보이는 글자 수로 셀지 | 예(정상 사유는 그대로 통과) |
| V-5 | DB CHECK 까지 바꿀지(3단계) | 나중에 — 서비스·가드 시험 뒤 |
| V-6 | 적용 순서 = 등급 1 → 2 → 3 | 예 |

## 검토

(결정 뒤 상위·사용자가 채움)
