"""
openagent 的 Harbor（Terminal-Bench 2.x 官方 harness）自定义 Agent 适配器。

设计要点（对齐本项目 headless 评测约束）：
- 采用 Harbor 的 Installed Agent 模式：openagent 作为独立进程跑在**任务容器内部**，
  它的 ShellCommandTool / 文件工具天然作用于容器工作目录，跑完由任务自带 tests/ 判分，
  零架构改动（相比 External 模式无需把 shell 执行改造成远程后端）。
- 容器预置：install() 阶段把宿主机预下载的 uber-jar + linux(x64) JRE 21 拷进 /opt，
  全程离线、不依赖 apt/容器网络（镜像多为 amd64，Apple 芯片走 x86 模拟，故用 x64 JRE）。
- headless 覆盖（关键）：容器内无人读 stdin，必须用 -D 关掉交互式权限确认与本项目自带
  的 VerifyGate，否则 run 会卡死或和 Terminal-Bench 判分器打架。-D 能覆盖是因为
  ConfigUtil.getFromFile 优先读 System.getProperty（见该项目源码）。
- 模型：openagent 自带配置走 DeepSeek（model.provider=deepseek），适配器只负责把
  DEEPSEEK_API_KEY 注入容器环境变量；底层 LLM 端点 api.deepseek.com 在容器内可直接达。

注册方式：--agent openagent_adapter.openagent:OpenAgent（需 PYTHONPATH 指向本文件上级目录）。
"""

import os
import re
import shlex
from pathlib import Path

from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

# 宿主机预置产物路径（可被同名环境变量覆盖，便于换机/换构建）
_STAGING = Path("/Users/demus/IdeaProjects/openagent-harness/eval/terminal-bench/staging")
JAR_PATH = Path(os.environ.get("OPENAGENT_JAR", _STAGING / "openagent.jar"))
JRE_TARBALL_PATH = Path(
    os.environ.get(
        "OPENAGENT_JRE_TARBALL",
        _STAGING / "OpenJDK21U-jre_x64_linux_hotspot.tar.gz",
    )
)

# 容器内固定落位
_REMOTE_JAR = "/opt/openagent/openagent.jar"
_REMOTE_JRE = "/opt/jre"
_LOGS_FILE = "/logs/agent/openagent.txt"

# headless 场景必须覆盖的配置项（详见文件头说明）。这些经 -D 传入，ConfigUtil 优先于
# application.properties 读取。关闭交互确认+沙箱+项目 VerifyGate，并停掉长期记忆以免
# 反思额外烧 token、污染单题评测。openagent.metrics.summary 让 --prompt 结束时往 stdout
# 打一行机器可读的 token/轮次/耗时摘要，供本适配器解析回填 AgentContext。
_HEADLESS_OVERRIDES = [
    "-Dshell.security.enabled=false",
    "-Dshell.permission.interactive=false",
    "-Dshell.permission.autoApprove=true",
    "-Dfile.delete.confirm.autoApprove=true",
    "-Dagent.verify.enabled=false",
    "-Dmemory.enabled=false",
    "-Dmemory.instruction.enabled=false",
    "-Dmodel.stream.enabled=false",
    "-Dsubagent.enabled=false",
    # 容器一次性且 shell 已全开放，文件工具边界对齐同一水位：allowOutside 只放开到
    # 策略允许根（PathGuard.isWithinAllowed 查 writableRoots），故需将 extra 指到 /，
    # 否则读 site-packages 等第三方库源码会被 read_file 拒绝、攒够连续故障诱发误杀。
    "-Dfile.tools.allowOutside=true",
    "-Dshell.sandbox.writable.extra=/",
    # 容器 locale 非 UTF-8 时 System.out 中文会变 ?（log4j2 自带编码不受影响）。
    "-Dsun.stdout.encoding=UTF-8",
    "-Dsun.stderr.encoding=UTF-8",
    "-Dopenagent.metrics.summary=true",
]

# openagent --prompt 结束时打印的用量摘要行（见 com.szh.cli.Repl.emitMetricsSummary）。
_METRICS_RE = re.compile(
    r"OPENAGENT_METRICS\s+input=(?P<input>\d+)\s+output=(?P<output>\d+)\s+"
    r"total=(?P<total>\d+)\s+cached=(?P<cached>\d+)\s+rounds=(?P<rounds>\d+)\s+"
    r"elapsed_ms=(?P<elapsed_ms>\d+)"
)


