package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.domain.HealthDashboard;
import com.bgssai.healthcheck.service.HealthCheckService;
import com.bgssai.healthcheck.service.UnknownApplicationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Thymeleaf 看板。
 *
 * <p>页面骨架和数据区共用同一段模板：{@code GET /} 渲染整页，
 * {@code GET /fragments/dashboard} 只渲染 {@code dashboard} 片段，
 * 前端定时拉取该片段替换 DOM。这样服务端渲染是唯一的一份视图逻辑，
 * 不需要在 JavaScript 里再写一遍。</p>
 *
 * <p>详情弹窗同理：{@code GET /fragments/apps/{id}/detail} 返回一整块渲染好的明细，
 * 前端只负责把它塞进 dialog。原始应答里可能有任何字符，交给 Thymeleaf 转义比在
 * JavaScript 里手工拼 DOM 安全得多。</p>
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

    /**
     * 单个目标的完整明细，含最近一次原始应答。
     *
     * <p>id 不存在时不抛出去：这个片段是弹窗内容，返回 500 会让前端只能显示一句
     * 「加载失败」；渲染一行「目标不存在」更有信息量，也说明请求确实到达了后端。</p>
     */
    @GetMapping("/fragments/apps/{id}/detail")
    public String detailFragment(@PathVariable String id, Model model) {
        try {
            model.addAttribute("app", this.healthCheckService.findById(id));
        }
        catch (UnknownApplicationException ex) {
            model.addAttribute("error", "目标 " + id + " 不存在，可能是配置已经改过、页面还没刷新。");
        }
        return "detail :: detail";
    }

    private HealthDashboard dashboardData() {
        return this.healthCheckService.dashboard();
    }
}
