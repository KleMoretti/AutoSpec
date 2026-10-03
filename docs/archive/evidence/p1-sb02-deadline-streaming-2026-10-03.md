# SB-02 verifier 流式上限与共享 deadline

日期：2026-10-03（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`

## 已实现

- `4ed8585`：`/verify` 先校验专用 Token，再通过 `request.stream()` 累计读取请求体，超过 512 KiB 立即返回 413；`timeout_ms` 上限收紧为 30 秒，拒绝 900 秒任意延长。
- `c47716d`：L2 以 monotonic deadline 贯穿 TypeScript 子进程和 MySQL schema 执行；子进程创建独立进程组，超时后终止并回收；每条 DDL 执行前检查剩余时间并刷新 socket timeout；规格错误/环境错误分类保持 fail-closed。
- `8dbd878`：Java `ToolGatewayService` 将 `deadline_epoch_ms` 传给 `SpecVerificationClient`，HTTP timeout 与冻结 execution deadline、请求 timeout 取最小值，剩余不足时不再发起调用。
- `c6667f4`：每个验证 MySQL session 在执行 DDL 前设置有界 `innodb_lock_wait_timeout`，并继续在每条语句前检查剩余 monotonic deadline。

## 验证

- verifier service 定向测试：`3 passed`；spec verifier 定向测试：`8 passed`。
- Agent Engine 全量：`262 passed`。
- Backend `mvn test`：退出码 0；正常测试中的故障日志是既有故障恢复测试主动注入，不是失败。
- 重建后的 `scripts/verify_spec_sandbox.py --project autospec-formal`：401/413/422、FULL/L2、tmp/schema 清理、外网阻断和容器 hard-limit 全部通过。
- 同一重建镜像新增主动探针：TypeScript 子进程超时返回 `ERROR/L2_TYPESCRIPT_TIMEOUT`，实际 elapsed `1.045s`；有界子进程创建触发 PID limit 并完成全部回收，`active_pid_limit=true`。
- 另用同一 verifier image 创建一次性 `--rm` 临时容器，施加 `64m` memory、`64` PID、read-only/cap-drop/no-new-privileges 后申请 256 MiB，容器以 exit code `137` 退出；现有 verifier 服务未受影响。

## 尚未关闭

- PID/进程超时、server-side lock wait 和一次性 memory cgroup 注入均有证据；真实 `.env` 冷启动和远端 Sandbox CI 仍未完成。
- MySQL 语句级取消依赖驱动 socket timeout 和剩余时间检查，尚未在真实锁等待/断连场景做专门故障注入。
