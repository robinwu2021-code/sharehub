package ai.neargo.opskit.commons.actor;

import java.util.Locale;
import java.util.Objects;

/**
 * 主体引用，文本形态为 {@code kind:id}（如 {@code member:mb_01j8}、{@code group:field-ops}）。
 * 这个文本形态同时是候选表 {@code candidate_key} 与参与者表 {@code principal} 的存储值。
 */
public record PrincipalRef(Kind kind, String id) {

    public enum Kind {
        MEMBER, GROUP, ROLE, PERM, APP, CONTACT, PARTICIPANT;

        String prefix() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 与候选表列宽 160 对齐，留出前缀。 */
    private static final int MAX_ID_LENGTH = 140;

    public PrincipalRef {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH || id.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("invalid principal id: " + id);
        }
    }

    public static PrincipalRef member(String id) {
        return new PrincipalRef(Kind.MEMBER, id);
    }

    public static PrincipalRef group(String code) {
        return new PrincipalRef(Kind.GROUP, code);
    }

    public static PrincipalRef role(String code) {
        return new PrincipalRef(Kind.ROLE, code);
    }

    public static PrincipalRef perm(String code) {
        return new PrincipalRef(Kind.PERM, code);
    }

    public static PrincipalRef parse(String text) {
        int colon = text == null ? -1 : text.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("principal must be kind:id, got: " + text);
        }
        Kind kind;
        try {
            kind = Kind.valueOf(text.substring(0, colon).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown principal kind in: " + text, e);
        }
        return new PrincipalRef(kind, text.substring(colon + 1));
    }

    public String key() {
        return kind.prefix() + ":" + id;
    }

    @Override
    public String toString() {
        return key();
    }
}
