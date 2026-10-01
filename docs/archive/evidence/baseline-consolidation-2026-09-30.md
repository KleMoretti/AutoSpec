# AutoSpec 数据库基线与冗余收敛记录

日期：2026-09-30。分支：`codex/p0-p1-complete-20260930`。

## 授权与范围

用户要求删除旧迁移版本，以当前结构作为新的数据库 V1，适当保留最近几次数据，精简重复测试和中间候选；并明确同意终止 run 5、32、33、35 的人工介入状态。此次是一次本地基线重置，不是可直接应用到任何旧环境的增量迁移。

未提交、未 push、未调用付费模型。原先未跟踪的根目录 package 文件保持原样。

## 代码变化

- 117 个旧 Flyway 文件收敛为 `V1__autospec_baseline.sql`，直接创建当前 31 张业务表、索引和外键，不再重放旧路线图、旧表和逐次实验种子。后续数据库变更从 V2 开始。
- 新安装只初始化 `autospec-v5:pm-schema-repair-v12`；数据库 V1 不表示重命名工作流、Prompt、Schema 或 Handler 版本。
- 移除中间 PM 候选 v1–v11 和未再使用的 spec-repair JSON。必要的旧协议 JSON 继续作为兼容测试输入；不再要求将这些测试输入全部写入数据库。
- 契约检查统一遍历当前保留的 JSON，并核对当前候选与数据库种子，以及每个节点的 Handler、Prompt 和 Schema 哈希。
- PM 候选测试集中验证最终配置；Java 不再为每个中间候选重复断言。SchemaInitSqlTest 改为一次创建与重复启动、当前表/关键列/唯一约束/索引/唯一当前种子检查，删除旧路线图文案断言。
- 历史工作流生命周期测试在自身事务中显式装入兼容快照，避免生产数据库为测试保留旧默认工作流。
- 权限、预算、deadline、幂等、恢复、交付门禁等行为测试保留。既有单次结构修复与主键推断适配没有删除；后者涉及旧 Artifact 缺少显式主外键字段，需要独立的输出契约调整，不应通过减少校验实现“精简”。

## 数据保留与恢复

1. 完整旧库备份保存在本地忽略路径 `target/baseline-reset/before-v1.sql`，不进入 Git。SHA-256：`c6648a2744ed73a5236cb91e786a227e8b87491028b568463b60c39db207e75b`。备份可能含本地敏感数据，不得公开上传。
2. 在独立 MySQL 数据库实际恢复该备份，确认原迁移 117 条、最新运行 34–38 完整，再预演清理。
3. 正式执行时短暂停止后端与两个 Worker，确认无新运行，按外键依赖顺序在事务中精简数据；外键检查始终开启。
4. 保留项目 32–35、运行 34–38、36 个 Artifact，以及相关节点、审批、追踪、模型/工具/步骤台账、知识数据、导出和冻结执行包。Artifact 内容 SHA-256 在清理前后逐个一致。
5. run 34 为 FAILED；run 35 从 MANUAL_INTERVENTION 转为 CANCELLED，保留取消迁移事件；run 36–38 仍为 COMPLETED。run 5、32、33 先终止，再连同其他旧运行从在线库移除。
6. 在线库共删除 33 条旧运行、31 个旧项目、135 个旧 Artifact 及其关联行。删除四张已废弃且为空的表：agent_task、agent_event、external_call_log、workflow_snapshot。
7. 为保留历史运行，在线库仍保存工作流版本 id 28、29、30（v10、v11、v12）及其原冻结内容。没有将历史版本改名为 v1。
8. 结构与新 V1 核对后，旧 Flyway 历史通过一次性维护重置；后端启动记录 `version=1,type=BASELINE,success=1`。新空库执行的则是 V1 SQL。不要将这次维护方式用于后续普通升级。

恢复时必须同时使用重置前代码 `bc78e3d9` 与完整旧库备份。先停止写入，在独立库恢复并核验，再决定切换；仅回滚源码会造成 Flyway 历史与运行环境不匹配。重置后若已有新数据，需先单独备份，不能直接覆盖。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| Agent Engine 完整 pytest | 176 passed |
| 后端完整 Maven test | 179 tests，0 failures，0 errors |
| 前端 Vitest | 9 files / 25 tests passed |
| 前端生产构建 | 通过 |
| WorkflowSpec / Prompt / Handler / Schema 契约校验 | 通过 |
| 新 V1 在独立 MySQL 中创建 | 31 张业务表、一个当前工作流种子；通过 |
| 完整旧库恢复及数据清理预演 | 通过 |
| 默认 / monitoring / verification Compose 配置 | 通过 |
| 正式库 Flyway 基线、五次运行及 Artifact 哈希 | 通过 |
| 登录后运行、节点、Artifact、metrics、trace API | 通过；run 38 六节点均 SUCCEEDED |
| 后端 readiness、全部容器健康状态 | UP / healthy |

前端第一次验证因沙箱禁止 esbuild 子进程而失败；正常权限下重跑通过。未重新进行 live 生成、未重新触发交付导出、未验证远端 CI。本轮 API 检查证明历史数据仍可读取，不代表过期的验证证据重新获得交付资格。
