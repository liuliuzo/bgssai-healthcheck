package com.bgssai.healthcheck.web;

import com.bgssai.healthcheck.service.HealthCheckService;
import com.bgssai.healthcheck.service.HealthReportService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可下载的 Markdown 巡检报告。
 *
 * <pre>
 * GET /api/report.md                      下载完整报告（含原始应答）
 * GET /api/report.md?download=false       在浏览器里直接打开
 * GET /api/report.md?raw=false            不含原始应答的精简版
 * GET /api/report.md?refresh=true         先跑一轮巡检再生成
 * </pre>
 *
 * <p>单独一个 Controller 而不是并进 {@code HealthApiController}：它返回的不是 JSON，
 * 而是一份带下载头的文本，和那边的接口既不共用产出类型也不共用异常处理。</p>
 */
@RestController
public class HealthReportController {

    private final HealthReportService reportService;

    private final HealthCheckService healthCheckService;

    public HealthReportController(HealthReportService reportService, HealthCheckService healthCheckService) {
        this.reportService = reportService;
        this.healthCheckService = healthCheckService;
    }

    @GetMapping(path = "/api/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "true") boolean raw,
            @RequestParam(defaultValue = "true") boolean download) {
        // 默认不触发巡检：下载报告是个随手动作，不该每点一次就对全部目标压一轮探测
        if (refresh) {
            this.healthCheckService.refreshAll();
        }
        String fileName = this.reportService.fileName();
        String disposition = (download ? "attachment" : "inline") + "; filename=\"" + fileName
                + "\"; filename*=UTF-8''" + fileName;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .cacheControl(CacheControl.noStore())
                .body(this.reportService.render(raw));
    }
}
