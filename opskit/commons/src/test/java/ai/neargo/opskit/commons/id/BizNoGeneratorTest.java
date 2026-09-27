package ai.neargo.opskit.commons.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BizNoGeneratorTest {

    @Test
    void recordNumbersFitTheColumnAndUseCrockfordAlphabet() {
        String no = new BizNoGenerator(Clock.systemUTC()).next("TK");
        assertThat(no).hasSize(28).startsWith("TK").matches("TK[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void publicIdsAreLowercaseAndFitTheTenantColumn() {
        String id = new BizNoGenerator(Clock.systemUTC()).nextPublicId("ws");
        assertThat(id).startsWith("ws_").hasSize(29).isLowerCase();
        assertThat(id.length()).isLessThanOrEqualTo(32);
    }

    @Test
    void laterMillisecondsSortAfterEarlierOnes() {
        BizNoGenerator earlier = new BizNoGenerator(Clock.fixed(Instant.ofEpochMilli(1_758_900_000_000L), ZoneOffset.UTC));
        BizNoGenerator later = new BizNoGenerator(Clock.fixed(Instant.ofEpochMilli(1_758_900_000_001L), ZoneOffset.UTC));
        assertThat(later.next("PI")).isGreaterThan(earlier.next("PI"));
    }

    @Test
    void doesNotCollideWithinTheSameMillisecond() {
        BizNoGenerator g = new BizNoGenerator(Clock.fixed(Instant.ofEpochMilli(1_758_900_000_000L), ZoneOffset.UTC));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(g.next("TK"))).isTrue();
        }
    }

    @Test
    void rejectsBadPrefixes() {
        BizNoGenerator g = new BizNoGenerator(Clock.systemUTC());
        assertThatThrownBy(() -> g.next("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.next("T-K")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.nextPublicId("workspaceslong")).isInstanceOf(IllegalArgumentException.class);
    }
}
