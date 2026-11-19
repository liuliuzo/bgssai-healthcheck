# bgssai-healthcheck 归档交接核查 · 2026-11-19

被测源码为 develop 的 `2216b7314b29399e531e9cc67cfacc0f85b7b3b5`。运行环境为 Windows，本地执行范围见下表。

| 用例ID | 关联需求 | 优先级 | 端 | 前置条件与数据 | 测试步骤 | 期望结果 | 结果 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| TC-HEALTHCHECK-20261119-001 | 巡检迁移验收入口 | P1 | 仓库交接 | 本仓与 bgssai-support 的 develop 源码 | 查看本仓根 README 的迁移说明，再在责任仓核对状态路由与对应测试。 | 当前责任仓为 bgssai-support；公开入口为 / 或 /status，内部入口为 /status/internal。实际服务验收在支持仓执行。 | 通过 | [README.md](../../README.md) |

## 复跑与实测记录

```shell
git show HEAD:README.md
```

已核对迁移说明和责任仓的状态测试入口。

责任仓开发基线为 `ccc20d2f742ae52023941cd0f90d8145f79ab365`。服务端验收见 [status.test.ts](https://github.com/liuliuzo/bgssai-support/blob/ccc20d2f742ae52023941cd0f90d8145f79ab365/apps/server/tests/status.test.ts)。
