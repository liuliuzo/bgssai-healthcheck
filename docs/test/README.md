# bgssai-healthcheck 功能测试

本目录存放本项目的功能测试案例和测试进度。

| 文件 | 职责 |
| --- | --- |
| [PROGRESS.md](./PROGRESS.md) | 测试进度。按功能汇总待测、通过、失败、阻塞、不适用 |
| `TC-*.md` | 功能测试案例。一条功能一份，按验收标准展开 |
| [功能测试模板](https://github.com/liuliuzo/bgssai-skeleton/blob/develop/docs/test/TEMPLATE.md) | 新案例模板 |

## 记录方式

1. 复制 [功能测试模板](https://github.com/liuliuzo/bgssai-skeleton/blob/develop/docs/test/TEMPLATE.md)，保存为 `TC-<功能>.md`。
2. 执行后把每条用例的「结果」写成待测、通过、失败、阻塞或不适用。
3. 同一天把汇总写回 [PROGRESS.md](./PROGRESS.md)。
4. 案例里不写口令、Token、密钥或客户隐私。

`docs/review` 继续放评审和检查结论。功能测试案例和测试进度放在本目录。

<!-- OPC-20261017 START -->
## 2026-10-17 规划补充与功能用例

[需求](../feature/opc-requirements-20261017.md) · [逐需求矩阵](TRACEABILITY-20261017.md) · [存量覆盖索引](REQUIREMENT-COVERAGE.md)

本轮新增2条可验收需求、4条用例；均为待测。历史测试不因本次补充改变结果。

- [TC-opc-product-20261017.md](TC-opc-product-20261017.md)
<!-- OPC-20261017 END -->

<!-- OPC-VALIDATION-20261017 START -->
## 覆盖与统计校验

机器可读清单：[functional-manifest-20261017.json](functional-manifest-20261017.json)。管理仓 `scripts/validate-functional-docs.py --repo <本仓路径>` 检查需求/用例ID、正反例、状态、链接、覆盖及历史套件指纹；实际运行测试证据另行填写。
<!-- OPC-VALIDATION-20261017 END -->
