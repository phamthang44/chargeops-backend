<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **chargeops-backend** (13024 symbols, 40070 relationships, 1088 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/chargeops-backend/context` | Codebase overview, check index freshness |
| `gitnexus://repo/chargeops-backend/clusters` | All functional areas |
| `gitnexus://repo/chargeops-backend/processes` | All execution flows |
| `gitnexus://repo/chargeops-backend/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->

---

## ChargeOps Knowledge Base (Obsidian Vault) — Quy Tắc Bắt Buộc

Toàn bộ tài liệu kiến trúc, đặc tả nghiệp vụ, API contract và chính sách hệ thống được quản lý tập trung tại **Obsidian Vault** (`chargeops/docs/ChargeOps`), được theo dõi bởi repository độc lập: `https://github.com/phamthang44/chargeops-vault.git`.

1. **Vault-First**: Trước khi triển khai code mới hoặc refactor các tính năng liên quan đến logic nghiệp vụ, tính giá, đặt chỗ, duyệt trạm hay quản lý license, agent **PHẢI** đọc tài liệu trong `docs/ChargeOps/` trước (bắt đầu từ `00 - ChargeOps Knowledge Hub.md`).
2. **Bắt buộc Commit & Push Thẳng Lên GitHub**: Mỗi lần chỉnh sửa, thêm mới hoặc cập nhật tài liệu trong `docs/ChargeOps/`, agent **BẮT BUỘC** phải commit và push thẳng lên repository `chargeops-vault` trên GitHub (`origin main`):
   ```bash
   cd docs/ChargeOps
   git add .
   git commit -m "docs(<scope>): <mô tả thay đổi rõ ràng>"
   git push origin main
   ```
