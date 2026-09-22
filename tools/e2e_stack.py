# -*- coding: utf-8 -*-
"""三端联调验证 —— 真起 Python 认知服务 + Spring Boot 业务层，走真实 HTTP。

验证的链路（每一跳都是真的）：

    浏览器契约 ──► Spring Boot (:8081) ──► Python 认知服务 (:8090)
       /api/auth/login          （本地鉴权）
       /api/consult/start       （薄转发 → 问诊树）
       /api/lab/parse           （薄转发 → 检验单识别，返回"待确认"）
       /api/encounters          （薄转发 → 记忆时间轴）
       /api/patient/encounter/{id}  （就诊结构化详情 + 归属校验）
       /api/admin/config        （对接设置读写 + 与认知服务实际值对账）

判据只看**响应体与状态码**，不看进程有没有起来。

★ 另外两个"关掉之后必须不成立"的反例（在第二个后端进程里跑）：
  · 演示账号开关置 false 时，写在代码里的 staff/123456 **必须登不进去**；
  · 患者角色令牌访问 /api/admin/** **必须 403**（后台能读全院患者、能改模型指向）。

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
    strict_port = free_port()
    tmp = tempfile.mkdtemp(prefix="pasm-stack-")
    cog_log = os.path.join(tmp, "cognition.log")
    api_log = os.path.join(tmp, "backend.log")
    strict_log = os.path.join(tmp, "backend-strict.log")
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

        # ---------- 2b) 第二个后端：演示账号**关掉**（反例进程）
        # ★ 与主进程**并发**启动，避免把整体耗时翻倍。
        #   它只回答一个问题：写死在代码里的 staff/123456 在关闭开关后是否真的登不进去。
        #   只看代码不看行为，正是"默认开着、生产忘了关"这类事故的成因。
        strict = subprocess.Popen(
            [JAVA_BIN, "-jar", str(jar),
             "--spring.profiles.active=dev",
             "--server.port=%d" % strict_port,
             "--medical.auth.demo-login-enabled=false",
             "--pasm.cognition.base-url=http://127.0.0.1:%d" % cog_port,
             "--pasm.cognition.token=" + API_TOKEN],
            cwd=str(BACKEND), env=java_env,
            stdout=open(strict_log, "w", encoding="utf-8"), stderr=subprocess.STDOUT)
        procs.append(("backend-strict", strict))
        strict_ok = wait_port(strict_port, timeout=180)
        if not strict_ok:
            print("  [提示] 反例后端没起来，相关断言将失败。日志尾部：")
            print(read_tail(strict_log))

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

        # ---------- 8) 就诊结构化详情（+ 归属校验反例）
        st, encs = req("GET", base + "/api/patient/encounters?ref=demo-patient-001", token=token)
        first_id = str(encs[0].get("id")) if isinstance(encs, list) and encs else ""
        check("历史就诊列表带可用的 id（详情入口的前提）", bool(first_id), str(encs)[:160])

        st, d = req("GET", base + "/api/patient/encounter/%s?ref=demo-patient-001" % first_id,
                    token=token)
        check("★ /api/patient/encounter/{id} 返回结构化详情（主诉/判断/处置/科室）",
              st == 200 and bool(d.get("chiefComplaint")) and bool(d.get("assessment"))
              and bool(d.get("plan")) and bool(d.get("department")), str(d)[:220])

        # ★ 反例：换一个 ref 读同一条就诊 → 必须 404（不然自增 id 就是全院病历的钥匙）
        st, d = req("GET", base + "/api/patient/encounter/%s?ref=somebody-else" % first_id,
                    token=token)
        check("★ 反例：就诊归属不符 → 404（挡住 IDOR）", st == 404, str(st) + " " + str(d)[:120])

        # ---------- 9) 对接设置：真实读写 + 校验 + 与认知服务对账
        st, d = req("GET", base + "/api/admin/config", token=token)
        check("★ /api/admin/config 可读（返回期望值 + 认知服务实际值）",
              st == 200 and isinstance(d.get("desired"), dict) and "drift" in d, str(d)[:200])

        want = {"ocr": "vendor", "lis": "fhir", "llm": "ollama",
                "model": "qwen2.5:7b", "baseUrl": "http://127.0.0.1:11434"}
        st, d = req("POST", base + "/api/admin/config", want, token=token)
        check("★ 保存对接设置成功且回显期望值",
              st == 200 and d.get("desired", {}).get("llm") == "ollama"
              and d.get("desired", {}).get("model") == "qwen2.5:7b", str(d)[:200])

        # ★ 往返：重新 GET 必须读回刚存的值（证明是真落库，不是只在响应里回显）
        st, d = req("GET", base + "/api/admin/config", token=token)
        check("★ 往返持久化：重新读取仍是刚保存的值",
              st == 200 and d.get("desired", {}).get("llm") == "ollama"
              and d.get("desired", {}).get("baseUrl") == "http://127.0.0.1:11434",
              str(d.get("desired"))[:200])
        # 认知服务实际 provider 是 null（env 未配）→ 必须报出漂移，而不是假装生效
        check("★ 期望(ollama) vs 实际(null) 被判定为漂移（不假装已生效）",
              d.get("drift") is True and isinstance(d.get("applied"), dict),
              "drift=%s applied=%s" % (d.get("drift"), str(d.get("applied"))[:120]))
        check("已保存设置带修改时间/修改人（可追溯）",
              bool(d.get("updatedAt")) and d.get("updatedBy") == "staff",
              "at=%s by=%s" % (d.get("updatedAt"), d.get("updatedBy")))

        # ★ 反例：白名单外的取值必须 400，而不是"静默改成默认值"
        st, d = req("POST", base + "/api/admin/config",
                    {"ocr": "evil", "lis": "off", "llm": "null", "model": "", "baseUrl": ""},
                    token=token)
        check("★ 反例：非法 ocr 取值 → 400（服务端白名单，前端下拉不是安全边界）",
              st == 400, str(st) + " " + str(d)[:140])
        st, d = req("POST", base + "/api/admin/config",
                    {"ocr": "none", "lis": "off", "llm": "openai", "model": "gpt-4o-mini",
                     "baseUrl": "不是URL"}, token=token)
        check("★ 反例：baseUrl 非法 → 400", st == 400, str(st) + " " + str(d)[:140])
        # 非法请求不该把已存的值改坏
        st, d = req("GET", base + "/api/admin/config", token=token)
        check("★ 反例请求被拒后，已保存的值未被破坏",
              d.get("desired", {}).get("llm") == "ollama",
              str(d.get("desired"))[:160])

        # ---------- 10) 授权：患者角色拿不到后台（能读全院患者、能改模型指向）
        st, d = req("POST", base + "/api/auth/login",
                    {"username": "patient", "password": "123456"})
        ptoken = d.get("token")
        check("患者账号可登录（dev 演示账号）", st == 200 and bool(ptoken), str(d)[:120])
        if ptoken:
            st, d = req("GET", base + "/api/admin/patients", token=ptoken)
            check("★ 患者令牌访问 /api/admin/** → 403（最小权限）", st == 403, str(st))
            st, d = req("GET", base + "/api/patient?ref=demo-patient-001", token=ptoken)
            check("患者令牌仍可用自己的诊疗接口（不是一刀切封死）", st == 200, str(st))

        # ---------- 11) 演示账号开关：关掉之后写死的口令必须无效
        sbase = "http://127.0.0.1:%d" % strict_port
        if strict_ok:
            st, d = req("POST", sbase + "/api/auth/login",
                        {"username": "staff", "password": "123456"})
            check("★ 反例：demo-login-enabled=false 时写死的 staff/123456 登不进去（401）",
                  st == 401, str(st) + " " + str(d)[:120])
            st, d = req("GET", sbase + "/api/admin/patients")
            check("关闭演示账号后，未登录访问后台仍 401", st in (401, 403), str(st))
        else:
            check("★ 反例：demo-login-enabled=false 时写死的口令登不进去",
                  False, "反例后端未启动")
            check("关闭演示账号后未登录访问后台仍 401", False, "反例后端未启动")
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
