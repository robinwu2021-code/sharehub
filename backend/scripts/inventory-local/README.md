# 本机联调：ShareHub ↔ ai-shop 独立进销存（S5）

一次性准备（都在仓库外，不提交）：

1. **进销存 jar 拷到独立位置**：`cp ../../ai-shop/backend/inventory/inventory-svc/target/inventory-svc-1.0.0-SNAPSHOT.jar ~/.local/opt/inventory-svc/inventory-svc.jar`
   —— 别直接跑 ai-shop 的 target：那边重新打包会覆盖运行中的胖 jar，进程随即 `NoClassDefFoundError`。
2. **迁移转成 MySQL 方言**（本机是 MySQL 9.7，ai-shop 迁移有 MariaDB 语法）：把
   `inventory-domain/src/main/resources/db/inventory/*.sql` 复制到 `~/.local/var/mysql/flyway-conv/inventory/`，
   按 `~/work/tools/ai-shop-mysql-conv` 的 sed 规则改写；库 `inventory_test` 空库即可，服务启动时自己迁移。
3. 启动 `.claude/launch.json` 的 **inventory-svc-local**（8086），跑 `onboard.sh` 开 SHAREHUB 租户、档案与凭证
   （凭证写到 `~/.local/etc/inventory-sharehub-cred.json`，600）。
4. 生成运行时类路径：`mvn -o dependency:build-classpath -pl sharehub-app -Dmdep.outputFile=sharehub-app/target/runtime-classpath.txt`，
   启动 **backend-inv**（8085，连 test_sharehub）。

联调：`python3 backend/scripts/inventory-local/e2e.py` —— 运行期间临时把 test_sharehub 的 `inv.engine` 改成 SERVICE，
结束复原 LOCAL；**跑之前告知并行会话别在 test_sharehub 上跑测试**。

覆盖：入库（gateRef = 质检记录号）· 出库只选商户（当场领取）· 出库池退回（释放 + 撤回）· 库存服务拒绝 409 本地不动 ·
事件拉取与去重 · 经 BFF 读流水 · 盘点（提交人不能自己复核）· 撤机回仓（撤机完工 → 回仓调拨 → 回仓待检 → RETURN 入库 + 业务确认解冻 → 可再出库；投放一步直接置库跳过上线门禁）。

注意：zsh 不对 `$VAR` 做分词，curl 的多个 `-H` 不要塞进一个变量（会整串当成一个头，管理令牌 401）。
