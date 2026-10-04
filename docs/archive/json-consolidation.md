# AutoSpec JSON 配置收敛

## 目录约定

- `agent-engine/contracts/`：产品基线、兼容入口、当前候选及协议 Schema，共 7 个 JSON。
- `agent-engine/contracts/archive/`：19 个历史 WorkflowSpec，保持原文件名和原内容。
- `agent-engine/evaluation/configs/p2-p3-20261003-explicit-v2/`：当前 A/B/C/D 契约、development/holdout manifest 与 fixture 配置、v12 smoke manifest/config，共 10 个 JSON。
- `agent-engine/evaluation/configs/archive/<原批次目录>/`：历史实验配置，共 65 个 JSON。原批次内 manifest/config 相对引用保持有效。

## 引用与历史

8 份 live-loop v5～v12 契约副本经过逐字节 SHA-256 比较后移除。
每份契约仍在 `contracts/` 或 `contracts/archive/` 保留唯一原始快照。
manifest 首先读取同批次相对路径；没有副本时，只允许按 WorkflowSpec 文件名
在这两个固定目录查找，随后仍校验原 contract hash。绝对路径和父目录跳转仍禁止。

原证据中的旧路径是当时执行记录。复现历史实验时，配置路径添加 `archive/`；
历史工作流快照路径也添加 `contracts/archive/`。配置、预算和已发布版本内容不变。
数据库的冻结运行/执行 bundle、默认版本和真实 `.env` 没有修改。

## 新增配置规则

日常代码修改运行相关测试，无需新增实验 JSON。同一契约应由多个 manifest
引用；只有真正冻结新候选或新实验批次时才保存独立快照。已完成的实验成套归档，
避免在常用目录累计 smoke 副本。配置生成继续复用已有
`evaluation.freeze_ablation_contracts`，输出目录由调用者选择。

常用目录由 109 个 JSON 减至 17 个；84 个文件归档，8 个重复副本移除。
删除的副本可从唯一契约文件或 Git 历史恢复。
