package ai.neargo.opskit.ticket.api.definition;

import java.util.List;

/** 选项来源：内联列表，或引用一个选项集（二选一）。 */
public record Options(List<OptionDef> inline, String optionSet) {
    public Options {
        inline = inline == null ? List.of() : List.copyOf(inline);
    }

    public static Options of(List<OptionDef> inline) {
        return new Options(inline, null);
    }

    public static Options ref(String optionSet) {
        return new Options(List.of(), optionSet);
    }
}
