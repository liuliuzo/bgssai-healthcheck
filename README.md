# bgssai-healthcheck

## 产品线规划（全线统一）

BGSSAI 产品线按下面五条划分职责，各仓实现与文档不得与此冲突。

1. **BGSSAI** 是给一人公司（OPC）创业者的全行业工具集合，让用户能找到创业所需的全部工具。
2. **bgssai-website** 是公司官网，只对外介绍产品与服务，不承载产品操作、在线对话或中心账号。
3. **bgssai-chat** 提供对标 ChatGPT / Gemini / Claude home / Grok 的 Web 在线对话 AI。
4. **中心用户账号在 bgssai-chat**：可授权登录旗下各 App；各 App 同时可以有自己的用户账号体系，两者并存。
5. **bgssai-bot** 对标 Grok Bot。BGSSAI 的全部产品应用可以托管给 Bot 直接操作。

Tokenhub / Tokenhub-CN 是模型网关，不是业务 App，也不是 oauth_client。

愿景唯一权威：`bgssai-skeleton/docs/PRODUCT-LINE-VISION.md`。本段是各仓副本，变更以该文件为准。

**本仓位置**：巡检仓，非业务工具。

巡检各应用的健康接口，并直连数据库与中间件，用一个服务端渲染的看板集中展示。

- **Java 21** + **Spring Boot 4.1.0**
- 前端使用 Spring Boot 自带的 **Thymeleaf** 模板引擎，无需 Node 工具链，页面资源全部内置
- 五类被监控目标：HTTP 健康接口、Elasticsearch、Redis、MySQL、TCP 端口
- 每次探测都留下**原始请求与应答**，看板上可展开查看，凭据在写入前已脱敏
- 巡检结果通过 **REST 接口**、**看板页面** 和 **可下载的 Markdown 报告** 三处对外提供
- 状态由正常转为异常时**主动告警**：日志通道零配置即生效，另可配 Webhook 推到钉钉 / 企业微信 / 飞书

| 地址 | 说明 |
| --- | --- |
| `/` | 健康状态看板 |
| `/api/report.md` | 可下载的 Markdown 巡检报告 |
| `/api/alerts` | 告警配置与进行中的故障 |
| `/actuator/health` | 平台自身的健康端点 |

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
| <http://localhost:8080/api/report.md> | 下载 Markdown 巡检报告 |
| <http://localhost:8080/actuator/health> | 平台自身的健康端点 |

## 配置被监控的目标

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

bgssai.healthcheck.detail.enabled=true           # 是否保留每次探测的原始请求与应答
bgssai.healthcheck.detail.max-body-chars=16384   # 单条明细保留的最大字符数
bgssai.healthcheck.detail.keep-last-failure=true # 额外保留最近一次失败的明细

bgssai.healthcheck.redis.memory-warn-percent=90      # Redis 内存占 maxmemory 达此比例判降级
bgssai.healthcheck.mysql.connection-warn-percent=90  # MySQL 连接数占 max_connections 达此比例判降级
bgssai.healthcheck.mysql.query-timeout-seconds=3     # MySQL 单条校验语句的超时

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

### 单个目标支持的字段

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `id` | 由 `name` 推导 | 唯一标识，用在接口路径里；纯中文名会回退成 `app-N` |
| `name` | 必填 | 目标名称 |
| `group` | `未分组` | 看板上的分组 |
| `url` | 必填 | 目标地址，scheme 决定用哪个探针（见下表） |
| `type` | 由 scheme 推导 | 目标种类，只有 `elasticsearch` 必须显式写 |
| `method` | `GET` | 只支持 `GET` / `HEAD`，且只对 HTTP 系目标生效 |
| `enabled` | `true` | 关闭后保留配置但不巡检 |
| `critical` | `false` | 见下方「关键目标」 |
| `tags` | 空 | 展示用标签，同时参与页面搜索 |
| `headers` | 空 | 附加请求头（HTTP 系） |
| `username` / `password` | 空 | HTTP Basic 认证；Redis 用作 `AUTH` 参数，MySQL 用作 JDBC 登录账号 |
| `connect-timeout` / `read-timeout` | 取 `probe` 的值 | 单个目标的超时覆盖 |
| `expected-statuses` | 空（即任意 2xx） | 判定为调用成功的状态码（HTTP 系） |
| `expected-databases` | 空 | 期望存在的库名，只对 MySQL 生效 |
| `description` | 空 | 备注，展示在卡片上 |
| `skip-tls-verification` | 取 `probe` 的值 | 见「跳过证书校验」 |

`url` 的 scheme 决定用哪个探针，端口留空时按类型补默认值：

