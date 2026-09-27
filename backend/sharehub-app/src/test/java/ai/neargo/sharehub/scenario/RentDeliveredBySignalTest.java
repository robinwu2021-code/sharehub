package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEvent;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.IngestResult;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 借出要**等柜机的回执**才算出宝成功。
 *
 * <h3>为什么专门关掉模拟来测</h3>
 * 本测试类把 {@code sharehub.trade.simulate-eject.enabled} 置 false —— 其余环境默认开着，
 * 下单后立刻自补一次回执（{@code EjectSimulation}），行为与改造前一样。
 * 那个开关是为「没有真机」准备的，但它会**盖住这条链路本身是否接通**：
 * 开着的时候，不管 DISPENSING→IN_USE 是由回执推进的、还是又被谁写死了，用例都一样绿。
 *
 * <h3>钉三件事</h3>
 * <ol>
 *   <li>下单后订单停在 {@code DISPENSING} —— 而不是像改造前那样直接 IN_USE，
 *       宝没出来也照常计费；</li>
 *   <li>设备回执（{@code SLOT_EJECT_OK}）把它推到 {@code IN_USE}；</li>
 *   <li>弹仓超时（{@code EJECT_TIMEOUT}）把它撤成 {@code CLOSED} 且**不收费** ——
 *       这条路上的状态机边（DISPENSING→CLOSED）与自愈告警 RENT_NOT_DELIVERED 早就存在，
 *       只是此前没有任何订单会停在 DISPENSING，于是永远走不到。</li>
 * </ol>
 *
 * <p>驱动清单（{@code DemoPowerbankDriver}）归一化出来的是 {@code EJECT_OK} / {@code ITEM_INSERTED}，
 * 字典里对应的码叫 {@code SLOT_EJECT_OK} / {@code RETURN_SN_SEEN}。此前入口按字典原样查，
 * 驱动的名字进来就被当成「未知信号码」丢掉 —— 真柜机回执永远到不了订单，单子卡在出宝中。
 * 见 {@link #driverVocabularyIsTranslated()}。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "sharehub.trade.simulate-eject.enabled=false")
class RentDeliveredBySignalTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DeviceSignalService signals;

    @Test
    @DisplayName("没有回执就停在出宝中；回执到了才 IN_USE；超时则撤单不收费")
    void deliveryIsDrivenByDeviceSignal() {
        String cabinetNo = jdbc.queryForObject(
                "select cabinet_no from dev_cabinet where deleted=0 and available_count>0 "
                        + "and online_status='ONLINE' and status<>'FAULT' limit 1", String.class);
        assertThat(cabinetNo).as("测试库里得有一台在线、非故障、有宝的柜机").isNotBlank();

        String token = consumer();
        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cabinetNo, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();

        assertThat(status(orderNo))
                .as("柜机还没回执，这一单不该是「使用中」—— 改造前它在这里就已经开始计费了")
                .isEqualTo("DISPENSING");

        Integer slot = jdbc.queryForObject("select slot_index from ord_order where order_no=?", Integer.class, orderNo);
        signal(cabinetNo, "SLOT_EJECT_OK", slot);

        assertThat(status(orderNo)).as("回执到了才进使用中").isEqualTo("IN_USE");
        assertThat(get("/mp/trade/orders/" + orderNo, token).okData().path("status").asText())
                .as("C 端读到的也得是使用中").isEqualTo("IN_USE");

        // —— 另一单：超时 ——
        String failing = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cabinetNo, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();
        assertThat(status(failing)).isEqualTo("DISPENSING");

        Integer failSlot = jdbc.queryForObject("select slot_index from ord_order where order_no=?", Integer.class, failing);
        signal(cabinetNo, "EJECT_TIMEOUT", failSlot);

        assertThat(status(failing)).as("柜机报了弹仓超时 → 撤单").isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select fee_amount from ord_order where order_no=?", Double.class, failing))
                .as("没拿到宝就不该收钱").isZero();
    }

    @Test
    @DisplayName("驱动词表（EJECT_OK / ITEM_INSERTED）进来要被认出来，不能当未知码丢掉")
    void driverVocabularyIsTranslated() {
        String cabinetNo = jdbc.queryForObject(
                "select cabinet_no from dev_cabinet where deleted=0 and available_count>0 "
                        + "and online_status='ONLINE' and status<>'FAULT' limit 1", String.class);
        String token = consumer();
        String orderNo = post("/mp/trade/orders/rent",
                Map.of("cabinetNo", cabinetNo, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();
        assertThat(status(orderNo)).isEqualTo("DISPENSING");

        Integer slot = jdbc.queryForObject("select slot_index from ord_order where order_no=?", Integer.class, orderNo);
        IngestResult r = signal(cabinetNo, "EJECT_OK", slot);
        assertThat(r.unknownCode()).as("EJECT_OK 是驱动声明的事件，不是未知码").isZero();
        assertThat(status(orderNo)).as("驱动报的出宝成功也要推进订单").isEqualTo("IN_USE");

        assertThat(signal(cabinetNo, "ITEM_INSERTED", slot).unknownCode())
                .as("ITEM_INSERTED 同理（字典里叫 RETURN_SN_SEEN）").isZero();
    }

    private String consumer() {
        String phone = "+97150" + (System.nanoTime() % 10000000L);
        post("/mp/auth/otp", Map.of("phone", phone), null).okData();
        return post("/mp/auth/login",
                Map.of("grantType", "phone_otp", "phone", phone, "otp", "000000"), null)
                .okData().path("token").asText();
    }

    private String status(String orderNo) {
        return jdbc.queryForObject("select status from ord_order where order_no=?", String.class, orderNo);
    }

    private IngestResult signal(String cabinetNo, String code, Integer slot) {
        return signals.ingest(new DeviceEventBatch(List.of(new DeviceEvent(
                "E" + System.nanoTime(), code, cabinetNo, slot, null, "TEST", null,
                LocalDateTime.now(), Map.of()))));
    }
}
