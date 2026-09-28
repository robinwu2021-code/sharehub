-- 待办「参与者角色候选」一路：从我的参与记录（idx_principal）找到实例后，按实例定位开放的 participant:ROLE 候选（21/07 O4-5 性能基线）。
-- 手写迁移（V1 由 scripts/ddl-from-doc.py 从 21/04 生成；21/04 §proc_task_candidate 已同步）。
CREATE INDEX proc_task_candidate_idx_inst_key ON proc_task_candidate (workspace_id, instance_no, candidate_key, is_open);
