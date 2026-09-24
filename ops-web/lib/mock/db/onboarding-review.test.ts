import { describe, it, expect } from "vitest";
import { mockApi } from "../../api/mock";

/**
 * 进件审核：**审核是动作，不是改字段**。
 *
 * 此前运营端把「审核状态」做成编辑表单里的一个下拉，改成「已通过」也能保存成功 ——
 * 而后端那条路径根本不受理状态，于是 **mock 绿、线上静默不动**。
 * 状态机落在服务端（mock db），绕过表单直调同样被拒。
 */
describe("进件审核", () => {
  const draft = () => mockApi.saveVenueOnboarding({
    venueName: "审核测试门店", contact: "+97140000000", industry: "购物中心",
  });

  it("通过 → 真的建出场地方并回填编号", async () => {
    // 只翻状态不建场地方的话，运营下一步想给它签合同时才会发现查无此人
    const ob = await draft();
    const done = await mockApi.reviewVenueOnboarding(ob.onboardingNo, true);
    expect(done.status).toBe("APPROVED");
    expect(done.venueNo, "通过后必须回填场地方号").toBeTruthy();

    const venues = await mockApi.listVenues({ page: 1, size: 200 });
    expect(venues.list.some((v) => v.venueNo === done.venueNo), "那个号必须真有对应的行").toBe(true);
  });

  it("驳回必须给原因", async () => {
    // 不给原因，申请人只能反复猜着重提，每次都要运营再看一遍
    const ob = await draft();
    await expect(mockApi.reviewVenueOnboarding(ob.onboardingNo, false)).rejects.toThrow();
    const done = await mockApi.reviewVenueOnboarding(ob.onboardingNo, false, "营业执照不清晰");
    expect(done.status).toBe("REJECTED");
    expect(done.reviewNote).toBe("营业执照不清晰");
  });

  it("审过的不能再审", async () => {
    const ob = await draft();
    await mockApi.reviewVenueOnboarding(ob.onboardingNo, true);
    await expect(mockApi.reviewVenueOnboarding(ob.onboardingNo, true)).rejects.toThrow();
  });

  it("审过的内容也改不动", async () => {
    // 审核结论是对「当时那份内容」做的，事后改内容结论就对不上它审过的东西了
    const ob = await draft();
    await mockApi.reviewVenueOnboarding(ob.onboardingNo, true);
    await expect(mockApi.saveVenueOnboarding({ ...ob, venueName: "偷偷改掉" })).rejects.toThrow();
  });

  it("保存不受理状态——它只能由审核推动", async () => {
    // 这正是此前线上与 mock 分叉的那一处：表单把 status 一起提交，mock 存下来了、后端没有
    const ob = await mockApi.saveVenueOnboarding({
      venueName: "状态不该被表单改", contact: "+97140000001", industry: "写字楼",
      status: "APPROVED",
    });
    expect(ob.status, "新建的进件一律待审").toBe("PENDING");
  });
});
