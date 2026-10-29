# 本地部署（MySQL）与冒烟

1. **建库**：`mvn -o package -DskipTests -pl sharehub-app -am`，再 `~/work/tools/powerbank-mysql-rebuild backend pb_local`
   （MariaDB 方言迁移转 MySQL 9.7；会 DROP 目标库）。应用账号需要授权：
   `GRANT ALL PRIVILEGES ON pb_local.* TO 'powerbank'@'127.0.0.1'; …@'localhost'`。
2. **后端**：`cp backend/sharehub-app/target/sharehub-app-0.1.0-SNAPSHOT.jar ~/.local/opt/powerbank/sharehub-app.jar`，
   启动 `.claude/launch.json` 的 **backend-local**（8080，pb_local，演示种子，`admin` / 本地口令 `local-dev`）。
   —— 不设管理口令时，dev-mode 下登录页不传角色，一律落成只读 VIEWER，大量页面 403（是权限边界，不是缺陷）。
3. **前端**：用独立 worktree（各自的 `.next`，避免与别的 dev server 抢同一个目录），node_modules 用 `cp -c -R` 克隆
   （Turbopack 不认指向项目外的软链）：
   - **ops-web-local-real**（3100，真实接口）；后端 CORS 只放行 3100；
   - **ops-web-local-mock**（3200，mock）——库存页面（仓库、到仓待检、库存余额 / 流水 / 盘点、迁移）在这里测；
     mock 是内存数据，整页刷新即重置，跨页验证请用左侧菜单切换。
4. **冒烟**：`python3 backend/scripts/local-deploy/smoke.py` —— 管理员建 BD 员工（入驻须双人：录入人不能审自己）→
   商家入驻 → 建柜 → 到仓待检 → 质检 → 入库 → 出库（只选商户）→ 绑定 + 装机单 → 门禁 → 履历 / 库存视图 / 流水 / 期间报表。
