package io.smarthappy.module.cms.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 현장 단말 예방점검 실행 계약 (docs/25 4.58, docs/33 4.3 C2) — POP 이 작업자 자주 점검(CHECK 방식)을 실행할 때. CMS 표를 직접 만지지 않는다.
 * 틀은 {@link BreakdownReport} 와 같다.
 *
 * - <b>호출자(POP) 트랜잭션 안</b>에서 부른다(MANDATORY) — 호출 쪽이 롤백하면 결과·완료도 없다
 * - 사무 계정이 아니라 <b>작업자 + 단말</b>이 한 일로 남는다(cms_chk_run floor_worker_id·floor_terminal_id, 변경 감사 행위자 = {@code t{단말 id}:{코드}})
 * - 단말 공장의 설비 점검만 — 다른 공장·다른 회사(RLS)·없는 실행은 NOT_FOUND. 작업장 범위는 호출자가 본다
 * - <b>CHECK 방식만</b>. 작업지시(WO) 방식 실행은 보전 작업지시 완료가 끝낸다 → CMS_RUN_STATE(409)
 * - 완료·건너뛰기는 {@code clientReqId} 멱등: 같은 id 가 그 실행에 다시 오면 처음 결과(duplicate = true), 다른 실행에 오면 DUPLICATE_REQUEST
 * - 수치 판정은 서버(하한 ≤ 값 ≤ 상한, 경계 포함), 필수 항목은 완료 때 확인 — 사무 화면과 같은 규칙. 칸 오류 행 키 = 항목 순번
 * - 건너뛰기는 사유 코드(공통코드 CMS_SKIP_REASON) 필수 — 사유 없이는 못 건너뛴다(사용자 결정 2026-11-10)
 * CMS 가 없는 설치본은 {@link #NONE}(조회는 빈 목록, 쓰기는 CONFLICT) — POP 은 버튼을 숨긴다.
 */
public interface PmCheckPort {

    /** 열린 점검 하나. state = UPCOMING·DUE·OVERDUE(예정일·선행 기간 기준, 사무 화면과 같은 계산) */
    record DueRun(long runId, String planCd, String planNm, String tmplNm, long equipId, String equipCd, String equipNm, LocalDate dueDate,
            String state, int itemCount, int rowVer) {
    }

    /** 점검 항목(실행 때 점검표에서 스냅샷된 것) + 지금 값. judgeType = OKNG·NUM·TEXT */
    record Item(int seq, String itemNm, String methodText, String judgeType, BigDecimal lsl, BigDecimal usl, String unitNm, int scale,
            boolean required, BigDecimal valueNum, String valueText, String judge, String remark) {
    }

    record RunView(DueRun run, List<Item> items) {
    }

    /** 항목 결과 입력 — NUM 은 value(글자), OKNG 는 judge(OK·NG), TEXT 는 valueText */
    record ItemResult(Integer seq, String value, String valueText, String judge, String remark) {
    }

    /** 결과 저장 + 완료를 한 번에. results 에 없는 항목은 지금 값 그대로 */
    record Complete(long runId, Integer rowVer, List<ItemResult> results, long workerId, long terminalId, String clientReqId) {
    }

    record Skip(long runId, Integer rowVer, String reasonCd, String memo, long workerId, long terminalId, String clientReqId) {
    }

    /** status = DONE·SKIPPED, result = OK·NG(건너뛰면 null), ngItems = 불량 항목 이름(고장 신고로 이어 갈 때 쓴다) */
    record Done(String status, String result, List<String> ngItems, boolean duplicate) {
    }

    /** 구현 없음 — CMS 모듈이 없는 설치본 */
    PmCheckPort NONE = new PmCheckPort() {
        @Override
        public List<DueRun> due(String plantCd, Collection<Long> equipIds) {
            return List.of();
        }

        @Override
        public RunView detail(long runId, long terminalId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }

        @Override
        public Done complete(Complete in) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }

        @Override
        public Done skip(Skip in) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    };

    /** 그 공장·그 설비들의 열린 CHECK 방식 점검 — 예정일 순. 설비가 없으면 빈 목록 */
    List<DueRun> due(String plantCd, Collection<Long> equipIds);

    /** 점검 하나(항목 포함) — 단말 공장 밖·WO 방식·없음은 NOT_FOUND. 끝난 실행도 읽힌다(재전송 뒤 화면) */
    RunView detail(long runId, long terminalId);

    Done complete(Complete in);

    Done skip(Skip in);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static PmCheckPort of(ObjectProvider<PmCheckPort> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
