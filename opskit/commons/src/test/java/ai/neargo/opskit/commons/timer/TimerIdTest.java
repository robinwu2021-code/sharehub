package ai.neargo.opskit.commons.timer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.opskit.commons.tenancy.WorkspaceId;
import org.junit.jupiter.api.Test;

class TimerIdTest {

    @Test
    void roundTripsAndFitsTheSchedulerColumn() {
        TimerId id = new TimerId(new WorkspaceId("ws_01j8z3abcdefghijklmnopqrstu"), "PI01J8Z3ABCDEFGHJKMNPQRSTVWX", 12345);
        assertThat(TimerId.decode(id.encode())).isEqualTo(id);
        assertThat(id.encode().length()).isLessThanOrEqualTo(100);
    }

    @Test
    void rejectsTemplateWorkspaceAndColonsInKey() {
        assertThatThrownBy(() -> new TimerId(WorkspaceId.TEMPLATE, "k", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimerId(new WorkspaceId("ws_1"), "a:b", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TimerId.decode("ws_1:k")).isInstanceOf(IllegalArgumentException.class);
    }
}
