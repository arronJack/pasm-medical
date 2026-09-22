#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/demo/*.bat 的静态契约检查 + 真跑一遍。

为什么专门为两个 .bat 写检查：**编码和行尾写错不会报错**，只在用户双击时表现为
一串 "'xxx' is not recognized as an internal or external command"。
而且那句话随控制台代码页变（中文 Windows 上是"不是内部或外部命令"），
加上错误行会互相错位，看起来完全不像"文件编码问题"。
2026-09-22 实测踩到：**LF 行尾 + UTF-8 中文 + 跨行括号块三条同犯**，满屏报错。

检查项：
  A 静态契约  纯 ASCII / 全 CRLF 无孤立 LF / echo 与 title 行无未转义的 > < | &
              / 括号必须同行配平（跨行括号块在 LF 下会崩）/ 不按名字调 powershell
  B 反例对照  故意造 4 种坏文件（LF、非 ASCII、跨行括号块、调 powershell），
              **每一种都必须被 A 抓到** —— 抓不到就说明 A 的检查项是空的（假绿）
  C 真跑      cmd /c start-demo.bat --check（仅 Windows）：不允许出现"无法识别"类报错

用法：
  python tools/check_demo_launchers.py              # 全跑（Windows 上含 C）
  python tools/check_demo_launchers.py --no-run     # 只做静态检查 + 反例对照
  python tools/check_demo_launchers.py --fix        # 先把孤立 LF 规范成 CRLF 再检查
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEMO = ROOT / "tools" / "demo"
BATS = ("start-demo.bat", "stop-demo.bat")

# "命令无法识别" 在不同代码页下的两种常见措辞。任何一条出现都说明 cmd 把某行当成了命令。
PARSE_ERRORS = (
    "不是内部或外部命令",
    "is not recognized as an internal or external command",
    "系统找不到指定的路径",
    "The system cannot find the path specified",
)

ANCHOR = b"setlocal EnableExtensions"


