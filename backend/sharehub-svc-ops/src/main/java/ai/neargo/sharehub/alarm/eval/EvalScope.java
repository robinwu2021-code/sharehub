package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.api.platform.dto.SiteBrief;

import java.util.List;

/** 一轮判定负责的站点批次。 */
public record EvalScope(List<SiteBrief> sites) {
}
