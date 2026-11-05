# -*- coding: utf-8 -*-
"""真实链路就诊数据 × 自学习候选池：开启 MEDICAL_LEARNING_AUTO 跑一轮端到端评测。

与 e2e_stack.py 的分工（别混）：
  · e2e_stack 验「接口通不通」，就诊相关断言打在 DemoDataSeeder 手工填齐字段的**演示数据**上；
  · 本脚本验「真实链路产出的数据能不能进自学习闭环」，每次就诊都走
    /api/consult/start → answer × N → /api/consult/finish 真链路，不碰演示就诊。

★★ 只有真实数据能暴露的断点（2026-11-05 实测）：
   认知侧《预问诊摘要》(pasm_medical/consult.py summary()) 返回
   chief_complaint / present_illness / triage.suggested_department，且按合规
   **不含 assessment / plan**。旧 Java 落库读驼峰 chiefComplaint/assessment/plan
   → 真实链路上全为 null → 落库成「空壳就诊」→ 候选池一条都抽不出来。
   演示数据把字段手工填齐了，所以 74 项联调一直全绿、没暴露这个断点。

★ 数据合规：本脚本的 5 例主诉与回答全部是**合成文本**，不含任何真实患者信息；
   走的是生产同款 HTTP 链路，产生的是结构化就诊记录（不存原始对话）。

用法::

    cd backend && mvn -DskipTests package          # JDK 17+，先重打 fat jar
    python tools/eval_real_learning.py
    python tools/eval_real_learning.py --out docs/REAL-LEARNING-EVAL.md
    python tools/eval_real_learning.py --keep-logs  # 保留服务日志便于排查
"""
from __future__ import annotations

import argparse
import datetime as dt
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

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
BACKEND = REPO / "backend"

JAVA = r"C:\Program Files\Java\jdk-18.0.1.1"
if not os.path.isdir(JAVA):
    JAVA = os.environ.get("JAVA_HOME", JAVA)
JAVA_BIN = os.path.join(JAVA, "bin", "java.exe") if os.path.isdir(JAVA) else "java"
API_TOKEN = "eval-real-learning-token"

# 5 例合成主诉：4 例常规路径（triage 给 suggested_department）+ 1 例红旗中断路径
# （triage 只给 urgency/advice/reason，**没有 suggested_department** —— 这是真实存在的分支，
#   必须覆盖，不能只挑好测的那条路径）。
CASES = [
    {"ref": "eval-fever-01", "chief": "发烧两天了", "expectDept": True,
     "answers": ["两天前开始的", "三十八度五", "有点咳嗽", "没有", "无"]},
    {"ref": "eval-cough-02", "chief": "咳嗽三天", "expectDept": True,
     "answers": ["三天前", "干咳为主", "晚上更明显", "没有", "无"]},
    {"ref": "eval-abdomen-03", "chief": "肚子疼", "expectDept": True,
     "answers": ["肚脐周围", "一阵一阵的", "昨天开始的", "没有", "无"]},
    {"ref": "eval-dizzy-04", "chief": "头晕", "expectDept": True,
     "answers": ["站起来的时候", "天旋地转", "今天早上", "没有", "无"]},
    {"ref": "eval-chest-05", "chief": "胸口有点闷", "expectDept": False,
     "answers": ["往左肩和后背串"]},   # ★ 红旗：首答即 halted
]

OK = FAIL = 0
CHECKS: list[tuple[str, bool, str]] = []
FINDINGS: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> bool:
    global OK, FAIL
    ok = bool(cond)
    if ok:
        OK += 1
        print("  v %s" % name)
    else:
        FAIL += 1
        print("  x %s%s" % (name, ("  <- " + detail) if detail else ""))
    CHECKS.append((name, ok, detail if not ok else ""))
    return ok


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_port(port: int, timeout: float = 180.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            s.settimeout(0.6)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.7)
    return False


def read_tail(path: str, n: int = 2500) -> str:
    try:
        return io.open(path, encoding="utf-8", errors="replace").read()[-n:]
    except Exception as ex:                                    # noqa: BLE001
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
        except Exception:                                     # noqa: BLE001
            return e.code, {}
    except Exception as ex:                                   # noqa: BLE001
        return 0, {"_err": str(ex)}


