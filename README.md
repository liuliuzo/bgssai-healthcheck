# bgssai-healthcheck

通过接口巡检各应用健康状态，并用一个服务端渲染的看板集中展示。

- **Java 21** + **Spring Boot 4.1.0**
- 前端使用 Spring Boot 自带的 **Thymeleaf** 模板引擎，无需 Node 工具链，页面资源全部内置
- 巡检结果同时通过 **REST 接口** 和 **看板页面** 对外提供

## 快速开始

```bash
# 运行（默认端口 8080）
./mvnw spring-boot:run

# 或者先打包再运行
./mvnw clean package
java -jar target/bgssai-healthcheck-0.0.1-SNAPSHOT.jar
```

启动后访问：

| 地址 | 说明 |
| --- | --- |
| <http://localhost:8080/> | 健康状态看板 |
| <http://localhost:8080/actuator/health> | 平台自身的健康端点 |

## 配置被监控的应用

全部配置项都在 `application.yml` 的 `bgssai.healthcheck` 下。

```yaml
bgssai:
  healthcheck:
    scheduled: true          # 是否启用后台定时巡检
    refresh-interval: 30s    # 上一轮结束到下一轮开始的间隔
    initial-delay: 3s        # 启动后首轮巡检的延迟
    concurrency: 16          # 单轮巡检的最大并发探测数
    history-size: 60         # 每个应用保留的历史采样点数量
    ui-refresh-seconds: 10   # 看板自动刷新间隔，0 表示关闭

    probe:
      connect-timeout: 3s
      read-timeout: 5s
      follow-redirects: false  # 健康检查一般不希望跟随跳转
      max-body-bytes: 65536    # 读取响应体的上限

    applications:
      - name: 用户中心
        group: 核心服务
        url: http://user-center.internal:8080/actuator/health
        critical: true
        tags: [ java, 核心链路 ]

      - name: 对象存储网关
        group: 基础设施
        url: https://oss-gateway.internal/healthz
        expected-statuses: [ 200, 204 ]   # 该接口用 204 表示健康
        read-timeout: 10s

      - name: 报表服务
        group: 内部工具
        url: http://report.internal:9000/actuator/health
        username: monitor                 # HTTP Basic 认证
        password: "change-me"
        headers:
          X-Tenant: bgssai

      - name: 灰度环境
        group: 内部工具
        url: http://gray.internal:8080/actuator/health
        enabled: false                    # 保留配置但不巡检
```

### 单个应用支持的字段

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `id` | 由 `name` 推导 | 唯一标识，用在接口路径里；纯中文名会回退成 `app-N` |
| `name` | 必填 | 应用名称 |
| `group` | `未分组` | 看板上的分组 |
| `url` | 必填 | 健康检查接口地址，必须是 http/https 绝对地址 |
| `method` | `GET` | 只支持 `GET` / `HEAD` |
| `enabled` | `true` | 关闭后保留配置但不巡检 |
| `critical` | `false` | 见下方「关键应用」 |
| `tags` | 空 | 展示用标签，同时参与页面搜索 |
| `headers` | 空 | 附加请求头 |
| `username` / `password` | 空 | HTTP Basic 认证 |
| `connect-timeout` / `read-timeout` | 取 `probe` 的值 | 单个应用的超时覆盖 |
| `expected-statuses` | 空（即任意 2xx） | 判定为调用成功的状态码 |
| `description` | 空 | 备注，展示在卡片上 |

## 状态是怎么判定的

平台把各种健康检查约定统一收敛成四种状态：

| 状态 | 含义 |
| --- | --- |
| `UP` / 正常 | 接口可达且自报正常 |
| `DEGRADED` / 降级 | 自报 `OUT_OF_SERVICE`、`WARN` 等；或自报 `UP` 但状态码不在预期内 |
| `DOWN` / 异常 | 连接失败、超时、状态码不符合预期，或自报 `DOWN` |
| `UNKNOWN` / 未知 | 尚未巡检，或该应用已停用 |

判定规则：

1. 能从响应体解析出状态字段（依次尝试 `status`、`state`、`health`，也支持 `{"status":{"code":"UP"}}`）时，**以响应体为准**。
   这样才能正确处理 Actuator 用 `503 + {"status":"DOWN"}` 表示异常、`200 + {"status":"OUT_OF_SERVICE"}` 表示降级的约定。
   顶层没有状态字段时，会再往 `result` / `data` 下沉一层——BGSSAI 产品线的应用按 Standards §13 把健康负载
   放在各仓既有的统一响应封装里，封装的 `code` / `success` 表达的是「接口调用成功」，与健康无关。
2. 响应体不是可识别的健康报文（比如纯文本 `OK`）时，**按状态码判断**：命中 `expected-statuses`（未配置则任意 2xx）即为正常。
3. 响应体自报 `UP` 但状态码不在预期内，判定为降级而不是直接判死。
4. 连接失败、DNS 解析失败、超时一律判定为异常，并把原因写进 `message`。

响应体里的 `components`（或旧版的 `details`）会被解析成子组件列表，在卡片里展开显示。两种形态都认：
Actuator 的 `{"components": {"db": {...}}}`（键即组件名），以及 BGSSAI 的 `"components": [{"name": "db", ...}]`
（组件名在元素的 `name` 字段里）。

### 巡检 BGSSAI 产品线应用

