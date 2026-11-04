# -*- coding: utf-8 -*-
"""PostgreSQL 迁移冒烟 —— 真起一个 PG 容器，验证迁移脚本真的能建出可用 schema。

为什么不在 H2 上验就够了
------------------------
H2 与 PostgreSQL 的 DDL 兼容性只是"大体相似"：IDENTITY、时区类型、索引语法、
约束命名都可能出现"H2 能过、PG 报错"。而本项目生产用的是 PostgreSQL ——
迁移脚本必须**在真正的目标库上**跑一遍，否则等于没验。

本脚本做的事（每一步都是真的）：
  1. docker run 一个 postgres:16-alpine；
  2. 用**打好的 jar** 以 prod 配置启动（Flyway 迁移 + JPA ddl-auto=validate）；
  3. 断言 /actuator/health UP（validate 失败就起不来）；
  4. 直连 PG 断言：flyway_schema_history 有 V1 且 success、5 张表、关键索引、
     自增主键真的能生成 id；
  5. **重启一次**，断言迁移幂等（不会重复执行 V1，也不会因已存在而失败）。

用法::

    python tools/pg_smoke.py

前置：本机有 docker；后端已打包（``cd backend && mvn -DskipTests package``）。
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
BACKEND = REPO / "backend"

JAVA_HOME = r"C:\Program Files\Java\jdk-18.0.1.1"
JAVA_BIN = os.path.join(JAVA_HOME, "bin", "java.exe") if os.path.isdir(JAVA_HOME) else "java"

PG_IMAGE = "postgres:16-alpine"
PG_USER = "postgres"
PG_PASSWORD = "pasm-smoke"
PG_DB = "pasm_medical"

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


def wait_http(url: str, timeout: float = 180.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.7)
    return False


def sh(*args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(list(args), stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)


def psql(container: str, sql: str) -> str:
    p = sh("docker", "exec", container,
           "psql", "-U", PG_USER, "-d", PG_DB, "-tAc", sql)
    return p.stdout.decode("utf-8", "replace").strip()


def find_jar() -> Path | None:
    """取**最新**的 jar。

    ★ 不能按文件名排序：target/ 里可能同时有 0.1.0 与 0.1.1，
    字符串排序下 0.1.0 在前，会静默测到旧代码。
    """
    jars = [p for p in BACKEND.glob("target/*.jar")
            if "sources" not in p.name and "javadoc" not in p.name]
    return max(jars, key=lambda p: p.stat().st_mtime) if jars else None


def start_app(jar: Path, api_port: int, pg_port: int, log_path: Path):
    args = [
        JAVA_BIN, "-jar", str(jar),
        "--server.port=%d" % api_port,
        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:%d/%s" % (pg_port, PG_DB),
        "--spring.datasource.username=" + PG_USER,
        "--spring.datasource.password=" + PG_PASSWORD,
        # 认知服务在冒烟里不启动；健康检查不依赖它
        "--pasm.cognition.base-url=http://127.0.0.1:%d" % free_port(),
        "--pasm.cognition.token=smoke",
        "--medical.auth.demo-login-enabled=false",
    ]
    return subprocess.Popen(args, cwd=str(BACKEND),
                            stdout=open(log_path, "w", encoding="utf-8"),
                            stderr=subprocess.STDOUT)


def stop(proc) -> None:
    if proc is None:
        return
    try:
        proc.terminate()
        proc.wait(timeout=15)
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def main() -> int:
    print("PostgreSQL 迁移冒烟")
    print("=" * 70)

    jar = find_jar()
    if jar is None:
        print("[中止] 没找到后端 jar，先跑：cd backend && mvn -DskipTests package")
        return 2
    print("  使用 jar：%s" % jar.name)
    if sh("docker", "--version").returncode != 0:
        print("[中止] 本机没有可用的 docker")
        return 2

    pg_port, api1, api2 = free_port(), free_port(), free_port()
    name = "pasm-medical-pg-smoke-" + uuid.uuid4().hex[:8]
    tmp = Path(os.environ.get("TEMP", ".")) / name
    tmp.mkdir(parents=True, exist_ok=True)
    app1 = app2 = None
    try:
        # ---------- 1) 起 PG
        p = sh("docker", "run", "-d", "--rm", "--name", name,
               "-e", "POSTGRES_PASSWORD=" + PG_PASSWORD,
               "-e", "POSTGRES_DB=" + PG_DB,
               "-p", "%d:5432" % pg_port, PG_IMAGE, timeout=300)
        check("PG 容器启动成功", p.returncode == 0,
              p.stdout.decode("utf-8", "replace")[-200:])
        if p.returncode != 0:
            return 1

        ready = False
        end = time.time() + 90
        while time.time() < end:
            if sh("docker", "exec", name, "pg_isready", "-U", PG_USER,
                  timeout=15).returncode == 0:
                ready = True
                break
            time.sleep(1.0)
        check("PG 就绪（pg_isready）", ready)
        if not ready:
            return 1

        # ---------- 2) 第一次启动：迁移 + validate
        print("  启动后端（Flyway 迁移中，首次较慢）…")
        app1 = start_app(jar, api1, pg_port, tmp / "app1.log")
        up1 = wait_http("http://127.0.0.1:%d/actuator/health" % api1, timeout=180)
        check("★ 应用在 PostgreSQL 上启动成功（迁移 + JPA validate 通过）", up1,
              (tmp / "app1.log").read_text(encoding="utf-8", errors="replace")[-400:]
              if (tmp / "app1.log").exists() else "")

        # ---------- 3) schema 断言
        hist = psql(name, "SELECT version || ':' || success FROM flyway_schema_history "
                          "ORDER BY installed_rank")
        check("★ flyway_schema_history 记录 V1 且 success", hist.splitlines()[:1] == ["1:True"],
              repr(hist))

        tables = psql(name, "SELECT count(*) FROM information_schema.tables "
                            "WHERE table_schema='public' AND table_name IN "
                            "('patients','encounters','ai_audit','knowledge_docs','system_config')")
        check("★ 5 张业务表都已建出", tables == "5", "count=%s" % tables)

        idx = psql(name, "SELECT count(*) FROM pg_indexes WHERE schemaname='public' "
                         "AND indexname IN ('idx_audit_occurred_at','idx_audit_action_time',"
                         "'idx_audit_patient_time','idx_audit_action_refused',"
                         "'idx_encounters_patient_time','idx_encounters_department',"
                         "'idx_kb_department_status')")
        check("★ 7 个关键索引都存在", idx == "7", "count=%s" % idx)

        # 自增主键是真的能用（不是只建了列）
        psql(name, "INSERT INTO patients(ref, created_at) VALUES ('smoke-p1', now())")
        new_id = psql(name, "INSERT INTO encounters(patient_ref, occurred_at) "
                            "VALUES ('smoke-p1', now()) RETURNING id")
        check("★ encounters.id 自增可用（INSERT 不传 id 能返回数字）",
              bool(new_id) and new_id.isdigit(), "id=%s" % new_id)
        psql(name, "DELETE FROM encounters WHERE patient_ref='smoke-p1'")
        psql(name, "DELETE FROM patients WHERE ref='smoke-p1'")

        # ---------- 4) 第二次启动：幂等
        stop(app1)
        app1 = None
        print("  重启后端验证迁移幂等…")
        app2 = start_app(jar, api2, pg_port, tmp / "app2.log")
        up2 = wait_http("http://127.0.0.1:%d/actuator/health" % api2, timeout=180)
        check("★ 二次启动成功（迁移幂等，不会重复执行 V1）", up2,
              (tmp / "app2.log").read_text(encoding="utf-8", errors="replace")[-400:]
              if (tmp / "app2.log").exists() else "")

        hist2 = psql(name, "SELECT count(*) FROM flyway_schema_history WHERE version='1'")
        check("★ flyway_schema_history 里 V1 只有一条（没有重复安装）",
              hist2 == "1", "count=%s" % hist2)

    finally:
        stop(app1)
        stop(app2)
        sh("docker", "stop", name, timeout=120)
        sh("docker", "rm", "-f", name, timeout=60)

    print("-" * 70)
    print("PG 迁移冒烟：%d 项通过，%d 项失败" % (OK, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
