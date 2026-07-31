package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.domain.AppHealth;
import com.bgssai.healthcheck.domain.HealthDashboard;
import com.bgssai.healthcheck.domain.HealthSummary;
import com.bgssai.healthcheck.service.HealthCheckService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 健康状态查询接口。
 *
 * <pre>
 * GET  /api/apps                 全部应用的健康状态
 * GET  /api/apps/{id}            单个应用的健康状态
 * GET  /api/summary              汇总统计
 * GET  /api/dashboard            汇总 + 按分组归拢，看板一次拉取用
 * POST /api/refresh              立刻巡检全部应用
 * POST /api/apps/{id}/refresh    立刻巡检单个应用
 * </pre>
 */
@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class HealthApiController {

    private final HealthCheckService healthCheckService;

    public HealthApiController(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    @GetMapping("/apps")
    public List<AppHealth> apps() {
        return this.healthCheckService.findAll();
    }

    @GetMapping("/apps/{id}")
    public AppHealth app(@PathVariable String id) {
        return this.healthCheckService.findById(id);
    }

    @GetMapping("/summary")
    public HealthSummary summary() {
        return this.healthCheckService.summary();
    }

    @GetMapping("/dashboard")
    public HealthDashboard dashboard() {
        return this.healthCheckService.dashboard();
    }

    @PostMapping("/refresh")
    public List<AppHealth> refreshAll() {
        return this.healthCheckService.refreshAll();
    }

    @PostMapping("/apps/{id}/refresh")
    public AppHealth refresh(@PathVariable String id) {
        return this.healthCheckService.refresh(id);
    }
}
