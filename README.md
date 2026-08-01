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

全部配置项都在 `application.properties` 的 `bgssai.healthcheck` 下。**本仓统一用 `.properties`，
不用 `.yml` / `.yaml`。** 与之配套有三条硬性写法，改配置前务必先看：

1. **数组 / List 一律带下标 `[n]` 逐项展开**（Standards §4），禁止单行逗号分隔；
2. **值一律写最终字面量**，禁止 `${...}` 占位符（Standards §1），键、值、注释三处均禁——环境差异走
   `application-{profile}.properties`；
3. **非 ASCII 的值必须写成 `\uXXXX` 转义**。Spring Boot 按 `java.util.Properties` 规范以 ISO-8859-1
   解码 `.properties`（`.yml` 才是一律 UTF-8），直接写中文会被读成乱码——本平台的看板名称与分组名
   都是配置值，一旦乱码整个看板都不可读。注释里的中文不受影响（注释行不会被解析成值），所以配置
   文件里每条应用上方都用注释给出了可读的中文名。转换用
   `python3 -c "import sys;print(sys.argv[1].encode('unicode_escape').decode())" 用户中心`。
   URL / id / tags / 布尔 / 时长这些 ASCII 值照常直接写。

```properties
bgssai.healthcheck.scheduled=true          # 是否启用后台定时巡检
bgssai.healthcheck.refresh-interval=30s    # 上一轮结束到下一轮开始的间隔
bgssai.healthcheck.initial-delay=3s        # 启动后首轮巡检的延迟
bgssai.healthcheck.concurrency=16          # 单轮巡检的最大并发探测数
bgssai.healthcheck.history-size=60         # 每个应用保留的历史采样点数量
bgssai.healthcheck.ui-refresh-seconds=10   # 看板自动刷新间隔，0 表示关闭

bgssai.healthcheck.probe.connect-timeout=3s
bgssai.healthcheck.probe.read-timeout=5s
bgssai.healthcheck.probe.follow-redirects=false  # 健康检查一般不希望跟随跳转
bgssai.healthcheck.probe.max-body-bytes=65536    # 读取响应体的上限

# 用户中心 / 核心服务（name 与 group 是中文，故写 \uXXXX）
bgssai.healthcheck.applications[0].name=\u7528\u6237\u4e2d\u5fc3
bgssai.healthcheck.applications[0].group=\u6838\u5fc3\u670d\u52a1
bgssai.healthcheck.applications[0].url=http://user-center.internal:8080/bgssai/health/readiness
bgssai.healthcheck.applications[0].critical=true
bgssai.healthcheck.applications[0].tags[0]=java

# 对象存储网关：该接口用 204 表示健康，且需要更长读超时
bgssai.healthcheck.applications[1].name=oss-gateway
bgssai.healthcheck.applications[1].group=infra
bgssai.healthcheck.applications[1].url=https://oss-gateway.internal/healthz
bgssai.healthcheck.applications[1].expected-statuses[0]=200
bgssai.healthcheck.applications[1].expected-statuses[1]=204
bgssai.healthcheck.applications[1].read-timeout=10s

# 报表服务：HTTP Basic 认证 + 自定义请求头
bgssai.healthcheck.applications[2].name=report-service
bgssai.healthcheck.applications[2].group=internal-tools
bgssai.healthcheck.applications[2].url=http://report.internal:9000/bgssai/health/readiness
bgssai.healthcheck.applications[2].username=monitor
bgssai.healthcheck.applications[2].password=change-me
bgssai.healthcheck.applications[2].headers.X-Tenant=bgssai

# 灰度环境：保留配置但不巡检
bgssai.healthcheck.applications[3].name=gray-env
bgssai.healthcheck.applications[3].group=internal-tools
bgssai.healthcheck.applications[3].url=http://gray.internal:8080/bgssai/health/readiness
bgssai.healthcheck.applications[3].enabled=false
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
巡检目标已按真实地址列全并启用，放在两份 profile 文件里（Standards §1：随环境变化的项一律下沉到
profile）：

| 文件 | 环境 | 条目 |
|---|---|---|
| `src/main/resources/application-prod.properties` | 生产（华为云-境内-上海一 + 腾讯云） | `[0]` 平台自身 + `[1]`..`[18]` 共 19 条 |
| `src/main/resources/application-dev.properties`  | 开发（华为云-境外-墨西哥二 + 腾讯云） | 同上 19 条 |

启动时用 `SPRING_PROFILES_ACTIVE=prod`（或 `dev`）选定。**不指定 profile 时列表为空**，应用照常启动、
看板显示「还没有配置被监控的应用」。

三个决定 URL 长相的事实，改地址前务必知道：

1. **端口是 443、协议是 HTTPS**。18 个后端在 dev / prod 都是 `server.port=443` + `server.ssl.enabled=true`
   （各产品仓 profile 实测），`8080` / `8081` 只是本地开发端口。
2. **用公网 IP**。境内华为云私网是 `172.31.x`、境外是 `192.168.0.x`，属两个不同区域 / VPC，一台机器
   走不通对面私网；腾讯云 SaaS 更是只有公网 IP。只有公网 IP 这一套能同时覆盖三处。**若本平台就部署在
   某一侧 VPC 内**，把该侧条目换成私网 IP 更省流量——每条的私网地址都写在它上方的注释里。
3. **`skip-tls-verification=true`**。证书签给的是业务域名（`www.bgssai-blog.com` 等），而这里按机器 IP
   直连，TLS 握手会因主机名不匹配失败，健康的应用会被整片误判为 DOWN。见下一节。

### 跳过证书校验：为什么开、以及怎么关掉它

`bgssai.healthcheck.probe.skip-tls-verification`（全局默认，出厂 `false`）与每条目的
`applications[n].skip-tls-verification`（覆盖全局）控制是否放开证书链与主机名校验。当前两份 profile
里 18 条产品后端都显式打开了它。

**放开的只是本平台这一个出站客户端**，用一个仅供该目标使用的 `SSLContext`，不碰 JVM 全局默认值，
不影响本平台的其它请求，更不改任何被监控应用的配置。安全影响也有限：三个健康端点本就是公开的
（Standards §13.4），响应体只含白名单字段、不含凭据 / 主机 / 连接串 / 堆栈，探针只做 `GET` 且不带
业务令牌——中间人能看到或篡改的东西，与它自己直接请求那个公开端点等价。

**想收紧的正解是给每台机器配域名、按域名探测**：把 url 换成 `https://admin.bgssai-blog.com/...`
这类域名后，证书天然匹配，删掉该条的 `skip-tls-verification` 即可。

回归守护在 `TlsVerificationProbeTests`：一个用例证明不放开时确实会因主机名不匹配判 DOWN（说明这个坑
真实存在），另一个证明放开后能读到对端自报的 UP。

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
├── application.properties          # 巡检行为阈值（三环境不变量）
├── application-prod.properties     # 生产 19 个巡检目标
├── application-dev.properties      # 开发 19 个巡检目标
├── templates/index.html
└── static/{css/app.css, js/app.js, favicon.svg}
```