| scheme | 类型 | 默认端口 | 探测方式 |
| --- | --- | --- | --- |
| `http` / `https` | `http` | 80 / 443 | 调健康接口，按响应体状态字段或状态码判定 |
| `http` / `https` 且 `type=elasticsearch` | `elasticsearch` | 9200 | 调 `_cluster/health`，按集群颜色判定 |
| `redis` / `rediss` | `redis` | 6379 | RESP 协议发 `AUTH` / `SELECT` / `PING` / `INFO` |
| `mysql` | `mysql` | 3306 | JDBC 建连接跑 `SELECT 1` 并核对库清单 |
| `tcp` | `tcp` | 必须写明 | 只验证端口可连通 |

Elasticsearch 的 scheme 与普通 HTTP 接口相同，无法自动区分，所以**必须显式写 `type=elasticsearch`**；
写了之后 url 可以省略路径，探针会自动补 `/_cluster/health`。

```properties
# MySQL：不写库名就只验证实例，expected-databases 负责核对库是否还在
bgssai.healthcheck.applications[19].id=mysql-cn
bgssai.healthcheck.applications[19].url=mysql://121.36.230.185:3306/
bgssai.healthcheck.applications[19].username=root
bgssai.healthcheck.applications[19].password=change-me
bgssai.healthcheck.applications[19].expected-databases[0]=bgssai_blog
bgssai.healthcheck.applications[19].expected-databases[1]=bgssai_vpn

# Redis：password 就是 AUTH 的参数
bgssai.healthcheck.applications[20].id=redis-cn
bgssai.healthcheck.applications[20].url=redis://121.37.158.8:6379
bgssai.healthcheck.applications[20].password=change-me

# Elasticsearch：Basic 认证 + 自签证书
bgssai.healthcheck.applications[21].id=elasticsearch-cn
bgssai.healthcheck.applications[21].url=https://123.60.84.99:9200/_cluster/health
bgssai.healthcheck.applications[21].type=elasticsearch
bgssai.healthcheck.applications[21].username=elastic
bgssai.healthcheck.applications[21].password=change-me
bgssai.healthcheck.applications[21].skip-tls-verification=true
```

配置在启动时就会被解析并校验：地址非法、类型与 scheme 对不上、TCP 没写端口、
`expected-databases` 配到了非 MySQL 目标上，都会直接让应用起不来——这些错误如果留到巡检时才暴露，
在看板上跟「对端真的挂了」长得一模一样。

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

Elasticsearch 的集群颜色一并归到同一套状态里：`green` 是正常，`yellow`（主分片就绪、副本未分配）是降级，
`red` 是异常。这三个词不与任何产品的自报状态冲突，所以直接并入状态映射，不需要为它单写一套解析。

中间件与数据库没有「自报状态」可读，判定规则写在各自的探针里：

| 目标 | 判 UP | 判 DEGRADED | 判 DOWN |
| --- | --- | --- | --- |
| Redis | `PING` 返回 `+PONG` 且 `INFO` 各项正常 | 内存占 `maxmemory` 超阈值 / 从节点链路断开 / RDB 或 AOF 写失败 | 连不上、超时、`AUTH` 被拒 |
| MySQL | `SELECT 1` 通过 | 连接数占 `max_connections` 超阈值 / 实例只读 / `expected-databases` 有库缺失 | 连不上、超时、认证失败、`SELECT 1` 失败 |
| Elasticsearch | 集群颜色 `green` | 颜色 `yellow`，或有未分配分片 | 颜色 `red`、连不上、超时 |
| TCP | 端口能建立连接 | 不判降级 | 连不上、超时 |

MySQL 的辅助查询（版本、连接数、库清单）失败时**不会**把整体判成 DOWN——巡检账号权限不足是常见情况，
只要 `SELECT 1` 过了就说明库是活的，那几个组件记成「未知」即可，不该用巡检账号的权限去误报一台健康的库。

### 巡检 BGSSAI 产品线应用

9 个产品 × 管理端 / 用户端共 18 个后端，巡检地址统一为 `/bgssai/health/readiness`（Standards §13.7）。
巡检目标已按真实地址列全并启用，四份配置文件各写一份完整清单：

| 文件 | 生效条件 | 巡检目标 | 条数 |
|---|---|---|---|
| `src/main/resources/application.properties` | 不指定 profile（默认档） | 生产（华为云-境内-上海一 + 华为云-境外） | 25 |
| `src/main/resources/application-prod.properties` | `SPRING_PROFILES_ACTIVE=prod` | 同上，与主配置逐条一致 | 25 |
| `src/main/resources/application-dev.properties` | `SPRING_PROFILES_ACTIVE=dev` | 开发（华为云-境外-墨西哥二 + 腾讯云） | 22 |
| `src/main/resources/application-local.properties` | `SPRING_PROFILES_ACTIVE=local` | 同 dev，笔记本本机启动用 | 22 |

清单分两段：前 19 条是 `[0]` 平台自身 + `[1]`..`[18]` 十八个后端，四份文件完全相同；后面是中间件与
数据库——生产两地各一套（`mysql-cn` / `mysql-global` / `redis-cn` / `redis-global` /
`elasticsearch-cn` / `elasticsearch-global`），开发只有境外一套（`mysql-dev` / `redis-dev` /
`elasticsearch-dev`），所以 prod 家族与 dev 家族的条数本就不同。

