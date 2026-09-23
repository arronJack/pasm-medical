# -*- coding: utf-8 -*-
"""反例对照：故意改坏 P0.5 的医学动作后验，确认 e2e 脚本**真的抓得住**。

e2e_medical_learning.py 全绿也可能只是"断言恒真"。这里挑两处最要命的改坏：

1. **后效被忽略** —— 让 ``triage_candidates`` 不再读后验概率、永远返回 0.5。
   这样"否决某条建议后排序变化"这个核心验收就**永远成立不了**，
   必须能被 e2e 抓住（否则这个验收形同虚设）。
2. **否决不降权** —— 让 ``feedback_medical`` 把 reject 也当 success（永远升权）。
   这样"被否决科室概率下降"与"排序变化"都抓不住，必须被 e2e 抓住。

跑完无条件还原。

用法::

    python tools/falsify_medical_learning.py
"""
from __future__ import annotations

import io
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
SERVICE = REPO / "pasm_medical" / "service.py"

#: (要验的断言名, 目标文件, 原文锚点, 改坏后的文本)
CASES = [
    # ★ 让候选排序忽略后验：否决后顺序不变 → "排序变化"断言必须转红
    ("★ 否决规则建议科室后，分诊候选排序发生变化", SERVICE,
     '        scored = [(d, self.posterior.predict_op("triage:%s" % d, context))\n'
     '                  for d in pool]',
     '        scored = [(d, 0.5) for d in pool]  # 反例：忽略后验 → 排序永远不变'),
    # ★ 让 reject 也升权：否决后概率不降反升 → "概率下降"断言必须转红
    ("★ 否决后该科室采纳概率下降（predict < 0.5）", SERVICE,
     '        success = (decision == "adopt")',
     '        success = True  # 反例：否决也当采纳 → 后验永不降权'),
]


def run():
    env = dict(os.environ)
    extra = [str(REPO), str(REPO.parent / "pasm-skills"),
             str(REPO.parent / "pasm-framework")]
    env["PYTHONPATH"] = os.pathsep.join(extra + [env.get("PYTHONPATH", "")])
    p = subprocess.run([sys.executable, str(HERE / "e2e_medical_learning.py")],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env, cwd=str(REPO))
    out = (p.stdout or "") + (p.stderr or "")
    if p.returncode != 0:
        out += "\n[rc=%d]\n" % p.returncode
    return out, (0 if "0 项失败" in out and "[rc=" not in out else 1)


def main() -> int:
    originals = {SERVICE: io.open(SERVICE, encoding="utf-8", newline="").read()}

    out, rc = run()
    if rc != 0 or "0 项失败" not in out:
        print("[中止] 基线不是全绿，先修好再谈反例。rc=%s" % rc)
        print(out[-600:])
        return 2
    print("[基线] 医学动作后验 e2e 全绿 ✓\n")

    bad = 0
    try:
        for name, target, old, new in CASES:
            base = originals[target]
            if old not in base:
                print("[跳过] 锚点没命中（代码改过？）: %s" % name)
                bad += 1
                continue
            io.open(target, "w", encoding="utf-8", newline="").write(
                base.replace(old, new, 1))
            out2, rc2 = run()
            caught = (rc2 != 0) and (name in out2)
            print("%s %s  -> rc=%s 被抓=%s"
                  % ("[PASS]" if caught else "[FAIL]", name, rc2, caught))
            if not caught:
                bad += 1
                for line in out2.splitlines():
                    if line.strip().startswith("x ") or "结果" in line:
                        print("       " + line.strip())
            io.open(target, "w", encoding="utf-8", newline="").write(base)
    finally:
        io.open(SERVICE, "w", encoding="utf-8", newline="").write(originals[SERVICE])

    out3, rc3 = run()
    restored = (rc3 == 0) and ("0 项失败" in out3)
    print("\n[还原] 回到全绿=%s" % restored)
    if not restored:
        print(out3[-600:])
        bad += 1

    print("\n结论：%s" % ("全部反例都被抓住 ✓" if bad == 0 else "%d 处没抓住 ✗" % bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
