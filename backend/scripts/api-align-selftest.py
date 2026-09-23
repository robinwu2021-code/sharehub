#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对齐工具链的自测 —— 给量尺本身立卡口。

## 为什么需要它

`api-extract.py` + `api-align.py` 是排期的唯一量尺：
[实现状态总表] §六「131 条前端未接线」就出自它们的输出。

2026-09-23 实测，这条链路在**三个方向同时出错**，而且错的方向互相矛盾：

| 缺陷 | 后果 | 危险在哪 |
|---|---|---|
| align 的调用点正则要求路径紧跟 `(` | 三元写法 `x.no ? `/a/${x.no}` : "/a"` 整条漏扫 | 多报 84%：113 条待办里 95 条不存在 |
| extract 只取多值映射的第一个路径 | `@PostMapping({"", "/{no}"})` 丢掉第二个 | **把能用的功能报成运行期 404** |
| align 把嵌套类型成员拍平 | `trend: {gmv, orders}[]` 的成员当顶层字段 | DashboardStats 凭空多 4 条缺口，实际一条不缺 |

**这三个缺陷都不会让脚本报错，它只会自信地给出错的数字。**
而基于错数字的排期比没有排期更贵 —— 它让人去做不存在的工作，
同时对真实的 404 打勾说「已清零 ✅」。

## 自测的形状

不测"数字是多少"（那随代码变），测**"这三类输入认不认得出"**。
每条都给一段最小输入 + 一条断言；任一条红，说明量尺又坏了。

用法：`python3 scripts/api-align-selftest.py`（退出码 0 = 通过）
"""
import io
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

fails = []


def check(name, ok, detail=''):
    print(('  ✅ ' if ok else '  ❌ ') + name + (('  —— ' + detail) if detail and not ok else ''))
    if not ok:
        fails.append(name)


# ─────────────────── ① extract：多值映射要取全部路径 ───────────────────

JAVA = '''
@RestController
@RequestMapping("/api/demo/things")
public class DemoController {
    @GetMapping
    public List<Thing> list() { return null; }

    /** 新建或编辑合一。 */
    @PostMapping({"", "/{thingNo}"})
    @PreAuthorize("@perm.has('demo:thing:manage')")
    public Thing save(@PathVariable(required = false) String thingNo, @RequestBody Thing in) { return null; }
}
'''


def test_extract_multivalue():
    import importlib.util
    spec = importlib.util.spec_from_file_location('apiextract', os.path.join(HERE, 'api-extract.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    d = tempfile.mkdtemp()
    f = os.path.join(d, 'DemoController.java')
    io.open(f, 'w', encoding='utf-8').write(JAVA)
    eps = mod.parse_controller(f, {})
    paths = {(e['verb'], e['path']) for e in eps}
    check('① extract 取到多值映射的**两个**路径',
          ('POST', '/api/demo/things') in paths and ('POST', '/api/demo/things/{thingNo}') in paths,
          '实际取到 %s' % sorted(p for v, p in paths if v == 'POST'))


# ─────────────────── ② align：三元/模板串调用要认得出 ───────────────────

TS_CALLS = '''
export const demoHttp = {
  listThings: () => client.get("/api/demo/things"),
  saveThing: (x) => client.post(x.thingNo ? `/api/demo/things/${x.thingNo}` : "/api/demo/things", x),
};
'''


def test_align_ternary():
    import importlib.util
    spec = importlib.util.spec_from_file_location('apialign', os.path.join(HERE, 'api-align.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    got = {(c['verb'], c['path']) for c in mod.calls_in(TS_CALLS, 'demo.ts')}
    check('② align 从三元里取到**两个**调用（不是一个、也不是零个）',
          ('POST', '/api/demo/things') in got and ('POST', '/api/demo/things/{}') in got,
          '实际 %s' % sorted(got))


# ─────────────────── ③ align：嵌套类型不得拍平 ───────────────────

TS_TYPE = '''
  gmvToday: number;
  currency: string;
  trend: { day: string; gmv: number; orders: number }[];
  todos: { pendingRefunds: number; pendingWithdrawals: number };
'''


def test_align_nested():
    import importlib.util
    spec = importlib.util.spec_from_file_location('apialign2', os.path.join(HERE, 'api-align.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    names = {f['name'] for f in mod.top_level_fields(TS_TYPE)}
    leaked = names & {'day', 'gmv', 'orders', 'pendingRefunds', 'pendingWithdrawals'}
    check('③ align 只取顶层字段，嵌套成员不外泄',
          names == {'gmvToday', 'currency', 'trend', 'todos'} and not leaked,
          '取到 %s（泄漏 %s）' % (sorted(names), sorted(leaked)))


if __name__ == '__main__':
    print('对齐工具链自测（量尺自己的卡口）')
    test_extract_multivalue()
    test_align_ternary()
    test_align_nested()
    print()
    if fails:
        print('❌ %d 条未通过 —— 量尺坏了，此时它给出的任何数字都不能用来排期' % len(fails))
        sys.exit(1)
    print('✅ 三条全过')
