-- db-scheduler 的执行表。结构照官方 v16.12.0 的 mysql_tables.sql，只改表名（21/04 §3.2）。
-- task_instance 存我们的 TimerId（空间:键:epoch），task_data 不用（任务不带数据）。
CREATE TABLE plat_scheduled_tasks (
  task_name VARCHAR(100) NOT NULL,
  task_instance VARCHAR(100) NOT NULL,
  task_data BLOB,
  execution_time DATETIME(6) NOT NULL,
  picked BOOLEAN NOT NULL,
  picked_by VARCHAR(50),
  last_success DATETIME(6) NULL,
  last_failure DATETIME(6) NULL,
  consecutive_failures INT,
  last_heartbeat DATETIME(6) NULL,
  version BIGINT NOT NULL,
  priority SMALLINT,
  PRIMARY KEY (task_name, task_instance),
  INDEX execution_time_idx (execution_time),
  INDEX last_heartbeat_idx (last_heartbeat),
  INDEX priority_execution_time_idx (priority DESC, execution_time ASC)
);
