# bgssai-healthcheck

> **已迁入官网（2026-09-22）**：巡检引擎、探针、告警与全部单元测试已搬到 [bgssai-website](https://github.com/liuliuzo/bgssai-website) 的 `bgssai-website-user` 模块 `com.bgssai.website.status` 包；公开状态页 `https://www.bgssai.com/status/`，内部看板 `/status/internal`（按 IP 放行）。本仓不再单独部署、不再演进，仅作历史留档。

## 产品线规划（全线统一）

产品线职责与名称以[骨架仓产品线愿景](https://github.com/liuliuzo/bgssai-skeleton/blob/develop/docs/PRODUCT-LINE-VISION.md)为唯一权威；验收范围见[产品线验收基线](https://github.com/liuliuzo/bgssai-skeleton/blob/develop/docs/feature/product-line-acceptance.md)。本仓现行需求、原型、设计和实现保留在本仓，不再复制整段全线规划。

跨项目版本、未合并 PR 与工作副本状态统一查阅[项目管理仓](https://github.com/liuliuzo/bgssai-project-management)。规划、代码实现、PR 合并、部署和运行验收分别记录；历史报告保留原日期与源码基线，不能作为当前全部通过的证明。

本地参考代码位于工作区 `reference-github/`，参考原型位于 `reference-prototypes/`；参考版本只作对照，不覆盖产品代码。dev / prod 均部署 `develop`，分别使用 `application-dev.properties` / `application-prod.properties`。


**本仓位置**：巡检仓，非业务工具。

## 技术栈与运行

- 运行时：`Java 21` + `Spring Boot 4.1.0` + `Thymeleaf`（单实例服务端渲染，默认端口 `8080`）
- 支持目标类型：`http`、`elasticsearch`、`redis`、`mysql`、`tcp`、`boxpool`
- 判定边界：`/bgssai/health/readiness` 为 `UP` 仅代表进程存活且 `db` / `mybatis` 可达，不代表登录、短信、模型、支付等业务链路可用

```bash
# 编译
./mvnw -B -DskipTests compile

# 打包与启动
./mvnw -B -DskipTests clean package
java -jar target/bgssai-healthcheck-0.0.1-SNAPSHOT.jar

# 开发启动
./mvnw spring-boot:run
```

## 页面与接口契约

| 方法 | 路径 | 产出类型 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/` | `text/html` | 健康状态看板 |
| `GET` | `/fragments/dashboard` | `text/html` | 看板数据区 HTML 片段 |
| `POST` | `/fragments/dashboard/refresh` | `text/html` | 全量巡检并返回看板数据区片段 |
| `GET` | `/fragments/apps/{id}/detail` | `text/html` | 单目标详情弹窗 HTML 片段 |
| `GET` | `/api/apps` | `application/json` | 全部目标状态列表 |
| `GET` | `/api/apps/{id}` | `application/json` | 单目标状态详情（未知 `id` 返回 `404` + `application/problem+json`） |
| `GET` | `/api/summary` | `application/json` | 状态汇总统计 |
| `GET` | `/api/dashboard` | `application/json` | 汇总与按分组归拢结果 |
| `POST` | `/api/refresh` | `application/json` | 立即执行全量巡检 |
| `POST` | `/api/apps/{id}/refresh` | `application/json` | 立即巡检单个目标 |
| `GET` | `/api/alerts` | `application/json` | 告警配置、已启用通道与进行中的故障列表 |
| `GET` | `/api/report.md` | `text/markdown;charset=UTF-8` | Markdown 巡检报告 |
| `GET` | `/actuator/health` | `application/json` | 平台自身健康端点（含 `monitoredApplications`） |

### `/api/report.md` 查询参数

| 参数 | 默认值 | 取值行为 |
| --- | --- | --- |
| `download` | `true` | `true` 为 `attachment` 下载；`false` 为 `inline` 预览 |
| `raw` | `true` | `true` 附带最近原始请求与应答；`false` 省略原始正文 |
| `refresh` | `false` | `true` 先执行一轮全量巡检再渲染报告 |

报告章节固定为六节：报告元信息、状态汇总、需要处理的目标、全部目标一览、逐目标明细、当前巡检配置。凭据在探针记录前已脱敏，口令与 Webhook 地址不写入报告。

## 配置规范

全部配置位于 `src/main/resources/application*.properties` 的 `bgssai.healthcheck` 前缀下，遵循三条硬性约束：

1. 数组与集合一律使用下标 `[n]` 逐项展开（Standards §4），禁止单行逗号分隔。
2. 配置值一律写最终字面量，禁止 `${...}` 占位符（Standards §1）。
3. `.properties` 中非 ASCII 配置值必须写为 `\uXXXX` Unicode 转义。

### 环境配置文件

| 文件 | 生效条件 | 巡检范围 |
| --- | --- | --- |
| `src/main/resources/application.properties` | 默认（未指定 profile） | 全局标量参数 + 生产巡检目标基线 |
| `src/main/resources/application-prod.properties` | `SPRING_PROFILES_ACTIVE=prod` | 生产巡检目标（与主配置目标逐条一致） |
| `src/main/resources/application-dev.properties` | `SPRING_PROFILES_ACTIVE=dev` | 开发巡检目标 |
| `src/main/resources/application-local.properties` | `SPRING_PROFILES_ACTIVE=local` | 本机启动（目标与 `dev` 一致） |
| `src/main/resources/application-test.properties` | `SPRING_PROFILES_ACTIVE=test` | 测试档配置 |

### 全局参数

```properties
bgssai.healthcheck.scheduled=true
bgssai.healthcheck.refresh-interval=30s
bgssai.healthcheck.initial-delay=3s
bgssai.healthcheck.concurrency=16
bgssai.healthcheck.history-size=60
bgssai.healthcheck.ui-refresh-seconds=10

bgssai.healthcheck.probe.connect-timeout=3s
bgssai.healthcheck.probe.read-timeout=5s
bgssai.healthcheck.probe.follow-redirects=false
bgssai.healthcheck.probe.max-body-bytes=65536
bgssai.healthcheck.probe.skip-tls-verification=false

bgssai.healthcheck.detail.enabled=true
bgssai.healthcheck.detail.max-body-chars=16384
bgssai.healthcheck.detail.keep-last-failure=true

bgssai.healthcheck.redis.memory-warn-percent=90
bgssai.healthcheck.mysql.connection-warn-percent=90
bgssai.healthcheck.mysql.query-timeout-seconds=3
```

### 单目标配置字段（`bgssai.healthcheck.applications[n]`）

| 字段 | 默认值 | 约束与规则 |
| --- | --- | --- |
| `id` | 由 `name` 推导 | 路径唯一标识；纯非 ASCII 名称回退为 `app-N` |
| `name` | 必填 | 目标显示名称 |
| `group` | `未分组` | 看板分组名称 |
| `url` | 必填 | 目标地址，scheme 须与 `type` 匹配 |
| `type` | 由 scheme 推导 | `http` / `elasticsearch` / `redis` / `mysql` / `tcp` / `boxpool`；`elasticsearch` 与 `boxpool` 须显式指定 |
| `method` | `GET` | 仅支持 `GET` / `HEAD`（HTTP 系目标生效） |
| `enabled` | `true` | `false` 时保留配置、不执行探测 |
| `critical` | `false` | `true` 目标处于 `DOWN` / `DEGRADED` 时使平台 `/actuator/health` 报 `DOWN`；下游业务应用固定 `false` |
| `tags` | 空 | 标签列表，参与客户端搜索 |
| `headers` | 空 | 附加 HTTP 请求头 |
| `username` / `password` | 空 | HTTP Basic 认证、Redis `AUTH` 参数或 MySQL JDBC 凭据 |
| `connect-timeout` / `read-timeout` | 继承 `probe` | 单目标超时覆盖 |
| `expected-statuses` | 空（任意 `2xx`） | HTTP 成功状态码集合 |
| `expected-databases` | 空 | 仅 `mysql` 生效，核对 `information_schema.SCHEMATA` |
| `description` | 空 | 目标备注 |
| `skip-tls-verification` | 继承 `probe` | 单目标跳过 TLS 证书链与主机名校验 |

### `scheme` 与探针映射

| `scheme` | `type` | 默认端口 | 探测协议与动作 |
| --- | --- | --- | --- |
| `http` / `https` | `http` | `80` / `443` | 请求健康接口，解析响应体状态字段或校验 HTTP 状态码 |
| `http` / `https` | `elasticsearch` | `9200` | 请求 `/_cluster/health`，按集群 `status` 与未分配分片判定 |
| `http` / `https` | `boxpool` | `80` / `443` | 请求 `/v1/capacity`，校验进程状态与 `headroom` 余量 |
| `redis` / `rediss` | `redis` | `6379` | RESP 协议发送 `AUTH` / `SELECT` / `PING` / `INFO` |
| `mysql` | `mysql` | `3306` | JDBC 执行 `SELECT 1`、读取连接水位并核对 `expected-databases` |
| `tcp` | `tcp` | 必填 | 建立 TCP 连接并读取首包横幅 |

配置在启动期由 `ApplicationRegistry` 校验；非法 URI、协议不匹配、`tcp` 缺端口或非 `mysql` 配置 `expected-databases` 直接终止启动。

## 状态枚举与判定规则

| 状态 | 标签 | 判定条件 |
| --- | --- | --- |
| `UP` | 正常 | 目标可达且满足正常判据 |
| `DEGRADED` | 降级 | 目标可达但自报 `OUT_OF_SERVICE` / `WARN` / `DEGRADED` / `yellow`，或自报 `UP` 但 HTTP 状态码不匹配，或中间件/容量触达降级阈值 |
| `DOWN` | 异常 | 连接失败、超时、认证失败、HTTP 状态码不匹配，或自报 `DOWN` / `red` |
| `UNKNOWN` | 未知 | 尚未巡检、目标已停用，或状态词无法识别 |

### 各类型细则

- **HTTP**：优先读取响应体 `status` / `state` / `health`（顶层不存在时下沉读取 `result` 或 `data` 封装层），解析子组件 `components` / `details`（兼容对象映射与数组列表）；无状态字段时按 `expected-statuses`（默认 `2xx`）判定。
- **Elasticsearch**：`green` -> `UP`；`yellow` 或 `unassigned_shards > 0` -> `DEGRADED`；`red` 或不可达 -> `DOWN`。
- **云电脑宿主（`boxpool`）**：`headroom > 0` 且接口正常 -> `UP`；接口可达但 `headroom <= 0` -> `DEGRADED`；不可达 -> `DOWN`。
- **Redis**：`PING` 返回 `+PONG` 且指标正常 -> `UP`；`used_memory / maxmemory >= memory-warn-percent`、从节点 `master_link_status != up`、RDB/AOF 最近写入失败或 `INFO` 被拒 -> `DEGRADED`；连接/认证/`PING` 失败 -> `DOWN`。
- **MySQL**：`SELECT 1` 成功且水位正常 -> `UP`；连接数占比 `>= connection-warn-percent`、`@@read_only = 1` 或缺失 `expected-databases` -> `DEGRADED`（辅助查询无权限仅将对应子组件记为 `UNKNOWN`）；连接/认证/`SELECT 1` 失败 -> `DOWN`。
- **TCP**：端口连通 -> `UP`；连接失败或超时 -> `DOWN`。

## 告警规则

告警基于 `HealthStatusStore` 记录的状态转移在独立异步队列（容量 `256`）投递：

| 触发条件 | 事件类型 | 行为 |
| --- | --- | --- |
| 连续异常次数首次达到 `failure-threshold` | `FIRING` | 发送首次故障通知 |
| 故障持续期间状态在 `DEGRADED` 与 `DOWN` 间切换 | `FIRING` | 发送状态变更通知，故障起点 `since` 不重置 |
| 故障持续时间超过上次通知 `repeat-interval`（`> 0`） | `REMINDER` | 发送重复提醒 |
| 已触发过 `FIRING` 的目标恢复为 `UP`（且 `recovery-notice=true`） | `RECOVERED` | 发送恢复通知并附持续时长 |

未达到 `failure-threshold` 即恢复的抖动不发送通知；`include-unknown=false` 时 `UNKNOWN` 不计入异常亦不触发恢复。

### 告警配置与报文格式

```properties
bgssai.healthcheck.alert.enabled=true
bgssai.healthcheck.alert.failure-threshold=2
bgssai.healthcheck.alert.recovery-notice=true
bgssai.healthcheck.alert.repeat-interval=0s
bgssai.healthcheck.alert.include-unknown=false
bgssai.healthcheck.alert.only-critical=false

bgssai.healthcheck.alert.webhook.url=
bgssai.healthcheck.alert.webhook.format=generic
bgssai.healthcheck.alert.webhook.connect-timeout=3s
bgssai.healthcheck.alert.webhook.read-timeout=5s
```

| `webhook.format` | 请求体结构 | 响应校验 |
| --- | --- | --- |
| `generic` | 平台标准 `AlertEvent` JSON | HTTP `2xx` 视为成功 |
| `wecom` | `{"msgtype":"text","text":{"content":"..."}}` | HTTP `2xx` 且 `errcode == 0` |
| `dingtalk` | `{"msgtype":"text","text":{"content":"..."}}` | HTTP `2xx` 且 `errcode == 0` |
| `feishu` | `{"msg_type":"text","content":{"text":"..."}}` | HTTP `2xx` 且 `code == 0` / `StatusCode == 0` |

## 日志与部署

| Profile | `logging.config` | 输出目标 |
| --- | --- | --- |
| `dev` / `local` / `prod` | `log/logback-spring_file.xml` | `/opt/bgssai/log/bgssai-healthcheck_unstrct.log` + 控制台（按天及 `500MB` 滚动，保留 `30` 天，上限 `128GB`） |
| `test` | `log/logback-spring_stdout.xml` | 控制台 |

- 请求线程由 `RequestTraceFilter` 注入 MDC `traceId` 并回写响应头 `X-Trace-Id`；后台定时与并发探测线程 MDC 为空。
- 告警日志固定使用 logger `com.bgssai.healthcheck.ALERT` 单行输出（恢复为 `INFO`，其余为 `WARN`）。
- 部署通道：Jenkins 手动任务 `bgssai-healthcheck deploy(dev|prod)` / `stop(dev|prod)`，目标主机 `123.60.68.201`（详见 [`deploy/README.md`](deploy/README.md)）。日志采集与清理任务在 `bgssai-logs` 仓登记为 `healthcheck`。

## 目录结构

```text
src/main/java/com/bgssai/healthcheck/
  HealthCheckApplication.java
  config/HealthCheckProperties.java
  alert/
    AlertDispatcher.java
    AlertEvent.java
    AlertKind.java
    AlertNotifier.java
    AlertProperties.java
    AlertService.java
    AlertStatus.java
    LoggingAlertNotifier.java
    WebhookAlertNotifier.java
  domain/
    AppHealth.java
    HealthDashboard.java
    HealthSample.java
    HealthState.java
    HealthStats.java
    HealthSummary.java
    ProbeDetail.java
    ProbeResult.java
    TargetType.java
  filter/RequestTraceFilter.java
  service/
    ApplicationRegistry.java
    HealthCheckScheduler.java
    HealthCheckService.java
    HealthProbe.java
    HealthProbeDispatcher.java
    HealthReportService.java
    HealthStatusStore.java
    HttpHealthProbe.java
    MonitoredApplication.java
    MonitoredApplicationsHealthIndicator.java
    MysqlHealthProbe.java
    ProbeSecrets.java
    RedisHealthProbe.java
    TcpHealthProbe.java
    UnknownApplicationException.java
  web/
    ApiExceptionHandler.java
    DashboardController.java
    HealthApiController.java
    HealthReportController.java
    ViewFormatter.java
src/main/resources/
  application*.properties
  log/logback-spring_*.xml
  templates/{index.html, detail.html}
  static/{css/app.css, js/app.js, favicon.png, favicon.ico, apple-touch-icon.png, brand/bgss-mark-tile.png}
```
