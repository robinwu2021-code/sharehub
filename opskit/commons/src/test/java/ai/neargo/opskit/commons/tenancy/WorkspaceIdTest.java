package ai.neargo.opskit.commons.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkspaceIdTest {

    @Test
    void emptyMeansAccountLevelTemplate() {
        assertThat(WorkspaceId.TEMPLATE.isTemplate()).isTrue();
        assertThat(new WorkspaceId("ws_1").isTemplate()).isFalse();
    }

    @Test
    void rejectsValuesThatWouldNotFitOrCouldInjectIntoKeys() {
        assertThatThrownBy(() -> new WorkspaceId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceId("WS_1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceId("ws:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceId("x".repeat(33))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountId("")).isInstanceOf(IllegalArgumentException.class);
    }
}
