# P1 剩余验收边界复核

日期：2026-10-03（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`

## 远端 Sandbox CI

- 旧 head `492a388` 的 quality run `37130062282` 已完成，最终 `success`。
- `backend-tests`、`agent-tests`、`frontend-tests`、`compose-and-images` 和 `verifier-sandbox-probes` 全部 success；Sandbox probe job 已实际在 GitHub runner 执行。
- 最新 head `8003ba4fe371acc85315cad0ddb4c3f87d4aaac8` 的 run `37130433346` 已重新完成并成功；`backend-tests`、`agent-tests`、`frontend-tests`、`compose-and-images` 和 `verifier-sandbox-probes` 全部 success。
- 跨节点返工修复后的最新 head `9d745f7f400db457a761591e85e6d79c1b6d7695` 的 run `37131885506` 也已完成 success；五个 quality job（含 `verifier-sandbox-probes`）全部 success。

## 跨节点责任返工

- 隔离 project 8/run 7 的 Reviewer `REWORK` 路由实际创建了 Backend 新 revision（revision 1/2/3），最终因 `maxReviewRounds=2` 进入 `MANUAL_INTERVENTION`；issue 为 `SHARED_CONTRACT_BACKEND_DRIFT`，说明 Reviewer→Backend 责任边和 round 上限均生效。
- Backend 本节点显式 v2 FAILED→Replan→Verify 成功已由 project 13/run 11 单独覆盖；跨节点返工的“成功收敛”样本仍未完成。
- 既有 `ReworkPlanner`/`ReworkPlanExecutionService` Java 测试覆盖合法/非法返工边和 manual intervention 分支。

## 真实 `.env` 与 live P2

本机真实 `.env` 只做了状态检查，未回显值：

- `MODEL_API_KEY`：placeholder；
- `SPEC_VERIFIER_SERVICE_TOKEN`、`VERIFY_MYSQL_PASSWORD`、`VERIFY_MYSQL_ROOT_PASSWORD`：missing；
- MySQL/Redis/Agent service token 配置存在。

因此不能把 fixture `.env.example` 启动写成真实 `.env` 冷启动，也不能调用 DeepSeek live。P2 冻结矩阵需要额外付费预算，当前未执行批量 live 对照，不生成质量/成本收益结论。
