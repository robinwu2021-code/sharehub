package ai.neargo.sharehub.gw.driver;

import ai.neargo.sharehub.gw.driver.spi.CommandFamily;
import ai.neargo.sharehub.gw.driver.spi.DeviceDriver;
import ai.neargo.sharehub.gw.driver.spi.DriverManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 驱动路由：按 {@code (vendorCode, deviceType)} 找 driver（ADR-018 §四）。
 *
 * <p>Spring 自动注入全部 {@link DeviceDriver} 实现 —— <b>新增供应商 = 新增一个 driver 类，
 * 网关框架与业务代码零改动</b>（ADR-003 的承诺，分族后依然成立）。
 *
 * <p><b>一个 (vendor, deviceType) 只能有一个 driver</b>：重复注册在启动时就失败，
 * 而不是运行期随机选中一个 —— 后者会让「为什么这台柜机行为不对」变成不可复现的问题。
 */
@Component
public class DriverRegistry {

    private static final Logger log = LoggerFactory.getLogger(DriverRegistry.class);

    private final Map<String, DeviceDriver> byKey;

    public DriverRegistry(List<DeviceDriver> drivers) {
        this.byKey = drivers.stream().collect(Collectors.toMap(
                d -> key(d.manifest().vendorCode(), d.manifest().deviceType()),
                d -> d,
                (a, b) -> {
                    throw new IllegalStateException(
                            "同一 (vendor, deviceType) 注册了多个 driver: "
                                    + a.getClass().getSimpleName() + " / " + b.getClass().getSimpleName()
                                    + " —— 启动即失败，避免运行期随机选中一个造成不可复现的行为差异");
                }));
        log.info("驱动注册完成：{} 个 → {}", byKey.size(), byKey.keySet());
    }

    private static String key(String vendorCode, String deviceType) {
        return vendorCode + "::" + deviceType;
    }

    public Optional<DeviceDriver> find(String vendorCode, String deviceType) {
        return Optional.ofNullable(byKey.get(key(vendorCode, deviceType)));
    }

    /**
     * 取 driver 并**校验指令合法**，然后下发。
     *
     * <p>两道校验缺一不可：
     * <ol>
     *   <li><b>族校验</b> —— 给充电桩发 {@code EJECT_SLOT} 在这里被拒，
     *       而不是发出去等厂商云返回一个看不懂的错误码；</li>
     *   <li><b>manifest 白名单</b> —— 族里有、但这家厂商没实现的指令同样拒绝。
     *       白名单也是「供应商只做设备管理」红线的执行手段：
     *       厂商的订单/支付类能力不在白名单里，于是根本调不到。</li>
     * </ol>
     */
    public String dispatch(String vendorCode, String deviceType, String sn,
                           String command, Map<String, Object> params) {
        DeviceDriver driver = find(vendorCode, deviceType).orElseThrow(() ->
                new IllegalArgumentException(
                        "未注册驱动: vendor=" + vendorCode + " deviceType=" + deviceType));

        DriverManifest m = driver.manifest();
        if (!CommandFamily.supportsCommand(m.family(), command)) {
            throw new IllegalArgumentException(
                    "指令 " + command + " 不属于 " + m.family() + " 族 —— "
                            + "给该设备类型发这条指令是调用方的错误，在下发前拒绝");
        }
        if (!m.commands().contains(command)) {
            throw new IllegalArgumentException(
                    "供应商 " + vendorCode + " 未声明支持指令 " + command
                            + "（已声明：" + m.commands() + "）");
        }
        return driver.send(sn, command, params);
    }

    /** 已注册的能力清单，供运营端「供应商接入」页展示。 */
    public List<DriverManifest> manifests() {
        return byKey.values().stream().map(DeviceDriver::manifest)
                .sorted((a, b) -> (a.vendorCode() + a.deviceType())
                        .compareTo(b.vendorCode() + b.deviceType()))
                .toList();
    }
}
