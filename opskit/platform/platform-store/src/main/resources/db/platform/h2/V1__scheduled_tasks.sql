-- H2 快速测试档（MODE=MySQL）。与 MySQL 版同结构；索引拆成独立语句，H2 的索引名同样全库唯一。
CREATE TABLE plat_scheduled_tasks (
  task_name VARCHAR(100) NOT NULL,
  task_instance VARCHAR(100) NOT NULL,
  task_data BLOB,
  execution_time TIMESTAMP(6) NOT NULL,
  picked BOOLEAN NOT NULL,
  picked_by VARCHAR(50),
  last_success TIMESTAMP(6) NULL,
  last_failure TIMESTAMP(6) NULL,
  consecutive_failures INT,
  last_heartbeat TIMESTAMP(6) NULL,
  version BIGINT NOT NULL,
  priority SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX plat_scheduled_tasks_execution_time_idx ON plat_scheduled_tasks (execution_time);
CREATE INDEX plat_scheduled_tasks_last_heartbeat_idx ON plat_scheduled_tasks (last_heartbeat);
CREATE INDEX plat_scheduled_tasks_priority_execution_time_idx ON plat_scheduled_tasks (priority DESC, execution_time ASC);
