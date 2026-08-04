package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.finance.FinNos;
import ai.neargo.sharehub.finance.dto.FinDtos.Invoice;
import ai.neargo.sharehub.finance.dto.FinDtos.InvoiceSaveReq;
import ai.neargo.sharehub.finance.dto.FinDtos.InvoiceView;
import ai.neargo.sharehub.finance.entity.FinInvoice;
import ai.neargo.sharehub.finance.entity.FinInvoiceItem;
import ai.neargo.sharehub.finance.mapper.FinInvoiceItemMapper;
import ai.neargo.sharehub.finance.mapper.FinInvoiceMapper;
import ai.neargo.sharehub.finance.service.InvoiceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 发票实现。
 *
 * <p><b>业务键前缀 {@code INV}</b>（{@code BizKey.INVOICE_OPS}）—— C 端开票申请是 {@code UINV}
 * 的另一张表，两者一旦混用，两表业务键会直接撞。
 *
 * <p><b>关联订单落子表</b> {@code fin_invoice_item}，不落 {@code order_nos JSON}（[db-design §1.7]）。
 * 保存时整组重建：先清后插，避免「改一次少一单、多一单」的增量对账问题。
 */
@Service
public class InvoiceServiceImpl implements InvoiceService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FinInvoiceMapper mapper;
    private final FinInvoiceItemMapper itemMapper;

    public InvoiceServiceImpl(FinInvoiceMapper mapper, FinInvoiceItemMapper itemMapper) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
    }

    @Override
    public PageResult<Invoice> page(Integer page, Integer size, String keyword, String status) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : Math.min(size, 200);

        LambdaQueryWrapper<FinInvoice> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.like(FinInvoice::getInvoiceNo, kw)
                    .or().like(FinInvoice::getPayeeName, kw)
                    .or().like(FinInvoice::getVatTrn, kw));
        }
        if (status != null && !status.isBlank()) w.eq(FinInvoice::getStatus, status);
        w.orderByDesc(FinInvoice::getId);

        Page<FinInvoice> r = mapper.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(InvoiceServiceImpl::toVO).toList(), r.getTotal());
    }

    @Override
    public InvoiceView detail(String invoiceNo) {
        FinInvoice e = require(invoiceNo);
        return new InvoiceView(toVO(e), orderNosOf(invoiceNo));
    }

    @Override
    @Transactional
    public InvoiceView save(InvoiceSaveReq req) {
        if (req == null) throw new IllegalArgumentException("发票信息不能为空");

        String no = req.invoiceNo();
        FinInvoice e;
        if (no == null || no.isBlank()) {
            e = new FinInvoice();
            e.setTenantId(SecurityUtils.tenantId());
            e.setInvoiceNo(FinNos.nextNo(mapper, "invoice_no", BizKey.INVOICE_OPS, 6));
        } else {
            e = require(no);
            if ("VOID".equals(e.getStatus())) {
                // 红冲后的发票是终态凭证，再改就等于改了已交付给对方的税务单据
                throw new IllegalArgumentException("已红冲的发票不可修改: " + no);
            }
        }

        e.setPayeeType(req.payeeType());
        e.setPayeeNo(req.payeeNo());
        e.setPayeeName(req.payeeName());
        e.setAmount(req.amount());
        e.setVatTrn(req.vatTrn());
        e.setCurrency(req.currency());
        String status = (req.status() == null || req.status().isBlank()) ? "DRAFT" : req.status();
        e.setStatus(status);
        if ("ISSUED".equals(status) && e.getIssuedAt() == null) {
            e.setIssuedAt(LocalDateTime.now().format(TS)); // 开票时间只在首次开出时落，重复保存不覆盖
        }

        if (e.getId() == null) {
            mapper.insert(e);
        } else {
            mapper.updateById(e);
        }

        // 关联订单整组重建（先清后插）
        if (req.orderNos() != null) {
            itemMapper.delete(new LambdaQueryWrapper<FinInvoiceItem>()
                    .eq(FinInvoiceItem::getInvoiceNo, e.getInvoiceNo()));
            for (String orderNo : req.orderNos()) {
                if (orderNo == null || orderNo.isBlank()) continue;
                FinInvoiceItem item = new FinInvoiceItem();
                item.setTenantId(e.getTenantId());
                item.setInvoiceNo(e.getInvoiceNo());
                item.setOrderNo(orderNo.trim());
                itemMapper.insert(item);
            }
        }
        return detail(e.getInvoiceNo());
    }

    private List<String> orderNosOf(String invoiceNo) {
        return itemMapper.selectList(new LambdaQueryWrapper<FinInvoiceItem>()
                        .eq(FinInvoiceItem::getInvoiceNo, invoiceNo)
                        .orderByAsc(FinInvoiceItem::getId))
                .stream().map(FinInvoiceItem::getOrderNo).toList();
    }

    private FinInvoice require(String invoiceNo) {
        FinInvoice e = mapper.selectOne(new LambdaQueryWrapper<FinInvoice>()
                .eq(FinInvoice::getInvoiceNo, invoiceNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("发票不存在: " + invoiceNo);
        return e;
    }

    private static Invoice toVO(FinInvoice e) {
        return new Invoice(e.getInvoiceNo(), e.getPayeeType(), e.getPayeeNo(), e.getPayeeName(),
                e.getAmount(), e.getVatTrn(), e.getCurrency(), e.getStatus(), e.getIssuedAt(), e.getFileUrl(),
                e.getSourceType(), e.getSourceNo(), e.getInvoiceCode(), e.getInvoiceNumber(),
                e.getIssuedBy(), e.getVoidedAt() == null ? null : e.getVoidedAt().toString(),
                e.getVoidedBy(), e.getVoidReason());
    }

    @Override
    @Transactional
    public InvoiceView issue(String invoiceNo) {
        FinInvoice e = require(invoiceNo);
        if ("VOID".equals(e.getStatus())) {
            throw new IllegalStateException("已作废的发票不能开具: " + invoiceNo);
        }
        if ("ISSUED".equals(e.getStatus())) {
            // 幂等：重复开具直接返回，不报错也不重复盖时间 —— 运营点两次不该失败。
            return detail(invoiceNo);
        }
        e.setStatus("ISSUED");
        e.setIssuedAt(java.time.LocalDateTime.now().toString().replace('T', ' '));
        e.setIssuedBy(currentOperator());
        mapper.updateById(e);
        return detail(invoiceNo);
    }

    @Override
    @Transactional
    public InvoiceView voidInvoice(String invoiceNo, String voidReason) {
        if (voidReason == null || voidReason.isBlank()) {
            // 没有原因的作废等于没有记录 —— 稽查时无法解释这张票为什么废了。
            throw new IllegalArgumentException("作废发票必须填写作废原因");
        }
        FinInvoice e = require(invoiceNo);
        if ("VOID".equals(e.getStatus())) {
            throw new IllegalStateException("发票已作废: " + invoiceNo);
        }
        e.setStatus("VOID");
        e.setVoidReason(voidReason.trim());
        e.setVoidedAt(java.time.LocalDateTime.now());
        e.setVoidedBy(currentOperator());
        mapper.updateById(e);
        return detail(invoiceNo);
    }

    /** 操作人一律取登录态，不接受入参 —— 信前端传的操作人，留痕就可伪造。 */
    private static String currentOperator() {
        return ai.neargo.sharehub.auth.SecurityUtils.currentUser()
                .map(ai.neargo.sharehub.auth.LoginUser::username).orElse(null);
    }
}
