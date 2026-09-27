-- 照官方 v16.12.0 的 postgresql_tables.sql，只改表名与索引名（PG 的索引名全库唯一，加表前缀防撞）。
CREATE TABLE plat_scheduled_tasks (
  task_name TEXT NOT NULL,
  task_instance TEXT NOT NULL,
  task_data BYTEA,
  execution_time TIMESTAMP WITH TIME ZONE NOT NULL,
  picked BOOLEAN NOT NULL,
  picked_by TEXT,
  last_success TIMESTAMP WITH TIME ZONE,
  last_failure TIMESTAMP WITH TIME ZONE,
  consecutive_failures INT,
  last_heartbeat TIMESTAMP WITH TIME ZONE,
  version BIGINT NOT NULL,
  priority SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX plat_scheduled_tasks_execution_time_idx ON plat_scheduled_tasks (execution_time);
CREATE INDEX plat_scheduled_tasks_last_heartbeat_idx ON plat_scheduled_tasks (last_heartbeat);
CREATE INDEX plat_scheduled_tasks_priority_execution_time_idx ON plat_scheduled_tasks (priority DESC, execution_time ASC);
