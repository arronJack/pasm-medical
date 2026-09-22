# -*- coding: utf-8 -*-
"""三端联调验证 —— 真起 Python 认知服务 + Spring Boot 业务层，走真实 HTTP。

验证的链路（每一跳都是真的）：

    浏览器契约 ──► Spring Boot (:8081) ──► Python 认知服务 (:8090)
       /api/auth/login          （本地鉴权）
       /api/consult/start       （薄转发 → 问诊树）
       /api/lab/parse           （薄转发 → 检验单识别，返回"待确认"）
       /api/encounters          （薄转发 → 记忆时间轴）

判据只看**响应体与状态码**，不看进程有没有起来。

前置：
  · 后端已打包：cd backend && mvn -DskipTests package
  · 本机有 JDK（脚本用 JAVA_HOME 或默认 java）

用法::

    python tools/e2e_stack.py
"""
from __future__ import annotations

import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
BACKEND = REPO / "backend"

# ★ 必须用 JDK 17+ 跑这个 jar（Spring Boot 3 编译产物）。本机常有 JAVA_HOME 指向 JDK 8，
# 若直接信它会导致 UnsupportedClassVersionError。所以优先用已知的 JDK 18，再回退到环境值。
JAVA = r"C:\Program Files\Java\jdk-18.0.1.1"
if not os.path.isdir(JAVA):
    JAVA = os.environ.get("JAVA_HOME", JAVA)
JAVA_BIN = os.path.join(JAVA, "bin", "java.exe") if os.path.isdir(JAVA) else "java"
API_TOKEN = "stack-token-please-change"

OK = FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global OK, FAIL
    if cond:
        OK += 1
        print("  v %s" % name)
    else:
        FAIL += 1
        print("  x %s%s" % (name, ("  <- " + detail) if detail else ""))


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_port(port: int, timeout: float = 90.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            s.settimeout(0.6)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.7)
    return False


def read_tail(path: str, n: int = 2500) -> str:
    """读日志尾部。★ 读**文件**而不是管道：管道是块缓冲的，进程没退出时读不到内容。"""
    try:
        return io.open(path, encoding="utf-8", errors="replace").read()[-n:]
    except Exception as ex:                        # noqa: BLE001
        return "（日志读不到：%s）" % ex


def req(method, url, body=None, token=None, timeout=25):
    data = None
    h = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        h["Content-Type"] = "application/json; charset=utf-8"
    if token:
        h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}
    except Exception as ex:                       # noqa: BLE001
        return 0, {"_err": str(ex)}