**主配置自带整份基线，因此 `java -jar app.jar` 不带 profile 也能看到 25 个目标**，看板不再显示
「还没有配置被监控的目标」；Jenkins 部署仍注入 `--spring.profiles.active=<env>`，命中哪一档就整份
换成那一档的地址。

**为什么四份文件各写一遍，而不是主配置写公共部分、profile 只写差异**：Spring Boot 绑定集合时
**不跨 property source 合并**，只从优先级最高的那个源整份取。profile 文件优先级高于主配置，一旦
它出现 `applications` 键，主配置那份就整份失效；此时 profile 文件若只写 `[1..18]`、指望 `[0]` 从
主配置补上，绑定器会在下标 0 处遇到空洞并抛「left unbound」启动失败。代价是同一批目标在四个文件
里各有一份，改一处忘一处既没有编译期报错也没有启动期报错，只会在切换 profile 后悄悄探测到过时的
地址——所以由 `ConfigurationFilesConsistencyTests` 守护：主配置与 prod 档、local 档与 dev 档必须
逐条一致，四份文件的前 19 条与顺序必须相同，每份都必须覆盖到数据库、Redis 与 Elasticsearch，
凭据与证书开关也要配齐，任一条对不上 `./mvnw test`（部署构建同样会跑）直接失败。

注意 `detail.*` / `redis.*` / `mysql.*` 这些阈值是**标量键**，Spring Boot 跨 property source 是逐键
合并的，不像 `applications` 那样整份取代，所以只在主配置写一份即可，profile 文件不复制——多一份就多
一处会漂移的地方，这一点同样有用例守着。

三个决定 URL 长相的事实，改地址前务必知道：

1. **端口是 8080、协议是 HTTP**。18 个后端在 local / dev / test / prod 都是 `server.port=8080`，Spring SSL 关闭。user / admin 分开部署或本机交替启动，两端同一端口。
2. **用公网 IP**。境内华为云私网是 `172.31.x`、境外是 `192.168.0.x`，属两个不同区域 / VPC，一台机器
   走不通对面私网。只有公网 IP 这一套能同时覆盖两地。本平台部署在境内
   `123.60.68.201`（私网 `172.31.6.116`），与生产档 14 条境内条目同属 `172.31.x`，**想省公网流量可把
   这 14 条换成私网地址**（每条的私网地址都写在它上方的注释里）；GEO 海外与生产 SaaS（华为云境外、
   与中间件共机）这 4 条，以及整份 dev 档（境外 `192.168.0.x` + 腾讯云开发 SaaS），只能走公网。
3. **`skip-tls-verification=true`**。证书签给的是业务域名（`www.bgssai-blog.com` 等），而这里按机器 IP
   直连，TLS 握手会因主机名不匹配失败，健康的应用会被整片误判为 DOWN。三台 Elasticsearch 同理——
   它们用的是自签证书（`curl` 需要 `-k`）。见下一节。

### 跳过证书校验：为什么开、以及怎么关掉它

`bgssai.healthcheck.probe.skip-tls-verification`（全局默认，出厂 `false`）与每条目的
`applications[n].skip-tls-verification`（覆盖全局）控制是否放开证书链与主机名校验。当前四份配置文件
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

## 中间件与数据库为什么要单独直连探

18 个产品后端的巡检地址是就绪探针 `/bgssai/health/readiness`，而按 Standards §13.2，
**就绪探针只由 critical 组件决定结论**，critical 只有 `db` 与 `mybatis`；Redis、Elasticsearch
这类依赖一律非 critical，既不参与判定、**也不在就绪端点被检查**。换句话说 Redis 挂了，
应用的就绪探针照样返回 `UP`，看板上一片绿。

Standards §13.4 又禁止健康负载回显连接串、主机、端口与数据库版本，所以即使去查全量报告
`/bgssai/health`，也拿不到中间件本身的水位（内存占用、连接数、集群颜色）。

因此中间件与数据库的可用性只能由本平台自己连过去看。四份配置文件里各带一套：
生产两地各一套（境内 / 境外的 MySQL、Redis、Elasticsearch 共 6 条），开发只有境外一套（3 条）。

`expected-databases` 值得单独说一句：`bgssai-database` 仓的 clean 流水线会 `DROP DATABASE`，
库被清掉时 3306 端口照样开着，应用要到下一次访问才报错。把期望的库名列出来，探针每轮都去
`information_schema` 对一遍，库没了当轮就能看见。

**口令为什么直接写在配置文件里**：Standards §1 全局禁用配置占位符，环境差异一律在 profile 文件里
写死最终字面量，这是产品线的既定口径；这些口令的权威出处是 `bgssai-logs` 仓的 `inventory/infra.md`。
看板、REST 接口与 `/api/report.md` 都不会回显它们——探针在写入原始明细之前就已统一脱敏
（`Authorization` 头、Redis 的 `AUTH` 参数、JDBC 口令一律替换成占位符）。

