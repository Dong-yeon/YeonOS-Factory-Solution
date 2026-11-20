package io.smarthappy.platform.common.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** API 성공 응답: {"result":"OK","data":…} / 목록 {"result":"OK","list":[…],"total":n} (01 설계 5.7) */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String result, T data, List<?> list, Long total) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", data, null, null);
    }

    public static ApiResponse<Void> list(List<?> list, long total) {
        return new ApiResponse<>("OK", null, list, total);
    }
}
