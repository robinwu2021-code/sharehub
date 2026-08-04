package ai.neargo.sharehub.user.consumer;

/** C 端登录返回：统一 Bearer token + 用户号 + 是否新建。 */
public record ConsumerLoginVO(String token, String cUserNo, boolean isNew, String tenantNo) {
}
