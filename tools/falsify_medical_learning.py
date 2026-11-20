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
#:
#: ★★ 锚点匹配必须**行尾不敏感**（2026-11-20 修）：本仓 ``service.py``/
#:    ``domain.py``/``llm.py`` 是 CRLF、其余是 LF（无 ``*.py`` 的 eol 契约，
#:    ``core.autocrlf=true`` 让检出态随人漂移）—— 多行锚点写死 ``\n``
#:    就会在这些文件上**静默失配**，反例根本没被执行，却被记成"没抓住"。
#:    这种失败没有任何异常，只有"结论：1 处没抓住"。
#:
#:    所以本文件统一用 :func:`_normalize` 归一化后再匹配 —— 既保留多行锚点
#:    的精确性（单行锚点会把"只改一行"变成"改坏语法"，反例就失效了：
#:    语法错误也能让 e2e 变红，那是**假抓住**），又能跨行尾风格工作。
#:    同目录 ``falsify_medical_service.py`` 早有同款教训（多行锚点转义写错 →
#:    静默不命中），此处一并治本。
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


def _normalize(text: str) -> str:
    """行尾归一化（CRLF/CR → LF）。匹配锚点前用。

    ★ 这样即使有人又写了多行锚点，也不会因为本机/仓库里行尾风格不同而失配。
      失配 = 反例没被执行 = 假失败，且毫无异常提示 —— 这种坑必须从根上堵。
    """
    return (text or "").replace("\r\n", "\n").replace("\r", "\n")


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
            # ★ 行尾不敏感匹配：锚点与原文都归一化后再比。
            #   命中则把**原文那一段**换掉（用归一化后的原文写回，再还原原始行尾风格）。
            base_n = _normalize(base)
            old_n = _normalize(old)
            if old_n not in base_n:
                print("[跳过] 锚点没命中（代码改过？）: %s" % name)
                bad += 1
                continue
            mutated = base_n.replace(old_n, _normalize(new), 1)
            # 写回时沿用原文件的主导行尾，避免"改一次代码就把整个文件的行尾改了"
            io.open(target, "w", encoding="utf-8", newline="").write(
                mutated.replace("\n", "\r\n") if "\r\n" in base else mutated)
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
