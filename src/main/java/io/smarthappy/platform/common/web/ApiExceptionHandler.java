package io.smarthappy.platform.common.web;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.metrics.HbMetrics;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantNotSetException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * API 공통 오류 응답 (HbErrorCode). SQL·스택트레이스·사용자 입력은 응답에 넣지 않는다.
 * 404·405 는 /api/** 만 JSON 으로 바꾸고, 화면·정적 경로는 Spring 기본 처리에 돌려준다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

    /** SQLState → 오류 코드 (RLS 위반은 별도) */
    private static final Map<String, HbErrorCode> SQL_STATE_CODES = Map.ofEntries(
            Map.entry(PgErrors.UNIQUE_VIOLATION, HbErrorCode.DUPLICATE),
            Map.entry(PgErrors.FOREIGN_KEY_VIOLATION, HbErrorCode.IN_USE),
            Map.entry(PgErrors.CHECK_VIOLATION, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.NOT_NULL_VIOLATION, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.STRING_TOO_LONG, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.NUMERIC_OUT_OF_RANGE, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.INVALID_TEXT_REPRESENTATION, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.INVALID_DATETIME, HbErrorCode.INVALID_VALUE),
            // NUL 문자 — 서비스 칸 검사가 놓친 곳의 안전망(500 이 아니라 400)
            Map.entry(PgErrors.CHARACTER_NOT_IN_REPERTOIRE, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.UNTRANSLATABLE_CHARACTER, HbErrorCode.INVALID_VALUE),
            Map.entry(PgErrors.DATETIME_OUT_OF_RANGE, HbErrorCode.INVALID_VALUE),
            // 상태 규칙 트리거(예: 확정 BOM 수정) · 배제 제약(예: 확정 BOM 기간 겹침) — 동시 작업 경쟁에서 서비스 검사를 지나친 경우
            Map.entry(PgErrors.OBJECT_NOT_IN_STATE, HbErrorCode.CONFLICT),
            Map.entry(PgErrors.EXCLUSION_VIOLATION, HbErrorCode.CONFLICT));

    private final ErrorMessages errors;
    private final AccessLogService accessLog;
    private final HbMetrics metrics;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException e) {
        return errors.response(e);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException e, HttpServletRequest request) {
        if (PgErrors.isRlsViolation(e)) {
            // Spring 은 42501 을 BadSqlGrammarException 으로 분류하지만, RLS 위반은 문법 오류가 아니라 권한 위반이다
            log.warn("RLS 위반 차단 compCd={} {}", TenantContext.currentCompCd().orElse("-"), PgErrors.summary(e));
            accessLog.recordCurrent(AccessEvent.TENANT_FORBIDDEN, null, null, AccessLogService.Result.DENIED,
                    request.getMethod() + " " + request.getRequestURI());
            metrics.tenantForbidden();   // 운영 지표 hb_tenant_forbidden_total(docs/52 Y2) — 1건이라도 경보
            return errors.response(HbErrorCode.TENANT_FORBIDDEN);
        }
        HbErrorCode code = PgErrors.sqlState(e).map(SQL_STATE_CODES::get).orElse(null);
        if (code != null) {
            log.info("DB 제약 위반 {} → {}", PgErrors.summary(e), code);
            return errors.response(code);
        }
        log.error("DB 처리 오류 {}", PgErrors.summary(e), e);
        return errors.response(HbErrorCode.DB_ERROR);
    }

    /** 서버 업로드 한도(spring.servlet.multipart) 초과·깨진 multipart → 첨부 거부 */
    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public ResponseEntity<ApiError> handleMultipart(Exception e) {
        log.info("업로드 거부 {}", e.getClass().getSimpleName());
        return errors.response(HbErrorCode.FILE_REJECTED);
    }

    @ExceptionHandler(TenantNotSetException.class)
    public ResponseEntity<ApiError> handleTenantNotSet(TenantNotSetException e) {
        return errors.response(HbErrorCode.TENANT_REQUIRED);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception e) {
        log.debug("잘못된 요청 {}", e.getClass().getSimpleName());
        return errors.response(HbErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> handleValidation(Exception e) {
        return errors.response(HbErrorCode.INVALID_VALUE);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException e, HttpServletRequest request)
            throws NoResourceFoundException {
        if (!isApi(request)) {
            throw e;
        }
        return errors.response(HbErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        if (!isApi(request)) {
            throw e;
        }
        return errors.response(HbErrorCode.METHOD_NOT_ALLOWED);
    }

    private static boolean isApi(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
    }
}
