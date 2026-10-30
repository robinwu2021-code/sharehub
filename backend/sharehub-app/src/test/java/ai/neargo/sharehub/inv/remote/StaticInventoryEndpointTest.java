package ai.neargo.sharehub.inv.remote;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认寻址：固定地址，去掉结尾斜杠；没配就是未配置（HttpInventoryApi 据此返回 not_configured）。 */
class StaticInventoryEndpointTest {

    @Test
    void trimsTrailingSlashAndReportsConfigured() {
        InventoryEndpoint e = new StaticInventoryEndpoint(" http://127.0.0.1:8088/ ");
        assertThat(e.mode()).isEqualTo("STATIC");
        assertThat(e.configured()).isTrue();
        assertThat(e.baseUrl()).isEqualTo("http://127.0.0.1:8088");
        e.failed(e.baseUrl(), new RuntimeException("x"));
        assertThat(e.baseUrl()).as("静态地址不因失败摘除").isEqualTo("http://127.0.0.1:8088");
    }

    @Test
    void blankIsNotConfigured() {
        assertThat(new StaticInventoryEndpoint(null).configured()).isFalse();
        assertThat(new StaticInventoryEndpoint("  ").configured()).isFalse();
    }
}
