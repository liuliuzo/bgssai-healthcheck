package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.domain.HealthDashboard;
import com.bgssai.healthcheck.service.HealthCheckService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Thymeleaf 看板。
 *
 * <p>页面骨架和数据区共用同一段模板：{@code GET /} 渲染整页，
 * {@code GET /fragments/dashboard} 只渲染 {@code dashboard} 片段，
 * 前端定时拉取该片段替换 DOM。这样服务端渲染是唯一的一份视图逻辑，
 * 不需要在 JavaScript 里再写一遍。</p>
 */
@Controller
public class DashboardController {

    private final HealthCheckService healthCheckService;

    public DashboardController(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    @ModelAttribute("fmt")
    ViewFormatter formatter(ViewFormatter formatter) {
        return formatter;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("dashboard", dashboardData());
        return "index";
    }

    /** 供前端定时刷新的数据区片段。 */
    @GetMapping("/fragments/dashboard")
    public String dashboardFragment(Model model) {
        model.addAttribute("dashboard", dashboardData());
        return "index :: dashboard";
    }

    /** 页面上的「立刻巡检」按钮：触发一轮巡检后直接返回新片段。 */
    @PostMapping("/fragments/dashboard/refresh")
    public String refreshFragment(Model model) {
        this.healthCheckService.refreshAll();
        model.addAttribute("dashboard", dashboardData());
        return "index :: dashboard";
    }

    private HealthDashboard dashboardData() {
        return this.healthCheckService.dashboard();
    }
}
