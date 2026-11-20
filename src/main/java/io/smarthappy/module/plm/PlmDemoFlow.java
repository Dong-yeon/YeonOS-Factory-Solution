package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.smarthappy.module.demo.DemoSeedSupport;
import io.smarthappy.module.demo.DemoSeedSupport.SeedClock;
import io.smarthappy.module.plm.PlmDtos.CustApprovalForm;
import io.smarthappy.module.plm.PlmDtos.EcoActForm;
import io.smarthappy.module.plm.PlmDtos.EcoApproveForm;
import io.smarthappy.module.plm.PlmDtos.EcoForm;
import io.smarthappy.module.plm.PlmDtos.EcrAcceptForm;
import io.smarthappy.module.plm.PlmDtos.EcrForm;
import io.smarthappy.module.plm.PlmDtos.LineItem;
import io.smarthappy.module.plm.PlmDtos.SubmitForm;
import io.smarthappy.module.plm.PlmDtos.TargetItem;
import io.smarthappy.module.plm.PlmDtos.TargetSet;
import io.smarthappy.module.qms.ChangeDtos.ChangeForm;
import io.smarthappy.module.qms.ChangeService;
import io.smarthappy.module.qms.DocDtos;
import io.smarthappy.module.qms.DocDtos.DocForm;
import io.smarthappy.module.qms.DocDtos.NewRevForm;
import io.smarthappy.module.qms.DocDtos.TargetForm;
import io.smarthappy.module.qms.DocService;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * PLM 데모 관통 흐름 (docs/39 "데모 둘러보기") — 화면과 같은 서비스 길로, 지난 60일 안에:
 * 도면 2건(QMS 관리문서 DRAWING: A0 결재·배포 → A1 작성 중, DocService) →
 * ECO-1 설계 변경(도면 A1 대상, 고객 승인 기록 → 적용 = 도면 A1 배포·A0 폐기) →
 * ECO-2 4M 변경(설비·공정 대상 + QMS 4M 세부 ChangeService, 변경 뒤 첫 LOT 3개 표시, 적용) →
 * ECR 접수 → ECO-3(도면 B A1 대상, 승인됨·적용 대기 — 유효일 앞으로) → ECO-4 임시 4M(작성 중) · ECR 하나는 접수 대기.
 *
 * <ul>
 * <li>회사에 ECR·ECO 가 하나라도 있으면 아무것도 하지 않는다. 전부 한 트랜잭션</li>
 * <li>결재(도면·ECO)는 실제 ApprovalService 로 — 요청자 = 회사 관리자, 결재자 = 로그인할 수 없는 데모 결재자(DemoSeedSupport)</li>
 * <li>기준정보 데모(품목 64311-DM100·65110-DM200·64310-DM100·64320-DM100, 설비 SPOT-101, 공정 SPOT·CO2, 고객 S001)가 없으면 건너뛴다</li>
 * <li>지난 날짜는 시드 시계 — 유효일 ≥ 그날 검사를 그날 기준으로 통과한다. 결재·적용 시각 칸(DB now())은 실제 넣은 시각</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlmDemoFlow {

    static final String DWG_A = "DWG-64311-DM100";
    static final String DWG_B = "DWG-65110-DM200";

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final DemoSeedSupport support;
    private final PolicyService policies;
    private final ApprovalService approvals;
    private final DocService docs;
    private final ChangeService changes;
    private final EcoService ecos;
    private final EcrService ecrs;

    /** 만든 ECO 수(이미 있거나 기준정보가 없으면 0). user = 요청자(회사 관리자) */
    public int seed(String comp, LoginUser user) {
        return DemoSeedSupport.as(user, () -> runner.call(comp, () -> {
            if (count("select count(*) from plm_eco") + count("select count(*) from plm_ecr") > 0 || !ready()) {
                return 0;
            }
            LoginUser approver = support.approver(comp, user.loginId());
            ZoneId zone = clock.zone();
            LocalDate today = clock.today();
            return support.withClock(c -> run(c, user, approver, zone, today));
        }));
    }

    private boolean ready() {
        return count("""
                select count(*) from mst_item where use_yn = 'Y'
                   and item_cd in ('64311-DM100', '65110-DM200', '64310-DM100', '64320-DM100')""") == 4
                && count("select count(*) from mst_equip where equip_cd = 'SPOT-101' and use_yn = 'Y'") == 1
                && count("select count(*) from mst_proc where proc_cd in ('SPOT', 'CO2') and use_yn = 'Y'") == 2
                && count("select count(*) from mst_cust where cust_cd = 'S001' and cust_type in ('SALES', 'BOTH') and use_yn = 'Y'") == 1
                && count("select count(*) from mst_doc where doc_no in (?, ?)", DWG_A, DWG_B) == 0;
    }

    private int run(SeedClock c, LoginUser u, LoginUser apr, ZoneId zone, LocalDate today) {
        // 도면 2건 — 50일 전 A0 배포(결재)
        c.set(today.minusDays(50), LocalTime.of(10, 0), zone);
        long docA = drawing(DWG_A, "사이드 멤버 INR 도면", "64311-DM100", apr);
        long docB = drawing(DWG_B, "플로어 크로스 멤버 ASSY 도면", "65110-DM200", apr);

        // ECO-1 설계 변경: 고객 요청 — 사이드 멤버 INR 판 두께 1.6t → 1.8t (도면 A1 배포)
        c.set(today.minusDays(45), LocalTime.of(9, 0), zone);
        docs.newRevision(docA, new NewRevForm(null, "판 두께 1.6t → 1.8t, 홀 위치 X +0.5"));
        long eco1 = ecos.create(new EcoForm("사이드 멤버 INR 판 두께 변경(1.6t → 1.8t)", "CUSTOMER",
                "고객(가상) 충돌 해석 결과 반영 — 판 두께 상향, 홀 위치 조정", today.minusDays(40).toString(), null, "Y", "S001",
                "DESIGN", 3, null, null, null, null, null));
        targets(eco1, List.of(new TargetItem("d", null, "DOC", "64311-DM100", null, DWG_A, "도면 A1 — 두께 1.8t"),
                new TargetItem("i", null, "ITEM", "64311-DM100", null, null, "소재 사양 SPFC590 1.8t")));
        approveEco(c, eco1, today.minusDays(44), today.minusDays(43), zone, apr, "충돌 해석 근거 확인");
        c.set(today.minusDays(42), LocalTime.of(15, 0), zone);
        ecos.custApproval(eco1, new CustApprovalForm(ecoVer(eco1), "APPROVED", "CA-DEMO-0142", today.minusDays(42).toString()));
        c.set(today.minusDays(41), LocalTime.of(9, 0), zone);
        ecos.release(eco1, new EcoActForm(ecoVer(eco1), null));     // 도면 A1 배포·A0 폐기는 적용이 한다(DocService.releaseFromChange)

        // ECO-2 4M 변경: 스폿 셀 1 용접 건 교체 — 설비·공정 대상 + QMS 4M 세부, 변경 뒤 첫 LOT 3개 표시
        c.set(today.minusDays(20), LocalTime.of(9, 0), zone);
        long eco2 = ecos.create(new EcoForm("스폿 셀 1 용접 건 교체(서보 건)", "QUALITY",
                "스폿 누락 재발 — 공압 건을 서보 건으로 교체, 가압력 조건 재설정", today.minusDays(15).toString(), null, "N", null,
                "PROCESS_4M", 3, null, null, null, null, null));
        targets(eco2, List.of(new TargetItem("e", null, "EQUIP", "64310-DM100", null, "SPOT-101", "용접 건 공압 → 서보"),
                new TargetItem("p", null, "PROC", "64310-DM100", null, "SPOT", "가압력 3.2kN 재설정·조건표 개정")));
        changes.save(eco2, new ChangeForm(List.of("MACHINE", "METHOD"), "공압 스폿 건(가압 편차 ±0.4kN)",
                "서보 스폿 건(가압 편차 ±0.1kN), 용접 조건표 개정", "스폿 누락 NCR 재발 방지", "인발 강도·스폿 개수 초품 확인, 조건표 개정",
                null, null, null));
        approveEco(c, eco2, today.minusDays(19), today.minusDays(18), zone, apr, "초품 확인 조건으로 승인");
        c.set(today.minusDays(16), LocalTime.of(9, 0), zone);
        ecos.release(eco2, new EcoActForm(ecoVer(eco2), null));

        // ECR 접수 → ECO-3: 너트 M8 → M10 (도면 B A1, 승인됨·적용 대기 — 유효일 2주 뒤)
        c.set(today.minusDays(12), LocalTime.of(10, 0), zone);
        long ecr1 = ecrs.create(new EcrForm("플로어 크로스 멤버 너트 M8 → M10 변경 요청", "CUSTOMER",
                "고객(가상) 시트 체결 토크 상향 — 용접 너트 규격 변경 요청", "65110-DM200", null));
        c.set(today.minusDays(11), LocalTime.of(10, 0), zone);
        long eco3 = ecrs.accept(ecr1, new EcrAcceptForm(ver("plm_ecr", "ecr_id", ecr1), null, today.plusDays(14).toString(),
                "다음 달 첫 주 적용으로 접수"));
        c.set(today.minusDays(10), LocalTime.of(10, 0), zone);
        docs.newRevision(docB, new NewRevForm(null, "용접 너트 M8 → M10, 홀 지름 변경"));
        targets(eco3, List.of(new TargetItem("d", null, "DOC", "65110-DM200", null, DWG_B, "도면 A1 — 너트 M10")));
        approveEco(c, eco3, today.minusDays(9), today.minusDays(8), zone, apr, "재고 소진 일정 확인 뒤 적용");

        // ECO-4 임시 4M(작성 중): 와이어 대체품 — 복귀 조건 필수
        c.set(today.minusDays(2), LocalTime.of(16, 0), zone);
        long eco4 = ecos.create(new EcoForm("임시: CO2 용접 와이어 대체품 사용", "SUPPLIER", "공급사 결품 — 동등 규격 대체 와이어 2주 사용",
                today.plusDays(1).toString(), null, "N", null, "TEMP", null, today.plusDays(30).toString(), null, null, null, null));
        targets(eco4, List.of(new TargetItem("p", null, "PROC", "65110-DM200", null, "CO2", "대체 와이어 ER70S-6 동등품")));
        changes.save(eco4, new ChangeForm(List.of("MATERIAL", "SUPPLIER"), "와이어 ER70S-6 (협력사 P007)", "동등 규격 대체 와이어(타 공급사)",
                "협력사 결품", "용접 강도 초품 확인", "정품 와이어 입고 뒤 첫 LOT 부터 복귀", null, null));

        // 접수 대기 ECR
        c.set(today.minusDays(3), LocalTime.of(11, 0), zone);
        ecrs.create(new EcrForm("프런트 사이드 멤버 RH 스폿 타점 추가 검토", "QUALITY", "인발 강도 하한 근접 LOT 2건 — 타점 2점 추가 검토",
                "64320-DM100", null));
        return 4;
    }

    /** 도면 문서 + A0 결재·배포(정책이 결재 없음이면 바로 배포). 반환 문서 id */
    private long drawing(String docNo, String nm, String itemCd, LoginUser apr) {
        long docId = docs.create(new DocForm(docNo, "DRAWING", nm, "Y", "데모 도면(가상)", List.of(new TargetForm("ITEM", itemCd)), null));
        long a0 = id("select doc_rev_id from mst_doc_rev where doc_id = ? order by doc_rev_id limit 1", docId);
        if (policies.bool("QMS_DOC_APPROVAL")) {
            long aprId = docs.submit(a0, new DocDtos.SubmitForm(ver("mst_doc_rev", "doc_rev_id", a0), null,
                    List.of(new DocDtos.LineItem(apr.userId(), "APPROVE"))));
            DemoSeedSupport.as(apr, () -> approvals.approve(aprId, "최초 도면 승인"));
        } else {
            docs.release(a0, new DocDtos.ActionForm(ver("mst_doc_rev", "doc_rev_id", a0)));
        }
        return docId;
    }

    private void targets(long ecoId, List<TargetItem> items) {
        ecos.saveTargets(ecoId, new TargetSet(ecoVer(ecoId), items));
    }

    /** 상신(요청자) → 다음 날 승인(데모 결재자) */
    private void approveEco(SeedClock c, long ecoId, LocalDate submitOn, LocalDate approveOn, ZoneId zone, LoginUser apr, String comment) {
        c.set(submitOn, LocalTime.of(14, 0), zone);
        ecos.submit(ecoId, new SubmitForm(ecoVer(ecoId), null, List.of(new LineItem(apr.userId(), "APPROVE"))));
        c.set(approveOn, LocalTime.of(10, 30), zone);
        DemoSeedSupport.as(apr, () -> ecos.approve(ecoId, new EcoApproveForm(comment, null)));
    }

    private int ecoVer(long ecoId) {
        return ver("plm_eco", "eco_id", ecoId);
    }

    private int ver(String table, String key, long id) {
        Integer v = jdbc.queryForObject("select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
        return v == null ? 0 : v;
    }

    private long id(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