def decode_console(raw: bytes) -> str:
    """cmd 的输出按控制台代码页编码；中文 Windows 是 936，别的机器可能是 utf-8。"""
    for enc in ("cp936", "utf-8", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return repr(raw)


def lint(name: str, data: bytes) -> list[str]:
    issues: list[str] = []

    # ① 纯 ASCII：非 ASCII 会在别的代码页下变乱码，半截还会被当命令执行
    for i, b in enumerate(data):
        if b > 0x7F:
            line_no = data[:i].count(0x0A) + 1
            issues.append(f"{name}:{line_no} 含非 ASCII 字节 0x{b:02x}（本文件必须纯 ASCII）")
            break

    # ② 全 CRLF：孤立 LF 会让 cmd 错行
    lone = [i for i, b in enumerate(data) if b == 0x0A and (i == 0 or data[i - 1] != 0x0D)]
    if lone:
        issues.append(f"{name}: {len(lone)} 处孤立 LF（cmd 会错行，跨行块还会连环崩）")

    text = data.decode("ascii", errors="replace")
    for no, ln in enumerate(text.split("\r\n"), 1):
        s = ln.strip()
        low = s.lower()
        if not s or low.startswith("rem") or low.startswith("::"):
            continue
        if low.startswith(("echo", "title")):
            unescaped = s
            for pair in ("^>", "^<", "^|", "^&"):
                unescaped = unescaped.replace(pair, "")
            for ch in (">", "<", "|", "&"):
                if ch in unescaped:
                    issues.append(f"{name}:{no} echo/title 行含未转义的 {ch!r} -> {s[:56]}")
        if re.search(r"(?<![\w\\.-])powershell(\.exe)?(?=\s|$)", low):
            issues.append(f"{name}:{no} 按名字调 powershell（很多机器 PATH 里只有 pwsh）")
        if s.count("(") != s.count(")"):
            issues.append(f"{name}:{no} 括号未同行配平（跨行括号块在 LF 下会崩）-> {s[:56]}")
    return issues


def negative_controls(name: str, data: bytes) -> list[tuple[str, bool, str]]:
    """反例对照：把已知的坏写法造出来，A 必须抓到。"""
    cases: list[tuple[str, bytes]] = [
        ("LF 行尾", data.replace(b"\r\n", b"\n")),
        # bytes 字面量只能放 ASCII，中文要先 encode —— 这正好也是我们要复现的"UTF-8 中文注释"
        ("非 ASCII 中文注释", data.replace(ANCHOR, ANCHOR + b"\r\nREM " + "监控".encode("utf-8"), 1)),
        ("跨行括号块", data.replace(ANCHOR, ANCHOR + b"\r\nif 1==1 (\r\necho x", 1)),
        ("按名字调 powershell",
         data.replace(ANCHOR, ANCHOR + b'\r\npowershell -NoProfile -Command "echo hi"', 1)),
    ]
    out: list[tuple[str, bool, str]] = []
    for label, mutated in cases:
        assert mutated != data, f"{name}: 反例 {label} 没有真正改动内容（锚点没命中）"
        got = lint(name, mutated)
        detail = got[0].split(" ", 1)[-1] if got else "没抓到"
        out.append((f"反例被抓到：{label}", bool(got), detail))
    return out


def run_check(name: str, args: list[str]) -> tuple[int, str]:
    env = dict(os.environ)
    env["NOPAUSE"] = "1"
    env["PATH"] = r"C:\Windows\System32;C:\Windows;" + env.get("PATH", "")
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
        env.pop(k, None)
    r = subprocess.run(["cmd", "/c", str(DEMO / name), *args],
                       capture_output=True, cwd=str(ROOT), env=env, timeout=300)
    return r.returncode, decode_console(r.stdout + r.stderr)


def fix_line_endings(path: pathlib.Path) -> bool:
    """把孤立 LF 规范成 CRLF。返回是否改动过。"""
    raw = path.read_bytes()
    fixed = raw.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    if fixed != raw:
        path.write_bytes(fixed)
        return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-run", action="store_true", help="跳过真跑（只做静态 + 反例）")
    ap.add_argument("--fix", action="store_true", help="先把孤立 LF 规范成 CRLF")
    args = ap.parse_args()

    results: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append((name, ok, detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f"   {detail}" if detail else ""))

    print(f"仓库根：{ROOT}\n")

    for bat in BATS:
        path = DEMO / bat
        if not path.exists():
            check(f"{bat} 存在", False, str(path))
            continue
        if args.fix:
            print(f"  [fix] {bat} 行尾规范化：{'已改动' if fix_line_endings(path) else '无需改动'}")
        data = path.read_bytes()

        print(f"--- {bat} ---")
        issues = lint(bat, data)
        check(f"{bat} 静态契约", not issues, f"{len(issues)} 处问题")
        for it in issues:
            print(f"        - {it}")
        crlf = data.count(b"\r\n")
        print(f"        ({len(data)} 字节, {crlf} 行, 纯ASCII={all(b <= 0x7F for b in data)})")
        for label, ok, detail in negative_controls(bat, data):
            check(f"{bat} {label}", ok, detail)
        print()

    if not args.no_run:
        if os.name != "nt":
            print("  [SKIP] 真跑：非 Windows 平台，跳过（cmd 不存在）\n")
        else:
            print("--- 真跑 start-demo.bat --check ---")
            rc, out = run_check("start-demo.bat", ["--check"])
            for ln in [x for x in out.splitlines() if x.strip()][:12]:
                print("        | " + ln)
            hit = [m for m in PARSE_ERRORS if m in out]
            check("--check 没有出现'命令无法识别'类报错", not hit, f"命中={hit}")
            coherent = "[OK] preflight passed" in out or "[X]" in out
            check("--check 给出了明确结论（通过或具体缺什么）", coherent, f"rc={rc}")
            print()

    print("=" * 64)
    bad = [n for n, ok, _ in results if not ok]
    for n, ok, detail in results:
        print(f"  {'v' if ok else 'X'} {n}" + (f"   {detail}" if detail else ""))
    print("=" * 64)
    print(f"结果：{len(results) - len(bad)} 项通过，{len(bad)} 项失败")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
