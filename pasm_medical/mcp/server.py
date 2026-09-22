# -*- coding: utf-8 -*-
"""pasm-medical 的 MCP stdio 服务器主循环（零第三方依赖）。

★ 与 HTTP 网关共享同一份业务逻辑：复用 ``pasm_medical.service.build_service`` 装配
``MedicalService``，但**不调用 ``app.serve()``**，只读 ``gateway.cognitive.capabilities``
门面。这样：
  · MCP 侧与 HTTP 侧走的是同一套患者隔离 / 护栏 / 规则（同源一份代码，不用重写）；
  · 不占用任何网络端口 —— stdio 进程由客户端拉起，协议走 stdin/stdout。

三条硬约束（与 pasm-mcp-server 相同）：
1. stdout 只能有 JSON-RPC，日志走 stderr；
2. 每行一条完整 JSON，写完立刻 flush；
3. Windows 下用 buffer 读写，避免 \\n→\\r\\n 破坏协议。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, Optional

from . import protocol as P
from .tools import call_tool, list_tools


def _log(msg: str) -> None:
    """日志一律走 stderr —— stdout 是协议通道，不能污染。"""
    if os.environ.get("PASM_MEDICAL_MCP_QUIET") == "1":
        return
    print("[pasm-medical-mcp] %s" % msg, file=sys.stderr, flush=True)


def _write(out, obj: Dict[str, Any]) -> None:
    data = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
    out.write(data)
    out.flush()


def handle_message(msg: Dict[str, Any], svc) -> Optional[Dict[str, Any]]:
    """处理一条 JSON-RPC 消息。返回 None 表示无需回复（通知类）。"""
    if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0":
        return P.error_response(
            msg.get("id") if isinstance(msg, dict) else None,
            P.INVALID_REQUEST, "Invalid Request: jsonrpc must be '2.0'")

    method = msg.get("method")
    req_id = msg.get("id")
    params = msg.get("params") or {}

    # 通知类：无 id，不回复
    if method == "notifications/initialized":
        return None
    if isinstance(method, str) and method.startswith("notifications/"):
        return None

    if method == "initialize":
        cv = (params or {}).get("protocolVersion")
        return P.result_response(req_id, P.initialize_result(cv))

    if method == "ping":
        return P.result_response(req_id, {})

    if method == "tools/list":
        return P.result_response(req_id, {"tools": list_tools()})

    if method == "tools/call":
        name = (params or {}).get("name")
        args = (params or {}).get("arguments") or {}
        try:
            result = call_tool(name, args, svc)
        except KeyError:
            return P.error_response(req_id, P.INVALID_PARAMS,
                                   "Unknown tool: %s" % name)
        except Exception as ex:  # noqa: BLE001
            _log("tool %s failed: %s" % (name, ex))
            return P.result_response(req_id, {
                "content": [{"type": "text",
                             "text": "pasm-medical 工具 %s 执行失败：%s" % (name, ex)}],
                "isError": True,
            })
        text = json.dumps(result, ensure_ascii=False, indent=2)
        payload: Dict[str, Any] = {
            "content": [{"type": "text", "text": text}],
            "isError": False,
        }
        payload["structuredContent"] = result
        return P.result_response(req_id, payload)

    if method == "shutdown":
        try:
            svc.app.close()
        except Exception:  # noqa: BLE001
            pass
        return P.result_response(req_id, {})

    return P.error_response(req_id, P.METHOD_NOT_FOUND,
                           "Method not found: %s" % method)


def build_medical_service(*, tenant: str, work_dir: str,
                          kb_dir: Optional[str] = None):
    """装配医疗认知服务（**不启动 HTTP**）。"""
    from pasm_medical.service import build_service
    kb = kb_dir or os.path.join(work_dir, "kb")
    return build_service(tenant=tenant, kb_dir=kb,
                         persist_dir=os.path.join(work_dir, "persist"),
                         host="127.0.0.1", port=0, token="",
                         workspace=work_dir)


def serve(*, tenant: str = "demo-hospital",
          work_dir: Optional[str] = None,
          kb_dir: Optional[str] = None) -> int:
    """跑 stdio 主循环，直到 stdin 关闭。"""
    wd = work_dir or os.path.join(os.path.expanduser("~"), ".pasm-medical-mcp")
    svc = build_medical_service(tenant=tenant, work_dir=wd, kb_dir=kb_dir)
    stdin = sys.stdin.buffer
    out = sys.stdout.buffer
    _log("starting (tenant=%s, work_dir=%s)" % (tenant, wd))
    try:
        for raw in stdin:
            line = raw.strip()
            if not line:
                continue
            try:
                msg = json.loads(line.decode("utf-8"))
            except Exception:
                _write(out, P.error_response(None, P.PARSE_ERROR, "Parse error"))
                continue
            resp = handle_message(msg, svc)
            if resp is not None:
                _write(out, resp)
    except KeyboardInterrupt:  # pragma: no cover
        pass
    finally:
        # 客户端断开时把认知状态落盘 —— 记忆不该因为退出而丢
        try:
            svc.app.close()
        except Exception:  # noqa: BLE001
            pass
    return 0


def selftest() -> int:
    """本地自检：不起 stdio，直接把协议与工具跑一遍（含患者隔离反例）。"""
    import shutil
    import sys as _sys
    import tempfile
    import os as _os

    # 本地开发逃生口：把 pasm 三件套加进 path（安装态不需要）
    HERE = _os.path.dirname(_os.path.abspath(__file__))
    MCPPKG = _os.path.dirname(HERE)          # pasm_medical
    ROOT = _os.path.dirname(MCPPKG)          # pasm-medical
    for p in (ROOT,
              _os.path.join(ROOT, "..", "pasm-skills"),
              _os.path.join(ROOT, "..", "pasm-framework")):
        pp = _os.path.abspath(p)
        if _os.path.isdir(pp) and pp not in _sys.path:
            _sys.path.insert(0, pp)

    from .. import safety as _safety

    ok = True

    def check(cond: bool, msg: str, detail: str = "") -> None:
        nonlocal ok
        print("  %s %s%s" % ("v" if cond else "x", msg,
                             ("  <- " + detail) if detail and not cond else ""))
        if not cond:
            ok = False

    print("pasm-medical-mcp selftest v%s" % P.SERVER_VERSION)
    print("-" * 64)
    with tempfile.TemporaryDirectory() as td:
        svc = build_medical_service(tenant="h1", work_dir=td)
        try:
            # initialize
            r = handle_message(
                {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                 "params": {"protocolVersion": "2024-11-05"}}, svc)
            check(r["result"]["protocolVersion"] == "2024-11-05",
                  "版本协商回显客户端版本")
            check(r["result"]["serverInfo"]["name"] == P.SERVER_NAME,
                  "serverInfo 正确")

            # tools/list
            r = handle_message({"jsonrpc": "2.0", "id": 2,
                                "method": "tools/list"}, svc)
            names = [t["name"] for t in r["result"]["tools"]]
            check(len(names) >= 10, "tools/list 返回 %d 个工具" % len(names))
            check("fn" not in r["result"]["tools"][0], "对外清单不含内部 fn 字段")
            check("med_ask" in names and "med_check_herbs" in names,
                  "包含核心工具 med_ask / med_check_herbs")

            # health
            r = handle_message(
                {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                 "params": {"name": "med_health", "arguments": {}}}, svc)
            check(r["result"]["isError"] is False, "med_health 可执行")
            hc = r["result"].get("structuredContent") or {}
            check(hc.get("cognitive_available") is True,
                  "健康检查报告认知层可用", str(hc)[:160])
            check("requires_physician_confirmation" not in hc,
                  "运维探针不带医师确认标记")

            # ---- 患者级隔离（本 sidecar 的核心）----
            pa, pb = "patient-A", "patient-B"
            handle_message(
                {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                 "params": {"name": "med_record_allergy",
                            "arguments": {"patient_ref": pa,
                                          "allergen": "青霉素", "reaction": "皮疹"}}},
                svc)
            handle_message(
                {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                 "params": {"name": "med_record_allergy",
                            "arguments": {"patient_ref": pb,
                                          "allergen": "磺胺", "reaction": "发热"}}},
                svc)

            r = handle_message(
                {"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                 "params": {"name": "med_recall",
                            "arguments": {"patient_ref": pa, "query": "过敏"}}}, svc)
            ta = [x.get("title", "") for x in
                  (r["result"]["structuredContent"].get("hits") or [])]
            check(any("青霉素" in t for t in ta), "患者 A 能看到自己的过敏史", str(ta))
            check(not any("磺胺" in t for t in ta),
                  "★ 患者 A 看不到患者 B 的过敏史", str(ta))
            check("requires_physician_confirmation" in
                  r["result"]["structuredContent"],
                  "召回结果带医师确认标记")

            r = handle_message(
                {"jsonrpc": "2.0", "id": 7, "method": "tools/call",
                 "params": {"name": "med_recall",
                            "arguments": {"patient_ref": pb, "query": "过敏"}}}, svc)
            tb = [x.get("title", "") for x in
                  (r["result"]["structuredContent"].get("hits") or [])]
            check(any("磺胺" in t for t in tb) and not any("青霉素" in t for t in tb),
                  "★ 患者 B 能看到自己的、看不到 A 的", str(tb))

            # ---- 带护栏问答：无依据必须拒答 ----
            r = handle_message(
                {"jsonrpc": "2.0", "id": 8, "method": "tools/call",
                 "params": {"name": "med_ask",
                            "arguments": {"patient_ref": pb,
                                          "question": "髋关节置换术后康复方案"}}}, svc)
            g = r["result"].get("structuredContent") or {}
            check(g.get("refused") is True and g.get("text") == _safety.REFUSAL_TEXT,
                  "★ 无依据时必须拒答（不编造）", str(g)[:200])

            # ---- 确定性规则（不经 LLM）----
            r = handle_message(
                {"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                 "params": {"name": "med_check_herbs",
                            "arguments": {"herbs": ["附子", "半夏"]}}}, svc)
            c = r["result"].get("structuredContent") or {}
            check(c.get("blocking") is True, "十八反·附子+半夏 判为 blocking",
                  str(c)[:200])
            check("不表示安全" in (c.get("note") or ""),
                  "规则结果带'空违例≠安全'说明")

            # ---- LLM 生效配置对账（业务层「对接设置」靠它判断是否真生效）----
            st = svc.llm_status()
            check(st.get("provider") == "null" and st.get("provider_known") is True,
                  "未配 LLM 时如实报 null（且算「已知档位」）", str(st)[:160])
            check(st.get("source", "").startswith("env:"),
                  "配置来源标注为环境变量", str(st.get("source"))[:80])
            _saved = {k: _os.environ.get(k) for k in
                      ("PASM_MEDICAL_LLM", "PASM_MEDICAL_LLM_API_KEY")}
            try:
                _os.environ["PASM_MEDICAL_LLM"] = "火星模型"
                _os.environ["PASM_MEDICAL_LLM_API_KEY"] = "sk-abcdef123456"
                st2 = svc.llm_status()
                # ★ 反例对照：拼错的 provider 必须被判「未知」而不是静默当成可用
                check(st2.get("provider_known") is False,
                      "★ 未知 provider 判为未知（不假报可用）", str(st2)[:160])
                check(st2.get("api_key") and "sk-abcdef123456" not in st2["api_key"],
                      "★ 配置查询不回显 api_key（打码）", str(st2.get("api_key"))[:40])
            finally:
                for _k, _v in _saved.items():
                    if _v is None:
                        _os.environ.pop(_k, None)
                    else:
                        _os.environ[_k] = _v
            check(svc.llm_status().get("provider_known") is True,
                  "环境变量还原后恢复默认判定")

            # ---- 反例：未知工具 → 协议错误 ----
            r = handle_message(
                {"jsonrpc": "2.0", "id": 10, "method": "tools/call",
                 "params": {"name": "nope", "arguments": {}}}, svc)
            check("error" in r and r["error"]["code"] == P.INVALID_PARAMS,
                  "未知工具返回协议错误")

            # ---- 反例：参数缺失 → isError 内容 ----
            r = handle_message(
                {"jsonrpc": "2.0", "id": 11, "method": "tools/call",
                 "params": {"name": "med_record_allergy", "arguments": {}}}, svc)
            check(r["result"]["isError"] is True, "参数缺失返回 isError 内容")

            # ---- 通知类不回复 ----
            check(handle_message(
                {"jsonrpc": "2.0", "method": "notifications/initialized"}, svc) is None,
                "通知类消息不回复")
        finally:
            try:
                svc.app.close()
            except Exception:  # noqa: BLE001
                pass

    print("-" * 64)
    print("pasm-medical-mcp selftest:", "通过" if ok else "失败")
    return 0 if ok else 1


def main(argv: Optional[list] = None) -> int:
    ap = argparse.ArgumentParser(
        prog="pasm-medical-mcp",
        description="pasm-medical 的 MCP 服务器（stdio）")
    ap.add_argument("--tenant", default="demo-hospital",
                    help="租户（机构 / 科室）标识")
    ap.add_argument("--dir", default=os.path.join(os.path.expanduser("~"),
                                                  ".pasm-medical-mcp"),
                    help="工作根目录（认知层与知识库落点）")
    ap.add_argument("--kb-dir", default=None,
                    help="知识库目录，默认 <dir>/kb")
    ap.add_argument("--selftest", action="store_true",
                    help="本地自检（不起 stdio），用于验证安装是否可用")
    ap.add_argument("--version", action="version",
                    version="%(prog)s " + P.SERVER_VERSION)
    a = ap.parse_args(argv)

    if a.selftest:
        return selftest()
    return serve(tenant=a.tenant, work_dir=a.dir, kb_dir=a.kb_dir)


if __name__ == "__main__":
    raise SystemExit(main())
