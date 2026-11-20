package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CmsAlarmDtos.AlarmRow;
import io.smarthappy.module.cms.CmsAlarmDtos.DefForm;
import io.smarthappy.module.cms.CmsAlarmDtos.DefRow;
import io.smarthappy.module.cms.CmsAlarmDtos.TopRow;
import io.smarthappy.module.cms.CmsDtos.IdOption;
import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 2부 C8 API — 알람 이력(cms-alarm)·알람 정의(cms-alarm-def) (docs/33 11.6). 원장 쓰기 API 는 없다(수집만) */
@RestController
@RequiredArgsConstructor
public class CmsAlarmApiController {

    private static final String ALARM = "/api/cms/alarms";
    private static final String DEF = "/api/cms/alarm-defs";

    private final AlarmService alarms;
    private final StateTimelineService timeline;
    private final CmsMapper cmsMapper;
    private final CmsAlarmMapper alarmMapper;
    private final MessageSource messages;

    @GetMapping(ALARM + "/plants")
    @ProgAuth(prog = AlarmService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(timeline.plants());
    }

    @GetMapping(ALARM)
    @ProgAuth(prog = AlarmService.PROG, action = Action.SEARCH)
    public ApiResponse<List<AlarmRow>> list(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String openOnly) {
        return ApiResponse.ok(alarms.alarms(plantCd, from, to, keyword, "Y".equals(openOnly)));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(ALARM + "/grid")
    @ProgAuth(prog = AlarmService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> alarmGrid(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String openOnly,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = alarms.alarmGrid(plantCd, from, to, keyword, "Y".equals(openOnly), page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(ALARM + "/columns")
    @ProgAuth(prog = AlarmService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> alarmColumns(Locale locale) {
        List<Map<String, Object>> cols = alarms.alarmGridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 이력 엑셀(C21) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(ALARM + "/export")
    @ProgAuth(prog = AlarmService.PROG, action = Action.EXCEL)
    public void alarmExport(@RequestBody(required = false) CmsAlarmDtos.AlarmExport req, HttpServletResponse response) throws IOException {
        alarms.alarmExport(req, response);
    }

    @GetMapping(ALARM + "/top")
    @ProgAuth(prog = AlarmService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TopRow>> top(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.ok(alarms.top(plantCd, from, to));
    }

    @GetMapping(DEF)
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SEARCH)
    public ApiResponse<List<DefRow>> defs(@RequestParam(required = false) String keyword, @RequestParam(required = false) String classCd,
            @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(alarms.defs(keyword, classCd, useYn));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(DEF + "/grid")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SEARCH)
    public ApiResponse<Void> defGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String classCd,
            @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = alarms.defGrid(keyword, classCd, useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(DEF + "/columns")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SEARCH)
    public ApiResponse<Void> defColumns(Locale locale) {
        List<Map<String, Object>> cols = alarms.defGridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 정의 엑셀(C23) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(DEF + "/export")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.EXCEL)
    public void defExport(@RequestBody(required = false) CmsAlarmDtos.DefExport req, HttpServletResponse response) throws IOException {
        alarms.defExport(req, response);
    }

    @GetMapping(DEF + "/classes")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<IdOption>> classes() {
        return ApiResponse.ok(cmsMapper.findEquipClasses());
    }

    /** 현상 코드 후보(사용 중) — 자동 고장 신고의 현상 */
    @GetMapping(DEF + "/symptoms")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<IdOption>> symptoms() {
        return ApiResponse.ok(alarmMapper.symptoms());
    }

    @GetMapping(DEF + "/{defId}")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SEARCH)
    public ApiResponse<DefRow> def(@PathVariable long defId) {
        return ApiResponse.ok(alarms.def(defId));
    }

    @PostMapping(DEF)
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SAVE)
    public ApiResponse<Long> createDef(@RequestBody DefForm form) {
        return ApiResponse.ok(alarms.createDef(form));
    }

    @PutMapping(DEF + "/{defId}")
    @ProgAuth(prog = AlarmService.PROG_DEF, action = Action.SAVE)
    public ApiResponse<String> updateDef(@PathVariable long defId, @RequestBody DefForm form) {
        alarms.updateDef(defId, form);
        return ApiResponse.ok("OK");
    }
}
