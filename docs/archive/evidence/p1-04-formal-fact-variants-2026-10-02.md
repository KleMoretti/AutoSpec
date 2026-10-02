# P1-04 正式 verification fact 变体负例

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
隔离 Compose project：`autospec-formal`  

## 样本与方法

- 继续使用隔离项目 2/run 2；历史 EVALUATION_REPORT `id=11/version=1` 保留为原始成功报告。
- 每个变体都通过 `PUT /api/projects/2/artifacts/{latestEvaluationArtifactId}` 创建新的 EVALUATION_REPORT 版本，保留 `gate_status=PASSED` 和 `blocking_issue_count=0`，只修改一个 verification fact 字段。
- 每个变体随后都调用真实 `GET /api/projects/2/delivery-readiness` 和 `POST /api/projects/2/export?format=MARKDOWN`。
- 使用 fixture 隔离环境；不调用 DeepSeek，不产生外部模型费用。

## 结果

| 变体 | 新 Artifact | readiness | blocker | export |
| --- | --- | --- | --- | --- |
| 过期 | `id=26/version=3`，`PENDING_REVIEW` | `SPEC_BLOCKED`，`specReady=false`，`buildReady=false` | `Trusted verification evidence is missing, expired, or below the required level` | HTTP `422` |
| 低于要求层级 | `id=27/version=4`，`PENDING_REVIEW` | `SPEC_BLOCKED`，`specReady=false`，`buildReady=false` | `Trusted verification evidence is missing, expired, or below the required level` | HTTP `422` |
| 错误 scope | `id=28/version=5`，`PENDING_REVIEW` | `SPEC_BLOCKED`，`specReady=false`，`buildReady=false` | `Trusted verification evidence does not match the frozen verification policy` | HTTP `422` |

具体变异为：

- 过期：`expires_at_epoch_ms=1`。
- 低于要求层级：`achieved_level=L1`，而冻结策略要求 `L2`。
- 错误 scope：`scope=UNFROZEN_SCOPE`，不匹配任何本次执行策略。

## 结论与边界

- 直接导出接口不会把 `PASSED` 报告、旧运行或旧生成作业当成可信证据；当前 fact 的过期、层级不足和 scope 不匹配均被 DeliveryGate 拒绝。
- 本记录与 [`p1-04-formal-missing-fact-2026-10-02.md`](p1-04-formal-missing-fact-2026-10-02.md) 合并覆盖缺失、过期、低层级和错 scope 四个正式 fact 负例。
- 来源 digest/policy hash 不匹配和伪造 fact 的正式变体尚未单独执行；不能由本记录替代。