## 原始明细：看得到对端到底说了什么

归一化后的状态只回答「是不是好的」，排障要的却是「对端究竟返回了什么」。所以每次探测都会留下一份
`ProbeDetail`：发出去的请求（含请求头）、应答首行、响应头、以及**原样未加工的响应正文**。
卡片上的「查看详情」按钮会拉 `/fragments/apps/{id}/detail` 打开弹窗，JSON 会缩进后展示，
非 JSON 原样输出。

两个刻意的取舍：

- **明细里存的是原文，美化只发生在展示时**。对端返回的是压缩成一行还是本来就带缩进，
  偶尔正是线索；一旦在采集时就格式化，「原始响应」四个字就名不副实了。
- **额外保留最近一次失败的明细**（`detail.keep-last-failure`）。目标恢复之后，最近一次结果就变成
  一次成功的探测，那时想知道刚才究竟错在哪里，只能靠这份留档。目标当前就在失败状态时不重复展示。

明细按目标常驻内存，所以 `detail.max-body-chars` 给了上限。注意它只影响**保留**多少，
不影响**读取**多少——判定状态用的始终是完整响应体（读取上限由 `probe.max-body-bytes` 控制）。
截断过的正文会在页面和报告里明确标注。

## 可下载的 Markdown 报告

`GET /api/report.md` 返回一份完整的 Markdown 报告，用来交给 AI 分析、或者存档比对。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `download` | `true` | `false` 时改为 `inline`，在浏览器里直接打开 |
| `raw` | `true` | `false` 时不含原始应答，报告体积小一个数量级 |
| `refresh` | `false` | `true` 时先跑一轮巡检再生成 |

报告固定八个章节：报告元信息、状态汇总、需要处理的目标、全部目标一览、逐目标明细、
当前巡检配置、口径说明、可以让 AI 回答的问题。

后两节是专门为「让 AI 优化当前的健康检查」写的。只给一堆状态值，AI 只能泛泛地说「建议加强监控」；
把判定规则、阈值、目标清单、以及 Standards §13 里那几条决定了当前设计的约束一并写进去，
它才可能指出「这条目标的读超时对跨境链路偏紧」「这台实例的内存阈值该按实际水位调」这类有依据的结论。
最后一节直接列出十来个可以照着问的问题，问题里的数字取自本次巡检的真实数据。

报告里不会出现任何口令：目标清单只列到账号层面，明细里的凭据在探针写入前已经脱敏。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/apps` | 全部应用的健康状态 |
| `GET` | `/api/apps/{id}` | 单个应用的健康状态 |
| `GET` | `/api/summary` | 汇总统计 |
| `GET` | `/api/dashboard` | 汇总 + 按分组归拢，看板一次拉取用 |
| `POST` | `/api/refresh` | 立刻巡检全部应用 |
| `POST` | `/api/apps/{id}/refresh` | 立刻巡检单个应用 |
| `GET` | `/api/alerts` | 告警配置、启用的通道与进行中的故障 |
| `GET` | `/api/report.md` | 可下载的 Markdown 巡检报告 |
| `GET` | `/fragments/apps/{id}/detail` | 详情弹窗的 HTML 片段（看板内部使用） |

查询不存在的 id 返回 `404` 和 RFC 9457 的 `application/problem+json`。

```console
$ curl -s localhost:8080/api/summary
{"overall":"DOWN","total":4,"up":2,"degraded":0,"down":1,"unknown":0,"disabled":1,
 "generatedAt":"2026-07-31T06:12:03.117Z","lastCheckedAt":"2026-07-31T06:12:01.882Z","uiRefreshSeconds":10}