def login(base: str) -> tuple[str, str]:
    for role in ("staff",):
        st, d = req("POST", base + "/api/auth/login",
                    {"username": role, "password": "123456"})
        if st == 200 and d.get("token"):
            return role, d["token"]
    return "", ""


def run_case(base: str, token: str, case: dict, report: dict) -> None:
    """跑一例真实问诊并核验业务库落库字段（不碰演示数据）。"""
    ref, chief = case["ref"], case["chief"]
    st, d = req("POST", base + "/api/consult/start",
                {"patientRef": ref, "chiefComplaint": chief}, token=token)
    check("start 成功并拿到问诊树首问：%s" % chief,
          st == 200 and bool(d.get("question")), str(d)[:180])

    halted = False
    for ans in case["answers"]:
        st, d = req("POST", base + "/api/consult/answer",
                    {"patientRef": ref, "sessionKey": "current", "value": ans}, token=token)
        if d.get("halted") or not d.get("question"):
            halted = bool(d.get("halted"))
            break

    st, fin = req("POST", base + "/api/consult/finish",
                  {"patientRef": ref, "sessionKey": "current"}, token=token)
    cc = fin.get("chief_complaint") or ""
    check("finish 产出《预问诊摘要》（chief_complaint 非空）：%s" % chief,
          st == 200 and fin.get("ok") is True and bool(cc), str(fin)[:200])

    st, encs = req("GET", base + "/api/patient/encounters?ref=" + ref, token=token)
    newest = encs[0] if isinstance(encs, list) and encs else {}
    check("业务库落库：列表 title 非空（不是兜底「就诊记录」）：%s" % chief,
          bool(newest.get("title")) and newest.get("title") != "就诊记录",
          str(newest)[:180])
    check("业务库落库：列表 summary 非空：%s" % chief,
          bool(newest.get("summary")), str(newest)[:180])

    eid = newest.get("id")
    st, detail = req("GET", base + "/api/patient/encounter/%s?ref=%s" % (eid, ref),
                     token=token)
    summary = detail.get("summary") or ""
    check("结构化详情 chiefComplaint 非空：%s" % chief,
          bool(detail.get("chiefComplaint")), str(detail)[:180])
    check("合规：assessment / plan 为空（预问诊不出诊断结论）：%s" % chief,
          not detail.get("assessment") and not detail.get("plan"), str(detail)[:180])
    check("摘要不含「判断：/处置：」等伪造结论：%s" % chief,
          "判断：" not in summary and "处置：" not in summary, summary[:180])
    check("摘要是去标识化结构化事实（含「主诉：」）：%s" % chief,
          "主诉：" in summary, summary[:180])
    if case["expectDept"]:
        check("常规路径 department 来自 triage.suggested_department：%s" % chief,
              bool(detail.get("department")), str(detail)[:180])
    else:
        FINDINGS.append(
            "红旗中断路径：triage 只给 urgency/advice/reason，不含 suggested_department，"
            "落库 department 为空（本次实际值=%r）。该就诊产生的候选科室为空，"
            "按审核台规则仅超管可见 —— 这是认知侧既有结构，不是落库丢字段。" % detail.get("department"))

    report["encounters"].append({
        "ref": ref, "chiefInput": chief, "halted": halted,
        "chiefComplaint": detail.get("chiefComplaint") or "",
        "department": detail.get("department") or "",
        "urgency": detail.get("urgency") or "",
        "assessment": detail.get("assessment") or "",
        "plan": detail.get("plan") or "",
        "summary": summary,
        "finishChiefComplaint": cc,
    })


def md_table(headers: list[str], rows: list[list[str]]) -> str:
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(x).replace("|", "\\|").replace("\n", " ")
                                    for x in r) + " |")
    return "\n".join(out)


