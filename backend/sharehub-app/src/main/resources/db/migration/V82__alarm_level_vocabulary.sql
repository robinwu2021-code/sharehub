-- 告警等级存的是**工单优先级**的词表。
--
-- dev_alarm.level / dev_alarm_code.level 的词表是 INFO/WARN/CRITICAL
-- （列注释、AlarmLevel 枚举、AlarmVocabularyTest 三处一致），
-- 而库里存的是 LOW/MEDIUM/HIGH/URGENT —— 那是 wo_order 优先级的词表
-- （WoOpsServiceImpl.PRIORITIES）。写种子的人把两个概念当成了一个。
--
-- 为什么一路没人拦住：
--   · 种子走裸写入，**绕过了 AlarmLevel.of()**（它对非法值抛 400）——
--     校验只在接口入参那条路上，种子那条路上没有；
--   · AlarmVocabularyTest 断言的是**枚举有哪几个常量**，不是**库里存了什么**，
--     所以枚举再正确它也绿；
--   · StoredValueInVocabularyTest 本该抓到（它就是比「存的值 vs 列注释」的），
--     但它的扫描范围写死为 column_name IN ('status','state','type','mode'),
--     而这两列叫 level —— **根本没被扫到**。本次一并把 level 收进去。
--
-- 症状：运营端 /alarms 的等级列映射不出来（修复前是整页白屏），
-- 「全部等级」筛选的选项由正确词表生成，于是**选哪一档都筛不出东西**，
-- 而两边都不报错。
--
-- 映射按严重度保序，依据是每条代码自己的「建议处置」：
--   MEDIUM（标记待检，下次巡检带走）      → INFO
--   HIGH  （派工单换锁扣 / 30 分钟自动开单）→ WARN
--   URGENT（立即断电并现场处置）          → CRITICAL
--   LOW    未在数据中出现，一并兜住         → INFO
UPDATE dev_alarm_code
   SET level = CASE level
                 WHEN 'URGENT' THEN 'CRITICAL'
                 WHEN 'HIGH'   THEN 'WARN'
                 WHEN 'MEDIUM' THEN 'INFO'
                 WHEN 'LOW'    THEN 'INFO'
                 ELSE level
               END
 WHERE level IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT');

UPDATE dev_alarm
   SET level = CASE level
                 WHEN 'URGENT' THEN 'CRITICAL'
                 WHEN 'HIGH'   THEN 'WARN'
                 WHEN 'MEDIUM' THEN 'INFO'
                 WHEN 'LOW'    THEN 'INFO'
                 ELSE level
               END
 WHERE level IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT');
