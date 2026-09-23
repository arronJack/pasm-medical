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
       /api/auth/me             （身份 → 业务实体绑定：角色/科室/patientRef）

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
                    {"patientRef": "p1", "sessionKey": "current",
                     "value": "往左肩和后背串"}, token=token)
        check("★ 红旗命中并中断问诊（红→橙→红 的完整链路）",
              st == 200 and d.get("halted") is True
              and bool(d.get("red_flags")), str(d)[:200])

        # ★ 会话必须按患者隔离。原实现里会话键**恒为 "current"**（全租户一条会话），
        #   于是下面这一步会"回答成功"并把事实写到 p1 身上 —— 两个人的问诊串在一起。
        st, d = req("POST", base + "/api/consult/answer",
                    {"patientRef": "p2-session-probe", "sessionKey": "current",
                     "value": "往左肩和后背串"}, token=token)
        check("★ 反例：别的患者回答同一会话 → 查不到会话（会话按患者隔离）",
              st == 200 and d.get("ok") is False, str(st) + " " + str(d)[:160])
        st, d = req("POST", base + "/api/consult/finish",
                    {"patientRef": "p1", "sessionKey": "current"}, token=token)
        check("★ 被越权尝试之后 p1 的会话仍完好（能正常结束并产出摘要）",
              st == 200 and d.get("ok") is True, str(d)[:160])

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

        # ★★ 统计口径（这两条针对一个真 bug，别把它们改回"只看字段在不在"）
        #   旧实现 = 「取最近 200 条审计 → 在内存里筛今天、把 ask 与 consult-finish 都算一次」：
        #     ① 审计一多就**静默封顶**；② 一次问诊被重复计数。两者都不报错，只给假数字。
        st, s1 = req("GET", base + "/api/admin/stats", token=token)
        check("★ /api/admin/stats 返回统计 + 口径定义 + 统计窗口（数字必须能核对）",
              st == 200 and "refusalRateToday" in s1 and "adoptionRateTotal" in s1
              and bool((s1.get("definitions") or {}).get("consultation"))
              and bool(s1.get("windowFrom")),
              str(s1)[:220])

        # 先把"最近 200 条"填满（用 login：每次一条审计、不依赖认知服务，最快），
        # 再起一次问诊，要求今日问诊量**精确 +1**。旧实现在这里会给出 0（top200 里全是 login）。
        for _ in range(250):
            req("POST", base + "/api/auth/login", {"username": "staff", "password": "123456"})
        st, s2 = req("GET", base + "/api/admin/stats", token=token)
        before = s2.get("consultationsToday")
        req("POST", base + "/api/consult/start",
            {"patientRef": "stats-probe", "chiefComplaint": "咳嗽三天"}, token=token)
        st, s3 = req("GET", base + "/api/admin/stats", token=token)
        after = s3.get("consultationsToday")
        # None 安全：字段缺失（旧实现根本没有这个键）时要**清晰地转红**，而不是在断言里抛
        # TypeError 把整个套件带崩 —— 崩溃只会让人以为是脚本坏了，看不出是口径错了。
        counts_ok = isinstance(before, int) and isinstance(after, int) and after == before + 1
        check("★ 反例：灌 250 条审计后，今日问诊量仍按 consult-start 精确 +1（旧实现会掉到 0）",
              counts_ok, "before=%s after=%s" % (before, after))

        # ★★ 审计：读事件 + 操作者 + 问题原文
        #   注意顺序：上面的统计断言灌了 250 条审计，会把更早的记录挤出"最近 200 条"
        #   （/api/admin/audit 只返回 200 条），所以这里**现场**再读一次，
        #   保证被测事件一定落在窗口内 —— 否则断言会随执行顺序时绿时红。
        req("GET", base + "/api/patient?ref=demo-patient-001", token=token)
        st, d = req("GET", base + "/api/admin/audit", token=token)
        check("★ 后台 /api/admin/audit 返回审计（append-only）",
              st == 200 and isinstance(d, list), str(d)[:160])

        reads = [a for a in d if a.get("action") == "read-patient"
                 and a.get("ref") == "demo-patient-001"] if isinstance(d, list) else []
        check("★ 读审计：查看患者档案必须留痕，且带正确操作者"
              "（旧实现只有写事件、actor 还是空的 —— 出事查不出'谁看的'）",
              bool(reads) and all(a.get("actor") == "staff" for a in reads),
              "命中 %d 条 %s" % (len(reads), str(reads[:1])[:180]))

        marker = "e2e-audit-probe-7f3a"
        req("POST", base + "/api/assist/ask",
            {"patientRef": "audit-probe", "question": marker, "k": 3}, token=token)
        st, d2 = req("GET", base + "/api/admin/audit", token=token)
        asks = [a for a in d2 if a.get("action") == "ask"
                and marker in str(a.get("inputSnapshot") or "")] if isinstance(d2, list) else []
        check("★ 审计带问题原文：ask 的 inputSnapshot 含提问内容并下发到后台"
              "（旧接口不返回该字段 → 后台看不到患者问了什么）",
              bool(asks) and asks[0].get("actor") == "staff",
              "命中 %d 条 %s" % (len(asks), str(asks[:1])[:200]))

        # ---------- 7.5) 资料库：真的可增删，而且真的会影响问答 ----------
        #   ★ 判据必须落在"资料进了回答依据"（sources 里有 kind=doc 的条目），
        #     只断言接口 200 或"没报错"证明不了资料库不是摆设。
        st, d = req("POST", base + "/api/admin/kb",
                    {"title": "冒烟测试资料条目", "tags": "冒烟测试探针,冒烟测试",
                     "content": "e2e 写入的资料条目，用于验证资料库可增删且能被问答引用。",
                     "version": "e2e", "reviewer": "e2e"}, token=token)
        kb_key = str((d.get("doc") or {}).get("docKey") or "")
        kb_synced = (d.get("sync") or {}).get("synced")
        check("★ 新增资料：落业务库 + 同步到认知侧检索副本",
              st == 200 and bool(kb_key) and kb_synced is True,
              "key=%s synced=%s %s" % (kb_key, kb_synced, str(d)[:150]))

        kb_q = "冒烟测试探针是什么"
        st, d = req("POST", base + "/api/assist/ask",
                    {"patientRef": "kb-probe", "question": kb_q, "k": 5}, token=token)
        doc_src = [s for s in (d.get("sources") or []) if s.get("kind") == "doc"]
        check("★ 资料库真的进问答：提问命中新增资料 → 不拒答且来源标为 doc"
              "（旧实现走 /api/cog/recall，只召回患者记忆、不含资料库、也不过闸门）",
              st == 200 and d.get("refused") is False and bool(doc_src),
              "refused=%s docSources=%s" % (d.get("refused"), str(doc_src)[:180]))

        st, d = req("POST", base + "/api/admin/kb/%s/status" % kb_key,
                    {"active": False}, token=token)
        check("★ 下架资料：状态变更 + 重新同步检索副本",
              st == 200 and (d.get("doc") or {}).get("status") == "inactive", str(d)[:150])

        st, d = req("POST", base + "/api/assist/ask",
                    {"patientRef": "kb-probe", "question": kb_q, "k": 5}, token=token)
        check("★ 反例：资料下架后同一提问必须**回到拒答**（下架的资料不能继续当依据）",
              st == 200 and d.get("refused") is True and not (d.get("sources") or []),
              "refused=%s sources=%s" % (d.get("refused"), str(d.get("sources"))[:150]))

        # ★ 拒答率不再恒为 0：上面那次拒答要能在统计里看见
        #   （旧实现 ask 审计从不设置 refused → countByActionAndRefusedTrue("ask") 恒为 0）
        st, s = req("GET", base + "/api/admin/stats", token=token)
        check("★ 拒答被记入审计并可用于统计（以前分子恒为 0 → 拒答率永远显示 0.0）",
              st == 200 and int(s.get("refusalsToday") or 0) >= 1,
              "refusalsToday=%s questionsToday=%s" % (s.get("refusalsToday"), s.get("questionsToday")))

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

        # ---------- 10b) 身份 → 业务实体映射：患者只能看自己的记录（资源级判定）
        #   ★ 这一层是 URL 级授权挡不住的：接口本身合法（患者有令牌、路径也对），
        #     错的是"他要看的那条 ref 不属于他"。旧实现只校验"已认证"，所以这里全是 200。
        st, d = req("GET", base + "/api/auth/me", token=ptoken)
        check("★ /api/auth/me 返回身份与绑定（患者 patientRef=demo-patient-001）",
              st == 200 and d.get("patientRef") == "demo-patient-001"
              and d.get("role") == "PATIENT"
              and str(d.get("scopeLabel", "")).startswith("本人"),
              str(d)[:200])
        st, d = req("GET", base + "/api/patient?ref=demo-patient-002", token=ptoken)
        check("★ 患者令牌换 ref 读他人档案 → 403（IDOR；旧实现返回 200）",
              st == 403, str(st) + " " + str(d)[:120])
        st, d = req("GET", base + "/api/patient/encounters?ref=demo-patient-002", token=ptoken)
        check("★ 患者令牌换 ref 读他人就诊列表 → 403", st == 403, str(st))
        st, d = req("POST", base + "/api/consult/start",
                    {"patientRef": "demo-patient-002", "chiefComplaint": "咳嗽三天"}, token=ptoken)
        check("★ 患者令牌以他人 ref 发起问诊 → 403", st == 403, str(st))
        st, d = req("GET", base + "/api/patient", token=ptoken)
        check("患者不传 ref 时按本人处理（前端不必替患者重复声明一遍身份）",
              st == 200 and d.get("ref") == "demo-patient-001", str(d)[:140])

        # ---------- 10c) 数据范围：科室角色只看本科室，超管看全院
        st, d = req("POST", base + "/api/auth/login",
                    {"username": "doctor", "password": "123456"})
        dtoken = d.get("token")
        check("医护账号可登录且带科室绑定（doctor → 发热门诊）",
              st == 200 and d.get("role") == "DOCTOR"
              and d.get("department") == "发热门诊", str(d)[:180])
        if dtoken:
            st, d = req("GET", base + "/api/admin/patients", token=dtoken)
            refs = [x.get("ref") for x in d] if isinstance(d, list) else []
            # ★ 判据是"看不到不在本科室的那一位"。只有一位患者时科室过滤与不过滤结果一样，
            #   那种"全绿"测不出任何东西 —— 演示数据里因此刻意放了第二位（骨科）。
            check("★ 科室范围：发热门诊医护看得到本科室患者，看不到骨科那一位",
                  st == 200 and "demo-patient-001" in refs
                  and "demo-patient-002" not in refs,
                  "%s %s" % (st, refs))
            st, d = req("POST", base + "/api/admin/config",
                        {"ocr": "builtin", "lis": "none", "llm": "ollama"}, token=dtoken)
            check("★ 反例：医护改系统配置 → 403（仅超管；它决定患者数据会不会出网）",
                  st == 403, str(st))
            # ★ 请求体必须是**合法**的（tags 是逗号分隔字符串，不是数组）——否则会因
            #   JSON 反序列化失败返回 400/403，让"角色越权 403"这条断言**假绿**：
            #   它通过的真正原因是"请求畸形"，而不是"鉴权挡住了"。
            st, d = req("POST", base + "/api/admin/kb",
                        {"title": "不应写入", "content": "x", "tags": "x"}, token=dtoken)
            check("★ 反例：医护写资料库 → 403（临床读资料走问答接口，不写资料库）",
                  st == 403, str(st))
            # ★ 反例：资料库的"写/停用/删除/同步"全部仅超管。现有测试只验了新建，
            #   但停用/删除/同步同样能影响全院回答依据，必须同样挡在 403。
            #   （P1-2 起 SecurityConfig 把 /api/admin/kb/** 限 DEPT_ADMIN + SUPER_ADMIN，
            #    鉴权在控制器之前，所以医护即便 key 不存在也先 403，不会退化成 404。）
            kb_probe = kb_key or "probe-doc-key"
            st, d = req("POST", base + "/api/admin/kb/%s/status" % kb_probe,
                        {"active": False}, token=dtoken)
            check("★ 反例：科室角色停用资料库条目 → 403", st == 403, str(st))
            st, d = req("DELETE", base + "/api/admin/kb/%s" % kb_probe, token=dtoken)
            check("★ 反例：科室角色删除资料库条目 → 403", st == 403, str(st))
            st, d = req("POST", base + "/api/admin/kb/sync", token=dtoken)
            check("★ 反例：科室角色手动同步资料库 → 403", st == 403, str(st))
        # ---------- 10d) 第四个角色：科室管理员（deptadmin）—— P1-2 真实能力差异
        #   ★ P1-2 起，科室管理员与医护**不再同权**：科室管理员是第一个真正能写资料库
        #     的角色，但只能管**本科室（发热门诊）**资料。下面把这条差异端到端钉死：
        #     ① 新建被强制归到本科室（请求里带别的科室也落不到别科）；
        #     ② 列表只含本科室（全院通用/别科不出现在视图）；
        #     ③ 上架/下架/删除本科室资料 → 200；
        #     ④ 动"全院通用（空科室）"或"别科"资料 → 403。
        #   deptadmin 此前只在代码与文档里定义、从未被 e2e 覆盖，补上它，
        #   P1-2 四角色的数据范围才算真正被端到端钉死。
        st, d = req("POST", base + "/api/auth/login",
                    {"username": "deptadmin", "password": "123456"})
        gtoken = d.get("token")
        check("科室管理员账号可登录且带科室绑定（deptadmin → 发热门诊）",
              st == 200 and d.get("role") == "DEPT_ADMIN"
              and d.get("department") == "发热门诊", str(d)[:180])
        if gtoken:
            # ★ 在"患者范围"上仍与 doctor 同权（看本科室、看不到骨科）
            st, d = req("GET", base + "/api/admin/patients", token=gtoken)
            grefs = [x.get("ref") for x in d] if isinstance(d, list) else []
            check("★ 科室管理员：与 doctor 在患者范围上同权——看得到本科室、看不到骨科",
                  st == 200 and "demo-patient-001" in grefs
                  and "demo-patient-002" not in grefs,
                  "%s %s" % (st, grefs))
            # ★ 改系统配置仍 403（仅超管，决定患者数据是否出网）
            st, d = req("POST", base + "/api/admin/config",
                        {"ocr": "builtin", "lis": "none", "llm": "ollama"}, token=gtoken)
            check("★ 反例：科室管理员改系统配置 → 403（仅超管）", st == 403, str(st))
            # ★ P1-2：科室管理员可新建本科室资料，且科室被**强制**为本科室
            st, d = req("POST", base + "/api/admin/kb",
                        {"title": "发热门诊本科室手册", "content": "x",
                         "tags": "发热门诊", "department": "骨科"}, token=gtoken)
            gdoc = (d.get("doc") or {}) if st == 200 else {}
            gkey = str(gdoc.get("docKey") or "")
            check("★ 科室管理员新建资料 → 200，且 department 被强制为本科室（发热门诊）",
                  st == 200 and bool(gkey) and gdoc.get("department") == "发热门诊",
                  "st=%s dept=%s %s" % (st, gdoc.get("department"), str(d)[:150]))
            # ★ 列表只含本科室：每条返回资料的 department 都应是发热门诊
            st, d = req("GET", base + "/api/admin/kb", token=gtoken)
            gdocs = (d.get("docs") or []) if st == 200 else []
            only_own = bool(gdocs) and all(
                x.get("department") == "发热门诊" for x in gdocs)
            check("★ 科室管理员列表只含本科室资料（全院通用/别科不出现在视图）",
                  st == 200 and only_own
                  and any(x.get("docKey") == gkey for x in gdocs),
                  "st=%s n=%d %s" % (st, len(gdocs), str(gdocs)[:200]))
            # ★ 上架 / 下架 / 删除本科室资料 → 200
            st, d = req("POST", base + "/api/admin/kb/%s/status" % gkey,
                        {"active": False}, token=gtoken)
            check("★ 科室管理员下架本科室资料 → 200（status=inactive）",
                  st == 200 and (d.get("doc") or {}).get("status") == "inactive",
                  "st=%s %s" % (st, str(d)[:150]))
            st, d = req("POST", base + "/api/admin/kb/%s/status" % gkey,
                        {"active": True}, token=gtoken)
            check("★ 科室管理员重新上架本科室资料 → 200（status=active）",
                  st == 200 and (d.get("doc") or {}).get("status") == "active",
                  "st=%s %s" % (st, str(d)[:150]))
            st, d = req("DELETE", base + "/api/admin/kb/%s" % gkey, token=gtoken)
            check("★ 科室管理员删除本科室资料 → 200", st == 200, str(st))
            # ★ 反例：动"全院通用"资料（超管建的 kb_key，department 为空）→ 403
            hw_key = kb_key or "probe-doc-key"
            st, d = req("POST", base + "/api/admin/kb/%s/status" % hw_key,
                        {"active": False}, token=gtoken)
            check("★ 反例：科室管理员动全院通用（空科室）资料 → 403", st == 403, str(st))
            st, d = req("DELETE", base + "/api/admin/kb/%s" % hw_key, token=gtoken)
            check("★ 反例：科室管理员删除全院通用（空科室）资料 → 403", st == 403, str(st))
            # ★ 反例：动"别科"资料（超管建的 骨科 资料）→ 403
            st, d = req("POST", base + "/api/admin/kb",
                        {"title": "骨科别科资料", "content": "x",
                         "tags": "骨科", "department": "骨科"}, token=token)
            other_key = str((d.get("doc") or {}).get("docKey") or "")
            st, d = req("POST", base + "/api/admin/kb/%s/status" % other_key,
                        {"active": False}, token=gtoken)
            check("★ 反例：科室管理员动别科（骨科）资料 → 403", st == 403, str(st))
            st, d = req("DELETE", base + "/api/admin/kb/%s" % other_key, token=gtoken)
            check("★ 反例：科室管理员删除别科（骨科）资料 → 403", st == 403, str(st))
            # 清理：超管删掉这条别科资料，避免污染后续断言
            req("DELETE", base + "/api/admin/kb/%s" % other_key, token=token)
            # ★ 维持现状锁定：临床侧（含 deptadmin）可跨患者查阅个体档案（scopedRef 放行任意 ref），
            #   仅聚合视图按科室过滤。这道断言把"个体读不做科室收窄"的决策钉死，
            #   防止日后误改成 403 而破坏临床查档；也证明 deptadmin ≠ patient（不会 403）。
            st, d = req("GET", base + "/api/patient?ref=demo-patient-002", token=gtoken)
            check("★ 科室管理员（临床侧）可跨患者查阅个体档案（维持现状：仅聚合视图按科室过滤）",
                  st == 200 and d.get("ref") == "demo-patient-002",
                  str(st) + " " + str(d)[:100])

        st, d = req("GET", base + "/api/admin/patients", token=token)
        refs_all = [x.get("ref") for x in d] if isinstance(d, list) else []
        check("★ 超管看全院：两位演示患者都在",
              st == 200 and "demo-patient-001" in refs_all
              and "demo-patient-002" in refs_all, str(refs_all))

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
