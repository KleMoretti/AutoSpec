---
date: 2026-10-02
task: SIG-P1-03 step 1
status: partial_complete
commit: a1b2187
---

# P1-03 L2 schema isolation and cleanup

本记录只覆盖总计划第 9 节第 1 项，不关闭整个 SIG-P1-03。实现与测试提交为 `a1b2187`（`fix: isolate verifier MySQL schemas`）。

## 本次变更

- `agent-engine/spec_verifier/sandbox.py` 将 DSN 数据库只作为 bootstrap 数据库；每次验证创建 `autospec_verify_<24 位小写 hex>` 随机 schema，执行 DDL 后在 `finally` 中切回 bootstrap 数据库并 `DROP DATABASE`。
- 删除整库替代按表逆序删除，避免外键依赖顺序导致残留；清理失败返回 `L2_DATABASE_CLEANUP_FAILED`，不再吞掉异常。
- `agent-engine/spec_verifier/mysql-init/10-verifier-schema-grant.sql` 为字面 `autospec_verify_` 前缀增加最小初始化授权；Compose 只读挂载该脚本。
- verifier 镜像固定加入 PyMySQL 的 `caching_sha2_password` 所需 `cryptography==44.0.2`。

## 已执行证据

- `D:\miniconda3\envs\CrewAI_Study\python.exe -m pytest -q agent-engine/tests/test_spec_verifier.py`：6 passed。
- `docker compose --profile verification config --quiet`：通过。
- 使用新构建的 verifier 镜像和无持久卷临时 MySQL（仅加入 `metagpt_verification_internal`）验证：校园交易与请假审批规格各连续两次 `_verify_mysql` 均返回空 issues；两条并发验证均返回空 issues。
- 部分 DDL 故障返回 `L2_DATABASE_SCHEMA_FAILED`；随后查询 `INFORMATION_SCHEMA.SCHEMATA` 的 `autospec_verify_%` 前缀无残留。
- 使用初始化授权脚本启动临时 MySQL 后，`verify` 账号可创建/删除随机前缀 schema；连续验证后同样无残留。临时容器已移除，未删除现有业务或验证数据卷。
- fake-driver 高风险回归覆盖：随机 schema/成对 DROP、部分失败仍清理、DROP 异常返回 `L2_DATABASE_CLEANUP_FAILED`。

## 未关闭项

- 现有持久化 `verify-mysql` 卷使用的历史凭据不在当前 `.env` 中；本轮没有猜测凭据、重置密码或执行 `down -v`。初始化脚本只会在新 MySQL 数据目录初始化时执行，现有卷的启动凭据/授权由后续 P0-03/P1-03 部署任务处理。
- verifier 的只读文件系统、capability/PID/内存/网络出口探针、超时进程回收和 verification CI 尚未完成。
- P1-E/P1-03 的全部三 fixture、完整 L2 失败分类及正式六节点 FULL/L2 仍未关闭。

## 下一步

按总计划第 9 节进入第 2 项 SIG-P1-00：把显式 PK/FK、参数位置和前端请求/响应消费映射接入两条生产调用路径，并隔离旧推断适配器。
