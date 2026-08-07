# 部署（Jenkins）

单实例健康巡检平台：无 user/admin、无前端。构建 fat jar 后推到目标机，重启服务 → 健康检查 →
失败自动回滚。`deploy/scripts/ship.sh` 与 `deploy/remote-deploy.sh` 须与中央仓
`bgssai-workflows` 的 `deploy/` **逐字节一致**。

| 通道 | 状态 | 入口 |
| --- | --- | --- |
| **Jenkins** | 主用 | `bgssai` 文件夹 → `bgssai-healthcheck deploy(dev\|prod)` / `stop(dev\|prod)` → Build |

**环境由 Job 名决定**：名字末尾 `(dev)` / `(prod)`。点 Build 即执行，无参数。

搭建、凭据、Job 清单与排障见
[`bgssai-workflows/jenkins/README.md`](https://github.com/liuliuzo/bgssai-workflows/blob/develop/jenkins/README.md)。

## 开发环境的部署方式已改为「目标机自建」

dev 与 prod 现在走两条不同的通道：

| | dev | prod |
| --- | --- | --- |
| 源码 | **目标机自己** `git fetch` 本仓 `develop` | Jenkins 控制器克隆 |
| 构建 | **目标机上** `mvn package`（单模块、无前端） | 控制器上 `mvn package` |
| jar 怎么到位 | 就地产出，落 `app.jar.incoming` | `deploy/scripts/ship.sh` 推到 `app.jar.incoming` |
| 跨境流量 | 一条 SSH 控制通道（几十 KiB） | 每端点几十到上百 MiB |
| 之后的步骤 | **完全相同**：`deploy/remote-deploy.sh` 原子替换 → 重启 → 健康检查 → 失败回滚 | 同左 |

**为什么改**：控制器在华为云境外、部分目标机在境内，逐台推 fat jar 是整条部署链上唯一的跨境
大流量环节，也是 2026-08-07 全量部署失败的直接原因（那一轮 dev 挂掉的两个产品都倒在上传上，
与代码毫无关系）。改为目标机自建后，跨境只剩一条 SSH 控制通道，拉源码那段流量由目标机直连
GitHub。**生产不变** —— 生产产物必须来自同一台可控的构建机，否则「线上跑的是哪份编译产物」
就失去了唯一答案。

对本仓意味着什么：

- 本仓 `Jenkinsfile` 顶部新增 `PRODUCT` 映射，是本产品构建口径的**单一声明**，两条通道共用。
  dev 下 `Build` 阶段会显示为 skipped —— 这是对的，控制器在那条通道上不构建。
- `deploy/remote-deploy.sh` 两条通道共用，**逐字节一致的要求因此更硬**：dev 下目标机直接执行
  自己源码树里的这一份，副本一旦漂移，dev 与 prod 就会跑两套部署逻辑。
- `deploy/scripts/ship.sh` 只在 prod 通道上跑。下文关于上传吞吐、rsync 与 `_UPLOAD_*` 旋钮的
  说明只适用于 prod。
- **dev 目标机需要一套构建工具链**（git + JDK 21 + Maven；本产品无前端，Node 与 pnpm 用不到，但装机脚本会一并装齐），
  以及放源码与依赖缓存的磁盘（5 GB 起）与 4 GB 内存。部署流水线**只检测不安装**，缺工具即失败
  并给出指引，远端不会被改动。一次性准备：在目标机上以 root 跑一次中央仓的
  [`jenkins/install/provision-build-host.sh`](https://github.com/liuliuzo/bgssai-workflows/blob/develop/jenkins/install/provision-build-host.sh)。

完整说明（通道对比、按端点旋钮 `<KEY>_SRC_ROOT` / `_BUILD_TIMEOUT_SECONDS` / `_MIN_FREE_MB` /
`_MAVEN_OPTS` / `_NODE_OPTIONS`、排障）见中央仓
[`jenkins/README.md`](https://github.com/liuliuzo/bgssai-workflows/blob/develop/jenkins/README.md)
的「开发环境：目标机自建」一节。

## 目标映射

| 项 | 值 |
| --- | --- |
| 应用名 / 服务名 / 目录 | `bgssai-healthcheck` / `/opt/bgssai/bgssai-healthcheck` |
| 监听与健康检查 | `8080` / `http` / `/actuator/health` |
| 目标主机 | `123.60.68.201`（境内；私有 `172.31.6.116`；dev 与 prod 同机） |
| Spring profile | 由流水线注入 `--spring.profiles.active=<env>` |

主机清单键（Jenkins 凭据 `bgssai-<env>-hosts`）：`HEALTHCHECK_HOST` 及可选
`HEALTHCHECK_PORT` / `_HEALTH_SCHEME` / `_HEALTH_PATH`（缺省已在中央仓模板写好）。

> 上传通道：目标机装了 rsync 时走 `rsync --inplace --partial` 增量续传（只传两次构建之间变化的字节，重试从断点继续），没装则自动回落到 scp 全量传输。跨境端点强烈建议预装 rsync（`apt-get install -y rsync`，yum 系 `yum install -y rsync`）——与 JDK 一样属目标机基线环境，部署过程只检测不安装、不改动目标机软件包。单次尝试按「文件大小 / 吞吐下限」限时，单个文件全部尝试合计另有总预算，超出即失败并保持远端不变；详见中央仓 `jenkins/README.md` 的「跨境上传」一节。

服务不存在时由 `remote-deploy.sh` 自动创建并 enable，无需手工装单元。参考单元见
`deploy/systemd/bgssai-healthcheck.service`（可选加固用）。

**前置：目标机须预装 JDK 21**（应用以 `/usr/bin/java` 启动）。`remote-deploy.sh` **只检测不安装**，
不会改动目标机的软件包；未装 JDK 时会在写单元前直接失败并提示
`sudo apt-get install -y openjdk-21-jre-headless`（yum 系用 `sudo yum install -y java-21-openjdk-headless`），
装好后用单产品 Job 人工重跑。

## 运维注意

- **同机覆盖**：dev / prod 部署同一台机、同一个 systemd 服务；后一次部署会覆盖前一次的
  profile（`--spring.profiles.active`）。巡检目标列表随 profile 切换（见
  `application-dev.properties` / `application-prod.properties`）。
- 部署失败不自动重跑；健康检查失败时脚本已回滚到上一个可用 jar。
- 改主机清单后须重新上传 Jenkins Secret file 凭据 `bgssai-dev-hosts` /
  `bgssai-prod-hosts`。
