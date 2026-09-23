# -*- coding: utf-8 -*-
"""医学动作后验（P0.5）端到端验证 —— 真起 HTTP 服务。

核心验收（与 docs/PLAN-CASE-LEARNING.md §4 阶段 1 对齐）：
  ① 医生否决一条 ``triage:<科室>`` 建议后，同处境的分诊候选**排序**发生可观测变化；
  ② 被否决科室的采纳概率（GET /api/feedback/predict）**下降**；
  ③ 非法 decision 经 POST /api/feedback 返回 **400**。
并且：聊天动作池权重**不变**（反例对照见 tools/falsify_medical_learning.py）。

为什么必须端到端（而不是只调函数）
---------------------------------
后验落盘 + 路由 + 鉴权 + 网关 match_route 是一整条链路。
只调 feedback_medical / triage_candidates 会漏掉「路由没挂上 / 400 没生效 /
管理令牌鉴权」这类只有真走 HTTP 才暴露的问题。

用法::

    python tools/e2e_medical_learning.py
"""
from __future__ import annotations

import json
import os
import shutil
import socket
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
for p in (str(REPO), str(REPO.parent / "pasm-skills"), str(REPO.parent / "pasm-framework")):
    if os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)

from pasm_medical.service import build_service                          # noqa: E402

OK = FAIL = 0
TOKEN = "medical-e2e-token"

#: 处境模板（与前端 feedbackContext() 同构）：complaint=主诉;age_band=…;redflag=…
CTX = "complaint=胸痛;age_band=成年;redflag=无"


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


def req(method: str, url: str, body=None, token: str = TOKEN):
    data = None
    h = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        h["Content-Type"] = "application/json; charset=utf-8"
    if token:
        h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def _candidates_url(base: str, complaint: str, context: str) -> str:
    return (base + "/api/triage/candidates?chiefComplaint=%s&context=%s"
            % (urllib.parse.quote(complaint), urllib.parse.quote(context)))


def _predict_url(base: str, action: str, context: str) -> str:
    return (base + "/api/feedback/predict?action=%s&context=%s"
            % (urllib.parse.quote(action), urllib.parse.quote(context)))


