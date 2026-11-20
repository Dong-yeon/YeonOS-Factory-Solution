package io.smarthappy.module.master.classprop;

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

import io.smarthappy.module.master.classprop.ClassPropDtos.AttrsView;
import io.smarthappy.module.master.classprop.ClassPropDtos.ClassForm;
import io.smarthappy.module.master.classprop.ClassPropDtos.ClassRow;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropForm;
import io.smarthappy.module.master.classprop.ClassPropDtos.PropRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 클래스 속성 API(mst-class-prop) + 품목·설비 화면의 클래스·속성 값 API(각 화면 권한으로) — P1 7b. 삭제 없음 = 사용 중지
 */
@RestController
@RequiredArgsConstructor
public class ClassPropApiController {

    private static final String BASE = "/api/master/class-props";

    private final ClassPropService service;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 정의 화면

    @GetMapping(BASE + "/classes")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ClassRow>> classes(@RequestParam String classType) {
        return ApiResponse.ok(service.classes(classType, null));
    }

    /** 클래스 그리드(UI 단계 3 ①) — 기존 목록 API 는 그대로(품목·설비 화면도 씀), 같은 조건 + 페이지·정렬·건수 */
    @GetMapping(BASE + "/classes/grid")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> classGrid(@RequestParam(required = false) String classType,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        ClassPropService.ClassPage p = service.classGrid(classType, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(BASE + "/classes/columns")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> classColumns(Locale locale) {
        List<Map<String, Object>> cols = service.classColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(BASE + "/classes/export")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.EXCEL)
    public void classExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, ClassPropService.PROG, service.classColumns(), q -> {
            var p = service.classGrid(q.str("classType"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @PostMapping(BASE + "/item-classes")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createItemClass(@RequestBody ClassForm form) {
        return ApiResponse.ok(service.createItemClass(form));
    }

    @PutMapping(BASE + "/item-classes/{classId}")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateItemClass(@PathVariable long classId, @RequestBody ClassForm form) {
        service.updateItemClass(classId, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping(BASE)
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PropRow>> props(@RequestParam String classType, @RequestParam long classId) {
        return ApiResponse.ok(service.props(classType, classId, false));
    }

    @PostMapping(BASE)
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createProp(@RequestBody PropForm form) {
        return ApiResponse.ok(service.createProp(form));
    }

    @PutMapping(BASE + "/{propId}")
    @ProgAuth(prog = ClassPropService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateProp(@PathVariable long propId, @RequestBody PropForm form) {
        service.updateProp(propId, form);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 품목 화면 (품목관리 권한)

    @GetMapping("/api/master/items/classes")
    @ProgAuth(prog = "mst-item", action = Action.SEARCH)
    public ApiResponse<List<ClassRow>> itemClasses() {
        return ApiResponse.ok(service.classes("ITEM", "Y"));
    }

    /** 클래스를 고를 때 그 클래스의 사용 중 속성 */
    @GetMapping("/api/master/items/class-props")
    @ProgAuth(prog = "mst-item", action = Action.SEARCH)
    public ApiResponse<List<PropRow>> itemClassProps(@RequestParam long classId) {
        return ApiResponse.ok(service.props("ITEM", classId, true));
    }

    @GetMapping("/api/master/items/{itemId}/attrs")
    @ProgAuth(prog = "mst-item", action = Action.SEARCH)
    public ApiResponse<AttrsView> itemAttrs(@PathVariable long itemId) {
        return ApiResponse.ok(service.attrs("ITEM", itemId));
    }

    @PutMapping("/api/master/items/{itemId}/attrs")
    @ProgAuth(prog = "mst-item", action = Action.SAVE)
    public ApiResponse<String> saveItemAttrs(@PathVariable long itemId, @RequestBody String body) {
        service.saveAttrs("ITEM", itemId, ClassPropService.parseForm(body));
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 설비 화면 (설비 관리 권한)

    /** 설비 화면에서 클래스를 바꿀 때 새 클래스의 속성 칸 */
    @GetMapping("/api/master/equips/class-props")
    @ProgAuth(prog = "mst-equip", action = Action.SEARCH)
    public ApiResponse<List<PropRow>> equipClassProps(@RequestParam long classId) {
        return ApiResponse.ok(service.props("EQUIP", classId, true));
    }

    @GetMapping("/api/master/equips/{equipId}/attrs")
    @ProgAuth(prog = "mst-equip", action = Action.SEARCH)
    public ApiResponse<AttrsView> equipAttrs(@PathVariable long equipId) {
        return ApiResponse.ok(service.attrs("EQUIP", equipId));
    }

    @PutMapping("/api/master/equips/{equipId}/attrs")
    @ProgAuth(prog = "mst-equip", action = Action.SAVE)
    public ApiResponse<String> saveEquipAttrs(@PathVariable long equipId, @RequestBody String body) {
        service.saveAttrs("EQUIP", equipId, ClassPropService.parseForm(body));
        return ApiResponse.ok("OK");
    }
}
