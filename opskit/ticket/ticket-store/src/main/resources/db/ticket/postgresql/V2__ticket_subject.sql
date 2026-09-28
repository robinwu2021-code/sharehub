-- 工单的业务对象（外部引用）：列表直接显示，不跨模块查实例；实例的 subject 与它一致（21/02 R2 · 对账按业务对象）。
-- 手写迁移（V1 由 scripts/ddl-from-doc.py 从 21/04 生成；21/04 §3.4 已同步这三列）。
ALTER TABLE tkt_ticket ADD subject_type VARCHAR(64) NULL;
ALTER TABLE tkt_ticket ADD subject_ref VARCHAR(128) NULL;
ALTER TABLE tkt_ticket ADD subject_display JSONB NULL;
CREATE INDEX tkt_ticket_idx_subject ON tkt_ticket (workspace_id, subject_type, subject_ref);
