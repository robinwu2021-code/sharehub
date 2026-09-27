package ai.neargo.opskit.process.core.app.archfixture;

/** 违反 A8：业务层发出站 HTTP（等于同步回调接入方）。 */
public class RestCaller {
    org.springframework.web.client.RestClient client;
}
