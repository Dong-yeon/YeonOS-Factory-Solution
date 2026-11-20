package io.smarthappy.module.qms.link;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 현장 단말(POP) 검사 입력 계약 (docs/29_P3 53장, docs/25 4.57 PI-*) — MES POP 이 부르고 QMS 가 구현한다. 화면·API(/api/pop/**)는 MES, 기록은 QMS.
 * {@code BreakdownReport}(CMS 고장 신고)와 같은 틀.
 *
 * <ul>
 * <li>POP 트랜잭션 안에서 부른다(쓰기 MANDATORY, 읽기 SUPPORTS) — 예외면 호출 쪽 전체 롤백</li>
 * <li>행위자 = 작업자 id + 단말 id 따로(사무 계정 없음). 감사·기록 행위자 = {@code t{단말 id}:{단말 코드}}(POP 관례). QMS 가 같은 회사·사용 중 작업자,
 *     등록 단말, <b>단말 공장 = 검사 LOT·지시의 공장</b>을 다시 본다(다르면 칸 오류 FLOOR_PLANT)</li>
 * <li>검사 시작 = 자동(사용자 결정 2026-11-10): {@link #open} 은 같은 유형·LOT·공정의 <b>열린 검사가 있으면 그것</b>({@link InspRef#existing()}),
 *     없으면 오늘 유효한 확정 검사계획으로 새로 만든다. 초물(FIRST_OFF)은 그 공정 첫 실적 LOT, 중물은 순회(PATROL) — 새 유형 없음</li>
 * <li>판정 = 자동(사용자 결정 2026-11-10): {@link #measure} 가 넣을 때마다 행·검사 판정 미리보기를 돌려주고, 모든 행이 판정(OK/NG)되고 <b>전부 OK 면
 *     그 자리에서 판정 확정</b>({@link MeasureResult#judged()}). <b>NG 면 확정하지 않고</b> {@link FloorInsp#nextAction()} = CONFIRM_NG —
 *     작업자가 {@link #confirmNg} 를 한 번 눌러야 확정(오타로 LOT 이 보류되는 것을 막는 한 단계). 확정 후속(SPC 이벤트·재고 품질 상태)은 사무 판정과 같다</li>
 * <li>측정값 무효·검사 무효는 이 계약에 없다 — 사무 화면 qms-insp 에서만</li>
 * <li>입력 오류는 칸 오류 400 — inspType·lotNo·woProcId·workerId·terminalId·clientReqId, 측정은 행 키 = entries 순번(sampleNo·value·okYn·gaugeAssetId)</li>
 * <li>같은 clientReqId 재요청 = 처음 결과({@code duplicate = true}), 다시 쓰지 않는다(sys_inbox)</li>
 * <li>구현 없는 설치본은 {@link #NONE} — MES 는 {@link #of(ObjectProvider)} 가 NONE 이면 POP [검사] 버튼을 숨긴다</li>
 * </ul>
 */
public interface FloorInspPort {

    /** 단말에서 여는 유형 — 초물·순회(중물)·종물·공정 검사. 수입·출하 검사는 사무 화면 */
    Set<String> TYPES = Set.of("FIRST_OFF", "PATROL", "LAST_OFF", "PROCESS");

    /** 측정 입력 방법 — KEY(키패드)·SCAN(계측기 스캔·디지털 게이지) */
    Set<String> ENTRY_METHODS = Set.of("KEY", "SCAN");

    /**
     * 이 지시 공정에서 열 수 있는 검사 유형(오늘 유효한 확정 검사계획이 있는 것만).
     *
     * @param woProcId   지시 공정(공정 패널)
     * @param terminalId POP 단말 — 지시 공장 = 단말 공장이어야(아니면 빈 목록)
     */
    record DueQuery(long woProcId, long terminalId) {
    }

    /**
     * 유형 하나.
     *
     * @param suggestedLotNo 추천 LOT — 초물 = 이 공정 첫 실적 LOT, 그 밖 = 가장 최근 실적 LOT(정정된 실적 제외). 실적이 없으면 null(아직 열 수 없음)
     * @param openInspId     그 LOT·유형·공정의 열린 검사(없으면 null)
     * @param lastResult     그 공정·유형의 가장 최근 판정 확정 결과 OK·NG(없으면 null), lastJudgedAt = 그 시각
     */
    record DueInsp(String inspType, String suggestedLotNo, Long openInspId, String openInspNo, String lastResult,
            OffsetDateTime lastJudgedAt) {
    }

    /**
     * 검사 열기(있으면 그것).
     *
     * @param inspType    {@link #TYPES} 중 하나
     * @param lotNo       검사 LOT(보통 {@link DueInsp#suggestedLotNo()}) — 이 지시의 산출 LOT 이어야
     * @param woProcId    지시 공정(공정·지시·공장을 여기서 정한다)
     * @param workerId    배지로 확인한 작업자
     * @param terminalId  POP 단말
     * @param clientReqId 단말이 누른 순간 만든 요청 id(8~64자, 영문·숫자·_.:-)
     */
    record OpenForm(String inspType, String lotNo, long woProcId, long workerId, long terminalId, String clientReqId) {
    }

    /** existing = 이미 열린 검사를 돌려줌, duplicate = 같은 요청 id 재전송 */
    record InspRef(long inspId, String inspNo, boolean existing, boolean duplicate) {
    }

    /** 살아 있는 측정값 하나 — outOfSpec = 계량 규격 밖 또는 계수 NG */
    record Meas(long measId, int sampleNo, BigDecimal value, String okYn, Long gaugeAssetId, boolean outOfSpec) {
    }

    /**
     * 검사 행 하나(규격·샘플 수는 검사 스냅샷).
     *
     * @param verdict 판정 미리보기 OK·NG·OPEN(아직 모름)
     */
    record Line(long inspLineId, int lineNo, String charLabel, String charNm, String dataType, BigDecimal nominal, BigDecimal lsl,
            BigDecimal usl, Integer scale, String unitNm, String gaugeClass, int sampleSize, int ac, int re, String verdict, List<Meas> meas) {
    }

    /**
     * 단말 검사 화면 한 장.
     *
     * @param status     OPEN·JUDGED·VOID
     * @param result     확정 결과 OK·NG(확정 전 null)
     * @param preview    검사 판정 미리보기 OK·NG·OPEN
     * @param nextAction MEASURE(더 잴 것 있음) · CONFIRM_NG(모든 행 판정·NG — [불합격 확정] 필요) · DONE(확정·무효)
     */
    record FloorInsp(long inspId, String inspNo, String inspType, String lotNo, String itemCd, String itemNm, String procCd, String status,
            String result, String preview, String nextAction, List<Line> lines) {
    }

    /** 샘플 하나 — 계량은 value, 계수는 okYn(Y·N). 계측기 클래스가 VISUAL 이 아니면 gaugeAssetId 필수(교정 막힘 = GAUGE_BLOCKED) */
    record Entry(int sampleNo, BigDecimal value, String okYn, Long gaugeAssetId) {
    }

    /**
     * 측정 입력(한 행의 샘플 여러 개, 1~200).
     *
     * @param entryMethod {@link #ENTRY_METHODS} — 비우면 KEY
     */
    record MeasureForm(long inspId, long inspLineId, List<Entry> entries, String entryMethod, long workerId, long terminalId,
            String clientReqId) {
    }

    /** judged = 이번 입력으로 전부 OK 가 되어 자동 확정됨, insp = 입력(또는 확정) 뒤 화면 */
    record MeasureResult(boolean duplicate, boolean judged, FloorInsp insp) {
    }

    /** 불합격 확정 — 모든 행이 판정되고 검사 미리보기가 NG 일 때만 */
    record ConfirmForm(long inspId, long workerId, long terminalId, String clientReqId) {
    }

    /** 계측기 선택지(교정 막힘 표시 포함 — 막힌 것은 고를 수 없게) */
    record Gauge(long assetId, String assetCd, String assetNm, String gaugeClass, boolean blocked) {
    }

    /** 구현 없음 — 유형 없음, 쓰기는 409 CONFLICT(버튼이 숨겨져 있어야 한다) */
    FloorInspPort NONE = new FloorInspPort() {
        @Override
        public List<DueInsp> due(DueQuery q) {
            return List.of();
        }

        @Override
        public InspRef open(OpenForm f) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }

        @Override
        public FloorInsp detail(long inspId, long terminalId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }

        @Override
        public List<Gauge> gauges(String gaugeClass) {
            return List.of();
        }

        @Override
        public MeasureResult measure(MeasureForm f) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }

        @Override
        public FloorInsp confirmNg(ConfirmForm f) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    };

    /** 열 수 있는 유형(읽기). 단말 공장 밖·없는 지시 공정 = 빈 목록 */
    List<DueInsp> due(DueQuery q);

    /** 검사 열기 — 같은 유형·LOT·공정의 열린 검사가 있으면 그것, 없으면 새로(오늘 유효한 확정 계획 없음 = 400 QMS_INSP_NO_PLAN) */
    InspRef open(OpenForm f);

    /** 검사 화면(읽기). 없는·다른 회사·단말 공장 밖·단말 유형 밖 검사 = 404 NOT_FOUND */
    FloorInsp detail(long inspId, long terminalId);

    /** 계측기 선택지(읽기) — 사용 중, 그 클래스, 교정 상태 */
    List<Gauge> gauges(String gaugeClass);

    /** 측정 입력 → 미리보기, 전부 OK 면 자동 확정. 확정·무효 뒤 = 409 QMS_INSP_STATE */
    MeasureResult measure(MeasureForm f);

    /** 불합격 확정 — 미리보기 NG·모든 행 판정일 때만(아니면 409 QMS_INSP_STATE / 400 QMS_INSP_INCOMPLETE) */
    FloorInsp confirmNg(ConfirmForm f);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static FloorInspPort of(ObjectProvider<FloorInspPort> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
