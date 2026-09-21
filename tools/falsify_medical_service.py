# -*- coding: utf-8 -*-
"""反例对照：故意改坏医疗认知服务，确认端到端脚本**真的抓得住**。

端到端 17 项全绿也可能只是"断言恒真"。这里挑两处最要命的改坏：

1. **患者隔离失效** —— 让 agent_id 忽略患者维度（所有患者共享一个认知实例）。
   这是医疗场景最严重的一类缺陷（数据泄露），必须能被抓住。
2. **相关性闸门失效** —— 让"无依据"也能返回依据。
   这会让"答不上来就拒答"的保障形同虚设，最终表现为答非所问。

跑完无条件还原。

用法::

    python tools/falsify_medical_service.py
"""
from __future__ import annotations

import io
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
DOMAIN = REPO / "pasm_medical" / "domain.py"
SERVICE = REPO / "pasm_medical" / "service.py"

#: (要验的断言名, 目标文件, 原文锚点, 改坏后的文本)
CASES = [
    ("患者 A **看不到**患者 B 的过敏史", DOMAIN,
     '    return "%s_%s" % (t[:16], p[:48])',
     '    return t[:16]                 # 反例：丢掉患者维度 → 全租户共用一个实例'),
    ("无依据时必须拒答（不编造）", SERVICE,
     "            keep = relevance.select_evidence(question, raw, limit=k)",
     "            keep = list(raw)      # 反例：不过闸门 → 弱命中被当成依据"),
]


def run():
    env = dict(os.environ)
    extra = [str(REPO), str(REPO.parent / "pasm-skills"),
             str(REPO.parent / "pasm-framework")]
    env["PYTHONPATH"] = os.pathsep.join(extra + [env.get("PYTHONPATH", "")])
    p = subprocess.run([sys.executable, str(HERE / "e2e_medical_service.py")],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env, cwd=str(REPO))
    return (p.stdout or "") + (p.stderr or ""), p.returncode


def main() -> int:
    originals = {f: io.open(f, encoding="utf-8", newline="").read()
                 for f in {DOMAIN, SERVICE}}

    out, rc = run()
    if rc != 0 or "0 项失败" not in out:
        print("[中止] 基线不是全绿，先修好再谈反例。rc=%s" % rc)
        print(out[-600:])
        return 2
    print("[基线] 端到端全绿 ✓\n")

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
        for f, txt in originals.items():
            io.open(f, "w", encoding="utf-8", newline="").write(txt)

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