```

## 关键目标与平台自身的健康端点

被监控目标的汇总会并入平台自己的 `/actuator/health`，作为 `monitoredApplications` 贡献者：

```json
{
  "status": "UP",
  "components": {
    "monitoredApplications": { "status": "UP", "details": { "total": 4, "up": 2, "down": 1 } }
  }
}
```

**只有标记 `critical: true` 的目标确认异常（DOWN / DEGRADED）时，平台才会对外报 DOWN。**
巡检平台自身是否可用，和被监控方是否可用是两件事——不能因为随便一个下游挂了，就让编排系统去重启这个平台。
「尚未巡检」的 UNKNOWN 也不算异常，否则平台刚启动、首轮巡检还没跑完时就会对外报 DOWN。

> 注意：如果要把本平台自己的 `/actuator/health` 也配成被监控应用，**不要标成 `critical: true`**。
> 该端点包含 `monitoredApplications` 贡献者，一旦报过一次 DOWN 就会被自己记下来，从此再也回不到 UP。

## 告警

看板回答「现在怎么样」，告警回答「什么时候变了」。没有告警的巡检平台需要有人一直盯着屏幕，
而故障往往发生在没人看的时候。

**默认已开启，且不需要任何配置**：日志通道始终生效，告警行落进
`/opt/bgssai/log/bgssai-healthcheck_unstrct.log` 后由 [`bgssai-logs`](https://github.com/liuliuzo/bgssai-logs)
采走，等于零配置就有一份可检索、可留存的故障记录，也不会往任何群里发东西。要即时通知再配 Webhook。

### 什么时候会响

告警的输入不是状态本身，而是**状态的变化**。每个目标最多只有一次进行中的故障，从首次探测到异常
开始、到恢复正常结束，中间无论巡检多少轮，只在这几个时刻发通知：

| 时刻 | 类型 | 说明 |
| --- | --- | --- |
| 连续异常次数首次达到 `failure-threshold` | `FIRING` 告警 | 默认第 2 次，见下方「为什么是 2」 |
| 已在告警中，但异常程度变了（降级转异常，或反过来） | `FIRING` 告警 | 同一次故障，起点不重置 |
| 距上次通知超过 `repeat-interval` | `REMINDER` 提醒 | 默认 `0s`，即不重复 |
| 恢复正常 | `RECOVERED` 恢复 | 带上故障持续时长 |

两条刻意的「不响」：

- **没到阈值就恢复的抖动，告警与恢复通知都不发。** 既没告过警，就不该冒出一条「恢复了」——
  只有下半句的通知比没有通知更让人困惑。
- **`UNKNOWN` 默认不算异常，也不算恢复。** 它的含义是「对端自报了一个不认识的状态词」，
  把说不准当故障报会经常误伤；但正在告警的目标转成 `UNKNOWN` 时，也不会被当成已经好了。
  要把它计入异常就打开 `include-unknown`。

### 为什么阈值是 2、间隔是 0

这两个默认值是本平台的实际处境决定的，改之前先想清楚要换成什么：

- **`failure-threshold=2`**：巡检目标里有跨境链路（境内巡检机探境外机器），偶发一次超时是常态。
  单次失败就报会把告警变成噪音，而按 30s 的巡检间隔，等第 2 次也只把发现时间推迟半分钟。
- **`repeat-interval=0s`**：一条修不好的告警每隔几分钟响一次，最后的结果是所有人都不看告警了。
  想要「长时间未恢复要再提醒」时再打开它，建议不短于半小时。

### 配置

```properties
bgssai.healthcheck.alert.enabled=true
# 连续多少次探测为异常才告警
bgssai.healthcheck.alert.failure-threshold=2
# 恢复时补一条通知
bgssai.healthcheck.alert.recovery-notice=true
# 持续异常时的重复提醒间隔，0 表示只在状态变化时通知一次
bgssai.healthcheck.alert.repeat-interval=0s
# UNKNOWN 是否计入异常
bgssai.healthcheck.alert.include-unknown=false
# 是否只对 critical=true 的目标告警
bgssai.healthcheck.alert.only-critical=false

# Webhook 通道：留空即不启用
bgssai.healthcheck.alert.webhook.url=
bgssai.healthcheck.alert.webhook.format=generic
bgssai.healthcheck.alert.webhook.connect-timeout=3s
bgssai.healthcheck.alert.webhook.read-timeout=5s
```

`format` 决定报文长什么样。内置三家聊天机器人的格式，是因为只给通用 JSON 的话，
每个想接机器人的人都得自己再搭一个转换服务：

| `format` | 报文 | 用于 |
| --- | --- | --- |
| `generic` | 本平台自己的 JSON，字段最全 | 自研网关、告警聚合系统 |
| `wecom` | `{"msgtype":"text","text":{"content":…}}` | 企业微信机器人 |
| `dingtalk` | 同上（钉钉与企业微信报文结构相同） | 钉钉机器人 |
| `feishu` | `{"msg_type":"text","content":{"text":…}}` | 飞书机器人 |

```properties
# 钉钉机器人示例
bgssai.healthcheck.alert.webhook.url=https://oapi.dingtalk.com/robot/send?access_token=xxxx
bgssai.healthcheck.alert.webhook.format=dingtalk
# 自研网关要额外的鉴权头时
bgssai.healthcheck.alert.webhook.headers.X-Gateway-Token=xxxx
```

> **地址里的 `access_token` 等同于一把「可以往这个群里发任意消息」的口令。**
> 本平台不会把它写进日志——日志里出现的地址一律抹掉查询串与用户信息（`…/robot/send?***`），
> 报告与 `/api/alerts` 里也不回显地址本身。

### 通道与失败处理

告警的发送跑在一条专用线程上，不占巡检的并发额度——若在巡检线程里同步等一次 Webhook 往返，
一个响应慢的机器人就会拖住整轮巡检，「监控平台因为发告警而漏了下一轮探测」是最难堪的一种失效。
队列有界（256 条），满了就丢并记一条 ERROR：告警积压说明对端已经堵了很久，这时把内存堆满
比丢几条通知更糟。

单个通道发送失败**只影响它自己**：Webhook 挂了不会让日志通道跟着哑掉，也绝不会向上冒到巡检里。
Webhook 通道还会检查应答的业务错误码——钉钉 / 企业微信 / 飞书在报文被拒时（关键词不匹配、加签
错误、机器人被停用）一律返回 `200` 加一个非零的 `errcode`，只看 HTTP 状态码会把「发出去了但没
送达」当成成功，而这正是告警最不能出现的失效方式。这条检查**只对三家机器人生效**：
「`errcode` 为 0 才算成功」是它们的约定，而 `generic` 发给的是对方自建的网关，
那边返回 `{"code":200}` 完全可能就是成功，套用这条规则只会造出假警报。

### 查看当前告警

```console
$ curl -s localhost:8080/api/alerts
{"enabled":true,"failureThreshold":2,"repeatInterval":"PT0S","onlyCritical":false,
 "channels":["log"],
 "firing":[{"applicationId":"blog-admin","applicationName":"博客 管理端","group":"博客",
            "critical":false,"state":"DOWN","since":"2026-08-13T02:00:00Z",
            "lastNotifiedAt":"2026-08-13T02:00:30Z","notifications":1}]}
