# -*- coding: utf-8 -*-
"""pasm-medical 的 MCP stdio sidecar。

复用 :mod:`pasm_medical.service` 的医疗业务逻辑，通过 stdio 上的 JSON-RPC 2.0
把患者级隔离的诊疗记忆、受控自学习与中药处方核对暴露给任意 MCP 客户端，
**不占用网络端口**（与 HTTP 网关同源一份代码）。
"""
from __future__ import annotations

from . import protocol
from . import tools
from .server import main, serve, selftest, handle_message, build_medical_service

__all__ = [
    "main", "serve", "selftest", "handle_message", "build_medical_service",
    "protocol", "tools",
]