def main() -> int:
    print("医学动作后验（P0.5）端到端验证")
    print("-" * 64)

    port = free_port()
    tmp = tempfile.mkdtemp(prefix="pasm-med-learn-e2e-")
    svc = build_service(tenant="h1", kb_dir=os.path.join(tmp, "kb"),
                        persist_dir=os.path.join(tmp, "persist"),
                        host="127.0.0.1", port=port, token=TOKEN,
                        workspace=tmp)
    base = "http://127.0.0.1:%d" % port
    try:
        svc.app.serve()
        print("  服务已起：%s\n" % base)

        # ---------- 0. 否决前的候选排序（基线）
        st0, d0 = req("GET", _candidates_url(base, "胸痛", CTX))
        check("分诊候选接口可用（200）", st0 == 200 and d0.get("ok") is True, str(d0)[:160])
        cands0 = [c["department"] for c in d0.get("candidates", [])]
        suggested = d0.get("suggested_department", "")
        check("基线候选 ≥ 2 条（有东西可排）", len(cands0) >= 2, str(cands0))
        check("规则建议科室确实在候选集里",
              suggested in cands0, "suggested=%s cands=%s" % (suggested, cands0))

        # ---------- 1. 否决规则建议科室 → 排序变化 + 概率下降（核心验收）
        st_fb, fb = req("POST", base + "/api/feedback", body={
            "patientRef": "patient-learn-001",
            "decision": "reject",
            "medicalAction": "triage:%s" % suggested,
            "context": CTX,
        })
        check("否决反馈返回 200 且 learned=True",
              st_fb == 200 and fb.get("learned") is True, "st=%s fb=%s" % (st_fb, fb))
        check("否决后采纳概率下降（< 0.5，Beta(1,2)=0.333）",
              fb.get("adoption_probability", 1) < 0.5, str(fb))

        st1, d1 = req("GET", _candidates_url(base, "胸痛", CTX))
        cands1 = [c["department"] for c in d1.get("candidates", [])]
        check("★ 否决规则建议科室后，分诊候选排序发生变化",
              cands1 != cands0, "前=%s 后=%s" % (cands0, cands1))
        check("★ 被否决科室从首位后移",
              cands1.index(suggested) > cands0.index(suggested),
              "前位=%s 后位=%s" % (cands0.index(suggested), cands1.index(suggested)))

        # ---------- 2. predict 读后验：被否决科室概率确实下降
        st_p, p0 = req("GET", _predict_url(base, "triage:%s" % suggested, CTX))
        check("predict 接口可用（200）", st_p == 200, "st=%s" % st_p)
        check("★ 否决后该科室采纳概率下降（predict < 0.5）",
              p0.get("adoption_probability", 1) < 0.5, str(p0))

        # ---------- 3. 非法 decision → 400
        st_bad, _ = req("POST", base + "/api/feedback", body={
            "patientRef": "patient-learn-001",
            "decision": "bogus",
            "medicalAction": "triage:%s" % suggested,
            "context": CTX,
        })
        check("★ 非法 decision 经 POST /api/feedback 返回 400", st_bad == 400,
              "st=%s" % st_bad)

        # ---------- 4. 空 medicalAction（仅审计，不学）→ learned=False
        st_audit, fb_audit = req("POST", base + "/api/feedback", body={
            "patientRef": "patient-learn-002",
            "decision": "adopt",
            "medicalAction": "",
            "context": CTX,
        })
        check("空动作反馈 learned=False（仅审计，不碰后验）",
              st_audit == 200 and fb_audit.get("learned") is False, str(fb_audit))

        # ---------- 5. 处境隔离：另一处境的否决不影响本处境
        other_ctx = "complaint=咳嗽;age_band=老年;redflag=无"
        req("POST", base + "/api/feedback", body={
            "patientRef": "patient-learn-001",
            "decision": "reject",
            "medicalAction": "triage:%s" % suggested,
            "context": other_ctx,
        })
        _, p_iso = req("GET", _predict_url(base, "triage:%s" % suggested, CTX))
        check("★ 另一处境的否决不影响本处境概率（仍是 0.333）",
              abs(p_iso.get("adoption_probability", -1) - 1 / 3) < 1e-9, str(p_iso))

        # ---------- 6. 采纳某替代科室 → 其概率上升（升权能被观测）
        alt = next((c for c in cands1 if c != suggested), None)
        if alt:
            req("POST", base + "/api/feedback", body={
                "patientRef": "patient-learn-001",
                "decision": "adopt",
                "medicalAction": "triage:%s" % alt,
                "context": CTX,
            })
            _, p_adopt = req("GET", _predict_url(base, "triage:%s" % alt, CTX))
            check("采纳替代科室后其概率 > 0.5（升权）",
                  p_adopt.get("adoption_probability", 0) > 0.5, str(p_adopt))

        # ---------- 7. 绝不训练聊天动作池：非法动作前缀被后验拒绝（不学）
        st_chat, fb_chat = req("POST", base + "/api/feedback", body={
            "patientRef": "patient-learn-001",
            "decision": "adopt",
            "medicalAction": "greet",          # 聊天动作池前缀，应被后验拒绝
            "context": CTX,
        })
        check("聊天动作池前缀被后验拒绝（ok=False，不写后验）",
              st_chat == 200 and fb_chat.get("ok") is False
              and "illegal action" in (fb_chat.get("error") or ""), str(fb_chat))
    finally:
        try:
            svc.app.close()
        except Exception as ex:                                  # noqa: BLE001
            print("  (close 异常：%s)" % ex)
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 64)
    print("结果：%d 项通过，%d 项失败" % (OK, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