```

`firing` 只列**已经通知出去、还没恢复**的故障，和看板上的红点会不一致，而那种不一致恰恰是要看的：
目标已经红了却不在这里，说明它还在抖动窗口内；在这里却已经绿了，说明恢复通知还没发出去。

告警状态与巡检结果一样只放在内存里。平台重启后所有目标都会重新走一遍「连续 N 次才告警」，
最多把重启期间就已存在的故障重报一次——这比把告警状态持久化再考虑一致性划算得多。

## 看板

页面用 Thymeleaf 服务端渲染，JavaScript 只做三件事：定时拉取 `/fragments/dashboard` 片段替换 DOM、
客户端搜索与状态筛选、深浅色主题切换。视图逻辑只有服务端这一份，不需要在前端再写一遍。

- 顶部汇总：整体状态、目标总数、各状态计数
- 按分组展示卡片：类型标签、状态灯、响应耗时、HTTP 状态码、可用率、最近检查时间
- 每张卡片带一条历史趋势条（最近 N 次巡检结果），鼠标悬停可看单次明细
- 子组件（`db`、`memory`、`shards` 等）可展开查看
- 「查看详情」打开弹窗，展示完整统计、子组件明细、**原始请求与应答**、最近一次失败现场与全部历史采样
- 支持按名称 / 分组 / 地址 / 标签 / 类型搜索，按状态与类型两个维度筛选
- 「立刻巡检」按钮触发一轮全量巡检；每张卡片也可单独重新检查
- 「下载报告」「预览报告」直通 `/api/report.md`
- 自动跟随系统深浅色，也可手动切换

详情弹窗的内容同样由服务端渲染（`templates/detail.html`），前端只负责把片段塞进 `<dialog>`。
原始应答里可能有任何字符，交给 Thymeleaf 转义比在 JavaScript 里手工拼 DOM 安全得多——
模板里一律用 `th:text`，没有一处 `th:utext`。定时刷新不会关掉已打开的弹窗，弹窗内容也不自动刷新，
免得排障时看的现场在眼前被换掉。

## 实现要点

- 巡检使用 **虚拟线程**（Java 21）并发执行，用信号量限制同时在途的探测数
- 同一时刻只允许一轮全量巡检，重复触发会被合并
- 巡检结果只保存在进程内存里，不做持久化——重启后重新巡检即可恢复
- 每个被监控目标有独立的 `RestClient`，超时、请求头、认证按目标配置隔离
- 配置在启动时解析并校验：非法地址、不支持的请求方法会直接让应用启动失败，而不是等到巡检时才暴露
- 每种目标类型对应一个 `HealthProbe` 实现，由 `HealthProbeDispatcher` 按类型分派。
  新增一种被监控目标就是新增一个 `@Component`，别处不做 if-else 分发；装配期会检查
  「配置里用到的类型都有探针」，漏装一个组件在启动时就失败
- Redis 探针手写 RESP，不引 Jedis / Lettuce：为一次 `PING` 拉进连接池与 Netty 不划算，
  而且客户端会把应答解析成对象，反而拿不到要留进明细的原文
- MySQL 探针的 JDBC 调用跑在一个专用的小线程池上并带硬截止时间。
  调用线程是虚拟线程，而 JDK 21 上 Connector/J 内部大量 `synchronized` 会**钉住载体线程**，
  一台库不可达时会连累同一轮里其它目标的探测；挪到平台线程上，慢的代价就只由这一条目标承担
- 告警的判据是 `HealthStatusStore.record()` 返回的状态变化，而不是「写完再读快照做比较」：
  单个目标的手动重检不走全量巡检那把锁，同一个 id 可能被两个线程同时走到，分成两步就会
  漏判或重复判——而告警只认状态变化。变化由存储层在自己的锁里算出，天然没有这个缝隙
- 只引 `com.mysql:mysql-connector-j`（`runtime` 作用域），不引 `spring-boot-starter-jdbc`。
  少了 `spring-jdbc`，`DataSourceAutoConfiguration` 的条件不成立，Spring Boot 不会为本平台
  自动装配任何数据源，驱动只是探针手里的一个工具

## 开发

```bash
./mvnw test          # 运行测试
./mvnw spring-boot:run
```

测试用 JDK 自带的 `HttpServer` 模拟被监控应用（见 `StubHealthServer`），覆盖 200/503/204、
非 JSON 响应、404、连接被拒、读超时、Elasticsearch 集群颜色等场景，不依赖外部网络。

Redis 探针对着 `StubRedisServer` 跑——那是一个真的说 RESP 协议的假 Redis，因此覆盖的是完整链路
（编码请求、解析应答、解析 `INFO`、判定降级、口令脱敏），不是对着 mock 断言。
MySQL 没有可用的真实实例，覆盖的是不依赖对端的部分：JDBC 地址拼装与连不上时的失败路径。

告警分三组用例。`AlertServiceTests` 测状态机，探测结果真的写进 `HealthStatusStore` 再拿它算出的
变化去驱动——「连续失败几次」本身就是判据，手写一个假的 `Transition` 等于把要测的东西先假设成对的；
发送侧换成同步实现，好让每条断言都是「这一次探测之后应不应该有通知」，而不是等待与超时的赌局。
`AlertDispatcherTests` 单测异步投递：一个抛异常的通道不能连累排在它后面的通道。
`WebhookAlertNotifierTests` 对着一个真的 HTTP 服务发，验的是序列化之后在线上的那一串到底长什么样、
三家机器人各自的报文结构，以及对端返回 500 / 连不上 / `200 + errcode=310000` 时是不是真的不抛异常。

`ConfigurationFilesConsistencyTests` 只读四份 `.properties`、不发任何网络请求（也不会启动上下文去
连生产地址），断言六件事：主配置自带整份基线、主配置与 prod 档逐条一致、local 档与 dev 档逐条一致、
每份文件的下标连续且能被 `ApplicationRegistry` 真正解析出来、每份都覆盖了数据库与中间件且凭据与证书
开关配齐、按真实 ConfigData 加载顺序装配环境后各档覆盖语义符合预期（prod 得到基线地址、dev 得到开发地址）。
改巡检目标时它是唯一会拦住「只改了一个文件」的关卡。

`LoggingConfigurationTests` 同样只读 classpath 上的资源、不启动上下文、不写任何日志文件，
断言五件事：dev / local / prod 三档都把 `logging.config` 指向落文件的 logback 配置且落在
`/opt/bgssai/log`、test 档留在控制台、滚动文件名由 `spring.application.name` 拼出且与
`bgssai-logs` 仓 inventory 登记的一致、两份 logback 配置的 pattern 逐字一致且带 `traceId`、
stdout 档的 `<logger>` 指向本仓包名。改日志配置时它是拦住「配置文件改了但没人读」的关卡。

## 日志

与产品线其余 9 个仓（18 个后端）同一套口径，没有本平台专属的写法：

| 档 | `logging.config` | 落点 |
| --- | --- | --- |
| `dev` / `local` / `prod` | `log/logback-spring_file.xml` | `/opt/bgssai/log/bgssai-healthcheck_unstrct.log` + 控制台 |
| `test` | `log/logback-spring_stdout.xml` | 只有控制台 |

滚动策略与其它仓一致：按天 + 单文件 500MB，保留 30 天、总量上限 128GB，归档为 `.zip`。

**`logging.config` 这一行是整套日志的总开关**。`src/main/resources/log/` 下那两份 logback 配置，
只有在某档 `.properties` 写了 `logging.config` 指向它时才会被读；漏掉那一行不会有任何编译期或
启动期报错——应用照常起来、控制台照常有日志，只是文件里空空如也，
[`bgssai-logs`](https://github.com/liuliuzo/bgssai-logs) 那边采集到的永远是一个不存在的路径。
`LoggingConfigurationTests` 就是把这种静默失效变成构建失败的关卡，同时钉住文件名、pattern 与
`<logger>` 的包名。

日志文件名由 `spring.application.name` 拼出（`<应用名>_unstrct.log`），与 `logging.applog.path`
一起构成 `bgssai-logs` 仓 `inventory/<env>.tsv` 里本端那行登记的采集路径——**改这三处任意一个，
都要同步改那边**，否则 Collect logs 会拉到空。

每行日志带 `traceId`（`RequestTraceFilter` 在请求入口注入 MDC，Standards §6.1.7），业务代码
不得再手工把 traceId 拼进日志文案。本平台无登录、无租户，因此不带其它仓的 `userId` /
`companyCode` 两个 MDC 位。要注意本平台的日志有两类来源，只有前一类有 traceId：

- **请求线程**（看板页、`/api/**`、`/actuator/**`）——每行都带 traceId，可按 traceId 把一次
  页面刷新涉及的日志串起来；traceId 同时回写到响应头 `X-Trace-Id`，截到一次异常凭响应头即可
  定位日志行，不必按时间戳翻。
- **后台巡检线程**（`HealthCheckScheduler` 定时轮与探针的并发子任务，以及告警的 `healthcheck-alert`
  发送线程）——不经过任何请求，MDC 为空，该位渲染为空串。这是有意为之：巡检日志本就按目标 id
  检索，给巡检线程编一个假 traceId 只会让「有 traceId 就代表有对应请求」这个排障前提失真。

告警行用一个固定的 logger 名 `com.bgssai.healthcheck.ALERT`，与巡检的调试日志分开，采集侧与运维
可以直接按它筛；每条告警**只占一行**（对端应答里的换行会被压平），因为它最常见的用法是事后
`grep 告警` 拉出一段时间内的全部故障，多行会把每条记录拆散。恢复用 INFO，其余用 WARN——
不用 ERROR：ERROR 在本产品线的口径里表示「本平台自己出错了」，而被监控方挂掉恰恰说明本平台
在正常工作。人读的多行版本留给聊天机器人。

## 部署

Jenkins 主通道：`bgssai-healthcheck deploy(dev|prod)` / `stop(dev|prod)`。单实例、无
user/admin；目标机 `123.60.68.201`（dev/prod 同机）。细节见 [`deploy/README.md`](deploy/README.md)。

日志采集与清理走 [`bgssai-logs`](https://github.com/liuliuzo/bgssai-logs) 的
Collect logs / Clear logs / Check app status 三条同名 Job，本端在该仓
`inventory/dev.tsv` 与 `inventory/prod.tsv` 里登记为 `healthcheck`（两档同机 `123.60.68.201`，
`8080` / `http`）。

## 项目结构

```
src/main/java/com/bgssai/healthcheck/
├── HealthCheckApplication.java
├── config/HealthCheckProperties.java        # 巡检的全部配置项
├── alert/                                   # 告警：状态变化 → 通知
│   ├── AlertProperties.java                 # 告警的全部配置项（自成一体，不并进上面那份）
│   ├── AlertService.java                    # 状态机：什么时候该响、什么时候必须闭嘴
│   ├── AlertEvent.java                      # 一条告警的不可变快照，自带人读的正文
│   ├── AlertDispatcher.java                 # 专用线程 + 有界队列，逐通道投递
│   ├── AlertNotifier.java                   # 通道接口，每个通道一个实现
│   ├── LoggingAlertNotifier.java            # 单行、可 grep，零配置即生效
│   └── WebhookAlertNotifier.java            # generic / wecom / dingtalk / feishu
├── domain/                                  # 状态枚举、目标类型与对外数据结构
│   ├── TargetType.java                      # HTTP / ELASTICSEARCH / REDIS / MYSQL / TCP
│   ├── ProbeDetail.java                     # 原始请求与应答
│   └── ...
├── service/
│   ├── ApplicationRegistry.java             # 配置解析与校验
│   ├── HealthProbe.java                     # 探针接口，每种目标类型一个实现
│   ├── HealthProbeDispatcher.java           # 按类型分派 + 统一裁剪明细
│   ├── HttpHealthProbe.java                 # HTTP 与 Elasticsearch
│   ├── RedisHealthProbe.java                # RESP：AUTH / SELECT / PING / INFO
│   ├── MysqlHealthProbe.java                # JDBC：SELECT 1 + 库清单核对
│   ├── TcpHealthProbe.java                  # 只验证端口可连通
│   ├── ProbeSecrets.java                    # 明细里的凭据脱敏
│   ├── HealthStatusStore.java               # 内存中的结果、最近一次失败、历史与统计
│   ├── HealthCheckService.java              # 并发编排与查询视图
│   ├── HealthReportService.java             # Markdown 报告渲染
│   ├── HealthCheckScheduler.java            # 定时触发
│   └── MonitoredApplicationsHealthIndicator.java
├── filter/RequestTraceFilter.java           # traceId 注入 MDC（Standards §6.1.7）
└── web/
    ├── HealthApiController.java             # REST 接口
    ├── HealthReportController.java          # GET /api/report.md
    ├── DashboardController.java             # 看板页面与片段
    └── ViewFormatter.java                   # 页面格式化

src/main/resources/
├── application.properties          # 巡检行为阈值 + 基线 25 个巡检目标（= 生产，不指定 profile 时生效）
├── application-prod.properties     # 生产 25 个巡检目标（与主配置逐条一致）
├── application-dev.properties      # 开发 22 个巡检目标
├── application-local.properties    # 本机启动，目标同 dev
├── log/logback-spring_file.xml     # dev / local / prod：落 /opt/bgssai/log + 控制台
├── log/logback-spring_stdout.xml   # test：只打控制台
├── templates/{index.html, detail.html}
└── static/{css/app.css, js/app.js, favicon.png, favicon.ico, apple-touch-icon.png, brand/bgss-mark-tile.png}
```
