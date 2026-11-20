package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 보존·법적 보류·정리 프로시저 + 커버리지 가드 (P0 6단계)
 * - 회사별(comp_cd) 테이블은 모두 보존 클래스에 등록돼야 한다
 * - 회사별 테이블은 변경 감사 트리거가 있어야 한다 (예외는 아래 목록에 근거와 함께)
 */
class RetentionTest extends PostgresTestBase {

    /** 감사 트리거 예외: 로그 자체, 값이 매번 바뀌는 카운터, 추가만 하는 기록(바뀌지 않으므로 행 자체가 이력) */
    static final Map<String, String> AUDIT_EXEMPT = Map.ofEntries(
            Map.entry("sys_access_log", "로그 자체 (추가만)"),
            Map.entry("sys_esign", "전자서명 원장 (추가만 — 행 자체가 기록, 수정·삭제 차단)"),
            Map.entry("sys_audit_log", "로그 자체 (추가만)"),
            Map.entry("sys_log_digest", "로그 다이제스트 (추가만)"),
            Map.entry("sys_numbering_seq", "채번 카운터 — 규칙(sys_numbering_rule) 변경은 감사됨"),
            Map.entry("sys_outbox", "메시지 상태 기계(NEW→SENDING→SENT) — 행 자체가 처리 기록"),
            Map.entry("sys_inbox", "수신 기록 (추가만)"),
            Map.entry("apr_history", "결재 이력 자체 (추가만)"),
            Map.entry("sys_grid_state", "사용자 개인 화면 설정(열 폭·순서) — 업무 데이터 아님"),
            Map.entry("sys_usage_daily", "접속 로그에서 계산한 집계 (추가만, 다시 계산 가능)"),
            Map.entry("sys_import_row", "엑셀 올리기 스테이징(행 수만큼 대량) — 작업은 sys_import_job, 반영은 본 테이블 감사로 남는다"),
            Map.entry("sal_forecast_rev", "내시 버전 (추가만 — 수정·삭제 차단, 받은 사람·시각·원본 작업이 행에 있다)"),
            Map.entry("sal_forecast_line", "내시 행 (추가만 — 버전과 함께 들어오고 바뀌지 않는다)"),
            Map.entry("prd_wo_param", "지시 파라미터 스냅샷 (발행 때 추가만 — 바뀌지 않는다)"),
            Map.entry("prd_wo_bom", "지시 자재 소요 스냅샷 (발행 때 추가만 — 바뀌지 않는다)"),
            Map.entry("lot_genealogy", "LOT 계보 원장 (추가만 — 정정은 역분개 행, 행 자체가 이력: 입력자·시각·입력 방식)"),
            Map.entry("prd_wo_proc_event", "공정 착수·멈춤·재개·종료 원장 (추가만 — 행 자체가 이력: 단말·작업자·시각)"),
            Map.entry("prd_result", "생산 실적 원장 (추가만 — 정정은 역분개 행)"),
            Map.entry("prd_result_defect", "실적 불량 내역 (실적과 함께 추가만 — 역분개 때 반대 행)"),
            Map.entry("prd_param_actual", "파라미터 실측 원장 (추가만 — 정정은 CORRECT 행)"),
            Map.entry("qms_insp_meas", "검사 측정값 원장 (추가만 — 정정은 무효 행, 행 자체가 이력: 입력자·시각·게이지, docs/29_P3 Q6)"),
            Map.entry("qms_spc_event", "SPC 위반 원장 (추가만 — 행 자체가 기록: 한계 스냅샷·발생/기록 시각, docs/29_P3 19장)"),
            Map.entry("qms_spc_event_act_ocap", "SPC 위반 조치에서 고른 OCAP 항목 사본 (추가만 — 고른 순간 문구가 행, docs/29_P3 26장)"),
            Map.entry("qms_spc_event_act", "SPC 위반 처리 기록 (추가만 — ACK·ACTION·CLOSE·VOID 가 행, 처리자·시각, docs/29_P3 19장)"),
            Map.entry("inv_trx", "재고 원장 (추가만 — 정정은 역분개 행, 행 자체가 이력: 입력자·시각·입력 방식)"),
            Map.entry("inv_stock", "현재고 = 원장 투영 (원장 INSERT 트리거만 고친다 — 변경 이력은 inv_trx)"),
            Map.entry("qms_spc_event_state", "SPC 위반 상태 = 원장 투영 (원장·처리 기록 INSERT 트리거만 고친다 — 이력은 qms_spc_event_act)"),
            Map.entry("qms_spc_unit", "SPC 단위 요약 = 검사 판정 투영 (qms_insp 판정 상태 트리거만 고친다 — 이력은 qms_insp)"),
            // MES P2 6단계 설비 상태·Collector (docs/25 4.8)
            Map.entry("cms_equip_state_log", "설비 상태 구간 원장 (추가 + 끝 시각 한 번만 — 행 자체가 이력: 장치·이벤트·시각)"),
            Map.entry("cms_counter_src", "카운터 자동 입력 출처 (추가만 — 감사는 그 읽기 행 cms_counter_reading 이 남긴다)"),
            Map.entry("cms_alarm_log", "설비 알람 원장 (추가 + 해제 한 번만 — 행 자체가 이력: 장치·이벤트·두 시각, 수집량이 많음)"),
            Map.entry("cms_equip_conn_log", "설비 연결·수집 누락 원장 (추가만)"),
            Map.entry("cms_energy_reading", "에너지 구간 사용량 원장 (추가만 — 정정은 역분개 행)"),
            Map.entry("cms_equip_count", "수집 COUNT 증가분 원장 (추가만 — 정정은 역분개 행, docs/25 4.27)"),
            Map.entry("cms_equip_inbox", "설비 이벤트 수신 멱등 기록 (추가 + 보존 정리만 — 행 자체가 기록, docs/70 W2)"),
            Map.entry("cms_collector_seq", "수집 장치별 마지막 seq (누락 감지용 카운터 — 누락은 연결 원장에 남는다)"),
            // APS (docs/38)
            Map.entry("aps_plan_wo", "APS 계획 결과 (계산 트랜잭션에서만 추가 — 수정·삭제 차단, 계산한 사람·시각은 계획 머리에)"),
            Map.entry("aps_plan_op", "APS 계획 공정 결과 (계산 트랜잭션에서만 추가 — 수정·삭제 차단)"),
            // MES P2 7a 완료판정·입고·재작업 처리 (docs/25 4.9)
            Map.entry("prd_wo_proc_close", "공정 완료판정 원장 (공정당 한 번 추가만 — 판정자·시각·수량이 행에)"),
            Map.entry("prd_rework", "재작업·수리 처리 원장 (추가만)"),
            Map.entry("pop_approval", "POP 감독자 승인 원장 (추가만 — 승인자·요청 작업자·동작·대상·시각이 행에, docs/25 4.37)"),
            Map.entry("prd_receipt", "생산 입고 원장 (추가만 — 정정은 역분개 행)"),
            // MES P2 7c (docs/25 4.11)
            Map.entry("prd_erp_export", "ERP 엑셀 내보내기 기록 (추가만 — 사람·시각·기간·건수가 행에)"),
            // PLM 3b 변경 표시 (docs/39 13장)
            Map.entry("plm_eco_lot", "설계·4M 변경 LOT 표시 (추가만 — 행 자체가 이력: 표시한 사람·시각·발생 시각)"),
            // 시스템관리 2b 알림·공지 (docs/36 11.2)
            Map.entry("sys_notify", "알림함 메시지 (추가 + 읽은 시각 한 번만 — 행 자체가 기록: 받는 사람·이벤트·시각·보낸 행위자)"),
            Map.entry("sys_notice_read", "공지 읽음 표시 (추가만 — 사람·시각이 행에)"),
            // 시스템관리 4a MFA (docs/36 14.2)
            Map.entry("sys_user_mfa_recovery", "복구 코드 해시 (추가·사용 시각 한 번·초기화 때 삭제 — 비밀 파생값이라 감사에 남기지 않는다, 등록·초기화는 sys_user_mfa 감사와 접속 로그)"),
            // APS 5단계 ② 자재 가용 (docs/38 14장)
            Map.entry("aps_plan_mat", "APS 계획 자재 할당 결과 (계산 트랜잭션에서만 추가 — 수정·삭제 차단, 보존 정리만)"),
            Map.entry("aps_plan_supply", "APS 계획이 쓴 공급 사본 (계산 트랜잭션에서만 추가 — 수정·삭제 차단, 보존 정리만)"),
            Map.entry("aps_mps_row", "MPS 품목 행 (계산 트랜잭션에서만 추가 — 수정·삭제 차단, 계산한 사람·시각은 MPS 머리에)"),
            Map.entry("aps_mrp_item", "MRP 계획 품목 사본 (계산 트랜잭션에서만 추가 — 수정·삭제 차단, 계산한 사람·시각은 MRP 머리에)"),
            Map.entry("aps_mrp_elem", "MRP 재고/소요 요소 (계산 트랜잭션에서만 추가 — 수정·삭제 차단)"),
            Map.entry("aps_mrp_peg", "MRP 페깅 (계산 트랜잭션에서만 추가 — 수정·삭제 차단)"),
            Map.entry("aps_mrp_exc", "MRP 예외 메시지 (계산 트랜잭션에서만 추가 — 수정·삭제 차단)"),
            // MES 장비 시리얼 구성 (docs/77)
            Map.entry("prd_serial_part", "장비 장착 원장 (추가만 — 탈거는 반대 행, 행 자체가 이력: 기록자·작업자·시각·입력 방식)"),
            // 시스템관리 Y1 기록 무결성 점검 (docs/52) — 런타임 점검은 platform.log.AuditCoverage 와 같은 목록을 쓴다(IntegrityCheckTest)
            Map.entry("sys_integrity_run", "기록 무결성 점검 결과 (추가만 — 앱 UPDATE·DELETE 회수, 결과 자체가 점검 기록: 실행자·시각·상태)"),
            // 장비 연동 E0 (docs/65 16장)
            Map.entry("sys_inbound_msg", "수신 원문 저널 — 상태 기계(NEW→DONE·STALE·REJECTED·QUARANTINE), 원문 칸 고정·행 자체가 수신 기록"),
            Map.entry("cms_equip_cmd", "장비 명령 — 상태 기계, 의도 칸 고정. 이력은 단계 원장 cms_equip_cmd_step"),
            Map.entry("cms_equip_cmd_step", "장비 명령 단계 원장 (추가만 — 의도·보냄·쓰기·다시 읽기·결과)"),
            Map.entry("cms_equip_link_log", "장비 연동 사건 원장 (추가만)"),
            Map.entry("cms_equip_consumable", "소모품 마지막 수준(하트비트가 갱신 — 바뀜은 알람 원장에 남는다)"),
            Map.entry("cms_edge_session", "장치 세션(bootId·마지막 하트비트 — birth 는 연동 사건 원장에 남는다)"),
            // 장비 연동 E1 라벨 (docs/65 18장)
            Map.entry("wms_printer_status", "프린터 마지막 상태(확인 작업·에이전트가 갱신 — 인쇄 확인·오류는 인쇄 사건 원장에 남는다)"),
            Map.entry("wms_print_job_event", "인쇄 사건 원장 (추가만 — 보냄·확인·검증·오류·사람 확정)"),
            Map.entry("wms_print_dispatch", "인쇄 에이전트 큐 행(단계·lease — 상태 이력은 인쇄 사건 원장에 남는다, docs/65 19.2)"),
            // 장비 연동 E3 오토라벨러 (docs/65 21장)
            Map.entry("wms_labeler_event", "오토라벨러 박스 단위 원장 (추가만 — 요청·답·인쇄·부착·검증·리젝트·결번)"),
            Map.entry("wms_labeler_dev_log", "오토라벨러 장비 단위 원장 (추가만 — 오류·상태·리젝트 만재)"),
            // 장비 연동 E4 자동창고 (docs/65 23장)
            Map.entry("wms_asrs_event", "자동창고 사건 원장 (추가만 — 보냄·WES 상태·원장 반영·격리·경계 교환)"),
            Map.entry("wms_asrs_recon_part", "자동창고 대사 받은 조각 원문 (추가만 — 재전송 멱등, docs/65 25.3)"));

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
    }

    private static Set<String> ownerStrings(String sql) throws Exception {
        Set<String> out = new HashSet<>();
        try (Connection c = owner(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static final String COMP_TABLES = """
            select c.relname from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
              join pg_attribute a on a.attrelid = c.oid and a.attname = 'comp_cd' and not a.attisdropped
             where n.nspname = 'public' and c.relkind in ('r', 'p')""";

    // ---------- 커버리지 가드 ----------

    @Test
    void 회사별_테이블은_모두_보존_클래스에_등록돼_있다() throws Exception {
        Set<String> missing = ownerStrings(COMP_TABLES);
        missing.removeAll(ownerStrings("select table_nm from sys_retention_table"));
        assertThat(missing).as("sys_retention_table 에 없는 회사별 테이블 — 새 V 파일에서 클래스를 등록").isEmpty();
    }

    @Test
    void 회사별_테이블은_변경_감사_트리거가_있다() throws Exception {
        Set<String> missing = ownerStrings(COMP_TABLES);
        missing.removeAll(ownerStrings("""
                select c.relname from pg_trigger t join pg_class c on c.oid = t.tgrelid
                  join pg_proc p on p.oid = t.tgfoid
                 where p.proname = 'hb_audit' and not t.tgisinternal"""));
        missing.removeAll(AUDIT_EXEMPT.keySet());
        assertThat(missing).as("hb_audit 트리거가 없는 회사별 테이블 — 추가하거나 예외 목록에 근거와 함께").isEmpty();
    }

    @Test
    void 보존_클래스_등록은_앱이_바꿀_수_없다() {
        String comp = accounts.newComp("RT");
        assertThatThrownBy(() -> runner.run(comp, () -> jdbc.update(
                "update sys_retention_class set min_days = 1"))).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runner.run(comp, () -> jdbc.update(
                "delete from sys_retention_table where table_nm = 'sys_access_log'"))).rootCause().hasMessageContaining("permission denied");
    }

    // ---------- 정리 프로시저 ----------

    @Test
    void 앱_계정은_정리_프로시저를_실행할_수_없다() {
        String comp = accounts.newComp("RT");
        assertThatThrownBy(() -> runner.call(comp, () -> jdbc.queryForList("select * from hb_purge_logs()")))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void 보존_기간이_지난_로그만_지우고_법적_보류와_최소_기간은_지킨다() throws Exception {
        String a = accounts.newComp("RT");
        String b = accounts.newComp("RT");
        for (String comp : List.of(a, b)) {
            for (int days : new int[] {10, 400, 800, 900}) {
                accounts.exec(comp, """
                        insert into sys_access_log (comp_cd, occurred_at, event_cd, result_cd)
                        values (?, now() - make_interval(days => ?), 'LOGIN_OK', 'OK')""", comp, days);
            }
        }
        // A사: 보존 100일로 줄이려 해도 클래스 최소(365일)보다 짧게는 안 된다 + 850~950일 전은 법적 보류
        accounts.exec(a, """
                insert into sys_retention_policy (comp_cd, class_cd, keep_days, created_by, updated_by)
                values (?, 'ACCESS_LOG', 100, 't', 't')""", a);
        accounts.exec(a, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                values (?, 'ACCESS_LOG', now() - interval '950 days', now() - interval '850 days', '품질 클레임 소송', 't', 't')""", a);

        try (Connection c = owner(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.executeQuery("select * from hb_purge_logs()").close();
            c.commit();
        }
        // A: 10(유지) 400(최소 365 지나 삭제) 800(삭제) 900(보류 → 유지)
        assertThat(days(a)).containsExactly(10, 900);
        // B: 기본 730일 → 10, 400 유지
        assertThat(days(b)).containsExactly(10, 400);
    }

    @Test
    void 법적_보류를_해제하면_다음_정리에서_지워진다() throws Exception {
        String a = accounts.newComp("RT");
        accounts.exec(a, """
                insert into sys_access_log (comp_cd, occurred_at, event_cd, result_cd)
                values (?, now() - interval '900 days', 'LOGIN_OK', 'OK')""", a);
        accounts.exec(a, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, released_at, created_by, updated_by)
                values (?, 'ACCESS_LOG', now() - interval '950 days', now() - interval '850 days', '종결', now(), 't', 't')""", a);
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.executeQuery("select * from hb_purge_logs()").close();
        }
        assertThat(days(a)).isEmpty();
    }

    @Test
    void 법적_보류는_사유와_올바른_기간이_필요하다() {
        String a = accounts.newComp("RT");
        assertThatThrownBy(() -> accounts.exec(a, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                values (?, 'ACCESS_LOG', now(), now() - interval '1 day', '역전', 't', 't')""", a))
                .rootCause().hasMessageContaining("ck_sys_legal_hold_range");
        assertThatThrownBy(() -> accounts.exec(a, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                values (?, 'ACCESS_LOG', now() - interval '1 day', now(), '   ', 't', 't')""", a))
                .rootCause().hasMessageContaining("ck_sys_legal_hold_reason");
    }

    @Test
    void A사가_B사_법적_보류를_만들_수_없다() {
        String a = accounts.newComp("RT");
        String b = accounts.newComp("RT");
        assertThatThrownBy(() -> accounts.exec(a, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                values (?, 'ACCESS_LOG', now() - interval '1 day', now(), '남의 회사', 't', 't')""", b))
                .rootCause().hasMessageContaining("row-level security");
    }

    private List<Integer> days(String comp) {
        return runner.call(comp, () -> jdbc.queryForList("""
                select round(extract(epoch from now() - occurred_at) / 86400)::int
                  from sys_access_log order by occurred_at desc""", Integer.class));
    }
}
