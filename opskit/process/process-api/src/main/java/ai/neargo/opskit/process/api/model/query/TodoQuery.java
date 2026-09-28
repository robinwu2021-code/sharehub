package ai.neargo.opskit.process.api.model.query;

/**
 * @param origin    ALL / PROCESS / TICKET；空 = ALL
 * @param claimable true = 只看可领；false = 只看在我名下；空 = 两者
 */
public record TodoQuery(String origin, Boolean claimable, Integer page, Integer size) {
}
