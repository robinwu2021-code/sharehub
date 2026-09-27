package ai.neargo.opskit.platform.store.dao.archfixture;

/** 违反 A6：公开方法第一个参数不是租户键。 */
public class UnscopedDao {
    public int countAll(String table) {
        return 0;
    }
}
