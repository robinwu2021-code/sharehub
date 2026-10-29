package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.inv.dto.SupplierDtos.Supplier;
import ai.neargo.sharehub.inv.dto.SupplierDtos.SupplierReq;
import ai.neargo.sharehub.inv.entity.InvSupplier;
import ai.neargo.sharehub.inv.mapper.InvSupplierMapper;
import ai.neargo.sharehub.inv.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 供应商（V201）。 */
@Service
public class SupplierServiceImpl implements SupplierService {

    private static final Logger log = LoggerFactory.getLogger(SupplierServiceImpl.class);

    private final InvSupplierMapper suppliers;

    public SupplierServiceImpl(InvSupplierMapper suppliers) {
        this.suppliers = suppliers;
    }

    @Override
    public PageResult<Supplier> page(Integer page, Integer size, String keyword, Boolean showArchived) {
        LambdaQueryWrapper<InvSupplier> q = new LambdaQueryWrapper<InvSupplier>().orderByDesc(InvSupplier::getId);
        if (!Boolean.TRUE.equals(showArchived)) q.isNull(InvSupplier::getArchivedAt);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            q.and(w -> w.like(InvSupplier::getName, k).or().like(InvSupplier::getSupplierNo, k)
                    .or().like(InvSupplier::getContactName, k).or().like(InvSupplier::getPhone, k));
        }
        Page<InvSupplier> p = suppliers.selectPage(new Page<>(page == null ? 1 : page, size == null ? 20 : size), q);
        return new PageResult<>(p.getRecords().stream().map(SupplierServiceImpl::view).toList(), p.getTotal());
    }

    @Override
    @Transactional
    public Supplier create(SupplierReq req) {
        InvSupplier s = new InvSupplier();
        s.setSupplierNo(IdGenerator.next("SP"));
        apply(s, req);
        suppliers.insert(s);
        log.info("供应商新建 supplierNo={} name={}", s.getSupplierNo(), s.getName());
        return view(s);
    }

    @Override
    @Transactional
    public Supplier update(String supplierNo, SupplierReq req) {
        InvSupplier s = require(supplierNo);
        apply(s, req);
        // 显式 set：编辑时清空联系人等可选项也要落库（updateById 会跳过 null）
        suppliers.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvSupplier>().eq(InvSupplier::getSupplierNo, supplierNo)
                .set(InvSupplier::getName, s.getName()).set(InvSupplier::getContactName, s.getContactName())
                .set(InvSupplier::getPhone, s.getPhone()).set(InvSupplier::getEmail, s.getEmail())
                .set(InvSupplier::getAddress, s.getAddress()).set(InvSupplier::getNote, s.getNote()));
        return view(s);
    }

    @Override
    @Transactional
    public Supplier archive(String supplierNo) {
        InvSupplier s = require(supplierNo);
        if (s.getArchivedAt() == null) {
            s.setArchivedAt(LocalDateTime.now());
            suppliers.updateById(s);
            log.info("供应商归档 supplierNo={}", supplierNo);
        }
        return view(s);
    }

    @Override
    @Transactional
    public Supplier unarchive(String supplierNo) {
        InvSupplier s = require(supplierNo);
        if (s.getArchivedAt() != null) {
            s.setArchivedAt(null);
            // updateById 跳过 null 字段，清归档时间须显式 set
            suppliers.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvSupplier>()
                    .eq(InvSupplier::getSupplierNo, s.getSupplierNo()).set(InvSupplier::getArchivedAt, null));
            log.info("供应商取消归档 supplierNo={}", supplierNo);
        }
        return view(s);
    }

    @Override
    public String activeName(String supplierNo) {
        if (supplierNo == null || supplierNo.isBlank()) return null;
        InvSupplier s = byNo(supplierNo.trim());
        return s == null || s.getArchivedAt() != null ? null : s.getName();
    }

    private static void apply(InvSupplier s, SupplierReq r) {
        if (r == null || r.name() == null || r.name().isBlank())
            throw BizException.badRequest("error.common.missing_parameter", "name");
        s.setName(r.name().trim());
        s.setContactName(trim(r.contactName()));
        s.setPhone(trim(r.phone()));
        s.setEmail(trim(r.email()));
        s.setAddress(trim(r.address()));
        s.setNote(trim(r.note()));
    }

    private InvSupplier require(String no) {
        InvSupplier s = no == null ? null : byNo(no);
        if (s == null) throw BizException.notFound(no);
        return s;
    }

    private InvSupplier byNo(String no) {
        return suppliers.selectOne(new LambdaQueryWrapper<InvSupplier>().eq(InvSupplier::getSupplierNo, no).last("limit 1"));
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Supplier view(InvSupplier s) {
        return new Supplier(s.getSupplierNo(), s.getName(), s.getContactName(), s.getPhone(), s.getEmail(), s.getAddress(),
                s.getNote(), s.getArchivedAt() == null ? null : s.getArchivedAt().toString());
    }
}
