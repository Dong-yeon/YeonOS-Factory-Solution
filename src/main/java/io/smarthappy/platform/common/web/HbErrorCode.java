package io.smarthappy.platform.common.web;

import org.springframework.http.HttpStatus;

/**
 * API 오류 코드 — 한 곳에서 HTTP 상태와 문구(i18n 메시지 키)를 정한다 (docs/04 2.2).
 * 응답: {"result":"ERROR","code":name(),"message":메시지}. 화면 JS 는 code 로 분기한다.
 * 새 코드를 넣으면 messages*.properties 에 키를 같이 넣는다 (I18nKeyGuardTest 가 확인).
 */
public enum HbErrorCode {

    // 인증·권한
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "error.unauthenticated"),
    SESSION_REVOKED(HttpStatus.UNAUTHORIZED, "error.sessionRevoked"),
    /** 로그인 후 절대 수명(기본 12시간) 경과 */
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "error.sessionExpired"),
    TENANT_REQUIRED(HttpStatus.UNAUTHORIZED, "error.tenantRequired"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "error.forbidden"),
    NO_PERMISSION(HttpStatus.FORBIDDEN, "error.noPermission"),
    TENANT_FORBIDDEN(HttpStatus.FORBIDDEN, "error.tenantForbidden"),
    /** 이 회사에서 쓰지 않는 모듈(docs/80) — 화면·API·단말 모두. 관리자도 */
    MODULE_DISABLED(HttpStatus.FORBIDDEN, "error.moduleDisabled"),
    /** 사용 모듈 저장: 켜진 모듈의 필수 짝이 꺼져 있음(docs/80 2장) — details 에 빠진 짝 */
    MODULE_DEPENDENCY(HttpStatus.BAD_REQUEST, "error.moduleDependency"),

    // 요청
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "error.badRequest"),
    INVALID_VALUE(HttpStatus.BAD_REQUEST, "error.invalidValue"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "error.notFound"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "error.methodNotAllowed"),

    // 데이터 상태
    /** unique 위반 (23505) */
    DUPLICATE(HttpStatus.CONFLICT, "error.duplicate"),
    /** 참조 위반 (23503) — 사용 중이라 삭제 불가 또는 참조 대상 없음 */
    IN_USE(HttpStatus.CONFLICT, "error.inUse"),
    /** 다른 사용자가 먼저 수정 (row_ver 불일치) */
    CONFLICT(HttpStatus.CONFLICT, "error.conflict"),
    /** 채번 자릿수 초과 (예: {seq:4} 에 10000번째) */
    NUMBERING_EXHAUSTED(HttpStatus.CONFLICT, "error.numberingExhausted"),
    /** 현재 비밀번호 불일치 */
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "error.passwordMismatch"),
    /** 비밀번호 정책 위반 (details 에 위반 항목) */
    PASSWORD_POLICY(HttpStatus.BAD_REQUEST, "error.passwordPolicy"),
    /** 첨부 거부 — 확장자·크기·내용(시그니처)·이름 (details 에 사유) */
    FILE_REJECTED(HttpStatus.BAD_REQUEST, "error.fileRejected"),
    /** 첨부 대상이 잠김(결재 중·배포본 등 — FileLockRule) — 올리기·삭제 불가 */
    FILE_LOCKED(HttpStatus.CONFLICT, "error.fileLocked"),
    /** 조건에 맞는 행이 상한보다 많음 (엑셀 내보내기·클라이언트 목록) — 조건을 좁히라고 안내 */
    TOO_MANY_ROWS(HttpStatus.BAD_REQUEST, "error.tooManyRows"),
    /** 조회 기간: 시작 > 종료 또는 빈 값 (docs/16 I3) */
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "error.invalidRange"),
    /** 조회 기간이 화면·회사 정책 상한보다 김 (docs/16 I4) */
    RANGE_TOO_LONG(HttpStatus.BAD_REQUEST, "error.rangeTooLong"),
    /** 회사의 동시 엑셀 내보내기가 상한에 닿음 */
    EXPORT_BUSY(HttpStatus.TOO_MANY_REQUESTS, "error.exportBusy"),
    /** 운영자 인증 실패 — 계정 없음·비밀번호·잠김·MFA 코드를 구분하지 않는다 */
    OPS_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "error.opsAuthFailed"),
    /** 운영자 콘솔이 설정되지 않음(OPS_SECRET_KEY·허용 IP 없음) */
    OPS_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "error.opsDisabled"),
    /** 짧은 시간에 너무 많은 요청 (피드백 등) */
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "error.tooManyRequests"),
    /** 같은 X-Idempotency-Key 로 이미 처리된 요청 (연타·재전송) */
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "error.duplicateRequest"),
    /** 재고 부족 — 출고·이동·역분개로 현재고가 마이너스가 됨(WMS 원장, docs/30 D3) */
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "error.insufficientStock"),
    /** 수주 행 허용 초과 출하(정책 SAL_OVERSHIP_PCT, docs/25 4.22) — 인자: 행 번호·남은 수량 */
    OVER_SHIP(HttpStatus.CONFLICT, "error.overShip"),
    /** 취소·사람 마감 수주 행에 출하 */
    ORDER_LINE_CLOSED(HttpStatus.CONFLICT, "error.orderLineClosed"),

    // 결재
    /** 결재선 오류 (details 에 사유) */
    APR_INVALID_LINE(HttpStatus.BAD_REQUEST, "error.aprInvalidLine"),
    /** 같은 문서에 진행 중인 결재가 이미 있음 */
    APR_ALREADY_PENDING(HttpStatus.CONFLICT, "error.aprAlreadyPending"),
    /** 이미 끝난(승인·반려·회수) 결재 */
    APR_CLOSED(HttpStatus.CONFLICT, "error.aprClosed"),
    /** 내 차례가 아님 */
    APR_NOT_YOUR_TURN(HttpStatus.FORBIDDEN, "error.aprNotYourTurn"),
    /** 누군가 이미 처리해 회수할 수 없음 */
    APR_NOT_WITHDRAWABLE(HttpStatus.CONFLICT, "error.aprNotWithdrawable"),

    // 엑셀 올리기 (P1 2단계, docs/17 3b)
    /** xlsx 가 아니거나 읽을 수 없음(손상·암호) */
    IMPORT_FILE(HttpStatus.BAD_REQUEST, "error.importFile"),
    /** 이 템플릿 파일이 아니거나 옛 버전 — 템플릿을 다시 받아야 함 */
    IMPORT_TEMPLATE(HttpStatus.BAD_REQUEST, "error.importTemplate"),
    /** 필수 열 없음·같은 열 두 번 (args: 열 이름) */
    IMPORT_HEADER(HttpStatus.BAD_REQUEST, "error.importHeader"),
    /** 데이터 행 없음 */
    IMPORT_EMPTY(HttpStatus.BAD_REQUEST, "error.importEmpty"),
    /** 행 수 상한 초과 (args: 상한) */
    IMPORT_TOO_MANY(HttpStatus.BAD_REQUEST, "error.importTooMany"),
    /** 이미 실행·취소된 작업, 또는 동시에 실행됨 */
    IMPORT_STATE(HttpStatus.CONFLICT, "error.importState"),
    /** 오류 행이 있는데 "오류 행 제외"를 고르지 않음 (args: 오류 수) */
    IMPORT_HAS_ERRORS(HttpStatus.BAD_REQUEST, "error.importHasErrors"),
    /** 검증 뒤 대상 데이터가 바뀜 — 다시 올려 검증해야 함 (args: 행 번호) */
    IMPORT_STALE(HttpStatus.CONFLICT, "error.importStale"),

    // BOM (P1 3a, docs/18)
    /** 작성 중(DRAFT) BOM 만 고칠 수 있음 — 확정본은 새 리비전으로 */
    BOM_NOT_DRAFT(HttpStatus.CONFLICT, "error.bomNotDraft"),
    /** 같은 모품목·대체 번호에 작성 중 BOM 이 이미 있음 */
    BOM_DRAFT_EXISTS(HttpStatus.CONFLICT, "error.bomDraftExists"),
    /** 이전 확정본이 같은 날 이후에 시작 — 자동 마감 불가 (args: 그 리비전) */
    BOM_PERIOD_OVERLAP(HttpStatus.CONFLICT, "error.bomPeriodOverlap"),
    /** 확정 조건 미충족 — 필드·행 오류 동봉 */
    BOM_RELEASE_CHECK(HttpStatus.BAD_REQUEST, "error.bomReleaseCheck"),
    /** 자동 리비전(A0~Z9) 소진 — 직접 입력 */
    BOM_REV_EXHAUSTED(HttpStatus.CONFLICT, "error.bomRevExhausted"),
    /** 기준일에 유효한 확정 BOM 없음 (args: 품목코드, 날짜) */
    BOM_NO_EFFECTIVE(HttpStatus.NOT_FOUND, "error.bomNoEffective"),
    /** 전개 레벨 상한 초과 (args: 상한) — 정책 BOM_MAX_LEVEL */
    BOM_TOO_DEEP(HttpStatus.BAD_REQUEST, "error.bomTooDeep"),
    /** 전개 결과 행 상한 초과 (args: 상한) — 정책 BOM_EXPLODE_MAX_ROWS */
    BOM_TOO_MANY_ROWS(HttpStatus.BAD_REQUEST, "error.bomTooManyRows"),
    /** 복사·일괄 대체가 순환을 만든다 (args: 모품목 코드) */
    BOM_CYCLE_AT(HttpStatus.BAD_REQUEST, "error.bomCycleAt"),

    // 설비·물리 자산 (P1 4b, docs/19)
    /** 자산이 지금 다른 설비에 설치 중 — 해체 먼저 (args: 설비 코드) */
    ASSET_INSTALLED(HttpStatus.CONFLICT, "error.assetInstalled"),

    // 공정·라우팅 (P1 5단계, docs/20) — BOM 문구("BOM")가 맞지 않아 따로 둔다. 리비전 소진은 BOM_REV_EXHAUSTED(문구 공통)
    /** 작성 중 라우팅만 고칠 수 있음 — 확정본은 새 리비전으로 */
    ROUTING_NOT_DRAFT(HttpStatus.CONFLICT, "error.routingNotDraft"),
    /** 같은 품목·공장·대체 번호에 작성 중 라우팅이 이미 있음 */
    ROUTING_DRAFT_EXISTS(HttpStatus.CONFLICT, "error.routingDraftExists"),
    /** 이전 확정본이 같은 날 이후 시작 (args: 그 리비전) */
    ROUTING_PERIOD_OVERLAP(HttpStatus.CONFLICT, "error.routingPeriodOverlap"),
    /** 확정 조건 미충족 — 필드·행 오류 동봉 */
    ROUTING_RELEASE_CHECK(HttpStatus.BAD_REQUEST, "error.routingReleaseCheck"),

    // 외부 연계 (P1 7단계, docs/22)
    /** ERP 가 품목 주인(정책 ERP_ITEM_SYNC_MODE=PULL) — MES 신규·주인 필드 변경 불가 */
    ERP_OWNED(HttpStatus.CONFLICT, "error.erpOwned"),

    // POP (P2 5a, docs/25 4.6)
    /** 단말 쿠키가 없거나 무효(폐기·다시 발급·회사 정지) — 화면은 활성화 화면으로 */
    TERMINAL_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "error.terminalUnauthenticated"),
    /** 회사 코드·등록 코드가 틀렸거나 만료 — 어느 쪽인지 알리지 않는다 */
    TERMINAL_ACTIVATION_FAILED(HttpStatus.BAD_REQUEST, "error.terminalActivationFailed"),
    /** 없는 배지·사용 중지 작업자 — 어느 쪽인지 알리지 않는다 */
    BADGE_UNKNOWN(HttpStatus.BAD_REQUEST, "error.badgeUnknown"),
    /** PIN 틀림 (남은 횟수) */
    PIN_WRONG(HttpStatus.BAD_REQUEST, "error.pinWrong"),
    /** PIN 5회 틀려 잠김 (풀리는 시각) */
    PIN_LOCKED(HttpStatus.LOCKED, "error.pinLocked"),
    /** PIN 이 필요한 공장인데 작업자에게 PIN 이 없음 */
    PIN_NOT_SET(HttpStatus.CONFLICT, "error.pinNotSet"),
    // PMS 프로젝트관리 (docs/32)
    /** PM·담당자·PMO(APPROVE) 가 아니라 이 프로젝트·작업을 고칠 수 없음 (D8) */
    PMS_NOT_EDITABLE(HttpStatus.FORBIDDEN, "error.pmsNotEditable"),
    /** 닫힌(종료·취소) 프로젝트 — 바꿀 수 없음 */
    PMS_PROJECT_CLOSED(HttpStatus.CONFLICT, "error.pmsProjectClosed"),
    /** 작업 수·깊이 상한(정책 PMS_MAX_TASKS·PMS_MAX_DEPTH) 초과 (args: 상한) */
    PMS_TOO_MANY_TASKS(HttpStatus.BAD_REQUEST, "error.pmsTooManyTasks"),
    PMS_TOO_DEEP(HttpStatus.BAD_REQUEST, "error.pmsTooDeep"),
    /** 산출물이 지금 그 동작을 할 수 없는 상태(승인 안 된 것 승인, 제출물 있는 면제 풀기 등) — 다시 조회 (PM3) */
    PMS_DELIV_STATE(HttpStatus.CONFLICT, "error.pmsDelivState"),
    /** 게이트 심사가 지금 그 동작을 할 수 없는 상태(작성 중 아님·이미 열린 심사 있음·통과한 게이트) (PM3) */
    PMS_GATE_STATE(HttpStatus.CONFLICT, "error.pmsGateState"),
    /** 필수 산출물이 승인·면제되지 않아 통과(GO·조건부) 상신·결정 불가 (args: 건수) */
    PMS_GATE_NOT_READY(HttpStatus.CONFLICT, "error.pmsGateNotReady"),
    /** 정책 PMS_GATE_ENFORCE=BLOCK — 앞 게이트 통과 전 착수 거부 (args: 게이트 이름) */
    PMS_GATE_NOT_PASSED(HttpStatus.CONFLICT, "error.pmsGateNotPassed"),
    /** 정책 PMS_DEP_ENFORCE=BLOCK — 선행이 끝나지 않아 실적 시작 거부(PM 이 아닌 사람) (args: 선행 이름들) */
    PMS_DEP_NOT_DONE(HttpStatus.CONFLICT, "error.pmsDepNotDone"),
    /** 정책 PMS_DEP_ENFORCE=BLOCK — FF 선행이 끝나지 않아 완료 거부(PM 이 아닌 사람, docs/32 4.27) (args: 선행 이름들) */
    PMS_DEP_NOT_FINISHED(HttpStatus.CONFLICT, "error.pmsDepNotFinished"),
    /** 게이트 작업의 완료는 게이트 심사 결정으로만 */
    PMS_GATE_BY_REVIEW(HttpStatus.CONFLICT, "error.pmsGateByReview"),
    /** 확정된 날 — 고치려면 "다시 열기"(사유) 먼저 (PM4) */
    PMS_DAY_CONFIRMED(HttpStatus.CONFLICT, "error.pmsDayConfirmed"),
    /** 확정되지 않은 날을 다시 열기 */
    PMS_DAY_NOT_CONFIRMED(HttpStatus.CONFLICT, "error.pmsDayNotConfirmed"),
    /** 이슈가 지금 그 상태로 갈 수 없음(닫힌 이슈·건너뛰는 전이) — 다시 조회 */
    PMS_ISSUE_STATE(HttpStatus.CONFLICT, "error.pmsIssueState"),
    /** A3-5 — 이 PFMEA 조치는 이미 이슈로 올림(인자 = 이슈 번호) */
    PMS_APQP_ISSUE_EXISTS(HttpStatus.CONFLICT, "error.pmsApqpIssueExists"),
    /** 일정변경 요청 — 이 작업에 처리 중인 요청이 이미 있음 */
    PMS_CHG_PENDING(HttpStatus.CONFLICT, "error.pmsChgPending"),
    /** 일정변경 요청이 그 동작을 할 수 없는 상태(이미 결정·회수, 결재로 처리하는 요청을 바로 처리 등) — 다시 조회 */
    PMS_CHG_STATE(HttpStatus.CONFLICT, "error.pmsChgState"),
    PMS_PROJ_IN_APPROVAL(HttpStatus.CONFLICT, "error.pmsProjInApproval"),
    PMS_PROJ_APR_STATE(HttpStatus.CONFLICT, "error.pmsProjAprState"),
    // CMS 설비관리 (docs/33)
    /** 보전 작업지시가 그 동작을 할 수 없는 상태(완료 뒤 수정·건너뛰기 전이) — 다시 조회 */
    CMS_MWO_STATE(HttpStatus.CONFLICT, "error.cmsMwoState"),
    /** 점검 실행이 그 동작을 할 수 없는 상태(끝남·작업지시로 하는 점검) */
    CMS_RUN_STATE(HttpStatus.CONFLICT, "error.cmsRunState"),
    /** 목록 상한 초과 — 기간·조건을 좁히게 */
    CMS_TOO_MANY_ROWS(HttpStatus.BAD_REQUEST, "error.cmsTooManyRows"),
    /** 위험성평가가 지금 그 동작을 할 수 없는 상태(작성 중 아님·결재 중·확정본 수정) — 다시 조회 (C6) */
    CMS_RA_STATE(HttpStatus.CONFLICT, "error.cmsRaState"),
    /** POP 동작인데 지금 작업자가 없음(비활동으로 내려감 등) — 화면은 배지 화면으로 */
    POP_NO_WORKER(HttpStatus.PRECONDITION_REQUIRED, "error.popNoWorker"),
    /** POP 감독자 승인이 필요한 동작인데 승인 토큰이 없음·만료·다른 동작·다른 대상 — 화면은 승인 대화상자를 연다(docs/25 4.37) */
    POP_APPROVAL_REQUIRED(HttpStatus.FORBIDDEN, "error.popApprovalRequired"),
    /** 감독자 자격(정책 POP_APPROVER_QUAL)을 오늘 유효하게 갖지 않은 배지 */
    POP_APPROVER_NOT_QUALIFIED(HttpStatus.FORBIDDEN, "error.popApproverNotQualified"),
    /** 지금 작업자가 자기 정정을 승인 — 다른 사람이 승인해야 한다 */
    POP_APPROVER_SELF(HttpStatus.BAD_REQUEST, "error.popApproverSelf"),
    /** 자격 게이트 BLOCK — 빠진 자격 이름 */
    QUAL_BLOCKED(HttpStatus.CONFLICT, "error.qualBlocked"),
    /** 자격 게이트 WARN — 확인(ack) 뒤 다시 착수 */
    QUAL_WARN_ACK(HttpStatus.CONFLICT, "error.qualWarnAck"),
    /** 착수 게이트(QMS 등, docs/25 4.21) 차단 — 자격·게이트 이유를 한 문구로 */
    START_GATE_BLOCKED(HttpStatus.CONFLICT, "error.startGateBlocked"),
    /** 착수 게이트 경고 — 확인(ack) 뒤 다시 보내면 착수 */
    START_GATE_WARN_ACK(HttpStatus.CONFLICT, "error.startGateWarnAck"),
    // SRM (docs/31)
    /** 지금 상태에서 할 수 없는 동작(작성 중이 아닌 요청 수정, 승인된 요청 다시 요청 등) — 다시 조회 */
    SRM_STATE(HttpStatus.CONFLICT, "error.srmState"),
    /** 요청자 본인 승인 금지(정책 SRM_PR_SELF_APPROVE=N) */
    SRM_SELF_APPROVE(HttpStatus.CONFLICT, "error.srmSelfApprove"),
    /** 정책 SRM_PO_SOURCE=ERP — 발주는 ERP 가 주인(D1), SRM 에서 만들거나 행을 바꿀 수 없음 */
    SRM_ERP_OWNED(HttpStatus.CONFLICT, "error.srmErpOwned"),
    /** 결재가 필요한 발주를 결재 없이 확정하려 함 */
    SRM_APPROVAL_REQUIRED(HttpStatus.CONFLICT, "error.srmApprovalRequired"),
    /** 살아 있는 발주 행이 걸린 구매요청 취소 */
    SRM_PR_HAS_PO(HttpStatus.CONFLICT, "error.srmPrHasPo"),
    /** 같은 평가 템플릿에 작성 중 리비전이 이미 있음(템플릿마다 하나) */
    SRM_EVAL_DRAFT_EXISTS(HttpStatus.CONFLICT, "error.srmEvalDraftExists"),
    /** 구매 입고가 발주 행 열린 수량 × (1 + SRM_RECV_OVER_PCT) 를 넘음 — {0} = 더 받을 수 있는 양 (docs/31 4.9) */
    SRM_RECV_OVER(HttpStatus.CONFLICT, "error.srmRecvOver"),
    /** 입고 되돌림이 받은 양보다 많음 — {0} = 받은 양 */
    SRM_RECV_REVERSE(HttpStatus.CONFLICT, "error.srmRecvReverse"),
    /** 발주 단위 → 품목 기본 단위 환산 없음(원장은 기본 단위만) */
    SRM_RECV_NO_CONV(HttpStatus.CONFLICT, "error.srmRecvNoConv"),
    /** 발주 단위 → 기본 단위 환산 결과가 기본 단위 자릿수를 넘음(1 BOX = 2.5 EA 등) — {0} {1} = {2} {3}, 자릿수 {4} */
    SRM_RECV_BASE_SCALE(HttpStatus.CONFLICT, "error.srmRecvBaseScale"),
    /** ASN 행·되돌림 근거가 그 발주 행 것이 아님 (docs/31 4.11) */
    SRM_ASN_LINE_MISMATCH(HttpStatus.CONFLICT, "error.srmAsnLineMismatch"),
    /** 반품 출고 LOT 가 반품 지시 LOT 와 다름 — {0} = 지시 LOT (docs/31 4.12.7) */
    SRM_RTN_LOT_MISMATCH(HttpStatus.CONFLICT, "error.srmRtnLotMismatch"),
    /** 반품 출고 수량이 지시 수량과 다름(나눠 출고 없음 — R4-9) — {0} {1} = 지시 수량(기본 단위) */
    SRM_RTN_QTY_MISMATCH(HttpStatus.CONFLICT, "error.srmRtnQtyMismatch"),
    /** 교체 요구 반품인데 그 LOT 를 받은 입고 이력이 그 발주 행에 없음 — {0} = LOT */
    SRM_RTN_NO_RECEIPT(HttpStatus.CONFLICT, "error.srmRtnNoReceipt"),
    /** 교체 요구 반품 수량을 발주 단위로 바꾸면 나누어떨어지지 않거나 발주 단위 자릿수를 넘음 — {0} {1} = {2} {3}, 자릿수 {4} (R4c-단위) */
    SRM_RTN_PO_SCALE(HttpStatus.CONFLICT, "error.srmRtnPoScale"),
    /** 교체 요구 반품 출고를 되돌리려는데 발주가 마감·행이 닫혀 입고를 다시 걸 수 없음 — {0} = 발주 번호 */
    SRM_RTN_PO_CLOSED(HttpStatus.CONFLICT, "error.srmRtnPoClosed"),
    /** 협력사 포털: 거래 정지 협력사는 조회만 — 새 입력(회신·ASN) 거부 (docs/42 P1) */
    SRM_PORTAL_BLOCKED(HttpStatus.CONFLICT, "error.srmPortalBlocked"),
    /** 협력사 포털: 우리 직원이 대신 넣은·엑셀로 올린 ASN 은 포털에서 고치지 않는다(보기만) */
    SRM_PORTAL_ASN_INTERNAL(HttpStatus.CONFLICT, "error.srmPortalAsnInternal"),
    // QMS 검사 기준 (P3 Q1, docs/29) — 검사계획 리비전 규칙은 라우팅과 같고 문구만 다르다
    /** 작성 중 검사계획만 고칠 수 있음 — 확정본은 새 리비전으로 */
    QMS_PLAN_NOT_DRAFT(HttpStatus.CONFLICT, "error.qmsPlanNotDraft"),
    /** 같은 대상(유형·품목|클래스·공정·대체)에 작성 중 검사계획이 이미 있음 */
    QMS_PLAN_DRAFT_EXISTS(HttpStatus.CONFLICT, "error.qmsPlanDraftExists"),
    /** 이전 확정본이 같은 날 이후 시작 (args: 그 리비전) */
    QMS_PLAN_PERIOD_OVERLAP(HttpStatus.CONFLICT, "error.qmsPlanPeriodOverlap"),
    /** 확정 조건 미충족 — 필드·행 오류 동봉 */
    QMS_PLAN_RELEASE_CHECK(HttpStatus.BAD_REQUEST, "error.qmsPlanReleaseCheck"),
    // QMS 계측기 교정 (P3 Q2a, docs/29)
    /** 이미 무효 처리된 교정 기록 — 다시 조회 */
    QMS_CALIB_VOIDED(HttpStatus.CONFLICT, "error.qmsCalibVoided"),
    // QMS MSA (P3 Q2b, docs/29)
    /** 작성 중 GRR 연구만 고치거나 확정·취소할 수 있음 */
    QMS_MSA_NOT_DRAFT(HttpStatus.CONFLICT, "error.qmsMsaNotDraft"),
    /** 원자료가 측정자 × 부품 × 반복 모양대로 다 차지 않음 */
    QMS_MSA_INCOMPLETE(HttpStatus.BAD_REQUEST, "error.qmsMsaIncomplete"),
    // QMS 관리문서 (P3 Q3, docs/29)
    /** 지금 리비전 상태에서 할 수 없는 동작(작성 중 아님·결재 중 등) — 다시 조회 */
    QMS_DOC_STATE(HttpStatus.CONFLICT, "error.qmsDocState"),
    /** 이 문서에 작성 중(결재 중 포함) 리비전이 이미 있음 */
    QMS_DOC_OPEN_EXISTS(HttpStatus.CONFLICT, "error.qmsDocOpenExists"),
    /** 정책 QMS_DOC_APPROVAL=Y — 배포는 결재로만 */
    QMS_DOC_APPROVAL_REQUIRED(HttpStatus.CONFLICT, "error.qmsDocApprovalRequired"),
    // QMS 시정조치 (P3 Q4, docs/29)
    /** 지금 시정조치 상태에서 할 수 없는 동작(종결 결재 중·종결·취소 등) — 다시 조회 */
    QMS_CAPA_STATE(HttpStatus.CONFLICT, "error.qmsCapaState"),
    /** 다음 단계로 갈 조건 미충족 — 칸 오류 동봉 */
    QMS_CAPA_STEP_CHECK(HttpStatus.BAD_REQUEST, "error.qmsCapaStepCheck"),
    // QMS 4M 변경 (P3 2부 Q5, docs/29_P3 11장)
    /** ECO 가 작성 중이 아니라 4M 세부를 고칠 수 없음 — 다시 조회 */
    QMS_CHANGE_STATE(HttpStatus.CONFLICT, "error.qmsChangeState"),
    // QMS 검사 실행 (P3 2부 Q6, docs/29_P3 12.2)
    /** 그 LOT·유형(·공정)에 오늘 유효한 확정 검사계획이 없음 */
    QMS_INSP_NO_PLAN(HttpStatus.BAD_REQUEST, "error.qmsInspNoPlan"),
    /** 지금 검사 상태에서 할 수 없는 동작(확정·무효 뒤 측정 등) — 다시 조회 */
    QMS_INSP_STATE(HttpStatus.CONFLICT, "error.qmsInspState"),
    /** 판정이 정해지지 않은 행이 있어 확정할 수 없음 — 칸 오류(행 키 = 검사 행 id) 동봉 */
    QMS_INSP_INCOMPLETE(HttpStatus.BAD_REQUEST, "error.qmsInspIncomplete"),
    /** 엑셀 올리기 실행 때 그 샘플의 검사가 검증 뒤 바뀜(판정·무효·같은 샘플 입력·계측기 상태) — 전체 롤백 (docs/29_P3 25장) */
    QMS_INSP_IMPORT_STALE(HttpStatus.CONFLICT, "error.qmsInspImportStale"),
    // QMS 초품 (P3 2부 Q7, docs/29_P3 12.3)
    /** 그 첫 LOT 에 살아 있는 판정이 이미 있거나(무효 뒤 다시) 이미 무효인 판정 — 다시 조회 */
    QMS_SETUP_DONE(HttpStatus.CONFLICT, "error.qmsSetupDone"),
    // QMS SPC (P3 2부 Q10, docs/29_P3 12.6)
    /** 그 관리도 단위에 살아 있는 고정 한계가 이미 있거나(무효 뒤 다시) 이미 무효인 한계 — 다시 조회 */
    QMS_SPC_LIMIT_EXISTS(HttpStatus.CONFLICT, "error.qmsSpcLimitExists"),
    /** SPC 위반 이벤트가 이미 종결·무효됐거나 처리할 수 없는 동작 (docs/29_P3 19장) */
    QMS_SPC_EVENT_STATE(HttpStatus.CONFLICT, "error.qmsSpcEventState"),
    // QMS 부적합 (P3 2부 Q8a, docs/29_P3 12.7)
    /** 지금 NCR 상태에서 할 수 없는 동작(OPEN 아님 등) — 다시 조회 */
    QMS_NCR_STATE(HttpStatus.CONFLICT, "error.qmsNcrState"),
    /** 출처가 있는 NCR(고객 반품 등)은 QMS 에서 취소하지 않는다 — 출처 화면에서 취소(docs/29_P3 43장, CR-b) */
    QMS_NCR_SOURCE_OWNED(HttpStatus.CONFLICT, "error.qmsNcrSourceOwned"),
    /** 처분에 특채(그대로 사용)가 있어 결재로만 종결 */
    QMS_NCR_APPROVAL_REQUIRED(HttpStatus.BAD_REQUEST, "error.qmsNcrApprovalRequired"),
    /** MES 가 없어 LOT 을 보류할 수 없음 — 정책 QMS_NCR_AUTO_HOLD 를 끄거나 MES 설치 */
    QMS_NCR_HOLD_UNAVAILABLE(HttpStatus.CONFLICT, "error.qmsNcrHoldUnavailable"),
    QMS_SPC_HOLD_UNAVAILABLE(HttpStatus.CONFLICT, "error.qmsSpcHoldUnavailable"),
    /** 정책 QMS_ESIGN_REQUIRED = Y 인데 서명 토큰 없이 판정 (docs/29_P3 12.10) — 403: 서명 필수 응답은 PLM·ESIGN_TOKEN 과 같게(docs/36 18장) */
    QMS_ESIGN_REQUIRED(HttpStatus.FORBIDDEN, "error.qmsEsignRequired"),
    // QMS 봉쇄 (P3 2부 Q8b, docs/29_P3 12.8)
    /** 이미 닫힌 봉쇄 — 다시 조회 */
    QMS_CONTAIN_STATE(HttpStatus.CONFLICT, "error.qmsContainState"),
    /** MES 가 없어 봉쇄(LOT 보류)를 할 수 없음 */
    QMS_CONTAIN_HOLD_UNAVAILABLE(HttpStatus.CONFLICT, "error.qmsContainHoldUnavailable"),

    // PLM (docs/39)
    /** 설계 변경 지시가 지금 상태에서 할 수 없는 동작(작성 중 아님·승인 전 적용 등) */
    PLM_ECO_STATE(HttpStatus.CONFLICT, "error.plmEcoState"),
    /** 설계 변경 요청이 이미 닫힘(접수·반려·취소) */
    PLM_ECR_CLOSED(HttpStatus.CONFLICT, "error.plmEcrClosed"),
    /** 요청자만 할 수 있는 동작(요청 수정·취소) */
    PLM_NOT_REQUESTER(HttpStatus.FORBIDDEN, "error.plmNotRequester"),
    /** 상신 뒤 영향 대상 리비전이 바뀌었거나 이미 확정됨 — 대상 rowKey 로 표시 */
    PLM_TARGET_CHANGED(HttpStatus.CONFLICT, "error.plmTargetChanged"),
    /** 적용 때 대상 리비전 확정 검사 실패 — 대상 rowKey 로 표시 */
    PLM_RELEASE_CHECK(HttpStatus.BAD_REQUEST, "error.plmReleaseCheck"),
    /** 고객 승인 필요 — 승인 기록 전 적용 불가 (docs/02 2.3) */
    PLM_CUST_APPROVAL_REQUIRED(HttpStatus.CONFLICT, "error.plmCustApprovalRequired"),
    /** 유효일이 지났다 — 결재받은 날짜라 바꾸지 않는다(취소 후 새 지시, docs/39 D5) */
    PLM_EFFECTIVE_PAST(HttpStatus.CONFLICT, "error.plmEffectivePast"),
    /** 적용 조건 "재고 소진" — 소진 품목의 공장 가용 재고가 남음(또는 WMS 없음) (docs/39 18장) */
    PLM_STOCK_REMAIN(HttpStatus.CONFLICT, "error.plmStockRemain"),
    /** 회사 정책 PLM_ESIGN_REQUIRED=Y — ECO 결재 승인·PPAP 판정은 전자서명과 함께 (docs/39 19장) */
    PLM_ESIGN_REQUIRED(HttpStatus.FORBIDDEN, "error.plmEsignRequired"),
    /** 회사 정책 PLM_ECO_REQUIRED=Y — BOM·라우팅 확정은 설계 변경 지시 적용으로만 (D4, docs/39 14.2) */
    PLM_ECO_REQUIRED(HttpStatus.CONFLICT, "error.plmEcoRequired"),
    /** PPAP 패키지가 지금 상태에서 할 수 없는 동작(작성 중 아님·제출 전 판정 등) */
    PLM_PPAP_STATE(HttpStatus.CONFLICT, "error.plmPpapState"),
    /** PPAP 제출 조건 미달 — 제출(S) 요소 미완료·PSW 미완료, 요소 rowKey 로 표시 */
    PLM_PPAP_INCOMPLETE(HttpStatus.BAD_REQUEST, "error.plmPpapIncomplete"),
    /** PCN 응답은 통지 상태(SENT)에서 한 번만 */
    PLM_PCN_STATE(HttpStatus.CONFLICT, "error.plmPcnState"),

    // 시스템관리 (docs/36)
    /** 자기 권한그룹 소속·자기 계정 사용 중지·잠금·비밀번호 초기화 — 자기 권한은 바꿀 수 없다 */
    SELF_CHANGE(HttpStatus.FORBIDDEN, "error.selfChange"),
    /** 내가 가진 권한보다 큰 그룹·사용자를 다루려 함(부여 상한) */
    GRANT_EXCEEDS(HttpStatus.FORBIDDEN, "error.grantExceeds"),
    /** 이 변경으로 사용할 수 있는 회사 관리자가 0명이 됨 */
    LAST_ADMIN(HttpStatus.CONFLICT, "error.lastAdmin"),
    /** 임시 비밀번호로 로그인 — 비밀번호를 바꾸기 전에는 다른 기능을 쓸 수 없다 */
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "error.passwordChangeRequired"),
    /** 두 번째 인증(MFA)을 마치기 전에는 다른 기능을 쓸 수 없다 (docs/36 14장) */
    MFA_REQUIRED(HttpStatus.FORBIDDEN, "error.mfaRequired"),
    /** 인증 코드·복구 코드가 맞지 않음(남은 횟수 args) */
    MFA_INVALID(HttpStatus.BAD_REQUEST, "error.mfaInvalid"),
    /** 인증 코드를 여러 번 틀려 잠김(분 args) */
    MFA_LOCKED(HttpStatus.FORBIDDEN, "error.mfaLocked"),
    /** MFA 키가 서버에 설정되지 않아 등록·확인을 할 수 없다 — 운영자에게 */
    MFA_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "error.mfaUnavailable"),
    /** 이미 등록됨 / 아직 등록하지 않음 — 지금 상태에서 할 수 없는 동작 */
    MFA_STATE(HttpStatus.CONFLICT, "error.mfaState"),
    /** 전자서명 재인증: 비밀번호가 맞지 않음(남은 횟수 args) (docs/36 16장) */
    ESIGN_INVALID(HttpStatus.BAD_REQUEST, "error.esignInvalid"),
    /** 전자서명 재인증: MFA 를 쓰는 사람은 인증 코드도 넣어야 한다 */
    ESIGN_MFA_REQUIRED(HttpStatus.BAD_REQUEST, "error.esignMfaRequired"),
    /** 서명 토큰이 없거나 만료·다른 대상 — 다시 서명(구분해 알리지 않는다) */
    ESIGN_TOKEN(HttpStatus.FORBIDDEN, "error.esignToken"),
    /** 메일 설정(happyboot.mail.enabled)이 꺼져 있어 외부 메일을 보낼 수 없다 — 호출자가 보내지 않았음을 안다 (docs/36 18.3) */
    MAIL_DISABLED(HttpStatus.CONFLICT, "error.mailDisabled"),
    /** 외부 메일 발행 취소: 이미 보내는 중·보냄·실패·취소됨 — 보내기 전(대기)만 취소된다 (docs/36 18.7) */
    MAIL_NOT_CANCELABLE(HttpStatus.CONFLICT, "error.mailNotCancelable"),
    /** 협력사 포털 경계: 협력사 사용자가 포털 밖, 회사 사용자가 포털 API 를 불렀다 (docs/42 3장) */
    PORTAL_FORBIDDEN(HttpStatus.FORBIDDEN, "error.portalForbidden"),

    // APS (docs/38)
    /** 계산 뒤 지시·공정이 바뀌었거나 새 대상 지시가 생겨 반영할 수 없다 — 지시 번호 rowKey 로 사유, 다시 계산 */
    APS_PLAN_STALE(HttpStatus.CONFLICT, "error.apsPlanStale"),
    /** 같은 공정에 이미 사용 중 고정이 있다(동시 고정) — 고정 목록을 다시 읽어 옮긴다 */
    APS_PIN_EXISTS(HttpStatus.CONFLICT, "error.apsPinExists"),
    /** MPS 초안을 계산한 뒤 같은 공장의 현행 확정 MPS 가 바뀌어 확정할 수 없다 — 다시 계산 (docs/38 16.5) */
    APS_MPS_STALE(HttpStatus.CONFLICT, "error.apsMpsStale"),

    // WMS (docs/30)
    /** 마감된 공장·월의 발생 시각으로 원장을 넣으려 함(역분개 포함) — 재개 후에만 */
    PERIOD_CLOSED(HttpStatus.CONFLICT, "error.periodClosed"),
    /** 실사 상태가 이 동작을 허용하지 않음(다른 사람이 먼저 바꿈 포함) — 다시 조회 */
    WMS_COUNT_STATE(HttpStatus.CONFLICT, "error.wmsCountState"),
    /** 차이율이 허용 한도를 넘는 줄 — 확정은 승인 권한(APPROVE) 필요 */
    WMS_COUNT_APPROVAL(HttpStatus.FORBIDDEN, "error.wmsCountApproval"),
    /** 출하 지시 상태가 이 동작을 허용하지 않음(다른 사람이 먼저 바꿈 포함) — 다시 조회 (docs/30 12.7) */
    WMS_SHIP_STATE(HttpStatus.CONFLICT, "error.wmsShipState"),
    /** 출하 LOT 에 고객 승인(PPAP) 전 변경이 걸림 — {0} = LOT 번호, {1} = ECO 번호 (정책 SHIP_BLOCK_UNAPPROVED_CHANGE, docs/39 17장) */
    WMS_SHIP_CHANGE_BLOCKED(HttpStatus.CONFLICT, "error.wmsShipChangeBlocked"),
    /** 예비품 반납 누적이 출고를 넘음 — {0} = 더 반납할 수 있는 양 (CMS 계약 SpareIssueLink, docs/30 12.8) */
    WMS_MRO_RETURN_OVER(HttpStatus.CONFLICT, "error.wmsMroReturnOver"),
    /** 팔레트 필수 공장인데 닫힌 팔레트에 안 담긴 피킹이 있음 — {0} = 그 피킹 수 (정책 WMS_SHIP_PALLET_REQUIRED, docs/30 12.10) */
    WMS_SHIP_UNPALLETIZED(HttpStatus.CONFLICT, "error.wmsShipUnpalletized"),
    /** SSCC 를 만들 수 없음 — GS1 업체코드 정책 모양이 틀렸거나 일련번호 자리가 다 참 (정책 WMS_GS1_COMPANY_PREFIX) */
    WMS_SSCC_UNAVAILABLE(HttpStatus.CONFLICT, "error.wmsSsccUnavailable"),
    /** 처분이 반영된 LOT 의 검사 무효 — {0} = NCR 번호 (docs/30 12.15.4 WC-2) */
    WMS_QUALITY_DISPOSED(HttpStatus.CONFLICT, "error.wmsQualityDisposed"),
    /** 정위치 점검일이 오늘(공장 시간대)이 아님 — 자정을 넘긴 열린 점검, 위치를 다시 스캔해 오늘 점검으로 — {0} = 점검일 (docs/55, L-1) */
    WMS_LOC_CHECK_STALE(HttpStatus.CONFLICT, "error.wmsLocCheckStale"),
    /** 출하 취소로 다시 열면 수주 행의 열린 지시가 남은 수량을 넘음 — {0} = 수주 번호-행, {1} = 넘는 양 (U-1, docs/30 12.7.2) */
    WMS_SHIP_UNDO_OVER(HttpStatus.CONFLICT, "error.wmsShipUndoOver"),
    /** 고객 반품 입고(docs/59) — 받을 수 있는 양(순 출하 − 받은 반품) 초과. args: 받을 수 있는 양, 단위 */
    WMS_CUST_RTN_OVER(HttpStatus.CONFLICT, "error.wmsCustRtnOver"),
    /** 고객 반품 입고 상태가 바뀜(이미 취소 등) — 다시 조회 */
    WMS_CUST_RTN_STATE(HttpStatus.CONFLICT, "error.wmsCustRtnState"),
    /** 출하 조건 확장 지점(docs/72 2.1)이 막음 — {0} = 첫 LOT, {1} = 그 사유(구현 모듈 문구), 전부는 errors */
    WMS_SHIP_CHECK_BLOCKED(HttpStatus.CONFLICT, "error.wmsShipCheckBlocked"),
    /** 반품이 걸린 출하는 출하 취소 불가(반품 먼저 취소). args: 반품 번호 */
    WMS_SHIP_HAS_RETURN(HttpStatus.CONFLICT, "error.wmsShipHasReturn"),
    /** 적치 검사(W-12) — 정책 BLOCK 이고 위치의 허용 품목군이 아닌 품목. args = 위치 코드, 품목 코드, 품목군(없으면 -), 허용 품목군 */
    WMS_PUTAWAY_DENIED(HttpStatus.CONFLICT, "error.wmsPutawayDenied"),
    /** NCR 처분 합 &gt; 그 LOT 의 부적합(BLOCKED) 재고 — {0} = 있는 수량, {1} = 처분 합 (12.15.5 WB-2, 특채 결재 승인 중에도) */
    WMS_QUALITY_SHORT(HttpStatus.CONFLICT, "error.wmsQualityShort"),
    /** NCR 단위 ≠ 품목 기본 단위 — {0} = NCR 단위, {1} = 기본 단위 (12.15.5 WC-1, 환산하지 않음) */
    WMS_QUALITY_UNIT(HttpStatus.CONFLICT, "error.wmsQualityUnit"),

    // APQP (docs/41)
    /** 확정·폐기된 FMEA 리비전은 고칠 수 없다 — 새 리비전으로 / 작성 중 리비전이 있으면 확정본 조치도 그쪽에서 */
    APQP_FMEA_STATE(HttpStatus.CONFLICT, "error.apqpFmeaState"),
    /** 확정 조건 미달 — 위험행 O·D·AP 빠짐, 사슬 없는 고장 형태 등(행 rowKey 로) */
    APQP_RELEASE_CHECK(HttpStatus.BAD_REQUEST, "error.apqpReleaseCheck"),
    /** AP 규칙표가 S·O·D 1,000 조합을 빠짐·겹침 없이 덮지 않음 — {0} = 빠진 조합 수, {1} = 겹친 조합 수 */
    APQP_AP_INCOMPLETE(HttpStatus.BAD_REQUEST, "error.apqpApIncomplete"),
    /** 확정 FMEA 가 쓴 AP 규칙표는 고칠 수 없다 — 복사해서 새 표로 */
    APQP_AP_SET_USED(HttpStatus.CONFLICT, "error.apqpApSetUsed"),
    /** 확정·폐기된 관리계획서 리비전은 고칠 수 없다 — 새 리비전으로 (docs/41 5장) */
    APQP_CP_STATE(HttpStatus.CONFLICT, "error.apqpCpState"),
    /** A3-3 — 같은 번호가 올린 QMS 문서가 사용 중지됨(인자 = 문서 번호). 막기는 QMS 정책 그대로 */
    APQP_QMS_DOC_INACTIVE(HttpStatus.CONFLICT, "error.apqpQmsDocInactive"),
    /** 확정 방법이 회사 정책과 다름 — 정책 APQP_RELEASE_APPROVAL = Y 면 결재 상신으로, N 이면 바로 확정으로 (docs/41 A3-7) */
    APQP_APPROVAL_POLICY(HttpStatus.CONFLICT, "error.apqpApprovalPolicy"),

    /** 현장 단말 APK 가 아직 없거나 release.json 이 틀림 — 운영자가 배포 파일을 놓아야 한다 (docs/50 D2) */
    PDA_APP_NOT_READY(HttpStatus.CONFLICT, "error.pdaAppNotReady"),

    /** 연계 재시도·수동 종결: 실패(FAIL)이고 종결 결재 대기가 아닐 때만 — 이미 다른 사람이 처리했거나 결재 중 (docs/36 19장 O5·O6) */
    OUTBOX_STATE(HttpStatus.CONFLICT, "error.outboxState"),

    /** Web Push 키 설정이 없어 푸시를 쓸 수 없음 — 운영자가 VAPID 키를 넣어야 한다 (docs/45 9장 N7) */
    PUSH_DISABLED(HttpStatus.CONFLICT, "error.pushDisabled"),
    /** 허용 밖 푸시 서비스 주소(https·허용 호스트만 — SSRF 막기, docs/45 9장 N6) */
    PUSH_ENDPOINT(HttpStatus.BAD_REQUEST, "error.pushEndpoint"),

    // 장비 연동 E0 (docs/65 16장)
    /** 연동이 꺼져 있거나 그 설비의 쓰기가 활성화되지 않음(docs/05 "쓰기는 별도 활성화") */
    EQUIP_WRITE_DISABLED(HttpStatus.CONFLICT, "error.equipWriteDisabled"),
    /** 인터록 — 지금 장비 상태({0})에서는 그 태그를 쓸 수 없음(허용 {1}) */
    EQUIP_CMD_INTERLOCK(HttpStatus.CONFLICT, "error.equipCmdInterlock"),
    /** 같은 설비·태그에 진행 중 명령이 있음 — 끝난 뒤 다시 */
    EQUIP_CMD_BUSY(HttpStatus.CONFLICT, "error.equipCmdBusy"),
    /** 명령이 그 동작을 할 수 없는 상태(결과 불명 확정은 UNKNOWN 만) — 다시 조회 */
    EQUIP_CMD_STATE(HttpStatus.CONFLICT, "error.equipCmdState"),
    /** DIRECT 연동인데 어댑터 주소가 없음 */
    EQUIP_NO_ADAPTER(HttpStatus.CONFLICT, "error.equipNoAdapter"),

    // QMS 4M 복귀·효과 (docs/29_P3 51장)
    /** 임시 변경 복귀를 기록할 수 없음(임시 ECO 가 아니거나 적용 전, 4M 세부 없음, 이미 기록·무효) — 다시 조회 */
    QMS_REVERT_STATE(HttpStatus.CONFLICT, "error.qmsRevertState"),
    /** 변경 효과를 판정할 수 없음(적용 전, 4M 세부 없음, 이미 판정·무효) — 다시 조회 */
    QMS_EFFECT_STATE(HttpStatus.CONFLICT, "error.qmsEffectState"),

    // 기준정보 품목코드 (docs/17 9장)
    /** 채번 규칙 ITEM 이 만든 번호가 품목코드 형식에 맞지 않음(규칙 패턴을 고쳐야 함) */
    ITEM_CD_RULE(HttpStatus.CONFLICT, "error.itemCdRule"),

    // QMS 출하 전 검수 (docs/78)
    /** 지금 검수 상태에서는 할 수 없음(완료·취소 뒤 판정, 출하 확정 뒤 완료 취소 등) — 다시 조회 */
    QMS_PRESHIP_STATE(HttpStatus.CONFLICT, "error.qmsPreshipState"),
    /** 열린 미결이 있는 항목은 판정을 바꾸지 않고 미결 닫기(조치·재확인)로만 */
    QMS_PRESHIP_PUNCH_OPEN(HttpStatus.CONFLICT, "error.qmsPreshipPunchOpen"),

    // 서버
    DB_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "error.dbError");

    private final HttpStatus status;
    private final String messageKey;

    HbErrorCode(HttpStatus status, String messageKey) {
        this.status = status;
        this.messageKey = messageKey;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageKey() {
        return messageKey;
    }
}
