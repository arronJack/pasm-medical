#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/demo/*.bat 的静态契约检查 + 真跑一遍（含"无控制台父进程"这一路）。

为什么专门为两个 .bat 写检查：**编码和行尾写错不会报错**，只在用户双击时表现为
一串 "'xxx' is not recognized as an internal or external command"。
而且那句话随控制台代码页变（中文 Windows 上是"不是内部或外部命令"），
加上错误行会互相错位，看起来完全不像"文件编码问题"。
2026-09-22 实测踩到：**LF 行尾 + UTF-8 中文 + 跨行括号块三条同犯**，满屏报错。

同一天又踩到第二类问题（D/E 两组就是为它加的）：**父进程没有控制台时，
凡是靠"捕获子进程输出"的探测全部失效**——
  java -version > file      → 文件建成、0 字节（正常控制台 185 字节）
  netstat -ano > file       → 0 字节（正常控制台 28507 字节）
  echo x | findstr x        → 永不返回
于是启动器先报 "cannot read the java version"（对着一套完好的 JDK）；
改成读 release 文件后，又卡在 netstat|findstr 那一行 —— 卡死比报错更糟。
反面结论同样重要：**只看退出码的探测不受影响**，python 的 import 检查在那种父进程下照常通过。

检查项：
  A 静态契约  纯 ASCII / 全 CRLF 无孤立 LF / echo 与 title 行无未转义的 > < | &
              / 括号必须同行配平（跨行括号块在 LF 下会崩）/ 不按名字调 powershell
  B 反例对照  故意造 4 种坏文件（LF、非 ASCII、跨行括号块、调 powershell），
              **每一种都必须被 A 抓到** —— 抓不到就说明 A 的检查项是空的（假绿）
  F 静态      后端端口必须钉在命令行（--server.port=8081），否则环境里的
              SERVER__PORT / SERVER_PORT 会被 Spring 宽松绑定当成 server.port 把服务挪走；
              同组配一条反例（去掉钉端口必须被检查抓到）
  C 真跑      cmd /c start-demo.bat --check（仅 Windows）：不允许出现"无法识别"类报错
  D 真跑      java 版本探测走哪条分支（正常控制台 / 无控制台 / shim 回退）。
              判据不是"有没有 [OK] java"（两条分支都会打成功 → 假绿），
              而是 **[OK] java 行末尾括号里写的是哪条分支**
  E 真跑      端口探测：有监听时必须被报出来；
              反例 E2 把 :portstate 换回 netstat 版 → 无控制台时必须**报不出来**

用法：
  python tools/check_demo_launchers.py              # 全跑（Windows 上含 C/D/E）
  python tools/check_demo_launchers.py --no-run     # 只做静态检查 + 反例对照
  python tools/check_demo_launchers.py --fix        # 先把孤立 LF 规范成 CRLF 再检查
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import socket
import subprocess
import sys
import tempfile

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

# 无控制台父进程：DETACHED_PROCESS。实测这一路下 java / netstat 的输出会整段消失。
DETACHED_PROCESS = 0x00000008
# 后台启动的推荐组合：有（隐藏的）控制台，所以子进程输出正常。
CREATE_NO_WINDOW = 0x08000000
CREATE_NEW_PROCESS_GROUP = 0x00000200

JDK17_HINT = r"D:\Program Files\Java\jdk-17"
JAVAPATH_SHIM = r"C:\Program Files\Common Files\Oracle\Java\javapath\java.exe"

# 反例对照的两处"改坏"锚点，按当前文件的真实写法写死（命中数必须是 1）
MUT_RELEASE_PROBE = 'set "JSRC=release file"\r\n'
MUT_RELEASE_REPLACE = "goto java_probe_cmd\r\n"
MUT_PORT_PROBE = '"%PY%" -c "%PYARGS%" %1\r\n'
MUT_PORT_REPLACE = 'netstat -ano | findstr /c:":%1 " | findstr /i "LISTENING" >nul\r\n'

# 后端端口钉在命令行：环境里的 SERVER__PORT 能把它悄悄挪走，见 pinned_port_controls
PIN_PORT_ARG = "--server.port=8081"


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


def clean_env(extra: dict[str, str] | None = None) -> dict[str, str]:
    """干净的环境：去掉代理变量，并把 JAVA_HOME 指向 JDK 17（真跑启动器时就是这么做的）。

    不设 JAVA_HOME 的话，PATH 上的 java 往往是 1.8，启动器会正确地报
    "java 1.8 ... is too old" —— 那是真结论，但会让本检查永远测不到"通过"那一路。
    """
    env = dict(os.environ)
    env["NOPAUSE"] = "1"
    env["PATH"] = r"C:\Windows\System32;C:\Windows;" + env.get("PATH", "")
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
        env.pop(k, None)
    env.pop("PASM_MEDICAL_JAVA", None)
    if os.path.isdir(JDK17_HINT):
        env["JAVA_HOME"] = JDK17_HINT
    if extra:
        env.update(extra)
    return env


