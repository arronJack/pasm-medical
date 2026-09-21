# -*- coding: utf-8 -*-
"""医疗认知服务端到端验证 —— 真起 HTTP 服务，重点证明**患者隔离**。

为什么隔离要单独验
------------------
PASM 的知识库与记忆默认落点是**全机共享**的。在通用场景那只是"串味"；
在医疗场景，A 患者的过敏史出现在 B 患者的召回里 = **数据泄露**。

所以本脚本的核心不是"接口能用"，而是：
  造两个患者 → 各写一条**互不相同**的记忆 → 断言彼此**一条都看不到**。
并且这条断言必须能证伪（见 tools/falsify_medical_service.py）。

用法::

    python tools/e2e_medical_service.py
"""
from __future__ import annotations

import json
import os
import shutil
import socket
import sys
import tempfile
import urllib.error
import urllib.parse          # 显式导入：不要依赖 urllib.request 的副作用
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
for p in (str(REPO), str(REPO.parent / "pasm-skills"), str(REPO.parent / "pasm-framework")):
    if os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)

from pasm_medical import safety                                    # noqa: E402
from pasm_medical.domain import (Encounter, Observation, agent_id_for,  # noqa: E402
                                 pseudonymize)
from pasm_medical.service import build_service                     # noqa: E402

OK = FAIL = 0
TOKEN = "medical-e2e-token"


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


def main() -> int:                                          # noqa: C901
    print("医疗认知服务端到端验证")
    print("-" * 64)

    port = free_port()
    tmp = tempfile.mkdtemp(prefix="pasm-med-e2e-")
    svc = build_service(tenant="h1", kb_dir=os.path.join(tmp, "kb"),
                        persist_dir=os.path.join(tmp, "persist"),
                        host="127.0.0.1", port=port, token=TOKEN,
                        workspace=tmp)
    base = "http://127.0.0.1:%d" % port
    try:
        svc.app.serve()
        print("  服务已起：%s\n" % base)

        # ---------- 0. 健康
        h = svc.health()
        check("健康检查报告认知层可用", h["cognitive_available"] is True, str(h)[:180])
        check("健康检查声明确实不产出处方", "不产出处方" in h["note"])

        # ---------- 1. 患者级隔离（本脚本的核心）
        pa, pb = pseudonymize("patient-A"), pseudonymize("patient-B")
        svc.record_allergy(pa, "青霉素", "皮疹")
        svc.record_allergy(pb, "磺胺", "发热")

        a_id, b_id = agent_id_for("h1", pa), agent_id_for("h1", pb)
        check("★ 两个患者的 agent_id 不同", a_id != b_id, "%s / %s" % (a_id, b_id))

        # 走 HTTP（不是直接调函数）—— 验的是整条链路
        st, da = req("GET", base + "/api/cog/recall?agent_id=%s&query=%s"
                     % (a_id, urllib.parse.quote("过敏")))
        ta = [x.get("title", "") for x in da.get("hits", [])]
        st2, db = req("GET", base + "/api/cog/recall?agent_id=%s&query=%s"
                      % (b_id, urllib.parse.quote("过敏")))
        tb = [x.get("title", "") for x in db.get("hits", [])]

        check("★ 患者 A 能看到自己的过敏史",
              any("青霉素" in t for t in ta), "A=%s" % ta)
        check("★ 患者 A **看不到**患者 B 的过敏史",
              not any("磺胺" in t for t in ta), "A=%s" % ta)
        check("★ 患者 B 能看到自己的、看不到 A 的",
              any("磺胺" in t for t in tb) and not any("青霉素" in t for t in tb),
              "B=%s" % tb)

        # ---------- 2. 跨租户隔离（同一患者号在两个租户下不能撞）
        check("★ 跨租户 agent_id 不撞（同号不同租户）",
              agent_id_for("h1", pa) != agent_id_for("h2", pa),
              "%s / %s" % (agent_id_for("h1", pa), agent_id_for("h2", pa)))

        # ---------- 3. 就诊写入 + 时间轴
        enc = Encounter(encounter_id="E20260921001", patient_ref=pa,
                        occurred_at="2026-09-21T10:30:00+08:00",
                        department="心内科", chief_complaint="胸痛 2 小时",
                        observations=[Observation(code="血压", value="152/94",
                                                  unit="mmHg")],
                        assessment="高血压 3 级（高危）", plan="随访 + 生活干预")
        r = svc.record_encounter(enc)
        check("就诊写入成功", r.get("ok") is True and r.get("written", 0) >= 2, str(r))
        tl = svc.timeline(pa)
        check("时间轴能取到该次就诊",
              any("就诊" in (x.get("title") or "") for x in tl), str(tl)[:180])

        # ---------- 4. 带护栏的问答
        g1 = svc.ask(pa, "血压 高血压")
        check("★ 有依据时返回来源且强制医师确认",
              g1["requires_physician_confirmation"] is True and len(g1["hits"]) >= 1,
              str(g1)[:180])
        check("★ 有依据时不拒答", g1["refused"] is False)
        g2 = svc.ask(pb, "髋关节置换术后康复方案")
        check("★ 无依据时必须拒答（不编造）",
              g2["refused"] is True and g2["text"] == safety.REFUSAL_TEXT,
              str(g2)[:200])

        # ---------- 5. 确定性规则（不经过 LLM）
        c1 = svc.check_herbs(["附子", "半夏"])
        check("★ 十八反经服务层命中且判为 blocking",
              c1["blocking"] is True, str(c1)[:200])
        c2 = svc.check_herbs(["附子"], doses={"附子": 30})
        check("毒性剂量超限判为 blocking", c2["blocking"] is True, str(c2)[:200])
        c3 = svc.check_herbs(["黄芪", "当归"])
        check("正常配伍不拦（但仍带'空违例≠安全'说明）",
              c3["blocking"] is False and "不表示安全" in c3["note"],
              str(c3)[:200])

        # ---------- 6. 关键事实不被普通对话挤掉
        svc.record_allergy(pa, "头孢曲松", "过敏性休克")
        for i in range(30):          # 灌入大量闲聊噪声
            svc.record_encounter(Encounter(
                encounter_id="noise-%d" % i, patient_ref=pa, department="门诊",
                chief_complaint="复诊取药 第%d次" % i, assessment="稳定", plan="继续观察"))
        after = svc.recall(pa, "过敏 休克", k=10)
        titles = [x.get("title", "") for x in after.get("hits", [])]
        check("★ 30 轮噪声后，过敏史仍能被召回（salience=5 生效）",
              any("头孢曲松" in t or "青霉素" in t for t in titles), str(titles)[:200])

        # ---------- 7. 规则表元信息对外可见
        st, d = req("GET", base + "/api/cog/capabilities")
        check("认知接口仍可用（供 Spring Boot 调用）",
              st == 200 and d.get("available"), str(d)[:150])
    finally:
        try:
            svc.app.close()
        except Exception as ex:                            # noqa: BLE001
            print("  (close 异常：%s)" % ex)
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 64)
    print("结果：%d 项通过，%d 项失败" % (OK, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    import urllib.parse
    sys.exit(main())