9 个产品 × 管理端 / 用户端共 18 个后端，巡检地址统一为 `/bgssai/health/readiness`（Standards §13.7）。
`application.yml` 里已按这个路径列全 18 条，主机名是占位符——填入本环境真实主机后把该条的 `enabled`
改为 `true` 即可。

选就绪探针而不是另外两个端点，是因为三者里只有它既证明进程活着、又证明关键依赖可用，且不可用时返回
503：存活探针在数据库不通时照样返回 200，用它巡检等于自欺；全量报告 `/bgssai/health` 信息更全但最坏
耗时更长，适合人工排障。

这 18 条一律 **`critical: false`**——本平台自己的 `/actuator/health` 只该反映「平台还能不能巡检」，
不该因为某个下游应用挂了就对外报 DOWN，否则编排系统会去重启这个本来正常的平台。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/apps` | 全部应用的健康状态 |
| `GET` | `/api/apps/{id}` | 单个应用的健康状态 |
| `GET` | `/api/summary` | 汇总统计 |
| `GET` | `/api/dashboard` | 汇总 + 按分组归拢，看板一次拉取用 |
| `POST` | `/api/refresh` | 立刻巡检全部应用 |
| `POST` | `/api/apps/{id}/refresh` | 立刻巡检单个应用 |

查询不存在的 id 返回 `404` 和 RFC 9457 的 `application/problem+json`。

```console
$ curl -s localhost:8080/api/summary
{"overall":"DOWN","total":4,"up":2,"degraded":0,"down":1,"unknown":0,"disabled":1,
 "generatedAt":"2026-07-31T06:12:03.117Z","lastCheckedAt":"2026-07-31T06:12:01.882Z","uiRefreshSeconds":10}
```

## 关键应用与平台自身的健康端点

被监控应用的汇总会并入平台自己的 `/actuator/health`，作为 `monitoredApplications` 贡献者：

```json
{
  "status": "UP",
  "components": {
    "monitoredApplications": { "status": "UP", "details": { "total": 4, "up": 2, "down": 1 } }
  }
}
```

**只有标记 `critical: true` 的应用确认异常（DOWN / DEGRADED）时，平台才会对外报 DOWN。**
巡检平台自身是否可用，和被监控方是否可用是两件事——不能因为随便一个下游挂了，就让编排系统去重启这个平台。
「尚未巡检」的 UNKNOWN 也不算异常，否则平台刚启动、首轮巡检还没跑完时就会对外报 DOWN。

> ⚠️ 如果要把本平台自己的 `/actuator/health` 也配成被监控应用，**不要标成 `critical: true`**。
> 该端点包含 `monitoredApplications` 贡献者，一旦报过一次 DOWN 就会被自己记下来，从此再也回不到 UP。

## 看板

页面用 Thymeleaf 服务端渲染，JavaScript 只做三件事：定时拉取 `/fragments/dashboard` 片段替换 DOM、
客户端搜索与状态筛选、深浅色主题切换。视图逻辑只有服务端这一份，不需要在前端再写一遍。

- 顶部汇总：整体状态、应用总数、各状态计数
- 按分组展示卡片：状态灯、响应耗时、HTTP 状态码、可用率、最近检查时间
- 每张卡片带一条历史趋势条（最近 N 次巡检结果），鼠标悬停可看单次明细
- 子组件（`db`、`diskSpace` 等）可展开查看
- 支持按名称 / 分组 / 地址 / 标签搜索，按状态筛选
- 「立刻巡检」按钮触发一轮全量巡检；每张卡片也可单独重新检查
- 自动跟随系统深浅色，也可手动切换

## 实现要点

- 巡检使用 **虚拟线程**（Java 21）并发执行，用信号量限制同时在途的探测数
- 同一时刻只允许一轮全量巡检，重复触发会被合并
- 巡检结果只保存在进程内存里，不做持久化——重启后重新巡检即可恢复
- 每个被监控应用有独立的 `RestClient`，超时、请求头、认证按应用配置隔离
- 配置在启动时解析并校验：非法地址、不支持的请求方法会直接让应用启动失败，而不是等到巡检时才暴露

## 开发

```bash
./mvnw test          # 运行测试
./mvnw spring-boot:run
```

测试用 JDK 自带的 `HttpServer` 模拟被监控应用（见 `StubHealthServer`），覆盖 200/503/204、
非 JSON 响应、404、连接被拒、读超时等场景，不依赖外部网络。

## 项目结构

```
src/main/java/com/bgssai/healthcheck/
├── HealthCheckApplication.java
├── config/HealthCheckProperties.java        # 全部配置项
├── domain/                                  # 状态枚举与对外数据结构
├── service/
│   ├── ApplicationRegistry.java             # 配置解析与校验
│   ├── HttpHealthProbe.java                 # 单次探测与响应归一化
│   ├── HealthStatusStore.java               # 内存中的结果、历史与统计
│   ├── HealthCheckService.java              # 并发编排与查询视图
│   ├── HealthCheckScheduler.java            # 定时触发
│   └── MonitoredApplicationsHealthIndicator.java
└── web/
    ├── HealthApiController.java             # REST 接口
    ├── DashboardController.java             # 看板页面与片段
    └── ViewFormatter.java                   # 页面格式化

src/main/resources/
├── application.yml
├── templates/index.html
└── static/{css/app.css, js/app.js, favicon.svg}
```
