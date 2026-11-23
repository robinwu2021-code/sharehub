# 已迁移

OpsKit 已于 2026-11-23 拆为独立仓库 <https://codeup.aliyun.com/67221143cff8dd759318ddd9/p2027/opskit.git>（`git subtree split --prefix=opskit`，保留历史）。
本目录的设计文档已复制到 OpsKit 仓库的 `docs/design/`，**以那边为准**；这里保留只为不打断 ShareHub 文档里的既有链接。

- 代码、部署（原 `deploy/opskit/` → OpsKit 仓库 `deploy/prod/`）、CI 都在 OpsKit 仓库
- 本地开发默认把 OpsKit 克隆到本仓库同级目录 `../opskit`（`.claude/launch.json` 的 `opskit-local`、`ops-web/scripts/flow-e2e.sh` 都按这个找；后者可用 `OPSKIT_HOME` 覆盖）
- ShareHub 与 OpsKit 之间只走 HTTP（`integration/opskit`），没有构建期依赖
