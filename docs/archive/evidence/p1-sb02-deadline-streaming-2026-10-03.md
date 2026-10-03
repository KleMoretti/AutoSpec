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
- 同一重建镜像新增主动探针：TypeScript 子进程超时返回 `ERROR/L2_TYPESCRIPT_TIMEOUT`，实际 elapsed `1.045s`；有界子进程创建触发 PID limit 并完成全部回收，`active_pid_limit=true`。

## 尚未关闭

- PID/进程超时主动注入已通过；尚未主动注入内存 OOM（避免杀死 verifier 主进程），也未完成真实 `.env` 冷启动和远端 Sandbox CI。
- MySQL 语句级取消依赖驱动 socket timeout 和剩余时间检查，尚未在真实锁等待/断连场景做专门故障注入。
