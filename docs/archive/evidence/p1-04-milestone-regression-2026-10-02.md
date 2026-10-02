# P1-04 里程碑收尾回归

日期：2026-10-02  
分支：`codex/p0-p1-complete-20260930`  
最新代码提交：`5771150`  

## 三端回归

| 模块 | 命令 | 结果 |
| --- | --- | --- |
| Agent Engine | `D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q` | `254 passed in 31.47s` |
| Backend | `D:\apache-maven-3.8.9\bin\mvn.cmd -q test` | 70 个 Surefire suite、186 tests，0 failures、0 errors、0 skipped |
| Frontend tests | `npm test -- --run` | 10 files、29 tests passed |
| Frontend build | `npm run build` | `tsc -b` 与 Vite production build 成功 |

第一次前端测试在受限执行环境中受到 esbuild `spawn EPERM`，按授权提升本机执行后通过；没有因此修改前端代码。

## 契约与隔离复核

- `scripts/verify_workflow_contract.py`：`AutoSpec workflow contracts are synchronized`。
- `docker compose --env-file .env.example config --quiet`：通过。
- `docker compose --env-file .env.example --profile monitoring config --quiet`：通过。
- `docker compose --env-file .env.example --profile verification config --quiet`：通过。
- 重建后的 `autospec-formal` verifier：只读根、`cap_drop=ALL`、`no-new-privileges`、768 MiB、1 CPU、128 PID、64 MiB `/tmp`、internal network-only 均通过；401/413/422、FULL/L2、临时目录/MySQL 清理和外网阻断均通过。

## 未在本轮宣称完成

- GitHub 远端 Sandbox 专项 job 尚未实际运行；本轮只验证了本地脚本与 CI 配置语法/Compose 配置。
- 真实 `.env` 仍缺 verifier 三项本地配置，未使用占位值冒充真实冷启动；DeepSeek live、P2 A/B/C/D 和 live 自我修复未执行。
- 资源故障主动注入、来源/policy digest 单独变异的修复后正式 API 样本仍未完成；当前 P1-G 已由人工编辑报告来源/状态门禁 fail-closed，并有离线校验覆盖。
