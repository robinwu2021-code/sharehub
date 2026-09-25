-- C 端开票申请：补驳回原因与受理留痕。
--
-- usr_invoice.status 的词表是 APPLIED/ISSUED/REJECTED，但**没有任何列能说明为什么驳回** ——
-- 于是 REJECTED 对消费者是个死胡同：他看到「已驳回」，不知道是抬头信息不对、
-- 金额对不上、还是超过了开票期限，也就无从改正后重提，而他会做的事是再提一次。
--
-- 这也是 REJECTED 此前**根本到不了**的原因之一：运营端没有受理面，
-- 而一个只能靠改库才能进入的状态，等于词表里多写了一个词。
ALTER TABLE usr_invoice
    ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(255) NULL AFTER status,
    ADD COLUMN IF NOT EXISTS handled_by VARCHAR(36) NULL AFTER reject_reason,
    ADD COLUMN IF NOT EXISTS handled_at DATETIME(3) NULL AFTER handled_by;

-- 注释单独用 MODIFY 落，**不写在 ADD COLUMN IF NOT EXISTS 里**：
-- 列已存在时整条 ADD 会被跳过，注释也就跟着不生效 —— 一个用错字符集手工跑过一遍的库
-- 会永远停在乱码上，再跑多少次迁移都修不回来（本次就是这么踩的）。
-- MODIFY 是无条件的，任何起点跑完都收敛到同一状态。
ALTER TABLE usr_invoice
    MODIFY COLUMN reject_reason VARCHAR(255) NULL COMMENT '驳回原因；status=REJECTED 时必填',
    MODIFY COLUMN handled_by VARCHAR(36) NULL COMMENT '受理人（员工号）；开具或驳回时回填',
    MODIFY COLUMN handled_at DATETIME(3) NULL COMMENT '受理时间；开具或驳回时回填';
