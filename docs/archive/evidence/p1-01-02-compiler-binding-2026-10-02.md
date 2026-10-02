# P1-01/02 编译器与绑定消费证据（2026-10-02）

## 范围

本记录覆盖显式 `spec-contract-v2` 的 L1 编译、生成物结构检查和独立前端消费映射，不代表隔离 L2、部署硬限或正式候选晋级已完成。

## 实现

- 提交 `1023024`：v2 编译器生成 path/query/body 类型化 `client.ts`；path 使用受控编码，query 使用 `URLSearchParams`，body 使用结构化 JSON。
- `bindings.ts` 从独立 Frontend binding 声明 import client API，按自己的参数 source 映射调用，并按自己的响应字段映射返回消费结果；没有由 API 自动生成绑定真值。
- L1 新增 OpenAPI 3.1 JSON/operation/参数/请求体/响应结构检查，以及生成 DDL 的表、列、PK、FK、类型、可空性和唯一性解析检查。
- 新增稳定绑定错误码：参数位置/类型/必填性、未消费必填参数、source 冲突、响应字段/类型/可空性和不支持的嵌套响应路径。
- v2 报告和 L2 report 使用 `spec-verifier-v2` / `spec-compiler-v2`；legacy v1 编译输出和报告版本路径保持不变。

## 验证

```text
D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q agent-engine/tests/test_spec_verifier.py agent-engine/tests/test_explicit_contract_adapter.py agent-engine/tests/test_candidate_verification_loop.py agent-engine/tests/test_production_handlers.py
32 passed
```

覆盖内容包括：三个领域的正常规格、相同输入编译结果逐字节一致、path/query/body 生成标记、独立 client import/response consumption，以及参数位置/类型/必填性、响应类型/可空性/字段路径的单边变异稳定失败码。OpenAPI/DDL 解析通过三领域正常生成物。

## 未执行与下一步

- 本轮未把真实 `tsc --noEmit` 结果写成通过证据：本机测试临时目录 ACL 拒绝创建/清理文件；该边界必须在 P1-E 的 verifier sandbox 中执行，不能用 L1 自检替代。
- 本轮未运行真实 MySQL、正式 API/Worker、live 模型或新候选激活。
- 下一步按总计划处理默认 verification 依赖、隔离硬限/探针和 FAILED/ERROR/deadline/Replan Trace；旧 active/published 版本保持不变。
