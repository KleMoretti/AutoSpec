# AutoSpec P1: semantic retrieval and Shared Contract parallelism

Updated: 2026-09-21. This document supersedes the earlier fixture-only RAG baseline for current deployments; historical `v5` snapshots remain unchanged.

## Retrieval

`EmbeddingProvider` now separates vector production from BM25 → RRF → deterministic rerank. In Java, `KnowledgeIndexService` stores the provider model version and dimensions on each chunk, refuses to score mismatched vectors, and requests a rebuild when the configured model changes. Approval Outbox keys/payloads and workflow retrieval traces record that same active model version. Production rejects fixture hashing; set `AUTOSPEC_EMBEDDING_MODE=live`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`, and `EMBEDDING_DIMENSIONS` for a dedicated OpenAI-compatible `/embeddings` endpoint. Do not reuse a chat-only model merely because it offers OpenAI-compatible chat completions. Development defaults to deterministic hashing for offline tests. ACL, project, status and expiry filtering happen before any text reaches the Python embedding provider and before Java ranking.

The gold fixture is `agent-engine/evaluation/datasets/autospec_retrieval_gold_v2.json`. `run_retrieval_evaluation` reports Recall@K, MRR, nDCG@K, ACL leakage rate and expired-document hit rate; the test also asserts forbidden/expired text is never sent to the provider. From `agent-engine/`, run `python -m evaluation.cli_retrieval_gold` with the embedding configuration above to obtain a JSON report. The offline hash fixture validates ranking plumbing and security boundaries, not semantic quality. Before claiming a semantic lift, run the same gold cases against a configured live provider and compare scores with the fixture baseline; record model/version, cost and latency separately. No live score is claimed here.

The existing knowledge index recovery job scans active documents and requeues stale-model chunks. During a model change, retrieval excludes old-model chunks until reindexing succeeds. Keep recovery enabled and monitor backlog/failures before declaring cutover complete.

## Parallel engineering

New published workflow version: `autospec-v5:v5-parallel`; original `autospec-v5:v5` is retained for existing snapshots and replay. The product UI defaults new runs to the new version, while a version selector allows explicit historical runs.

`Product Manager → Architect → {Backend Engineer ∥ Frontend Engineer} → Reviewer → Evaluator`.

Architect V2 emits `ArchitectureDesignArtifactV2.shared_contract`: domain models, stable API signatures, DTOs, error codes and permission matrix. Backend V3 and Frontend V2 use this same frozen artifact. Frontend V2 has no `backend_design` input; its bindings reference stable Shared Contract API IDs. Deterministic validation rejects backend or frontend API drift before node success, and Reviewer checks both branches and routes HIGH contract mismatch issues to the responsible node. New handler and prompt versions protect historical bundles. The new WorkflowSpec, V103 seed, prompt resources, Java catalog, Python registry and verifier are synchronized.

## Verification

Run `python scripts/verify_workflow_contract.py`, Python pytest, Java `WorkflowExecutableContractTest` and `EmbeddingProviderTest`, then the frontend test/build. For live semantic quality, configure a real embeddings endpoint and run the gold dataset in a safe project; fixture test scores cannot establish semantic improvement.
