# CLAUDE.md

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

> 本文件与 `AGENTS.md` 内容保持一致（供不同 AI 工具各自读取），改一处必须同步改另一处。

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
| `develop` | 集成分支（默认分支） | 开发环境 |
| `release` | 测试冻结 | 测试环境 |
| `master` | 生产冻结 | 生产环境 |

- **AI Agent 必须先创建自己的 feature 分支再改文件**；禁止直接在 `develop` / `release` / `master` / `main` 上改。
- Feature 合入 **`develop`**（先开 PR）。`develop` → `release`、`release` → `master` 的晋升同样先开 PR。
- 开发环境发布 **`develop`**；测试环境发布 **`release`**；生产环境发布 **`master`**。
- 用户明确同意合并或直接要求合并时，可以执行指定 PR 的合并，无需再次询问。
- 用户未明确同意且未提出合并要求时，不得合并、开启 auto-merge，或直接推送到 `develop`、`release`、`master`、`main`、`Master` 等受保护分支。
- 合并前必须确认仓库、源分支、目标分支和待合并 commit；授权仅限用户指定的 PR 或分支，不得扩展到其他 PR 或分支。
- 本地 commit、远端工作分支 push、GitHub PR 创建和分支合并是四个不同状态，不得混淆或省略。
- 创建或更新 PR 后，对用户只说整体结果（已开 PR 等确认，或已合入 develop），不要列出 SHA、文件清单或检查详情。
- PR 状态查询遵守 **AI 成本红线**：不创建 PR 后持续唤醒的后台监控；在用户下一次交互开始时查询 PR 最新状态即可。

## 向用户汇报（强制）

用户不看、也看不懂修改细节。对用户**只汇报整体进度与结果**（做到哪一步、是否完成、要不要拍板）。禁止输出文件清单、diff、命令日志、commit SHA、逐仓 PR 表、工具过程。需要时最多给一个链接。

