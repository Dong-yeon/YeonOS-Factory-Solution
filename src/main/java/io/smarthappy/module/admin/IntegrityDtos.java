package io.smarthappy.module.admin;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 기록 무결성 점검(sys-integrity, docs/52 Y1) 응답 */
public final class IntegrityDtos {

    private IntegrityDtos() {
    }

    /** 상태 — 나쁜 쪽이 이긴다 */
    public enum Status {
        OK, WARN, FAIL;

        Status worst(Status o) {
            return o.ordinal() > ordinal() ? o : this;
        }
    }

    /**
     * 점검 결과 한 줄. 설명은 코드 + 값(화면·엑셀이 문구 키 js.sysIntegrity.item.{code} 로 그린다 — 언어를 바꿔도 이력이 읽힌다)
     *
     * @param subject 표 이름·날짜·서명 대상 등(서버가 카탈로그·원장에서 읽은 값)
     */
    public record Item(String subject, Status status, String code, List<String> args) {
    }

    /**
     * @param key      DIGEST·LEDGER·ESIGN·AUDIT
     * @param checked  본 개수(다이제스트 날 수·원장 표 수·서명 수·회사별 표 수)
     * @param problems 문제(WARN·FAIL) 줄 수
     * @param items    문제를 앞에, 최대 {@link IntegrityCheckService#MAX_ITEMS}줄
     * @param more     줄 상한에 걸려 못 실은 줄 수
     */
    public record Check(String key, Status status, int checked, int problems, List<Item> items, int more) {
    }

    public record Run(long runId, OffsetDateTime runAt, String runBy, Status status, List<Check> checks) {
    }

    /** 이력 목록 한 줄(결과 본문 없이) */
    public record RunRow(long runId, OffsetDateTime runAt, String runBy, Status status, int problems) {
    }

    /** 엑셀 한 줄(ExcelExportService 가 getter 로 읽는다) */
    @Getter
    @AllArgsConstructor
    public static final class ExcelRow {
        private final String checkNm;
        private final String subject;
        private final String status;
        private final String detail;
    }
}
