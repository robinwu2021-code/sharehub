package ai.neargo.powerbank.common;

/** 关键词匹配 helper（对齐 ops-web mock 的 kwHit：空关键词命中全部，否则任一字段包含即命中，忽略大小写）。 */
public final class Kw {

    private Kw() {
    }

    public static boolean hit(String keyword, String... fields) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.toLowerCase();
        for (String f : fields) {
            if (f != null && f.toLowerCase().contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
