package io.smarthappy.platform.policy;

/** 정책 키 (sys_policy_def.policy_key). 새 키는 Flyway 로 정의를 넣고 여기에 상수를 추가한다 */
public final class Policies {

    public static final String PWD_MIN_LENGTH = "PWD_MIN_LENGTH";
    public static final String PWD_COMPLEXITY_REQUIRED = "PWD_COMPLEXITY_REQUIRED";
    public static final String PWD_EXPIRE_DAYS = "PWD_EXPIRE_DAYS";
    public static final String PWD_BLOCKLIST_ENABLED = "PWD_BLOCKLIST_ENABLED";
    public static final String LOGIN_TRUSTED_IPS = "LOGIN_TRUSTED_IPS";
    public static final String MFA_REQUIRED_FOR_ADMIN = "MFA_REQUIRED_FOR_ADMIN";
    /** MFA 를 강제할 화면 목록(쉼표) — 그 화면에 권한 행이 있는 사람 (docs/36 14장) */
    public static final String MFA_REQUIRED_PROGS = "MFA_REQUIRED_PROGS";
    public static final String AI_ENABLED = "AI_ENABLED";
    public static final String AI_EXTERNAL_API_ALLOWED = "AI_EXTERNAL_API_ALLOWED";
    public static final String AI_PROVIDER = "AI_PROVIDER";
    public static final String AI_TRAINING_CONSENT = "AI_TRAINING_CONSENT";
    public static final String AI_PII_MASKING = "AI_PII_MASKING";
    public static final String FILE_MAX_MB = "FILE_MAX_MB";
    public static final String FILE_ALLOWED_EXT = "FILE_ALLOWED_EXT";
    /** 외부 메일 한 통 첨부 합계 최대 MB (docs/36 18.6 A3) */
    public static final String MAIL_ATTACH_MAX_MB = "MAIL_ATTACH_MAX_MB";
    public static final String APR_SELF_APPROVE_ALLOWED = "APR_SELF_APPROVE_ALLOWED";
    public static final String APR_MAX_LINES = "APR_MAX_LINES";
    public static final String GRID_PAGE_SIZE_MAX = "GRID_PAGE_SIZE_MAX";
    public static final String GRID_BATCH_MAX_ROWS = "GRID_BATCH_MAX_ROWS";
    public static final String EXCEL_EXPORT_MAX_ROWS = "EXCEL_EXPORT_MAX_ROWS";
    public static final String EXCEL_EXPORT_CONCURRENT_PER_COMP = "EXCEL_EXPORT_CONCURRENT_PER_COMP";
    public static final String EXCEL_EXPORT_TIMEOUT_SEC = "EXCEL_EXPORT_TIMEOUT_SEC";
    public static final String SEARCH_MAX_RANGE_DAYS = "SEARCH_MAX_RANGE_DAYS";
    /** 엑셀 올리기 한 파일의 데이터 행 상한 (docs/17 3b) */
    public static final String IMPORT_MAX_ROWS = "IMPORT_MAX_ROWS";
    /** BOM 전개·역전개 레벨 상한 (docs/18 5.2) */
    public static final String BOM_MAX_LEVEL = "BOM_MAX_LEVEL";
    /** BOM 전개 결과 행 상한 — 넘으면 오류(화면·엑셀 모두) */
    public static final String BOM_EXPLODE_MAX_ROWS = "BOM_EXPLODE_MAX_ROWS";
    /** 품목 동기화 방식 PULL·EXCEL·OFF — PULL 이면 ERP 주인 필드 잠금 (docs/07 D8, docs/22) */
    public static final String ERP_ITEM_SYNC_MODE = "ERP_ITEM_SYNC_MODE";
    /** 품목코드 정하는 방식 AUTO(채번 규칙 ITEM)·MANUAL(직접 입력) (docs/17 9장) */
    public static final String ITEM_CD_MODE = "ITEM_CD_MODE";
    /** PMS WBS 깊이 상한 (docs/32 3장) */
    public static final String PMS_MAX_DEPTH = "PMS_MAX_DEPTH";
    /** PMS 프로젝트·템플릿당 작업 수 상한 — 넘으면 잘라 보이지 않고 오류 */
    public static final String PMS_MAX_TASKS = "PMS_MAX_TASKS";
    /** PMS 게이트 통과 전 뒤 단계 착수: OFF / WARN / BLOCK (docs/32 D11) */
    public static final String PMS_GATE_ENFORCE = "PMS_GATE_ENFORCE";
    /** PMS 선행 미완 작업 실적 시작: OFF / WARN(위반 표시만) / BLOCK(PM 은 사유로 통과 + 기록) (docs/32 4.21) */
    public static final String PMS_DEP_ENFORCE = "PMS_DEP_ENFORCE";
    /** PMS 게이트 결정 결재 여부 (Y = 결재, N = APPROVE 권한으로 바로) */
    public static final String PMS_GATE_APPROVAL = "PMS_GATE_APPROVAL";
    /** PMS 기한 임박 일수(산출물·이슈·홈) */
    public static final String PMS_DUE_SOON_DAYS = "PMS_DUE_SOON_DAYS";
    /** PMS 하루 공수 합계 상한(시간, docs/32 PM4) */
    public static final String PMS_DAY_MAX_HOURS = "PMS_DAY_MAX_HOURS";
    /** PMS 하루 공수(시간) — 계획·실적 공수를 MD 로 보일 때 나누는 값(docs/48 R-2, 저장은 시간) */
    public static final String PMS_HOURS_PER_DAY = "PMS_HOURS_PER_DAY";
    /** PMS 담당자 일정변경 요청: Y = 플랫폼 결재(PMS_TASK_CHG), N = PM 이 바로 승인·반려 (docs/32 4.13) */
    public static final String PMS_TASK_CHG_APPROVAL = "PMS_TASK_CHG_APPROVAL";
    /** PMS 착수·종료: Y = 플랫폼 결재(PMS_PROJ_OPEN·PMS_PROJ_CLOSE), N = PM 이 바로 (docs/32 4.16) */
    public static final String PMS_PROJ_APPROVAL = "PMS_PROJ_APPROVAL";
    /** 예방점검 예정을 며칠 앞서 만들지 — 계획에 선행일이 없을 때 (docs/33 4.3) */
    public static final String CMS_PM_LEAD_DAYS = "CMS_PM_LEAD_DAYS";
    /** 예방점검 기한 준수 허용 일수 (docs/33 4.3) */
    public static final String CMS_PM_GRACE_DAYS = "CMS_PM_GRACE_DAYS";
    /** 협력사 인증서 만료 임박 일수 (docs/31 S1) */
    public static final String SRM_CERT_DUE_SOON_DAYS = "SRM_CERT_DUE_SOON_DAYS";
    /** 발주 회신 기한 — 발행일 + 일수가 지나도록 유효 회신이 없으면 "회신 안 옴" (docs/31 S5) */
    public static final String SRM_ACK_DUE_DAYS = "SRM_ACK_DUE_DAYS";
    /** 구매 입고 허용 초과율(%) — 발주 행 열린 수량을 이만큼 넘겨 받을 수 있음 (docs/31 4.9) */
    public static final String SRM_RECV_OVER_PCT = "SRM_RECV_OVER_PCT";
    /** 출하 확정 때 출하 전 검수를 본다(docs/78, 회사, 기본 N — docs/79 Q4) — N 이면 검수 확인 없이 확정 */
    public static final String QMS_PRESHIP_REQUIRED = "QMS_PRESHIP_REQUIRED";
    /** 계측기 교정 기한 임박 일수 (docs/29 Q2a) */
    public static final String QMS_CALIB_DUE_SOON_DAYS = "QMS_CALIB_DUE_SOON_DAYS";
    /** GRR 판정 구간: %GRR 이 이 값 이하면 합격 (docs/29 Q2b) */
    public static final String QMS_GRR_OK_PCT = "QMS_GRR_OK_PCT";
    /** 이 값 이하면 조건부, 넘으면 불합격 */
    public static final String QMS_GRR_LIMIT_PCT = "QMS_GRR_LIMIT_PCT";
    /** 설비 보안 대장: 악성코드 검사 주기 일수 — 마지막 검사 + 일수가 지나면 경고 (docs/33 C5) */
    public static final String CMS_SEC_SCAN_MAX_DAYS = "CMS_SEC_SCAN_MAX_DAYS";
    /** 설비 보안 대장: 정기 검토 주기 일수 — 검토일 + 일수가 지나면 "미검토" (docs/33 C5) */
    public static final String CMS_SEC_REVIEW_DAYS = "CMS_SEC_REVIEW_DAYS";
    /** 위험성평가 행렬 3X3/5X5 — 평가를 만들 때 스냅샷 (docs/33 C6) */
    public static final String CMS_RA_MATRIX = "CMS_RA_MATRIX";
    /** 위험성평가 확정 결재 여부 (Y = 결재 CMS_RA, N = APPROVE 권한으로 바로) */
    public static final String CMS_RA_APPROVAL = "CMS_RA_APPROVAL";

