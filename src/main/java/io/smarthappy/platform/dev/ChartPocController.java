package io.smarthappy.platform.dev;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.auth.PageController.PageProg;

/**
 * hb.chart 확인 화면 (docs/61 11장) — **local 프로필 전용**, sys_prog·메뉴에 없음. 가짜 데이터로 4종(파레토·막대·게이지·관리도)을 그린다.
 * 현행 CSP 를 그대로 받는다(화면에 CSP 위반 수 표시). Highcharts 가 없으면(scripts/fetch-highcharts.mjs 전) 안내 + 표만 나온다.
 * API 가 없다 — 데이터는 화면 JS 안의 고정 값.
 */
@Controller
@Profile("local")
public class ChartPocController {

    @GetMapping("/dev/poc/chart")
    public String page(Model model) {
        model.addAttribute("prog", new PageProg("dev-chart-poc", "hb.chart 확인"));
        return "dev/chart-poc";
    }
}
