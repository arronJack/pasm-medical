# -*- coding: utf-8 -*-
"""pasm-medical MCP 协议常量与版本协商（零依赖）。

stdio 上的 MCP 就是「换行分隔的 JSON-RPC 2.0」。本包刻意不依赖官方 mcp SDK，
以保持零第三方依赖、冷启动 <100ms，便于任意语言的客户端（Node / Electron / Java）
拉起。版本协商策略与 pasm-mcp-server 一致（客户端认得的版本原样回显）。
"""
from __future__ import annotations

#: 本服务端支持的协议版本（按时间升序）。
SUPPORTED_PROTOCOL_VERSIONS = (
    "2024-11-05",
    "2025-03-26",
    "2025-06-18",
    "2026-07-28",
)

#: 客户端没带版本 / 带了不认识的版本时，回这个。
DEFAULT_PROTOCOL_VERSION = "2025-03-26"

SERVER_NAME = "pasm-medical-mcp"
SERVER_VERSION = "0.1.0"

#: 服务端能力声明。只声明 tools —— 不声明就是不支持，别虚报。
CAPABILITIES = {"tools": {}}

# JSON-RPC 2.0 标准错误码
PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603


def negotiate(client_version: str | None) -> str:
    """版本协商：客户端认得的就回显，否则回默认版本。"""
    if client_version and client_version in SUPPORTED_PROTOCOL_VERSIONS:
        return client_version
    return DEFAULT_PROTOCOL_VERSION


def initialize_result(client_version: str | None) -> dict:
    """构造 `initialize` 的响应 result。"""
    return {
        "protocolVersion": negotiate(client_version),
        "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
        "capabilities": CAPABILITIES,
        "instructions": (
            "pasm-medical 医疗认知服务：在 PASM 认知内核之上提供患者级隔离的"
            "诊疗记忆、受控自学习与中药处方核对。典型用法：先用 med_ask 带护栏问答，"
            "有依据时才返回来源并强制 requires_physician_confirmation=true；"
            "无依据时如实拒答，绝不编造。所有临床结果都必须由医师确认。"
        ),
    }


def error_response(req_id, code: int, message: str, data=None) -> dict:
    err: dict = {"code": code, "message": message}
    if data is not None:
        err["data"] = data
    return {"jsonrpc": "2.0", "id": req_id, "error": err}


def result_response(req_id, result: dict) -> dict:
    return {"jsonrpc": "2.0", "id": req_id, "result": result}
