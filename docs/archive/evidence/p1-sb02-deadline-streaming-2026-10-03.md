# SB-02 verifier 流式上限与共享 deadline

日期：2026-10-03（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`

## 已实现

- `4ed8585`：`/verify` 先校验专用 Token，再通过 `request.stream()` 累计读取请求体，超过 512 KiB 立即返回 413；`timeout_ms` 上限收紧为 30 秒，拒绝 900 秒任意延长。
- `c47716d`：L2 以 monotonic deadline 贯穿 TypeScript 子进程和 MySQL schema 执行；子进程创建独立进程组，超时后终止并回收；每条 DDL 执行前检查剩余时间并刷新 socket timeout；规格错误/环境错误分类保持 fail-closed。
- `8dbd878`：Java `ToolGatewayService` 将 `deadline_epoch_ms` 传给 `SpecVerificationClient`，HTTP timeout 与冻结 execution deadline、请求 timeout 取最小值，剩余不足时不再发起调用。

## 验证

- verifier service 定向测试：`3 passed`；spec verifier 定向测试：`8 passed`。
- Agent Engine 全量：`262 passed`。
- Backend `mvn test`：退出码 0；正常测试中的故障日志是既有故障恢复测试主动注入，不是失败。
- 重建后的 `scripts/verify_spec_sandbox.py --project autospec-formal`：401/413/422、FULL/L2、tmp/schema 清理、外网阻断和容器 hard-limit 全部通过。

## 尚未关闭

- 尚未主动注入 PID/内存/进程树故障或真实 `.env` 冷启动；远端 Sandbox CI job 未在本轮重新触发。
- MySQL 语句级取消依赖驱动 socket timeout 和剩余时间检查，尚未在真实锁等待/断连场景做专门故障注入。
