# P1-04 正式伪造 verification fact 回归

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
隔离 Compose project：`autospec-formal`  
修复提交：`5771150`  

## 缺口复现

- 在修复前，基于隔离项目 2/run 2 的原始成功 EVALUATION_REPORT，使用正式 Artifact API 创建版本 `id=29/version=6`。
- 只把 `verification_fact.workflow_run_id`、`node_run_id` 和 `execution_id` 改成伪造值，保留原 `source_digest`、`policy_hash`、`scope=FULL`、`achieved_level=L2`、`status=PASSED` 和未来过期时间。
- 当时 readiness 错误地返回 `BUILD_REQUIRED` 且 `specReady=true`；直接 `POST /api/projects/2/export?format=MARKDOWN` 返回 HTTP `200`。这确认仅检查 digest/policy 不能阻止人工编辑后的伪造报告。

## 修复与复验

`DeliveryGateService` 现在要求最新 EVALUATION_REPORT 同时满足：

1. `status=GENERATED`；
2. `sourceAgent` 与最新 evaluator node 的 `handlerKey` 相同；
3. 之后才检查报告状态、可信 fact、冻结 policy、Artifact 集合和生成包 manifest。

修复后重建隔离 backend，重新读取同一项目和同一伪造版本，结果为：

```json
{
  "readinessStatus": "SPEC_BLOCKED",
  "specReady": false,
  "buildReady": false,
  "blockers": [
    "A generated evaluation report from the latest evaluator node is required before delivery"
  ],
  "exportHttpStatus": 422
}
```

对应新增单测：`DeliveryGateServiceTest.rejectsHumanEditedEvaluationEvenWhenFactLooksValid`。

## 结论

- 人工编辑、恢复或伪造的评估报告即使复用旧的可信字段，也不能借用历史 Evaluator 事实进入交付。
- 原始伪造样本只用于证明修复前缺口；修复后的正式 API 结果和单测共同证明当前行为已 fail-closed。
- 本次复验仍运行在 fixture 隔离环境，不调用 DeepSeek，不改变默认 active WorkflowSpec 或真实业务数据库。
