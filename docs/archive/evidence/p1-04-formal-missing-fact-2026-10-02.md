# P1-04 正式缺失 verification fact 交付门禁负例

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
隔离 Compose project：`autospec-formal`  

## 前置条件

- 目标为隔离项目 2/run 2；该 run 已经通过六节点执行、Evaluator 和完整交付链路。
- 编辑前 `GET /api/projects/2/delivery-readiness` 返回 `READY`，`specReady=true`、`buildReady=true`。
- 原 EVALUATION_REPORT 为 `id=11`、`version=1`、`status=GENERATED`，内容包含本次运行的可信 `verification_fact`。
- 使用 `AGENT_MODEL_MODE=fixture` 的隔离环境，不调用 DeepSeek，不产生外部模型费用。

## 正式 API 操作与结果

1. 读取 `GET /api/projects/2/artifacts`，定位 EVALUATION_REPORT `id=11`。
2. 通过 `PUT /api/projects/2/artifacts/11` 提交同一份评估报告，但移除顶层 `verification_fact`，并传入原 `expectedLockVersion=0`。服务创建新版本 `id=25`、`version=2`、`status=PENDING_REVIEW`，没有覆盖历史报告。
3. 编辑后读取 readiness，得到：

```json
{
  "specReady": false,
  "buildReady": false,
  "status": "SPEC_BLOCKED",
  "workflowRunId": 2,
  "codeGenerationJobId": null,
  "blockers": ["Trusted verification evidence is required before delivery"]
}
```

4. 直接调用 `POST /api/projects/2/export?format=MARKDOWN` 返回 HTTP `422`，消息为 `Trusted verification evidence is required before delivery`。

## 结论与边界

- Evaluator 报告即使仍保留 `gate_status=PASSED` 和无阻断问题，缺少与冻结执行策略匹配的可信 verification fact 也不能进入正式交付。
- 该结果通过真实 Artifact 更新、DeliveryGate readiness 和导出 API 获得，不是离线 mock 的结论。
- 本样本覆盖“缺失 fact”；过期、低层级、错误 scope、来源 digest/policy hash 不匹配仍需各自的正式负例或明确记录为未完成。
