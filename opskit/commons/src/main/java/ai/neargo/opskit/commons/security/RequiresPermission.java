package ai.neargo.opskit.commons.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用例所需的产品权限（01 §九 的码）。应用服务的每个公开方法要么标它、要么标 {@link SystemAction}，
 * 由架构测试 A12 强制 —— 漏标的用例在构建期就会被发现，而不是上线后才发现谁都能调。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {
    /** 满足任一即可。 */
    String[] value();
}
