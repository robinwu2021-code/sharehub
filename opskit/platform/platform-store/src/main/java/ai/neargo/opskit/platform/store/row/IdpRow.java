package ai.neargo.opskit.platform.store.row;

import java.util.Map;

/** config / claimMapping 以 JSON 存（plat_idp.config · claim_mapping）；clientSecret 在 config 里是密文。 */
public record IdpRow(String accountId, String issuer, String kind, Map<String, Object> config, Map<String, Object> claimMapping,
        boolean jitEnabled, String status, long updatedAtMs) {
}
