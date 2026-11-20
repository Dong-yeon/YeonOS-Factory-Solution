# QA 2026-10-24 — 공통 사용자 고르기 UserDirectory(#472) · QMS 문서 쓰기 계약 DocPort(#474)·APQP→QMS(#487)

> 독립 QA 세션(docs/29 8장). 기준 main `d3f90dfa`. 제품 코드는 고치지 않는다. 표시: **[코드]** · **[재현]**.
> 시험 `src/test/java/io/smarthappy/qa/UserDirectoryQaTest.java` 4건(통과 2 · 재현 `@Disabled` 2). DocPort 는 모듈 시험(`ApqpA3Test`)이 이미 넓게 덮어 [코드] 확인만 했다.

## 0. 요약

| ID | 구분 | 내용 | 조치 |
|---|---|---|---|
| U-1 | 결함(작음) | SRM 협력사 **구매 담당**(`buyerLoginId`)이 사용 중인지 보지 않는다 — 관리자 잠금·사용 중지 계정이 새 담당으로 들어간다(200) | **SRM** `@Disabled` 재현 |
| U-2 | 결함(작음) | CMS **위험성평가 구성원**(`/members` `loginId`)도 같다. 같은 화면의 평가 담당(`ownerLoginId`)은 `INACTIVE` 로 거부한다 | **CMS** `@Disabled` 재현 |
| — | 확인 | 협력사 계정·다른 회사 계정은 아이디로도 id 로도 담당자가 될 수 없다 · CAPA 담당은 잠금·중지 거부 · DocPort 권한·멱등·경합·A사로 B사·롤백 | 이상 없음 |
| — | 관찰 | DocPort 멱등 키는 내용을 비교하지 않는다(같은 키 = 처음 리비전) · 연결된 QMS 문서를 사용 중지하면 APQP 올리기가 계속 `INACTIVE` | 계약 주석·정책 |

## 1. UserDirectory — 모듈별 담당자 받는 곳 [코드]

UserDirectory 는 **회사 사용자(INTERNAL)만** 돌려준다(모든 문장 `user_type = 'INTERNAL'`). 다른 회사는 RLS 로 걸러진다.
- `byLogin`/`byId` 는 상태와 무관하게 찾고 `active` 를 함께 준다.
- `activeByLogin`/`isActive`/`activeUsers` 는 사용 중인 사람만 준다.
- 사용 중 = `use_yn = 'Y'` 이고 관리자 잠금이 아님. 자동 잠금(5회 실패 15분)은 사용 중으로 친다 — 결재선과 같은 규칙.

| 모듈 · 칸 | 부르는 것 | 잠금·중지 | 비고 |
|---|---|---|---|
| PMS 과제·프로젝트·이슈·산출물·게이트 담당 | `activeByLogin` | 거부 | — |
| CMS PM 계획 담당 · 설비 담당 · 보전 지시 사람 · 위험성평가 담당(owner) | `byLogin` + `active()` | 거부(바꾸지 않은 사람은 둔다) | — |
| QMS CAPA 담당(id) · PLM ECO 담당(id) | `isActive` | 거부 | [재현] CAPA |
| APQP FMEA 담당 · SRM 발주 담당 | `activeByLogin` | 거부 | — |
| SRM 발주 승인자 선택지 | `groupActionHolders` | 사용 중만 | 관리자 그룹(행 없이 전부)은 넣지 않음 — 주석대로 |
| **SRM 협력사 구매 담당** | `byLogin` 만 | **들어감** | **U-1** |
| **CMS 위험성평가 구성원** | `byLogin` 만 | **들어감** | **U-2** |
| 작업자 ↔ 계정 연결(기준정보) | `byLogin` 만 | 들어감 | 주석에 "상태 무관" 의도 — 정책 |
| QMS 문서·PLM ECO·CMS·APQP 고르기 목록 | `activeUsers` | 목록에 없음 | — |

**[재현]** `UserDirectoryQaTest`

| 해 본 것 | 결과 |
|---|---|
| SRM 구매 담당·CMS 구성원에 협력사 아이디 `sup1` / 다른 회사 아이디 `bq1` | 400 `NO_SUCH_CODE`, 행 0 |
| CAPA 담당 id = 협력사 사용자 id / 다른 회사 사용자 id | 400, CAPA 0건 |
| CAPA 담당 id = 관리자 잠금 / 사용 중지 | 400 |
| SRM 구매 담당 = 관리자 잠금 / 사용 중지 | **200**(U-1) |
| CMS 위험성평가 구성원 = 관리자 잠금 / 사용 중지 | **200**(U-2) |

DB 쪽 마지막 막기는 결재 표(`apr_request`·`apr_line`·`apr_delegate`)와 권한 그룹(`sys_user_auth`)에만 있다(`ck_internal_user_only`). 모듈 담당 칸(`owner_user_id` 등)은 서비스 검사뿐이다. 지금 협력사가 끼는 API 경로는 찾지 못했다.

## 2. DocPort · APQP→QMS [코드]

| 볼 것 | 근거 | 판단 |
|---|---|---|
| 권한 PRINT + qms-doc SAVE | 컨트롤러 `@ProgAuth(PRINT)` + `ApqpDocService.publish` 가 `qms-doc` SAVE 확인. 모듈 시험이 둘 중 하나 없을 때 403 을 본다 | 이상 없음 |
| 멱등 키 경합 | `publishDraft` 가 `pg_advisory_xact_lock('mst_doc_rev.source_key:'+key)` 뒤 `findRevBySource`. 같은 키 동시 = 줄 서서 둘째가 `created=false`. 키 유일 색인 `uq_mst_doc_rev_source_key(comp_cd, source_key)`. APQP 쪽은 FMEA·CP 행 `FOR UPDATE` 가 먼저 | 이상 없음 |
| 작성 중 리비전 경합(APQP 올리기 ↔ QMS 화면 새 리비전) | 두 길 모두 `lockDoc`(머리 FOR UPDATE) 뒤 열린 리비전 검사 + 부분 유일 색인 `uq_mst_doc_rev_open` | 하나만 — 이상 없음 |
| A사로 B사 | 문서 번호·품목·APQP 원본은 RLS(모듈 시험 404). 잠금 키에 회사가 없어 다른 회사의 같은 키와 줄을 서지만 자료는 안 섞인다 | 이상 없음 |
| 롤백 | 첨부 `attachAs` 는 커밋이 안 되면 저장소 파일을 지운다(`afterCompletion`). APQP 연결 행이 실패하면 리비전·파일도 같이 롤백 | 이상 없음 |
| **관찰 — 멱등 키가 내용을 안 봄** | 같은 `sourceKey` 면 파일·요약이 달라도 처음 리비전을 돌려준다. APQP 키 `APQP:{kind}:{id}` 는 확정된(바뀌지 않는) 원본 한 리비전이라 문제없다. 다음 호출자(PMS 산출물 `PMS:DELIV:{id}:{rev}`)도 리비전을 키에 넣어야 한다 | 계약 주석에 "키에 원본 리비전을 넣는다" 를 적기 제안 |
| **관찰 — 연결 문서 사용 중지** | APQP 는 같은 번호의 이전 리비전이 올린 QMS 문서 번호로 올린다. 그 문서를 QMS 에서 사용 중지하면 이후 올리기가 계속 `docNo:INACTIVE`(새 문서로 넘어가지 않음) | 정책 질의(의도면 문구만) |
