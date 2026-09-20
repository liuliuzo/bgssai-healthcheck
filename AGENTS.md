# AGENTS.md

## 产品线规划（全线统一）

产品线职责与名称以[骨架仓产品线愿景](https://github.com/liuliuzo/bgssai-skeleton/blob/develop/docs/PRODUCT-LINE-VISION.md)为唯一权威；验收范围见[产品线验收基线](https://github.com/liuliuzo/bgssai-skeleton/blob/develop/docs/feature/product-line-acceptance.md)。本仓现行需求、原型、设计和实现保留在本仓，不再复制整段全线规划。

跨项目版本、未合并 PR 与工作副本状态统一查阅[项目管理仓](https://github.com/liuliuzo/bgssai-project-management)。规划、代码实现、PR 合并、部署和运行验收分别记录；历史报告保留原日期与源码基线，不能作为当前全部通过的证明。

本地参考代码位于工作区 `reference-github/`，参考原型位于 `reference-prototypes/`；参考版本只作对照，不覆盖产品代码。dev / prod 均部署 `develop`，分别使用 `application-dev.properties` / `application-prod.properties`。


**本仓位置**：巡检仓，非业务工具。

> 本文件与 `CLAUDE.md` 内容保持一致（供不同 AI 工具各自读取），改一处必须同步改另一处。

## 仓库定位

本仓库是 BGSSAI 产品线的**健康巡检平台**（单实例，无用户端 / 管理端拆分）。巡检各应用健康接口并直连中间件，用服务端渲染看板集中展示。不承载产品业务功能。

## 强制约定

* 提交信息遵循 Conventional Commits。
* **部署通道（强制）**：部署只走 Jenkins（本仓 `Jenkinsfile` / `jenkins/Jenkinsfile.stop`，仅手动触发）。不得用 GitHub Actions 做部署。部署失败不得自动重部署。
* **AI 成本红线**：不得创建 PR 后会持续唤醒 AI 的 heartbeat / automation / 后台轮询；PR 状态仅在当前会话单次查询或用户下次交互时再查。
* 一切产出物（代码 / 文档 / 提交信息 / PR 描述 / 评审与回信）**全局禁用 emoji 及装饰性图形符号**；语义性符号（→、×、§ 等）不属装饰性符号，允许使用。
* 跨仓通用规范以骨架仓 `bgssai-skeleton` 的 `docs/BGSSAI-Standards.md` 为准；本仓不另立第二份规范副本。
* 中间件连接参数只写各环境 `application-*.properties` 字面量，禁止 `${}` 占位符。

## Git 分支（Git Flow，强制）

本产品线严格遵循 Git Flow。GitHub 默认分支一律是 **`develop`**。

| 分支 | 用途 | 发布环境 |
| --- | --- | --- |
| `feature/*` | Agent / 开发者的工作分支 | 不直接发布 |
| `develop` | 集成分支（默认分支） | 开发环境、生产环境 |
| `release` | 测试冻结 | 测试环境 |
| `master` | 生产冻结（晋升保留） | 当前不作为部署源 |

- **AI Agent 必须先创建自己的 feature 分支再改文件**；禁止直接在 `develop` / `release` / `master` / `main` 上改。
- **多 Agent 并行：各自开独立工作目录（git worktree）**。grok-build、Claude Code、codex、cursor、gemini 会同时在同一个工作区（`Desktop/github`）上干活，而主工作区的工作树与 HEAD 是共用的——别人 `checkout` 一次就把你的 HEAD 带走，你的提交会落到别人的分支上。**接到任务先在仓库目录之外建一个独立 worktree**（形如 `github/.<工具名>-worktrees/<任务名>/<仓名>`），在里面开自己的 `feature/*` 分支，各管各的；**合并成功后把这个工作文件夹删掉**（`git worktree remove`）。实在要在主工作区改，每次写文件前先 `git branch --show-current` 确认自己还在自己的分支上。
- **别人的活不要碰**。grok-build、Claude Code、codex、cursor、gemini 各自的分支、worktree 和未合并的改动，不是自己的就不要去管——不要替别人提交、合并、改分支或删目录，**除非用户明确下达命令**。看到别人留下的半成品，报告即可，不要顺手处理。
- Feature 合入 **`develop`**（先开 PR）。`develop` → `release`、`release` → `master` 的晋升同样先开 PR。
- **部署源码（强制，现阶段）**：dev 开发环境与 prod 生产环境都部署 **`develop`**，只换 `application-dev.properties` / `application-prod.properties`（及 `sql/<env>/`）；测试环境仍发布 **`release`**。原因：当前版本不稳定，只走 `develop` 才能快速修问题并上线。禁止把生产部署或建库指到 `master`。待版本稳定后再恢复 prod 部署 `master`。
- 用户明确同意合并或直接要求合并时，可以执行指定 PR 的合并，无需再次询问。
- 用户未明确同意且未提出合并要求时，不得合并、开启 auto-merge，或直接推送到 `develop`、`release`、`master`、`main`、`Master` 等受保护分支。
- 合并前必须确认仓库、源分支、目标分支和待合并 commit；授权仅限用户指定的 PR 或分支，不得扩展到其他 PR 或分支。
- 本地 commit、远端工作分支 push、GitHub PR 创建和分支合并是四个不同状态，不得混淆或省略。
- 创建或更新 PR 后，对用户只说整体结果（已开 PR 等确认，或已合入 develop），不要列出 SHA、文件清单或检查详情。
- PR 状态查询遵守 **AI 成本红线**：不创建 PR 后持续唤醒的后台监控；在用户下一次交互开始时查询 PR 最新状态即可。

## 端口约定（强制）

所有 web 项目的 **dev / test / prod 三个环境，服务端口一律 8080**，不按环境换端口。

换端口的代价不在改配置本身：部署脚本、反向代理、健康检查、前端开发代理各要记一套，
而这几处记错都不会在开发时暴露，要等上线才发现。统一成一个值，这类错就不存在。

本机要同时跑两个服务而必须错开时，用启动参数临时改
（`--server.port=8081`），不要写进 `application-<profile>.properties`。

## 向用户汇报（强制）

用户不看、也看不懂修改细节。对用户**只汇报整体进度与结果**（做到哪一步、是否完成、要不要拍板）。执行过程中也不要在对话里复述改了什么，不要逐步播报文件或 diff。禁止输出文件清单、diff、命令日志、commit SHA、逐仓 PR 表、工具过程。需要时最多给一个链接。

