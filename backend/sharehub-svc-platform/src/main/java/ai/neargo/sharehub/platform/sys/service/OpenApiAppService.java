package ai.neargo.sharehub.platform.sys.service;

import ai.neargo.sharehub.common.crud.CrudService;
import ai.neargo.sharehub.platform.sys.dto.SysDtos.OpenApiApp;
import ai.neargo.sharehub.platform.sys.entity.OpenapiApp;

/** 开放平台应用。凭据与限流的登记表 → 继承通用 CRUD；出参不含密钥哈希。 */
public interface OpenApiAppService extends CrudService<OpenapiApp, OpenApiApp> {

    /**
     * 重置密钥：服务端生成新明文 → 只落哈希 + 重置时间。
     * **明文既不入库也不出参**（口径见 `openapi_app.app_secret_hash` 的库注释：明文归 KMS/vault）；
     * 返回完整行，其中密钥只出掩码。这一下会作废调用方手上的旧密钥，属破坏性操作。
     */
    OpenApiApp resetSecret(String appNo);
}