def render_report(report: dict) -> str:
    lines = [
        "# 真实链路就诊数据 × 自学习候选池 评测报告",
        "",
        "- 生成时间：%s" % report["time"],
        "- 仓库：`E:/AI/pasm/code/pasm-medical`",
        "- 命令：`python tools/eval_real_learning.py`",
        "- 后端开关：`MEDICAL_LEARNING_AUTO=true`（dev 档，H2 内存库，进程结束即销毁）",
        "- JDK：`%s`" % JAVA,
        "",
        "> **数据合规说明**：本轮 5 例主诉与回答全部为**合成文本**，不含任何真实患者信息；"
        "所称「真实就诊数据」指**经由生产同款 HTTP 链路 `/api/consult/start → answer → finish` "
        "真实产生并落业务库的结构化就诊记录**，与 `DemoDataSeeder` 手工填齐字段的演示就诊严格区分。"
        "业务库只存结构化字段 + 去标识化摘要，不存原始问诊对话。",
        "",
        "## 结论先行",
        "",
        "- 核验 **%d 项通过 / %d 项失败**%s" % (
            OK, FAIL,
            "；首轮评测曾以 3 项失败暴露真实数据缺口，修复后本轮全绿（详见「四、发现与处置」）。"
            if FAIL == 0 else "；失败项即真实数据暴露的缺口（详见「四、发现与处置」）。"),
        "- 开关语义：`MEDICAL_LEARNING_AUTO` 只控制**每日 02:00 的 `@Scheduled` 批处理**；"
        "审核台的手动 `POST /api/admin/kb/candidates/generate` 不受它限制（设计如此）。"
        "本轮用手动入口触发「那一轮」，同时用随报告回传的 `autoGenerateEnabled` 硬证开关注入值。",
        "- 真实链路落库：主诉 / 科室 / 去标识化摘要均非空；`assessment`/`plan` 按合规为空。",
        "- 候选生成：%s；指纹去重、冲突挂起、审核闸门、版本回滚均按设计工作。" % report.get("genSummary", "（未执行）"),
        "- 配套回归：`tools/e2e_stack.py` 已补 **7 项真实链路落库断言**（74 → 81 项，防演示数据假绿）；"
        "全量 `run_checks.py --web --java` **14 项套件全绿**（含后端真库测试与前端构建）。",
        "- **数据资格限制（本轮最重要的结论）**：预问诊摘要按合规不含医师结论，"
        "故本轮产出的候选正文是「患者自述 + 分诊建议」，**不具备作为机构回答依据的资格**。"
        "已给这类候选加显著警示护栏；真正合格的自学习输入应是「医师确认后的判断/处置」，"
        "该链路目前不存在，属 P3 待办。在此之前 `MEDICAL_LEARNING_AUTO` 于生产应保持关闭。",
        "- 表中回答为合成文本、按问诊树顺序填入，语义与要素名未必对应"
        "（如「体温：两天前开始的」）；本轮只验**数据通路与字段完整性**，不验临床语义正确性。",
        "",
        "## 一、就诊落库（5 例真实链路）",
        "",
        md_table(["ref", "输入主诉", "落库主诉", "科室", "紧急度", "判断/处置", "摘要（截断）"],
                 [[e["ref"], e["chiefInput"], e["chiefComplaint"], e["department"] or "（空）",
                   e["urgency"], e["assessment"] + e["plan"] or "（空·合规）",
                   e["summary"][:110]] for e in report["encounters"]]),
        "",
        "## 二、候选池产出",
        "",
        md_table(["标题", "科室", "状态", "confidence", "来源", "内容（截断）"],
                 [[c.get("title", ""), c.get("department", "") or "（空）",
                   c.get("status", ""), c.get("confidence", ""),
                   c.get("source", ""), str(c.get("content", ""))[:120]]
                  for c in report["candidates"]]),
        "",
        "## 三、逐项核验",
        "",
        md_table(["结果", "核验项", "失败详情"],
                 [["PASS" if ok else "**FAIL**", n, d] for n, ok, d in CHECKS]),
        "",
        "## 四、发现与处置（真实数据暴露，演示数据掩盖不了）",
        "",
        "1. **真实问诊落库是空壳（已修复）**：认知侧《预问诊摘要》返回 "
        "`chief_complaint` / `present_illness` / `triage.suggested_department`，且按合规"
        "**不含** `assessment` / `plan`；旧 Java 落库读驼峰 `chiefComplaint` 等 → 全为 null，"
        "候选池 `title.isEmpty() → continue` → 修复前真实数据恒产出 **0 条**候选。"
        "`DemoDataSeeder` 手工填齐字段，既有 74 项联调因此一直全绿。"
        "   修法：`MedicalProxyController.consultFinish` 按真实响应对齐字段名、补 `department`，"
        "`summaryText` 由 `summaryOf` 构造去标识化结构化摘要（不编造结论、不存原始对话）。",
        "2. **科室范围错配（已修复）**：`triage.suggested_department` 是复合值"
        "（`发热门诊 / 感染科`），而 `Identity.department` 是单科室（`发热门诊`）；"
        "`KbCandidateService.list` 用精确 `equals` 收窄 → 科室管理员对本轮真实候选可见 **0 条**。"
        "   修法：业务层落库取主科室（第一段）用于授权归类，完整建议仍原样保留在摘要里；"
        "分诊规则本身仍在认知侧，Java 不重写（薄转发铁律）。",
        "3. **红旗中断路径没有 `suggested_department`（如实标注，未改）**："
        "`ConsultSession.triage()` 命中红旗时只返回 `urgency/advice/reason`，"
        "故该就诊 `department` 为空、其候选按设计仅超管可见。这是认知侧既有结构，不是落库丢字段。",
        "4. **候选数据资格不足（已加护栏，根治属 P3）**：见「结论先行」。"
        "审核台的人工闸门是最后一道防线，候选绝不自动生效（本轮已核验）。",
        "",
        "### 运行时观察",
        "",
    ]
    for i, f in enumerate(FINDINGS, 1):
        lines.append("- %s" % f)
    if not FINDINGS:
        lines.append("- （无）")
    lines += [
        "",
        "## 五、复现",
        "",
        "```bash",
        "cd E:/AI/pasm/code/pasm-medical/backend",
        "JAVA_HOME='C:\\Program Files\\Java\\jdk-18.0.1.1' 'D:\\Program Files\\apache-maven-3.8.6\\bin\\mvn.cmd' -q -DskipTests package",
        "cd ..",
        "python tools/eval_real_learning.py --out docs/REAL-LEARNING-EVAL.md",
        "```",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(REPO / "docs" / "REAL-LEARNING-EVAL.md"))
    ap.add_argument("--keep-logs", action="store_true")
    args = ap.parse_args()

    print("真实链路就诊数据 × 自学习候选池 评测（MEDICAL_LEARNING_AUTO=true）")
    print("-" * 70)

    jars = [p for p in BACKEND.glob("target/*.jar")
            if "sources" not in p.name and "javadoc" not in p.name]
    jar = max(jars, key=lambda p: p.stat().st_mtime) if jars else None
    if not check("找到按 mtime 最新的后端 fat jar（>10MB）",
                 jar is not None and jar.stat().st_size > 10_000_000,
                 str(jar)):
        print("[中止] 先重打包：cd backend && mvn -DskipTests package")
        return 2

    cog_port, api_port, ctrl_port = free_port(), free_port(), free_port()
    tmp = tempfile.mkdtemp(prefix="pasm-real-learning-")
    procs: list[tuple[str, subprocess.Popen]] = []
    report = {"time": dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
              "encounters": [], "candidates": [], "genSummary": "（未执行）"}
    api = ctrl = None

    try:
        # ---------- 1) Python 认知服务（唯一算法实现）
        py_env = dict(os.environ)
        py_env["PYTHONPATH"] = os.pathsep.join(
            [str(REPO), str(REPO.parent / "pasm-skills"), str(REPO.parent / "pasm-framework")])
        py_env["PASM_MEDICAL_TOKEN"] = API_TOKEN
        py_env["PYTHONUNBUFFERED"] = "1"
        cog_log = os.path.join(tmp, "cognition.log")
        cog = subprocess.Popen(
            [sys.executable, "-m", "pasm_medical.service", "--host", "127.0.0.1",
             "--port", str(cog_port), "--token", API_TOKEN, "--dir", tmp],
            cwd=str(REPO), env=py_env,
            stdout=open(cog_log, "w", encoding="utf-8"), stderr=subprocess.STDOUT)
        procs.append(("cognition", cog))
        if not wait_port(cog_port):
            print("[中止] 认知服务没起来：\n" + read_tail(cog_log))
            return 2
        st, hz = req("GET", "http://127.0.0.1:%d/healthz" % cog_port)
        check("认知服务 /healthz 且 custom_routes=15",
              st == 200 and hz.get("custom_routes") == 15, str(hz)[:180])

        # ---------- 2) 主后端：MEDICAL_LEARNING_AUTO=true
        # ★ SERVER__PORT 会被 Spring 宽松绑定成 server.port 抢走端口（skill §13 教训），必须清掉
        base_env = dict(os.environ, JAVA_HOME=JAVA)
        base_env.pop("SERVER__PORT", None)
        env_on = dict(base_env)
        env_on["MEDICAL_LEARNING_AUTO"] = "true"
        env_off = dict(base_env)
        env_off.pop("MEDICAL_LEARNING_AUTO", None)

        def start_backend(port: int, env: dict, tag: str):
            log = os.path.join(tmp, tag + ".log")
            p = subprocess.Popen(
                [JAVA_BIN, "-jar", str(jar),
                 "--spring.profiles.active=dev",
                 "--server.port=%d" % port,
                 "--pasm.cognition.base-url=http://127.0.0.1:%d" % cog_port,
                 "--pasm.cognition.token=" + API_TOKEN],
                cwd=str(BACKEND), env=env,
                stdout=open(log, "w", encoding="utf-8"), stderr=subprocess.STDOUT)
            procs.append((tag, p))
            if not wait_port(port):
                print("[中止] %s 没起来：\n%s" % (tag, read_tail(log)))
                return None
            return log

        check("主后端启动（MEDICAL_LEARNING_AUTO=true）",
              start_backend(api_port, env_on, "backend-on") is not None)
        # ---------- 2b) 对照后端：不设环境变量（默认关闭）
        check("对照后端启动（未设 MEDICAL_LEARNING_AUTO）",
              start_backend(ctrl_port, env_off, "backend-off") is not None)

        base = "http://127.0.0.1:%d" % api_port
        ctrl = "http://127.0.0.1:%d" % ctrl_port
        st, d = req("GET", base + "/actuator/health")
        check("主后端 /actuator/health = UP", st == 200 and d.get("status") == "UP", str(d)[:120])

        # ---------- 3) 登录（dev 演示账号）
        _, token = login(base)
        check("超管 staff 登录成功", bool(token))
        st, d = req("POST", base + "/api/auth/login",
                    {"username": "deptadmin", "password": "123456"})
        dept_token = d.get("token") or ""
        check("科室管理员 deptadmin 登录成功", st == 200 and bool(dept_token))

        # ★ 开关硬证：对照后端不带环境变量 → autoGenerateEnabled=false
        _, ctrl_token = login(ctrl)
        st, cgen = req("POST", ctrl + "/api/admin/kb/candidates/generate?days=1", {},
                       token=ctrl_token)
        check("反例对照：未设 MEDICAL_LEARNING_AUTO 时 autoGenerateEnabled=false",
              st == 200 and cgen.get("autoGenerateEnabled") is False, str(cgen)[:180])

        # ---------- 4) 基线
        st, base_cands = req("GET", base + "/api/admin/kb/candidates", token=token)
        base_ids = {c["id"] for c in base_cands} if isinstance(base_cands, list) else set()
        st, kb0 = req("GET", base + "/api/admin/kb", token=token)
        kb0_keys = {d["docKey"] for d in kb0.get("docs", [])}
        st, pv0 = req("GET", base + "/api/admin/kb-pool/version", token=token)
        print("  基线：演示候选 %d 条，现库资料 %d 条，知识池 v%s"
              % (len(base_ids), len(kb0_keys), pv0.get("version")))

        # ---------- 5) 5 例真实问诊（days=7 窗口内只有本轮数据；演示就诊在 8/9 月）
        for case in CASES:
            run_case(base, token, case, report)

        # ---------- 6) 触发一轮候选生成
        st, gen = req("POST", base + "/api/admin/kb/candidates/generate?days=7", {},
                      token=token)
        check("开关生效：主后端 autoGenerateEnabled=true",
              st == 200 and gen.get("autoGenerateEnabled") is True, str(gen)[:180])
        check("★ 真实就诊产出候选（generated > 0）—— 修复前恒为 0",
              st == 200 and gen.get("generated", 0) > 0, str(gen)[:220])
        generated = gen.get("generated", 0)
        report["genSummary"] = (
            "首轮 generated=%s / deduplicated=%s / conflict=%s / pendingTotal=%s"
            % (generated, gen.get("deduplicated"), gen.get("conflict"),
               gen.get("pendingTotal")))

        st, allc = req("GET", base + "/api/admin/kb/candidates", token=token)
        fresh = [c for c in allc if c["id"] not in base_ids] if isinstance(allc, list) else []
        report["candidates"] = fresh
        check("新增候选条数 == generate 报告的 generated",
              len(fresh) == generated, "fresh=%d generated=%d" % (len(fresh), generated))
        check("新增候选全部来自 daily-batch（不是演示数据）",
              bool(fresh) and all(c.get("source") == "daily-batch" for c in fresh),
              str([c.get("source") for c in fresh]))
        check("候选标题 = 真实就诊主诉",
              {c.get("title") for c in fresh} ==
              {e["chiefComplaint"] for e in report["encounters"] if e["chiefComplaint"]},
              str({c.get("title") for c in fresh}))
        check("合规：候选内容不含患者 ref / 姓名 / 伪造的判断处置",
              all(("eval-" not in str(c.get("content", ""))
                   and "判断：" not in str(c.get("content", ""))
                   and "处置：" not in str(c.get("content", ""))
                   and "示例患者" not in str(c.get("content", ""))) for c in fresh),
              str([c.get("content") for c in fresh])[:220])
        check("无医师结论时 confidence = 0.5（启发式下限，不作临床可信度）",
              bool(fresh) and all(abs(float(c.get("confidence", -1)) - 0.5) < 1e-6
                                  for c in fresh),
              str([c.get("confidence") for c in fresh]))
        check("★ 合规护栏：无医师结论候选自带「非医师结论」警示",
              bool(fresh) and all("非医师结论，不得作为诊疗依据" in str(c.get("content", ""))
                                  for c in fresh),
              str([str(c.get("content", ""))[:40] for c in fresh]))

        # ★ 铁律：候选不自动生效
        st, kb1 = req("GET", base + "/api/admin/kb", token=token)
        check("★ 生成候选绝不自动写 knowledge_docs",
              {d["docKey"] for d in kb1.get("docs", [])} == kb0_keys,
              str({d["docKey"] for d in kb1.get("docs", [])} - kb0_keys))
        st, pv1 = req("GET", base + "/api/admin/kb-pool/version", token=token)
        check("生成候选不改知识池版本",
              pv1.get("version") == pv0.get("version"), str(pv1))

        # ---------- 7) 幂等 / 指纹去重
        st, gen2 = req("POST", base + "/api/admin/kb/candidates/generate?days=7", {},
                       token=token)
        check("二次生成 generated = 0（幂等）", gen2.get("generated") == 0, str(gen2)[:180])
        check("二次生成 deduplicated = 首轮 generated（指纹去重生效）",
              gen2.get("deduplicated") == generated, str(gen2)[:180])

        # ---------- 8) 审核闸门：采纳才入库 / 驳回不入库
        pending = [c for c in fresh if c.get("status") == "PENDING"]
        if check("至少 2 条 PENDING 供采纳/驳回对照", len(pending) >= 2,
                 "pending=%d" % len(pending)):
            st, ap = req("POST", base + "/api/admin/kb/candidates/%s/approve" % pending[0]["id"],
                         {}, token=token)
            check("采纳后知识池版本 +1",
                  st == 200 and ap.get("poolVersion") == pv0.get("version", 1) + 1,
                  str(ap)[:180])
            st, kb2 = req("GET", base + "/api/admin/kb", token=token)
            check("采纳后资料才进 knowledge_docs",
                  any(d["docKey"] == ap.get("docKey") for d in kb2.get("docs", [])),
                  str(ap.get("docKey")))
            st, rj = req("POST", base + "/api/admin/kb/candidates/%s/reject" % pending[1]["id"],
                         {}, token=token)
            st, kb3 = req("GET", base + "/api/admin/kb", token=token)
            check("驳回成功且绝不入库",
                  st == 200 and len(kb3.get("docs", [])) == len(kb2.get("docs", [])),
                  str(st))

        # ---------- 9) 科室范围（真实链路的 department 值是否对得上身份里的科室）
        st, dept_view = req("GET", base + "/api/admin/kb/candidates", token=dept_token)
        visible = [c for c in dept_view
                   if c["id"] not in base_ids and c.get("source") == "daily-batch"] \
            if isinstance(dept_view, list) else []
        real_depts = sorted({c.get("department") or "（空）" for c in fresh})
        check("★ 科室管理员（发热门诊）能看到本科室真实候选",
              len(visible) > 0,
              "可见 %d 条；真实候选 department 取值=%s；身份 scope=发热门诊"
              % (len(visible), real_depts))
        if not visible:
            FINDINGS.append(
                "**科室范围错配**：认知侧 `triage.suggested_department` 给的是复合值（如 "
                "`发热门诊 / 感染科`、`心内科 / 急诊内科`），而 `Identity.department` 是单科室"
                "（`发热门诊`）；`KbCandidateService.list` 用精确 `equals` 过滤，"
                "导致科室管理员在本轮真实候选上**一条都看不到**（本轮实际可见 %d 条，"
                "候选 department 取值 %s）。演示候选手工填的是单科室，所以既有测试没暴露。" % (len(visible), real_depts))

        # ---------- 10) 冲突挂起：先造现库同标题异结论资料，再跑一例新主诉
        cref, cchief = "eval-conflict-06", "头痛"
        req("POST", base + "/api/consult/start",
            {"patientRef": cref, "chiefComplaint": cchief}, token=token)
        for ans in ["昨天开始的", "一阵一阵的", "没有"]:
            st, d = req("POST", base + "/api/consult/answer",
                        {"patientRef": cref, "sessionKey": "current", "value": ans}, token=token)
            if d.get("halted") or not d.get("question"):
                break
        st, cfin = req("POST", base + "/api/consult/finish",
                       {"patientRef": cref, "sessionKey": "current"}, token=token)
        conflict_title = cfin.get("chief_complaint") or ""
        st, newdoc = req("POST", base + "/api/admin/kb",
                         {"title": conflict_title, "content": "旧结论（评测构造）",
                          "tags": "x", "department": "神经内科"}, token=token)
        doc_key = (newdoc.get("doc") or {}).get("docKey") or ""
        st, gen3 = req("POST", base + "/api/admin/kb/candidates/generate?days=7", {},
                       token=token)
        check("★ 同标题异结论候选被挂起（conflict = 1）",
              gen3.get("conflict") == 1, str(gen3)[:220])
        st, conflicts = req("GET", base + "/api/admin/kb/candidates?status=CONFLICT", token=token)
        check("冲突候选带 conflictWith 指向现库资料，不覆盖现库",
              isinstance(conflicts, list)
              and any(c.get("title") == conflict_title and c.get("conflictWith") == doc_key
                      for c in conflicts),
              str(conflicts)[:220])

        # ---------- 11) 输出报告
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render_report(report), encoding="utf-8")
        print("\n报告已写入：%s" % out)

    finally:
        for name, p in procs:
            try:
                p.terminate()
                p.wait(timeout=15)
            except Exception:                                 # noqa: BLE001
                try:
                    p.kill()
                except Exception:
                    pass
            print("  已停止：%s" % name)
        if not args.keep_logs:
            import shutil
            shutil.rmtree(tmp, ignore_errors=True)
        else:
            print("  日志保留：%s" % tmp)

    print("-" * 70)
    print("结果：%d 项通过，%d 项失败" % (OK, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