    /** 관리문서 배포를 결재로 할지(Y) — N 이면 APPROVE 권한으로 바로 (docs/29 D7) */
    public static final String QMS_DOC_APPROVAL = "QMS_DOC_APPROVAL";
    /** 시정조치 초기 대응 기한(발생일 + 일) — 고객 CSR CONTAIN_DAYS 가 이김 (docs/29 Q4) */
    public static final String QMS_CAPA_CONTAIN_DAYS = "QMS_CAPA_CONTAIN_DAYS";
    /** 시정조치 종결 기한(발생일 + 일) — 고객 CSR CLOSE_DAYS 가 이김 */
    public static final String QMS_CAPA_CLOSE_DAYS = "QMS_CAPA_CLOSE_DAYS";
    /** ECO 변경 종류 PROCESS_4M·TEMP 는 QMS 4M 세부가 있어야 상신 (docs/29_P3 11.3 ④, BOOL 기본 Y) */
    public static final String QMS_4M_REQUIRED = "QMS_4M_REQUIRED";
    /** 부적합(NCR) 등록 때 그 LOT 을 MES LotHoldPort 로 보류(BOOL 기본 Y — 사용자 Q-h, docs/29_P3 12.7) */
    public static final String QMS_NCR_AUTO_HOLD = "QMS_NCR_AUTO_HOLD";
    /** QMS 판정 지점(NCR 종결·특채 상신, 교정 등록)에 전자서명 필수(BOOL 기본 N — docs/29_P3 12.10) */
    public static final String QMS_ESIGN_REQUIRED = "QMS_ESIGN_REQUIRED";
    /** 4M 고객 통지 — CSR 이 승인을 요구하는데 ECO 고객 승인 필요가 꺼져 있으면 상신을 막을지(WARN·BLOCK, 기본 WARN — docs/29_P3 52장 CN-7) */
    public static final String QMS_CUST_NOTIFY_GATE = "QMS_CUST_NOTIFY_GATE";
    /** 착수 게이트 Q9-1 초품 미판정(OFF·WARN·BLOCK, 기본 WARN — docs/29_P3 12.9 Q-m) */
    public static final String QMS_GATE_FIRST_PENDING = "QMS_GATE_FIRST_PENDING";
    /** 착수 게이트 Q9-2 쓸 수 있는 계측기 없음(OFF·WARN·BLOCK, 기본 WARN) */
    public static final String QMS_GATE_GAUGE = "QMS_GATE_GAUGE";
    /** 착수 게이트 Q9-3 이 품목·공정의 SPC 위반이 미처리(OFF·WARN·BLOCK, 기본 WARN — docs/29_P3 19장) */
    public static final String QMS_GATE_SPC = "QMS_GATE_SPC";
    /** SPC 위반 → LOT 자동 보류(ENUM OFF·LIMIT·ALL, 기본 OFF — LIMIT = 한계 밖 WE1·R1 만, docs/29_P3 23장 SV-6) */
    public static final String QMS_SPC_AUTO_HOLD = "QMS_SPC_AUTO_HOLD";
    /** 연계(아웃박스) 실패 수동 종결에 결재를 거치나 (Y = 결재 OUTBOX_CLOSE, N = FN1 권한으로 바로 — docs/36 19장 O6) */
    public static final String OUTBOX_CLOSE_APPROVAL = "OUTBOX_CLOSE_APPROVAL";
    /** MPS 확정 구간 일수 — 계산일부터 이 일수 안 계획 수량은 현행 확정 MPS 값을 이월, 바꾸면 사유 필수(INT 기본 7, docs/38 16장 AP-3) */
    public static final String APS_MPS_FENCE_DAYS = "APS_MPS_FENCE_DAYS";
    /** 상세 일정 확정 구간(일, 기본 2) — 반영이 이 구간 안 지시의 날짜·설비를 바꾸지 않는다. 품목별 계획 기준정보 값 우선 (docs/38 18장) */
    public static final String APS_FENCE_DAYS = "APS_FENCE_DAYS";
    /** MRP 밀기 예외 허용 일수 (docs/38 19.6) */
    public static final String APS_MRP_OUT_DAYS = "APS_MRP_OUT_DAYS";

    // CMS 설비·생산 대시보드 (docs/51 C2) — 기본 생산 수 기준, KPI 목표(0 = 목표 없음)
    public static final String CMS_DASH_BASIS = "CMS_DASH_BASIS";
    public static final String CMS_TARGET_OEE_PCT = "CMS_TARGET_OEE_PCT";
    public static final String CMS_TARGET_AVAIL_PCT = "CMS_TARGET_AVAIL_PCT";
    public static final String CMS_TARGET_PERF_PCT = "CMS_TARGET_PERF_PCT";
    public static final String CMS_TARGET_QUAL_PCT = "CMS_TARGET_QUAL_PCT";
    public static final String CMS_TARGET_MTTR_MIN = "CMS_TARGET_MTTR_MIN";
    /** 반복 고장 간격 일수 — 같은 설비 × 같은 원인 고장이 이 일수 안에 다시 나면 반복(INT 기본 30, docs/33 16.4 G1) */
    public static final String CMS_REPEAT_FAIL_DAYS = "CMS_REPEAT_FAIL_DAYS";

    private Policies() {
    }
}