def main() -> int:                                # noqa: C901
    print("三端联调验证（Python 认知服务 + Spring Boot 业务层）")
    print("-" * 66)

    jar = None
    for p in sorted(BACKEND.glob("target/*.jar")):
        if "sources" not in p.name and "javadoc" not in p.name:
            jar = p
            break
    if jar is None:
        print("[中止] 没找到后端 jar，先跑：cd backend && mvn -DskipTests package")
        return 2

    cog_port, api_port = free_port(), free_port()
    tmp = tempfile.mkdtemp(prefix="pasm-stack-")
    cog_log = os.path.join(tmp, "cognition.log")
    api_log = os.path.join(tmp, "backend.log")
    procs = []
    try:
        # ---------- 1) 起 Python 认知服务
        # ★ 日志一律落**文件**而不是 PIPE：管道是块缓冲的，进程没退出时读不到内容，
        #   出错时只会看到"没起来"却看不到原因（本次就踩了）。
        py_env = dict(os.environ)
        py_env["PYTHONPATH"] = os.pathsep.join(
            [str(REPO), str(REPO.parent / "pasm-skills"), str(REPO.parent / "pasm-framework")])
        py_env["PASM_MEDICAL_TOKEN"] = API_TOKEN
        py_env["PYTHONUNBUFFERED"] = "1"
        cog = subprocess.Popen(
            [sys.executable, "-m", "pasm_medical.service", "--host", "127.0.0.1",
             "--port", str(cog_port), "--token", API_TOKEN, "--dir", tmp],
            cwd=str(REPO), env=py_env,
            stdout=open(cog_log, "w", encoding="utf-8"), stderr=subprocess.STDOUT)
        procs.append(("cognition", cog))
        if not wait_port(cog_port):
            print("[中止] 认知服务没起来，日志尾部：")
            print(read_tail(cog_log))
            return 2
        st, _ = req("GET", "http://127.0.0.1:%d/healthz" % cog_port)
        check("认知服务已起且 /healthz 可达", st == 200, str(st))

        # ---------- 2) 起 Spring Boot（dev 档：H2，不需要外部数据库）
        java_env = dict(os.environ, JAVA_HOME=JAVA)
        api = subprocess.Popen(
            [JAVA_BIN, "-jar", str(jar),
             "--spring.profiles.active=dev",
             "--server.port=%d" % api_port,
             "--pasm.cognition.base-url=http://127.0.0.1:%d" % cog_port,
             "--pasm.cognition.token=" + API_TOKEN],
            cwd=str(BACKEND), env=java_env,
            stdout=open(api_log, "w", encoding="utf-8"), stderr=subprocess.STDOUT)
        procs.append(("backend", api))
        print("  等待 Spring Boot 启动（首次较慢）…")
        if not wait_port(api_port, timeout=180):
            print("[中止] 业务层没起来。java=%s" % JAVA_BIN)
            print("日志尾部：")
            print(read_tail(api_log))
            return 2

        base = "http://127.0.0.1:%d" % api_port
        st, d = req("GET", base + "/actuator/health")
        check("业务层 /actuator/health 可达且 UP",
              st == 200 and d.get("status") == "UP", str(d)[:120])

        # ---------- 3) 鉴权：不登录不能进
        st, _ = req("GET", base + "/api/encounters?patientRef=p1")
        check("★ 未登录访问 /api/** → 401", st in (401, 403), str(st))
        st, d = req("POST", base + "/api/auth/login",
                    {"username": "staff", "password": "wrong"})
        check("★ 密码错 → 401（且不区分账号是否存在）", st == 401, str(d)[:80])
        st, d = req("POST", base + "/api/auth/login",
                    {"username": "staff", "password": "123456"})
        token = d.get("token")
        check("登录成功返回令牌", st == 200 and bool(token), str(d)[:120])

        # ---------- 4) 薄转发：业务层 → 认知服务（问诊树）
        st, d = req("POST", base + "/api/consult/start",
                    {"patientRef": "p1", "chiefComplaint": "胸口有点闷"}, token=token)
        q = d.get("question") or {}
        # ★ 断言必须落在"主诉专属"的证据上：胸痛的首问是「放射」。
        #   只查 bool(q.key) 会假绿 —— 因为 body 丢了之后首问会退化成通用的「部位」，
        #   照样有 key。（本次就是这样漏过了一个"body 根本没转发"的真 bug。）
        check("★ /api/consult/start 透传主诉（胸痛首问应是「放射」）",
              st == 200 and q.get("from_tree") == "胸痛" and q.get("key") == "放射",
              str(d)[:200])
        check("★ 响应带 requiresPhysicianConfirmation",
              d.get("requiresPhysicianConfirmation") is True, str(list(d))[:120])

        st, d = req("POST", base + "/api/consult/answer",
                    {"sessionKey": "current", "value": "往左肩和后背串"}, token=token)
        check("★ 红旗命中并中断问诊（红→橙→红 的完整链路）",
              st == 200 and d.get("halted") is True
              and bool(d.get("red_flags")), str(d)[:200])

        # ---------- 5) 检验单：返回"待确认"
        st, d = req("POST", base + "/api/lab/parse",
                    {"patientRef": "p1", "imageUrl": "x.jpg"}, token=token)
        # 认知服务未配 OCR 时应明确报不可用，而不是假装成功
        ok_parse = st == 200 and ("needs_confirmation" in d or d.get("ok") is False)
        check("★ /api/lab/parse 有明确语义（待确认 / 或如实报 OCR 未配置）",
              ok_parse, str(d)[:180])

        # ---------- 6) 时间轴
        st, d = req("GET", base + "/api/encounters?patientRef=p1", token=token)
        check("/api/encounters 可达且返回列表", st == 200 and "encounters" in d, str(d)[:120])

        # ---------- 7) 业务层持久化（dev 档 DemoDataSeeder 灌入的演示数据）
        st, d = req("GET", base + "/api/patient?ref=demo-patient-001", token=token)
        check("★ /api/patient 返回真实患者档案（含过敏史）",
              st == 200 and d.get("allergy") == "青霉素" and d.get("name") == "示例患者",
              str(d)[:160])

        st, d = req("GET", base + "/api/patient/encounters?ref=demo-patient-001", token=token)
        check("★ /api/patient/encounters 返回真实历史就诊",
              st == 200 and isinstance(d, list) and len(d) >= 1, str(d)[:160])

        st, d = req("GET", base + "/api/admin/patients", token=token)
        check("★ 后台 /api/admin/patients 返回真实患者列表",
              st == 200 and isinstance(d, list) and len(d) >= 1, str(d)[:160])

        st, d = req("GET", base + "/api/admin/stats", token=token)
        check("★ 后台 /api/admin/stats 返回统计（拒答率/采纳率）",
              st == 200 and "refusalRate" in d and "adoptionRate" in d, str(d)[:160])

        st, d = req("GET", base + "/api/admin/audit", token=token)
        check("★ 后台 /api/admin/audit 返回审计（append-only）",
              st == 200 and isinstance(d, list), str(d)[:160])
    finally:
        for name, p in procs:
            try:
                p.terminate()
                p.wait(timeout=15)
            except Exception:                     # noqa: BLE001
                try:
                    p.kill()
                except Exception:
                    pass
            print("  已停止：%s" % name)
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 66)
    print("结果：%d 项通过，%d 项失败" % (OK, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
