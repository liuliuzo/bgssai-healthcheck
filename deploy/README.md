# 部署（Jenkins）

单实例健康巡检平台（无 user/admin 拆分、无独立前端工程）。`deploy/scripts/ship.sh` 与 `deploy/remote-deploy.sh` 必须与中央仓 `bgssai-workflows` 的 `deploy/` 逐字节一致。

| 通道 | 状态 | 入口 |
| --- | --- | --- |
| **Jenkins** | 主用 | `bgssai` 文件夹 → `bgssai-healthcheck deploy(dev\|prod)` / `stop(dev\|prod)` → Build |

环境由 Job 名称后缀 `(dev)` / `(prod)` 决定，无构建参数。搭建、凭据与 Job 清单见 [`bgssai-workflows/jenkins/README.md`](https://github.com/liuliuzo/bgssai-workflows/blob/develop/jenkins/README.md)。

## 构建与部署通道

| 阶段 | `dev` | `prod` |
| --- | --- | --- |
| 源码拉取 | 目标机本地 `git fetch` 本仓 `develop` | Jenkins 控制器克隆 |
| 编译打包 | 目标机本地执行 `mvn package`（控制器 `Build` 阶段跳过） | Jenkins 控制器执行 `mvn package` |
| 产物落位 | 就地产出至 `app.jar.incoming` | `deploy/scripts/ship.sh` 传输至 `app.jar.incoming`（支持 `rsync --inplace --partial`，缺省回退 `scp`） |
| 发布与回滚 | `deploy/remote-deploy.sh` 原子替换 → 重启 → 健康检查 → 失败自动回滚 | 同 `dev` |

- `Jenkinsfile` 顶部的 `PRODUCT` 映射为构建与发布参数的唯一声明。
- `dev` 目标机须预置构建工具链（`git` + `JDK 21` + `Maven`），磁盘剩余不少于 `5 GB`、内存不少于 `4 GB`。装机脚本：[`jenkins/install/provision-build-host.sh`](https://github.com/liuliuzo/bgssai-workflows/blob/develop/jenkins/install/provision-build-host.sh)。

## 目标映射

| 项 | 值 |
| --- | --- |
| 应用名 / 服务名 / 目录 | `bgssai-healthcheck` / `/opt/bgssai/bgssai-healthcheck` |
| 监听与健康检查 | `8080` / `http` / `/actuator/health` |
| 目标主机 | `123.60.68.201`（境内；私网 `172.31.6.116`；`dev` 与 `prod` 同机） |
| Spring profile | 流水线注入 `--spring.profiles.active=<env>` |
| Jenkins 主机凭据键 | `bgssai-<env>-hosts` 中的 `HEALTHCHECK_HOST`（可选 `HEALTHCHECK_PORT` / `_HEALTH_SCHEME` / `_HEALTH_PATH`） |

- systemd 单元不存在时由 `remote-deploy.sh` 自动创建并启用；参考单元文件位于 `deploy/systemd/bgssai-healthcheck.service`。
- 目标机须预装 `JDK 21`（`/usr/bin/java`）；流水线只检测不安装，缺失时直接报错退出。

## 运维规则

- **同机覆盖**：`dev` 与 `prod` 共用同一主机与 systemd 服务，后一次部署覆盖 `--spring.profiles.active`。
- **禁止自动重跑**：部署失败不自动重试；健康检查未通过时由 `remote-deploy.sh` 自动回滚至上一可用 jar。
- **凭据同步**：修改主机清单后须同步更新 Jenkins Secret file 凭据 `bgssai-dev-hosts` / `bgssai-prod-hosts`。
