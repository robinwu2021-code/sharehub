package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.sharehub.api.core.event.OrderRefundedEvent;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.ShareRecordStatus;
import ai.neargo.sharehub.finance.entity.ShareRecord;
import ai.neargo.sharehub.finance.mapper.ShareRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

/**
 * 退款冲回分成（V170）。
 *
 * <p>按「本次退款 ÷ 本单实收」的比例，给该单每一条原分成写一条负数明细，状态 PENDING、账期取当月：
 * 原分成已结算的，冲回进下一张结算单；还没结算的，在同一张单里相抵。<b>不改原行</b> ——
 * 原行是当时的事实，改了就对不上已经确认、甚至已经付掉的结算单。
 *
 * <p>两条护栏：
 * <ul>
 *   <li><b>幂等</b>：唯一键含退款单号（{@code reversal_key}），同一笔退款的事件重投只冲一次；</li>
 *   <li><b>不超冲</b>：同一条原分成被几次部分退款冲回的合计，不超过它本身 —— 四舍五入的分差
 *       累积起来，会让商家倒欠平台几分钱，而那几分钱没有任何业务含义。</li>
 * </ul>
 */
@Slf4j
@Component
public class ShareReversal {

    private final ShareRecordMapper records;

    public ShareReversal(ShareRecordMapper records) {
        this.records = records;
    }

    /** @return 写入的冲回行数 */
    public int reverse(OrderRefundedEvent e) {
        if (e.amount() == null || e.amount().signum() <= 0) return 0;
        List<ShareRecord> originals = records.selectList(new LambdaQueryWrapper<ShareRecord>()
                .eq(ShareRecord::getOrderNo, e.orderNo()).isNull(ShareRecord::getRefundNo));
        if (originals.isEmpty()) {
            log.info("退款单 {} 对应订单 {} 没有分成明细，无需冲回", e.refundNo(), e.orderNo());
            return 0;
        }
        String period = YearMonth.now().toString();
        int n = 0;
        for (ShareRecord o : originals) {
            BigDecimal gross = o.getGrossAmount();
            if (gross == null || gross.signum() <= 0 || o.getAmount() == null || o.getAmount().signum() <= 0) continue;
            BigDecimal ratio = e.amount().divide(gross, 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
            BigDecimal want = o.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            BigDecimal left = o.getAmount().add(reversedSoFar(o));   // 已冲回的是负数
            BigDecimal back = want.min(left);
            if (back.signum() <= 0) continue;

            ShareRecord r = new ShareRecord();
            r.setRecordNo(FinNos.nextNo(records, "record_no", BizKey.SHARE_RECORD, 6));
            r.setOrderNo(o.getOrderNo());
            r.setDimension(o.getDimension());
            r.setBasis(o.getBasis());
            r.setPayeeType(o.getPayeeType());
            r.setPayeeNo(o.getPayeeNo());
            r.setPayeeName(o.getPayeeName());
            r.setRate(o.getRate());
            r.setGrossAmount(e.amount().min(gross).negate());
            r.setBaseMode(o.getBaseMode());
            r.setTaxRate(o.getTaxRate());
            r.setBaseAmount(o.getBaseAmount() == null ? null
                    : o.getBaseAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP).negate());
            r.setAmount(back.negate());
            r.setCurrency(o.getCurrency());
            r.setMode(o.getMode());
            r.setStatus(ShareRecordStatus.PENDING.name());
            r.setPeriod(period);
            r.setSourceNo(o.getRecordNo());   // 冲的是哪一条原分成
            r.setRefundNo(e.refundNo());
            try {
                records.insert(r);
                n++;
            } catch (DuplicateKeyException dup) {
                // 事件重投：这笔退款已经冲过了 —— 唯一键正是为它准备的
                log.info("退款冲回已存在，跳过 refundNo={} orderNo={} payee={}", e.refundNo(), e.orderNo(), o.getPayeeNo());
            }
        }
        return n;
    }

    private BigDecimal reversedSoFar(ShareRecord o) {
        return records.selectList(new LambdaQueryWrapper<ShareRecord>()
                        .eq(ShareRecord::getOrderNo, o.getOrderNo()).eq(ShareRecord::getDimension, o.getDimension())
                        .eq(ShareRecord::getPayeeNo, o.getPayeeNo()).eq(ShareRecord::getBasis, o.getBasis())
                        .isNotNull(ShareRecord::getRefundNo))
                .stream().map(ShareRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
