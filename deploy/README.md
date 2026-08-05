# 部署（Jenkins）

单实例健康巡检平台：无 user/admin、无前端。构建 fat jar 后推到目标机，重启服务 → 健康检查 →
失败自动回滚。`deploy/scripts/ship.sh` 与 `deploy/remote-deploy.sh` 须与中央仓
`bgssai-workflows` 的 `deploy/` **逐字节一致**。

| 通道 | 状态 | 入口 |
| --- | --- | --- |
| **Jenkins** | 主用 | `bgssai` 文件夹 → `bgssai-healthcheck deploy(dev\|prod)` / `stop(dev\|prod)` → Build |
| GitHub Actions | 未接入 | — |

**环境由 Job 名决定**：名字末尾 `(dev)` / `(prod)`。点 Build 即执行，无参数。

搭建、凭据、Job 清单与排障见
[`bgssai-workflows/jenkins/README.md`](https://github.com/liuliuzo/bgssai-workflows/blob/develop/jenkins/README.md)。

## 目标映射

| 项 | 值 |
| --- | --- |
| 应用名 / 服务名 / 目录 | `bgssai-healthcheck` / `/opt/bgssai/bgssai-healthcheck` |
| 监听与健康检查 | `8080` / `http` / `/actuator/health` |
| 目标主机 | `123.60.68.201`（境内；私有 `172.31.6.116`；dev 与 prod 同机） |
| Spring profile | 由流水线注入 `--spring.profiles.active=<env>` |

主机清单键（Jenkins 凭据 `bgssai-<env>-hosts`）：`HEALTHCHECK_HOST` 及可选
`HEALTHCHECK_PORT` / `_HEALTH_SCHEME` / `_HEALTH_PATH`（缺省已在中央仓模板写好）。

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
