-- 测试用内存库（H2, MODE=MySQL）建表：列与 doc/schema/ed.sql 逐列对齐，
-- 仅去掉 H2 不认的引擎/字符集/排序规则子句。唯一键保留——幂等靠它兜底。
DROP TABLE IF EXISTS t_ed_course_task;
CREATE TABLE t_ed_course_task (
  id bigint NOT NULL PRIMARY KEY,
  item_no varchar(64),
  due_at datetime,
  amount decimal(12,2),
  content varchar(255),
  counselor varchar(64),
  dispatched_at datetime,
  hold_reason varchar(255),
  status int,
  del_flag int DEFAULT 0,
  create_by varchar(64),
  create_time datetime,
  update_by varchar(64),
  update_time datetime,
  remark varchar(500)
);
CREATE UNIQUE INDEX uk_course_task_item_no ON t_ed_course_task(item_no);

DROP TABLE IF EXISTS t_ed_course_task_log;
CREATE TABLE t_ed_course_task_log (
  id bigint NOT NULL PRIMARY KEY,
  task_id bigint NOT NULL,
  item_no varchar(64),
  action varchar(16),
  slot varchar(8),
  counselor varchar(64),
  detail varchar(500),
  create_time datetime
);
CREATE UNIQUE INDEX uk_course_task_log_task_action ON t_ed_course_task_log(task_id, action);
