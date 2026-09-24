package ai.neargo.sharehub.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 当前身份持有者（{@link CurrentUser}）—— 脱离 Spring 容器的那一层。
 *
 * <p>本类不起 Spring 上下文：{@code CurrentUser} 就是为了让身份在**没有容器的地方**
 * 也能读到，用 {@code @SpringBootTest} 测它等于测错了对象。
 */
class CurrentUserTest {

    @AfterEach
    void clean() {
        CurrentUser.clear();
    }

    private static LoginUser user(String no) {
        return new LoginUser(Realm.STAFF, no, "张三", "ADMIN", List.of("*"), "MAIN", null, null);
    }

    @Test
    @DisplayName("设了能读到，清了读不到")
    void setAndClear() {
        assertThat(CurrentUser.get()).isEmpty();
        CurrentUser.set(user("U1"));
        assertThat(CurrentUser.get().orElseThrow().userNo()).isEqualTo("U1");
        CurrentUser.clear();
        assertThat(CurrentUser.get()).isEmpty();
    }

    @Test
    @DisplayName("**不自动传播到子线程** —— 传播必须显式")
    void doesNotLeakIntoChildThreads() throws Exception {
        CurrentUser.set(user("U1"));
        AtomicReference<String> seen = new AtomicReference<>("<未运行>");

        Thread t = new Thread(() -> seen.set(CurrentUser.get().map(LoginUser::userNo).orElse(null)));
        t.start();
        t.join();

        assertThat(seen.get())
                .as("若用 InheritableThreadLocal，线程池里的线程会继承到创建池那一刻的陈旧身份，"
                        + "而且**看起来像是对的** —— 所以刻意不做自动传播")
                .isNull();
    }

    @Test
    @DisplayName("跨线程要显式 capture + runWith")
    void explicitPropagation() throws Exception {
        CurrentUser.set(user("U1"));
        LoginUser snapshot = CurrentUser.capture();
        AtomicReference<String> seen = new AtomicReference<>();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> CurrentUser.runWith(snapshot,
                    () -> seen.set(CurrentUser.get().orElseThrow().userNo()))).get();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(seen.get()).isEqualTo("U1");
    }

    @Test
    @DisplayName("**线程池复用不得泄漏** —— 上一个任务的身份不能被下一个读到")
    void poolThreadIsCleanBetweenTasks() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();   // 同一根线程跑两个任务
        AtomicReference<String> second = new AtomicReference<>("<未运行>");
        try {
            pool.submit(() -> CurrentUser.runWith(user("U1"), () -> { })).get();
            pool.submit(() -> second.set(CurrentUser.get().map(LoginUser::userNo).orElse(null))).get();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(second.get())
                .as("runWith 必须在 finally 里恢复 —— 否则第二个任务会读到第一个的身份，"
                        + "那不是「读到空」而是「读到别人」")
                .isNull();
    }

    @Test
    @DisplayName("嵌套 runWith 恢复外层身份，而不是清空")
    void nestedRestoresOuter() {
        CurrentUser.set(user("OUTER"));
        CurrentUser.runWith(user("INNER"), () ->
                assertThat(CurrentUser.get().orElseThrow().userNo()).isEqualTo("INNER"));

        assertThat(CurrentUser.get().orElseThrow().userNo())
                .as("清空的话外层身份会凭空消失 —— 嵌套调用里这一点很难查")
                .isEqualTo("OUTER");
    }

    @Test
    @DisplayName("任务抛异常也要恢复")
    void restoresEvenOnException() {
        CurrentUser.set(user("OUTER"));
        try {
            CurrentUser.runWith(user("INNER"), () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // 预期
        }
        assertThat(CurrentUser.get().orElseThrow().userNo()).isEqualTo("OUTER");
    }

    @Test
    @DisplayName("系统身份有明确主体，不是 null")
    void systemIdentityIsNamed() {
        LoginUser sys = CurrentUser.system("outbox-dispatcher");
        assertThat(sys.userNo())
                .as("审计里记 SYSTEM:outbox-dispatcher 比记空值有用得多")
                .isEqualTo("SYSTEM:outbox-dispatcher");
        assertThat(sys.realm()).isEqualTo(Realm.STAFF);
    }

    @Test
    @DisplayName("SecurityUtils 优先读 ThreadLocal（这才是「脱离容器」的意思）")
    void securityUtilsReadsHolderWithoutSpringContext() {
        // 没有任何 SecurityContext —— 这正是定时任务/@Async 的处境
        CurrentUser.set(user("U9"));
        assertThat(SecurityUtils.currentUser().orElseThrow().userNo()).isEqualTo("U9");
        assertThat(SecurityUtils.userNo()).isEqualTo("U9");
        assertThat(SecurityUtils.tenantId()).isEqualTo("MAIN");
        assertThat(SecurityUtils.realm()).isEqualTo(Realm.STAFF);
    }
}
