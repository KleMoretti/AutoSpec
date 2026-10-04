# P2 live smoke 与批量对照状态

日期：2026-10-04（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`

## 真实 `.env` 冷启动

- 独立 Compose project `autospec-realenv-20261003` 使用真实 `.env` 启动成功。
- Backend readiness、Agent health、Frontend HTTP 均通过；容器确认 `AGENT_MODEL_MODE=live`、模型 key、Agent token、verifier token 和 verifier MySQL DSN 已注入。值未回显。
- 本地 `.env` 新增的三个 verifier 配置是本地开发专用值，未提交仓库；默认/业务 Compose 和历史卷未修改。

## 真实 live smoke

- 使用 DeepSeek Flash 真实调用，未使用 fixture 回退。
- 多次显式 v2 smoke 均被 fail-closed：
  - 首次实际成本 `0.156308 CNY`，Backend 预算预占失败、Frontend 触发 `MODEL_OUTPUT_LIMIT`；
  - 后续版本暴露 live Backend loop 的非 JSON/validation 问题，以及 Frontend explicit type/source 输出不符合 Schema；
  - 最后一次 live run 实际成本 `0.212290 CNY`，仍以真实结构化输出错误终止。
- 没有把失败样本标成自我修复成功，也没有继续盲目重试扩大费用。

## P2 explicit v2 冻结

- 新 explicit v2 A/B/C/D 合约与 live development/holdout manifest 已冻结在 `agent-engine/evaluation/configs/p2-p3-20261003-explicit-v2/`。
- 治理版本 4–7 在隔离库中 validate/publish；两个 manifest 的 `--validate-only` 均通过，未创建 live project/run。
- 完整 development/holdout 批次保守上限为 `384 + 192 = 576 CNY`，当前没有新的本批次预算确认；因此 P2-E 保持 `blocked/NOT_EVALUATED`，不填写质量收益或置信区间。

## 结论

- 真实 `.env` 冷启动已完成；远端 Sandbox CI、fixture 跨节点返工和本地 L2/Replan 已完成。
- DeepSeek live provider 已真实调用并留下失败台账，但 live 自我修复和 P2 A/B/C/D 质量对照尚未完成；原因分别是 live structured-output/validation 仍需继续修复，以及批量预算/启动授权尚未确认。
