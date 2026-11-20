package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CmsCardDtos.CardSummary;
import io.smarthappy.module.cms.CmsCardDtos.EquipOption;
import io.smarthappy.module.cms.CmsCardDtos.HistExport;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS C17 API — 설비 이력 카드 (cms-equip-card, docs/33 13.5). 조회·엑셀만 */
@RestController
@RequiredArgsConstructor
public class CmsCardApiController {

    private static final String BASE = "/api/cms/equip-card";

    private final EquipCardService cards;
    private final MessageSource messages;

    @GetMapping(BASE + "/equips")
    @ProgAuth(prog = EquipCardService.PROG, action = Action.SEARCH)
    public ApiResponse<List<EquipOption>> equips(@RequestParam(required = false) String keyword, @RequestParam(required = false) String plantCd) {
        return ApiResponse.ok(cards.equips(keyword, plantCd));
    }

    @GetMapping(BASE + "/summary")
    @ProgAuth(prog = EquipCardService.PROG, action = Action.SEARCH)
    public ApiResponse<CardSummary> summary(@RequestParam(required = false) String equipCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(cards.summary(equipCd, from, to));
    }

    /** 이력 한 표 grid — page·size·sort(허용 목록)·total */
    @GetMapping(BASE + "/history/grid")
    @ProgAuth(prog = EquipCardService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> historyGrid(@RequestParam(required = false) String equipCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = cards.grid(equipCd, from, to, kind, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/history/columns")
    @ProgAuth(prog = EquipCardService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> historyColumns(Locale locale) {
        List<Map<String, Object>> cols = cards.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @PostMapping(BASE + "/history/export")
    @ProgAuth(prog = EquipCardService.PROG, action = Action.EXCEL)
    public void historyExport(@RequestBody(required = false) HistExport req, HttpServletResponse response) throws IOException {
        cards.export(req, response);
    }
}
