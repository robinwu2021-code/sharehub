package ai.neargo.sharehub.seed.domain;

import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dto.Dto;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.seed.SeedData;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** dev 域首次落库：dev_cabinet 空时灌内存种子。幂等。 */
@Component
// 演示种子数据：**默认关闭**（sharehub.seed.enabled=true 才装配）。
//
// 原先无任何门禁 —— 任何空库启动都会灌入 12 个假代理商 / 20 个假站点 / 演示订单，
// **生产首次上线会被写入演示数据**，且因为 seeder 幂等（有数据即跳过），
// 一旦灌入就再也不会被覆盖或提示。这不是拆分问题，是上线事故。
@ConditionalOnProperty(name = "sharehub.seed.enabled", havingValue = "true")
@Order(3)
public class DevSeeder implements CommandLineRunner {

    private final SeedData seed;
    private final CabinetMapper mapper;
    private final ai.neargo.sharehub.dev.mapper.PowerbankMapper powerbanks;
    private final ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper locations;
    private final ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper sites;

    public DevSeeder(SeedData seed, CabinetMapper mapper,
                     ai.neargo.sharehub.dev.mapper.PowerbankMapper powerbanks,
                     ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper locations,
                     ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper sites) {
        this.seed = seed;
        this.mapper = mapper;
        this.powerbanks = powerbanks;
        this.locations = locations;
        this.sites = sites;
    }

    @Override
    public void run(String... args) {
        if (mapper.selectCount(null) > 0) {
            return;
        }
        for (Cabinet c : seed.cabinets()) {
            DevCabinet e = new DevCabinet();
            e.setCabinetNo(c.cabinetNo());
            e.setTenantId("MAIN");
            e.setSn(c.sn());
            e.setVendorCode(c.vendorCode());
            e.setModel(c.model());
            e.setLocationNo(c.locationNo());
            e.setLocationName(c.locationName());
            /*
             * **站点/代理按点位反查回填**（V9 的数据范围锚点列，口径同 CabinetService#save）。
             * 不填的后果不在设备页，而在经营侧：概览把订单按 `dev_cabinet.site_no` 归到站点，
             * 空的话站点排行、单站统计、场景 GMV 全是 0 —— 120 单俱在，页面却像没生意。
             */
            LocLocation loc = c.locationNo() == null ? null : locations.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LocLocation>()
                            .eq(LocLocation::getLocationNo, c.locationNo()).last("limit 1"));
            if (loc != null) {
                e.setSiteNo(loc.getSiteNo());
                LocSite site = loc.getSiteNo() == null ? null : sites.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LocSite>()
                                .eq(LocSite::getSiteNo, loc.getSiteNo()).last("limit 1"));
                if (site != null) e.setAgentNo(site.getAgentNo());
            }
            e.setSlotTotal(c.slotTotal());
            e.setAvailableCount(c.availableCount());
            e.setOnlineStatus(c.onlineStatus());
            e.setStatus(c.status());
            e.setFwVersion(c.fwVersion());
            e.setLastHeartbeatAt(SeedTime.dt(c.lastHeartbeatAt()));
            mapper.insert(e);
            seedBanks(e);
        }
    }

    /**
     * 每台柜按 availableCount 建出同样多的在柜宝。
     *
     * <p>此前只建柜不建宝，于是 {@code dev_powerbank} 全库零行 —— 而借还是靠**宝台账**驱动的：
     * {@code PowerbankTracker} 查不到台账就走「该柜未接仓位级上报」的兼容分支，
     * 既不挑宝也不回写可借数。结果是门店卡上的「可借 N」永远等于种子里那个数：
     * 借走一个还是 N，还回来也是 N（2026-09-28 真机实测）。
     * 宝台账为空同时让「宝疑似丢失」「老化报废」「按宝查订单」整条资产线都在空转。
     *
     * <p>电量给 60–100 的散值而不是统一 100：统一值会让「挑电量最高的宝」这条规则
     * 在演示环境里永远看不出效果，也掩盖 {@code minBattery} 门槛配错的情况。
     */
    private void seedBanks(DevCabinet cab) {
        int n = cab.getAvailableCount() == null ? 0 : cab.getAvailableCount();
        for (int slot = 1; slot <= n; slot++) {
            DevPowerbank b = new DevPowerbank();
            b.setPowerbankNo("PB" + cab.getCabinetNo() + String.format("%02d", slot));
            b.setTenantId("MAIN");
            b.setSn(b.getPowerbankNo());
            b.setVendorCode(cab.getVendorCode());
            b.setBattery(60 + (slot * 7) % 41);
            b.setCycles(0);
            b.setStatus(PowerbankStatus.IN_CABINET.name());
            b.setCabinetNo(cab.getCabinetNo());
            b.setSlotIndex(slot);
            powerbanks.insert(b);
        }
    }
}