def run_check(name: str, args: list[str]) -> tuple[int, str]:
    r = subprocess.run(["cmd", "/c", str(DEMO / name), *args],
                       capture_output=True, cwd=str(ROOT), env=clean_env(), timeout=300)
    return r.returncode, decode_console(r.stdout + r.stderr)


# --------------------------------------------------------------------------
# D/E 组：真跑启动器的探测分支（含无控制台父进程 + 正反例对照）
# --------------------------------------------------------------------------

def run_bat(bat: pathlib.Path, args: list[str], flags: int = 0,
            extra_env: dict[str, str] | None = None,
            timeout: int = 60) -> tuple[int | None, str, bool]:
    """跑一个 bat，输出落临时文件（不用管道，免得多件事搅在一起）。

    返回 (退出码, 输出文本, 是否超时被强杀)。超时本身也是一种结论 —— 卡死是最差的失败模式。
    """
    env = clean_env(extra_env)
    fd, out = tempfile.mkstemp(prefix="pasm-medical-bat-", suffix=".txt")
    os.close(fd)
    try:
        with open(out, "wb") as fh:
            p = subprocess.Popen(["cmd", "/c", str(bat), *args], env=env, cwd=str(ROOT),
                                 stdout=fh, stderr=subprocess.STDOUT,
                                 stdin=subprocess.DEVNULL, creationflags=flags)
            try:
                p.wait(timeout=timeout)
                rc, timed_out = p.returncode, False
            except subprocess.TimeoutExpired:
                rc, timed_out = None, True
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], env=env,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                for im in ("netstat.exe", "findstr.exe"):
                    subprocess.run(["taskkill", "/F", "/IM", im], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return rc, decode_console(open(out, "rb").read()), timed_out
    finally:
        try:
            os.remove(out)
        except OSError:
            pass


def mutate(text: str, old: str, new: str, label: str) -> str:
    """按锚点改坏一份副本；命中数不是 1 就直接失败（防止锚点漂了、反例变成空跑）。"""
    n = text.count(old)
    assert n == 1, f"反例锚点 {label} 命中 {n} 次（期望 1）"
    return text.replace(old, new)


def pinned_port_controls(text: str) -> list[tuple[str, bool, str]]:
    """后端端口必须钉在命令行上，并配一条反例。

    环境里的 SERVER__PORT / SERVER_PORT 会被 Spring Boot 映射到 server.port，
    而环境属性源优先级高于 application.yml —— 端口会被悄悄挪走，
    报出来却是"Port xxx was already in use"（看着像端口冲突，其实是端口被改）。
    命令行参数能压住环境变量，所以钉在 start 那一行里。
    """
    out: list[tuple[str, bool, str]] = [
        (f"后端端口钉在命令行（{PIN_PORT_ARG}）", PIN_PORT_ARG in text, "")]
    mutated = text.replace(" " + PIN_PORT_ARG, "")
    hits = text.count(" " + PIN_PORT_ARG)
    out.append(("反例被抓到：去掉钉端口后必须报红",
                hits >= 1 and PIN_PORT_ARG not in mutated, f"锚点命中 {hits} 次"))
    return out


def _listen(port: int) -> tuple[socket.socket | None, str]:
    """在 127.0.0.1:port 起一个监听。失败时返回原因（端口已被真演示占着同样可用）。"""
    s = socket.socket()
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", port))
        s.listen(8)
        return s, ""
    except OSError as e:
        s.close()
        return None, str(e)


def runtime_controls() -> list[tuple[str, bool, str]]:
    """D/E 组：真跑分支。每条主判据都配一条反例。"""
    start = DEMO / "start-demo.bat"
    if not start.exists():
        return [("start-demo.bat 存在（D/E 组前提）", False, str(start))]

    out: list[tuple[str, bool, str]] = []
    with open(start, "r", encoding="ascii", newline="") as fh:
        raw = fh.read()

    # ---- D1 正常控制台：必须走 release 分支 ----
    rc, t, to = run_bat(start, ["--check"], 0)
    out.append(("D1 正常控制台：java 探测走 release 分支",
                "(release file)" in t and not to, f"rc={rc} 超时={to}"))
    out.append(("D1 正常控制台：--check 结论通过",
                rc == 0 and "preflight passed" in t, f"rc={rc}"))

    # ---- D2 无控制台父进程：修复点。修 java 前是 [X] cannot read；改端口探测前是卡死 ----
    rc, t, to = run_bat(start, ["--check"], DETACHED_PROCESS, timeout=90)
    out.append(("D2 无控制台：java 探测仍走 release 分支",
                "(release file)" in t and not to, f"rc={rc} 超时={to}"))
    out.append(("D2 无控制台：不卡死且 --check 通过",
                rc == 0 and "preflight passed" in t and not to, f"rc={rc} 超时={to}"))

    # ---- D3 shim（旁边没有 release 文件）：证明回退分支真的会被走到 ----
    if os.path.exists(JAVAPATH_SHIM):
        rc, t, _ = run_bat(start, ["--check"], 0, {"PASM_MEDICAL_JAVA": JAVAPATH_SHIM})
        out.append(("D3 shim：回退到 java -version 并解析成功",
                    "(java -version)" in t, f"rc={rc}"))
    else:
        out.append(("D3 shim：机器上没有 javapath，跳过", True, "SKIP"))

    # ---- D4 反例：摘掉 release 探测，同一无控制台场景必须转红 ----
    # 反例 bat 必须放在 tools/demo 下：脚本靠 %~dp0..\.. 解析仓库根
    neg = DEMO / "_negctl_java_probe.bat"
    try:
        neg.write_text(mutate(raw, MUT_RELEASE_PROBE, MUT_RELEASE_REPLACE, "release 探测"),
                       encoding="ascii", newline="")
        rc, t, to = run_bat(neg, ["--check"], DETACHED_PROCESS, timeout=90)
        out.append(("D4 反例：摘掉 release 探测后，无控制台必须转红",
                    "cannot read the java version" in t, f"rc={rc} 超时={to}"))
        out.append(("D4 反例：确实非零退出", rc not in (0, None), f"rc={rc}"))
    finally:
        neg.unlink(missing_ok=True)

    # ---- E1/E2 端口探测：有监听必须报出来；netstat 版在无控制台下必须报不出来 ----
    listener, err = _listen(8090)
    if not listener:
        probe = subprocess.run(
            [sys.executable, "-c",
             "import socket,sys;s=socket.socket();s.settimeout(1);"
             "sys.exit(0 if s.connect_ex(('127.0.0.1',8090))==0 else 1)"])
        if probe.returncode != 0:
            out.append(("E1 8090 有监听（自建监听失败且端口也是空的）", False, err))
    try:
        note = "自建监听" if listener else "8090 已被占用，用现成的"
        rc, t, to = run_bat(start, ["--check"], 0)
        out.append((f"E1 有监听时端口被报出来（{note}）",
                    "port 8090 is already in use" in t and not to, f"rc={rc} 超时={to}"))

        neg2 = DEMO / "_negctl_netstat_ports.bat"
        try:
            neg2.write_text(mutate(raw, MUT_PORT_PROBE, MUT_PORT_REPLACE, "端口探测"),
                            encoding="ascii", newline="")
            rc, t, to = run_bat(neg2, ["--check"], DETACHED_PROCESS, timeout=45)
            # 反例的合格标准正是"报不出来"：无控制台下 netstat 输出为空，或整条管道卡死
            out.append(("E2 反例：netstat 版端口探测在无控制台下报不出来",
                        "port 8090 is already in use" not in t,
                        f"rc={rc} 超时={to}（超时=卡死，也算报不出来）"))
        finally:
            neg2.unlink(missing_ok=True)
    finally:
        if listener:
            listener.close()

    return out


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
        if bat == "start-demo.bat":
            for label, ok, detail in pinned_port_controls(data.decode("ascii", "replace")):
                check(f"{bat} {label}", ok, detail)
        print()

    if not args.no_run:
        if os.name != "nt":
            print("  [SKIP] 真跑：非 Windows 平台，跳过（cmd 不存在）\n")
        else:
            print("--- C 真跑 start-demo.bat --check ---")
            rc, out = run_check("start-demo.bat", ["--check"])
            for ln in [x for x in out.splitlines() if x.strip()][:12]:
                print("        | " + ln)
            hit = [m for m in PARSE_ERRORS if m in out]
            check("--check 没有出现'命令无法识别'类报错", not hit, f"命中={hit}")
            coherent = "[OK] preflight passed" in out or "[X]" in out
            check("--check 给出了明确结论（通过或具体缺什么）", coherent, f"rc={rc}")
            if os.path.isdir(JDK17_HINT):
                check("--check 在 JDK 17 下 preflight 通过",
                      rc == 0 and "preflight passed" in out, f"rc={rc}")
            print()

            print("--- D/E 真跑：探测分支 + 无控制台父进程 + 反例对照 ---")
            for label, ok, detail in runtime_controls():
                check(label, ok, detail)
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
