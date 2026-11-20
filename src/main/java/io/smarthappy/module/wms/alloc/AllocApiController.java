package io.smarthappy.module.wms.alloc;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.wms.alloc.AllocDtos.Preview;
import io.smarthappy.module.wms.alloc.AllocDtos.PreviewForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 출고 후보 미리보기 — 현재고 화면(wms-stock)의 조회 기능. 아무것도 저장·예약하지 않는다(W4) */
@RestController
@RequiredArgsConstructor
public class AllocApiController {

    private final AllocService service;

    @PostMapping("/api/wms/stock/alloc-preview")
    @ProgAuth(prog = "wms-stock", action = Action.SEARCH)
    public ApiResponse<Preview> preview(@RequestBody(required = false) PreviewForm form) {
        return ApiResponse.ok(service.preview(form));
    }
}
