package io.smarthappy.module.master.item;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.master.item.ItemDtos.DeleteRequest;
import io.smarthappy.module.master.item.ItemDtos.ExportRequest;
import io.smarthappy.module.master.item.ItemDtos.ItemForm;
import io.smarthappy.module.master.item.ItemDtos.ItemRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 품목관리 API — 거래처관리(01 5.7 규약)와 같은 모양 */
@RestController
@RequiredArgsConstructor
public class ItemApiController {

    private static final String BASE = "/api/master/items";

    private final ItemService service;
    private final MessageSource messages;

    @GetMapping(BASE)
    @ProgAuth(prog = ItemService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String itemNm, @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String procType, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개 버리므로 원래 값 그대로 읽는다
        String[] sort = request.getParameterValues("sort");
        ItemService.Page p = service.list(itemCd, itemNm, itemType, procType, useYn, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/columns")
    @ProgAuth(prog = ItemService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = service.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(BASE + "/{itemId}")
    @ProgAuth(prog = ItemService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemRow> detail(@PathVariable long itemId) {
        return ApiResponse.ok(service.detail(itemId));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = ItemService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody ItemForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @PutMapping(BASE + "/{itemId}")
    @ProgAuth(prog = ItemService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long itemId, @RequestBody ItemForm form) {
        service.update(itemId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(BASE + "/delete")
    @ProgAuth(prog = ItemService.PROG, action = Action.DELETE)
    public ApiResponse<Integer> delete(@RequestBody DeleteRequest req) {
        return ApiResponse.ok(service.disable(req == null ? null : req.items()));
    }

    @PostMapping(BASE + "/export")
    @ProgAuth(prog = ItemService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportRequest req, HttpServletResponse response)
            throws IOException {
        service.export(req, response);
    }
}
