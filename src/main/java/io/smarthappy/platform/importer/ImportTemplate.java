package io.smarthappy.platform.importer;

import java.util.List;
import java.util.Map;

import io.smarthappy.platform.common.web.FieldErrors;

/**
 * 엑셀 올리기 템플릿 (P1 2단계, docs/17 3b·P1-8). 업무 모듈이 빈으로 등록한다(예: 거래처·품목).
 * 열 정의·검증·반영이 한 곳 — 버전을 올리면 옛 템플릿 파일은 거부된다(파일의 숨김 시트 _meta 로 확인).
 */
public interface ImportTemplate {

    /** 템플릿 코드 (대문자, 예: MST_ITEM) */
    String code();

    /** 열 구성이 바뀌면 올린다 */
    int version();

    /** 권한·첨부 기준 화면 (조회 = 템플릿·미리보기, 저장 = 올리기·실행) */
    String progCd();

    /** 이름 문구 키 (보통 화면 이름) */
    String titleKey();

    /** 열 — 순서가 템플릿 파일의 열 순서. 첫 열이 업무 키 */
    List<ImportColumn> columns();

    /** 한 번의 검증 또는 실행 동안 쓰는 캐시(기존 행·허용 코드). 트랜잭션·회사 범위 안에서 부른다 */
    ImportSession open();

    /** 실행용 세션 — 반영한 행이 원본 작업(파일)을 가리켜야 하는 템플릿(내시 버전 등)이 덮어 쓴다 */
    default ImportSession openForExecute(long jobId) {
        return open();
    }

    /**
     * @param key        열 키(화면 필드명과 같게)
     * @param titleKey   헤더 문구 키
     * @param required   필수 열(헤더가 없으면 파일 거부)·필수 값
     * @param guideKey   안내 시트 설명 문구 키(없으면 null)
     * @param codeGrp    코드 열이면 공통코드 그룹(안내 시트에 허용값, 코드 또는 이름 둘 다 받음)
     * @param example    안내 시트 예시
     * @param textCell   템플릿에서 텍스트 서식 열(코드·번호)
     */
    record ImportColumn(String key, String titleKey, boolean required, String guideKey, String codeGrp,
            String example, boolean textCell) {

        public ImportColumn(String key, String titleKey, boolean required, String guideKey, String codeGrp, String example) {
            this(key, titleKey, required, guideKey, codeGrp, example, false);
        }

        /** 템플릿 열 서식을 텍스트(@)로 — 코드 열이 Excel 에서 숫자로 바뀌어 앞자리 0 을 잃지 않게 */
        public ImportColumn asText() {
            return new ImportColumn(key, titleKey, required, guideKey, codeGrp, example, true);
        }
    }

    /** 기존 행 — 비교 값은 검증 결과와 같은 정규화 문자열 */
    record Existing(long id, int rowVer, Map<String, String> values) {
    }

    /** 행 검증 결과: 업무 키(정규화) + 정규화 값(모든 열, 빈 칸 = null) + 오류 */
    record Validated(String key, Map<String, String> values, List<FieldErrors.FieldError> errors) {
    }

    /** 검증·실행 세션 */
    interface ImportSession {

        /** 업무 키(정규화)로 기존 행 */
        Existing find(String key);

        /** 행 검증 — 파일 내 중복·기존 행 여부는 엔진이 본다. cells 는 파일에 있는 열만(정리된 글자) */
        Validated validate(Map<String, String> cells);

        /** 새 행 (values = 모든 열) */
        long insert(Map<String, String> values, String by);

        /** 새 행 여러 개 — 템플릿이 JDBC 일괄 INSERT 로 바꿔 쓴다(5만 행 실측: 한 건씩 62초). 기본은 한 건씩 */
        default void insertAll(List<Map<String, String>> rows, String by) {
            rows.forEach(r -> insert(r, by));
        }

        /** 기존 행 갱신 (values = 기존 값 위에 파일 열을 덮은 것). 0 이면 그 사이 바뀜 */
        int update(long id, int rowVer, Map<String, String> values, String by);

        /** 대사용 본 테이블 건수(회사 범위) */
        long count();
    }
}
