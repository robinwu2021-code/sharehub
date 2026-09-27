package ai.neargo.opskit.commons.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 不经产品权限判定的用例（定时触发、应用自身的系统动作、内部查询），须写明理由。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface SystemAction {
    String value();
}
