package ai.neargo.sharehub.user.asset.service.impl;

import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.crud.AbstractCrudService;
import ai.neargo.sharehub.user.asset.dto.UserAssetDtos.RechargePackageRow;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkg;
import ai.neargo.sharehub.user.asset.entity.UsrRechargePkgMarket;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrRechargePkgMapper;
import ai.neargo.sharehub.user.asset.mapper.UserAssetMappers.UsrRechargePkgMarketMapper;
import ai.neargo.sharehub.user.asset.service.RechargePackageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 充值套餐实现 —— 字典类样板 + 一处域特有逻辑（适用市场关联表）。
 *
 * <p>{@code markets} 走关联表而非 CSV 列：C 端要按「用户所在国家」筛套餐，
 * CSV 列只能 {@code LIKE '%AE%'}，会把 {@code UAE} 之类的子串误命中。
 */
@Service
public class RechargePackageServiceImpl extends AbstractCrudService<UsrRechargePkg, RechargePackageRow>
        implements RechargePackageService {

    private final UsrRechargePkgMarketMapper markets;

    public RechargePackageServiceImpl(UsrRechargePkgMapper mapper, UsrRechargePkgMarketMapper markets) {
        super(mapper);
        this.markets = markets;
    }

    @Override
    protected String keyColumn() {
        return "package_no";
    }

    @Override
    protected String keyOf(UsrRechargePkg e) {
        return e.getPackageNo();
    }

    @Override
    protected void setKey(UsrRechargePkg e, String no) {
        e.setPackageNo(no);
    }

    @Override
    protected String keyPrefix() {
        return BizKey.RECHARGE_PACKAGE;
    }

    @Override
    protected String[] keywordColumns() {
        return new String[]{"package_no", "name"};
    }

    @Override
    protected String[] filterFields() {
        return new String[]{"status", "currency"};
    }

    @Override
    protected String orderColumn() {
        return "sort_no";
    }

    @Override
    protected boolean orderDesc() {
        return false;
    }

    @Override
    public List<RechargePackageRow> listForMarket(String countryCode) {
        List<String> packageNos = null;
        if (countryCode != null && !countryCode.isBlank()) {
            packageNos = markets.selectList(new LambdaQueryWrapper<UsrRechargePkgMarket>()
                            .eq(UsrRechargePkgMarket::getCountryCode, countryCode))
                    .stream().map(UsrRechargePkgMarket::getPackageNo).distinct().toList();
            if (packageNos.isEmpty()) return List.of();
        }

        LambdaQueryWrapper<UsrRechargePkg> w = new LambdaQueryWrapper<UsrRechargePkg>()
                .eq(UsrRechargePkg::getStatus, "ENABLED")
                .orderByAsc(UsrRechargePkg::getSortNo);
        if (packageNos != null) w.in(UsrRechargePkg::getPackageNo, packageNos);

        return mapper.selectList(w).stream().map(this::toVO).toList();
    }

    @Override
    protected RechargePackageRow toVO(UsrRechargePkg e) {
        return new RechargePackageRow(e.getPackageNo(), e.getName(), e.getPayAmount(), e.getGiftAmount(),
                e.getCurrency(), marketsCsv(e.getPackageNo()), e.getValidDays(), e.getSortNo(), e.getStatus(),
                // 2026-09-25：这里原先硬编一个 null，配一句「DDL 无 archived_at 列」。
                // 那句在写下时是对的，**列后来补上了，注释没跟着改** —— 于是运营点完「归档」，
                // 列表刷新回来这一行看上去毫无变化（前端 RechargePackage extends Archivable，
                // 它等着这个字段）。功能是好的，只有「看不看得见」坏了，所以不报错。
                // 券模板 CouponTplServiceImpl.toVO 栽过一模一样的一次。
                e.getArchivedAt() == null ? null : e.getArchivedAt().toString());
    }

    /**
     * 保存套餐并同步适用市场。
     *
     * <p><b>全量重写而不是增量 diff</b>：表单发来的就是完整勾选集，
     * 增量要额外判「哪些被取消了」，而取消恰恰是最容易漏的那一半 ——
     * 漏了的表现是「取消勾选的市场仍然能买到」。
     *
     * <p>{@code marketsCsv} 为 null 时不动关联表（调用方没打算改市场）；
     * 空串是**清空**，与 null 不同 —— 两者混为一谈会让「全部取消」变成「不修改」。
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public RechargePackageRow saveWithMarkets(UsrRechargePkg e, String marketsCsv) {
        RechargePackageRow saved = save(e);
        if (marketsCsv == null) return saved;

        String packageNo = saved.packageNo();
        markets.delete(new LambdaQueryWrapper<UsrRechargePkgMarket>()
                .eq(UsrRechargePkgMarket::getPackageNo, packageNo));
        for (String code : marketsCsv.split(",")) {
            String c = code.trim().toUpperCase();
            if (c.isEmpty()) continue;
            UsrRechargePkgMarket m = new UsrRechargePkgMarket();
            m.setPackageNo(packageNo);
            m.setCountryCode(c);
            markets.insert(m);
        }
        // 重新出参：markets 是从关联表拼的，直接返 saved 会是刚才那份旧值
        return toVO(mapper.selectOne(
                new LambdaQueryWrapper<UsrRechargePkg>().eq(UsrRechargePkg::getPackageNo, packageNo)));
    }

    /** 关联表 → CSV，还原前端 {@code RechargePackage.markets} 的形状（如 {@code "AE,SA"}）。 */
    private String marketsCsv(String packageNo) {
        if (packageNo == null) return null;
        List<String> codes = markets.selectList(new LambdaQueryWrapper<UsrRechargePkgMarket>()
                        .eq(UsrRechargePkgMarket::getPackageNo, packageNo)
                        .orderByAsc(UsrRechargePkgMarket::getCountryCode))
                .stream().map(UsrRechargePkgMarket::getCountryCode).toList();
        return codes.isEmpty() ? null : String.join(",", codes);
    }
}
