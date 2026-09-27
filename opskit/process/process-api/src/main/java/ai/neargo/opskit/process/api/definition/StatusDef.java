package ai.neargo.opskit.process.api.definition;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.Objects;

/** @param gate 可空 */
public record StatusDef(String code, LocalizedText name, StatusCategory category, GateDef gate) {
    public StatusDef {
        Objects.requireNonNull(category, "category");
    }
}
