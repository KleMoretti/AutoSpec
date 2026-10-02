# P1-04 正式 Artifact 变更后交付门禁负例

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
隔离 Compose project：`autospec-formal`  

## 范围与前置状态

- 只使用 `.env.example` 启动的隔离 MySQL/Redis/Worker/Backend；未读取或修改真实 `.env`、默认业务数据库或默认 active WorkflowSpec。
- `AGENT_MODEL_MODE=fixture`，本次不调用 DeepSeek，不产生外部模型费用。
- 目标为隔离项目 4、run 4。该 run 已通过六节点执行、Evaluator 和 DeliveryGate，且使用正式 API 生成过 ZIP。
- 编辑前 `GET /api/projects/4/delivery-readiness` 返回 `READY`，`specReady=true`、`buildReady=true`。

## 正式 API 操作与结果

1. `POST /api/projects/4/code-skeleton` 返回 `format=ZIP`、`encoding=base64`、文件名 `autospec-project-4-skeleton.zip`；返回内容长度为 10,392。最近一次生成作业为 `id=6`、`status=SUCCEEDED`、`gateStatus=PASSED`。
2. `GET /api/projects/4/artifacts` 找到已批准的 PRD `id=15`、`version=1`、`status=APPROVED`、`lockVersion=1`。
3. 使用 `PUT /api/projects/4/artifacts/15`，提交原内容加一个换行并传 `expectedLockVersion=1`。后端没有覆盖历史版本，而是创建 PRD `id=24`、`version=2`、`status=PENDING_REVIEW`。
4. 编辑后再次读取 readiness：

```json
{
  "specReady": false,
  "buildReady": false,
  "status": "SPEC_BLOCKED",
  "workflowRunId": 4,
  "codeGenerationJobId": null,
  "blockers": ["The PRD must be approved before delivery"]
}
```

5. 直接调用 `POST /api/projects/4/export?format=MARKDOWN` 返回 HTTP `422`，错误为 `INTERNAL_ERROR`，消息为 `The PRD must be approved before delivery`。旧的成功 ZIP 作业没有被当作当前 Artifact 集合的交付授权。

## 结论与边界

- 该正式样本关闭了“Artifact 改动后仍可直接导出”的 P1-G 负例：历史通过的生成作业不能替代当前 Artifact 的审批状态。
- 本样本验证的是“新版本待审”路径，不等同于已经覆盖缺失、过期、错误 scope、低层级或伪造 verification fact；这些仍需各自通过正式交付 API 取得证据。
- 该样本没有切换默认 active 版本，也没有改变正式项目之外的数据库数据。
