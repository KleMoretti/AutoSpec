# SB-03 显式 v2 Backend FAILED → Replan → Verify

日期：2026-10-03（Asia/Shanghai）  
分支：`codex/r1-r2-sandbox-20261002`  
隔离 Compose project：`autospec-formal`

## 正式 run

- project `13` / run `11` 显式选择治理版本 id `3`：`spec-sandbox-explicit-v2`。
- 使用 fixture-only 的 `[[fixture-verification-failure]]` 标记，不调用 DeepSeek。
- 六节点最终全部 `SUCCEEDED`，Backend/Frontend/Reviewer/Evaluator 版本分别为 v7/v4/v5/v4。

Backend trace：

| step | phase | status/reason | fact |
| ---: | --- | --- | --- |
| 4 | OBSERVATION | `FAILED / SPEC_VERIFY_FAILED`，issue `TABLE_PRIMARY_KEY_INVALID` | `3529fa096dd2a4ed70a75f1f4de55cdc6ea337547d8c027ea795a66264baa3be` |
| 5 | REPLAN | `SUCCEEDED / REPLAN_ACCEPTED` | — |
| 8 | OBSERVATION | `SUCCEEDED / SPEC_VERIFY_PASSED` | `ec56112bd2d3239bbcc776e5e7111a951b6a469d7119dac6da9282e3cc58d85d` |
| 9 | FINISH | `SUCCEEDED / COMPLETED` | same passed fact |

候选 hash 在失败与修复后保持 `fc33f7d5842464635a27c3bac6cd229c943d4a030063d0c3968ef008390d3e73`；失败 fact 与通过 fact 均进入 Worker/控制面 trace，修复没有跳过再次验证。

## 交付门禁

- 编辑前 readiness：`BUILD_REQUIRED`。
- `POST /api/projects/13/code-skeleton`：成功，`autospec-project-13-skeleton.zip`，base64 内容长度 `10488`。
- 编辑后 readiness：`READY`，`specReady=true`、`buildReady=true`。
- `POST /api/projects/13/export?format=MARKDOWN`：成功，`autospec-project-13.md`，内容长度 `4691`。

## 结论与边界

- SB-03 的显式 v2 Backend 局部反馈循环已通过真实 API、Redis Worker、spec-verifier、事实台账和交付入口验收。
- 该样本是 fixture-only 的确定性规格缺陷，不代表 DeepSeek live 自我修复质量或费用；上游 Architect/Reviewer 跨节点返工、环境 ERROR 不 Replan 和 live 样本仍需独立验收。