def _parse_metrics(text: str) -> dict[str, int] | None:
    """从 stdout 抽取最后一条 OPENAGENT_METRICS 行并转为 int 字典，缺失返回 None。"""
    if not text:
        return None
    last = None
    for m in _METRICS_RE.finditer(text):
        last = m
    return {k: int(v) for k, v in last.groupdict().items()} if last else None


class OpenAgent(BaseInstalledAgent):
    @staticmethod
    def name() -> str:
        return "openagent"

    def version(self) -> str | None:
        return "1.0.0"

    async def install(self, environment: BaseEnvironment) -> None:
        """把 uber-jar + 便携 JRE 预置进容器 /opt，全程离线。"""
        if not JAR_PATH.exists():
            raise FileNotFoundError(f"openagent jar 不存在: {JAR_PATH}")
        if not JRE_TARBALL_PATH.exists():
            raise FileNotFoundError(f"JRE tarball 不存在: {JRE_TARBALL_PATH}")

        # 1) 目录（root）
        await self.exec_as_root(environment, command="mkdir -p /opt/openagent /opt/jre")

        # 2) 上传产物（upload_file 以 root 落位）
        await environment.upload_file(JAR_PATH, _REMOTE_JAR)
        await environment.upload_file(JRE_TARBALL_PATH, "/opt/openagent/jre.tar.gz")

        # 3) 解压 JRE、放开只读/可执行权限给 agent 用户、自检 java 版本
        #    --strip-components=1 去掉顶层 jdk-21.0.12.1+1-jre/ 目录名（含 + 号不便处理）
        await self.exec_as_root(
            environment,
            command=(
                "tar -xzf /opt/openagent/jre.tar.gz -C /opt/jre --strip-components=1 && "
                "rm -f /opt/openagent/jre.tar.gz && "
                "chmod -R a+rX /opt/jre && "
                "chmod a+r /opt/openagent/openagent.jar && "
                f"{_REMOTE_JRE}/bin/java -version"
            ),
        )

    @with_prompt_template
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        """以 agent 用户在工作目录跑一次 openagent --prompt，输出 tee 到日志目录。

        不设 cwd：继承镜像 WORKDIR（如 /app），使 openagent 的 user.dir=工作目录，
        文件/shell 工具改动落在任务要求的位置，供任务 tests/ 判分。
        """
        escaped_instruction = shlex.quote(instruction)
        overrides = " ".join(_HEADLESS_OVERRIDES)

        # openagent 通过环境变量兜底读取 API Key（ConfigUtil 之外的 DeepSeek 装配）
        env: dict[str, str] = {}
        api_key = os.environ.get("DEEPSEEK_API_KEY")
        if api_key:
            env["DEEPSEEK_API_KEY"] = api_key

        command = (
            f"{_REMOTE_JRE}/bin/java {overrides} "
            f"-jar {_REMOTE_JAR} --prompt {escaped_instruction} "
            f"2>&1 | tee {_LOGS_FILE}"
        )

        result = await self.exec_as_agent(
            environment,
            command=command,
            env=env,
            timeout_sec=3600,
        )

        # 解析 --prompt 结束时打印的 token/轮次/耗时摘要，回填 AgentContext 供 result.json 呈现。
        metrics = _parse_metrics(getattr(result, "stdout", "") or "")
        if metrics is None:
            # 兜底：harness 可能截断 stdout，再从容器落盘的完整日志里捞一次
            try:
                probe = await self.exec_as_agent(
                    environment,
                    command=f"grep -a OPENAGENT_METRICS {_LOGS_FILE} | tail -n 1",
                )
                metrics = _parse_metrics(getattr(probe, "stdout", "") or "")
            except Exception:
                metrics = None

        if metrics:
            # AgentContext.n_input_tokens 语义为「含缓存的输入」，与 DeepSeek prompt_tokens 一致
            context.n_input_tokens = metrics["input"]
            context.n_output_tokens = metrics["output"]
            context.n_cache_tokens = metrics["cached"]
            context.metadata = {
                "openagent_total_tokens": metrics["total"],
                "openagent_rounds": metrics["rounds"],
                "openagent_elapsed_ms": metrics["elapsed_ms"],
            }
