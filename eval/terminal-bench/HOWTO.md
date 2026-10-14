# openagent × Terminal-Bench 评测使用说明

> 面向场景:我修改了 openagent 的代码(或配置),想重新跑一遍 Terminal-Bench 评测,看效果/token 变化。

## 整体链路一览

```
改 Java 代码 → mvn package 出 uber-jar → 拷进 staging/ → harbor run(适配器把 jar+JRE 塞进任务容器)
→ 容器内跑 openagent --prompt → 任务自带 tests/ 判分 → jobs/<时间戳>/result.json 出 reward + token
```

关键文件:

| 文件 | 作用 |
|---|---|
| `eval/terminal-bench/staging/openagent.jar` | 评测用的 agent 产物(每次改代码后要更新) |
| `eval/terminal-bench/staging/OpenJDK21U-jre_x64_linux_hotspot.tar.gz` | 容器内便携 JRE(**只在升级 JDK 时才需要重下**) |
| `eval/terminal-bench/openagent_adapter/openagent.py` | Harbor 适配器(headless 开关、token 解析都在这) |
| `eval/terminal-bench/run_openagent.sh` | 一键运行脚本 |
| `eval/terminal-bench/terminal-bench-2/<任务目录>/` | 本地 clone 的 89 道题 |
| `eval/terminal-bench/jobs/<时间戳>/` | 每次评测的输出目录(结果全在这) |

## 第 1 步:重新构建 jar(改完 Java 代码后必做)

本机默认 `JAVA_HOME` 可能不是 JDK 21,**必须先切**,否则会用错 JDK 打包:

```bash
cd /Users/demus/IdeaProjects/openagent-harness
export JAVA_HOME=/Users/demus/Library/Java/JavaVirtualMachines/ms-21.0.12.1-1/Contents/Home
mvn -q -DskipTests package
```

产物在 `target/openagent.jar`(约 30M,Main-Class=com.szh.cli.Repl)。

## 第 2 步:把新 jar 拷进 staging(容易忘的一步!)

harbor 上传的是 `staging/` 里的 jar,**不是** `target/` 里的。只打包不拷贝 = 跑的还是旧代码:

```bash
cp target/openagent.jar eval/terminal-bench/staging/openagent.jar
```

> 忘了拷的后果:改了代码但评测行为一模一样,很难排查。

## 第 3 步(可选):本地秒级冒烟,确认 jar 没坏

不用等 13 分钟的容器评测,先花 1 分钟验证 jar 能跑、metrics 行能打出来:

```bash
"$JAVA_HOME/bin/java" \
  -Dshell.security.enabled=false -Dshell.permission.interactive=false \
  -Dshell.permission.autoApprove=true -Dagent.verify.enabled=false \
  -Dmemory.enabled=false -Dmodel.stream.enabled=false \
  -Dproject.workspace=/tmp/oa-smoke -Dopenagent.metrics.summary=true \
  -jar target/openagent.jar --prompt "只回复两个字：好的" 2>/dev/null \
  | grep OPENAGENT_METRICS
# 期望看到类似:OPENAGENT_METRICS input=4166 output=2 total=4168 cached=3968 rounds=1 elapsed_ms=852
```

## 第 4 步:跑评测

前置:Docker Desktop 在运行(代理配置保持 `http://127.0.0.1:7897`,两栏都是 http:// 不是 https://),
且 `DEEPSEEK_API_KEY` 已在当前 shell 环境里。

```bash
cd /Users/demus/IdeaProjects/openagent-harness/eval/terminal-bench
export PATH="$HOME/.local/bin:$PATH"     # harbor 装在这里但没进默认 PATH

./run_openagent.sh largest-eigenval      # 单题
./run_openagent.sh <其它任务目录名>       # 换题:任务名 = terminal-bench-2/ 下的目录名
./run_openagent.sh --full                # 全量 89 题(4 并发,慎跑,烧 token + 数小时)
```

**注意事项**:

- **别用 `... &` 丢后台再关终端**——曾踩过坑:harbor 进程被挂断,install 上传到一半死掉,留下孤儿容器空转。
  想挂后台请用 `nohup ./run_openagent.sh xxx > run.log 2>&1 &`,或者干脆开一个专门终端窗口前台跑。
- 单题典型耗时 **5~15 分钟**(含镜像内 java 冷启动 + 模型多轮推理),不要看到没输出就急着杀进程。
- 中途要看进度:容器还在跑时执行
  `docker exec $(docker ps | grep largest-eigenval | awk '{print $1}' | head -1) tail -20 /logs/agent/openagent.txt`
- 异常退出留下孤儿容器时清理:`docker rm -f <容器名>`。

## 第 5 步:看结果

每次运行在 `eval/terminal-bench/jobs/<日期__时间>/` 下生成一个目录:

```bash
JD=$(ls -dt jobs/*/ | head -1)          # 最新一次 job
cat "$JD/result.json"                    # 总分:reward + token 统计
cat "$JD"*/verifier/reward.txt           # 单题判分:1=通过,0=失败
```

`result.json` 里关心的字段:

```jsonc
"stats": {
  "n_completed_trials": 1,               // 完成的 trial 数
  "n_errored_trials": 0,                 // 出错数(非 0 先查 trial.log)
  "evals": { "openagent__deepseek-chat__...": {
      "metrics": [{ "mean": 1.0 }] }},   // ← accuracy 分数在这
  "n_input_tokens": 908263,              // 含缓存的输入 token
  "n_cache_tokens": 829952,              // 其中命中缓存的
  "n_output_tokens": 43506
}
```

单题更细的数据在 `jobs/<...>/<任务>__<随机id>/result.json` 的 `metadata` 里:
`openagent_rounds`(模型调用轮数)、`openagent_elapsed_ms`、`openagent_total_tokens`。

完整 agent 运行日志(每一轮工具调用)在 `<任务>__<id>/agent/openagent.txt`。

## 改其它东西怎么改(对号入座)

| 我想改… | 动哪里 |
|---|---|
| agent 行为/工具/运行时逻辑 | Java 代码 → 回到第 1~2 步重新打包拷贝 |
| 容器内的 headless 开关(权限/记忆/流式等) | `openagent_adapter/openagent.py` 的 `_HEADLESS_OVERRIDES` 列表(改完直接重跑,不用打包) |
| 底层模型 | `OPENAGENT_MODEL=deepseek/deepseek-reasoner ./run_openagent.sh xxx`;若换 provider 需同步改 jar 内配置或再加 `-D` 覆盖 |
| 上报哪些指标 | Java 侧 `Repl.emitMetricsSummary()` 加字段 **且** 适配器 `_METRICS_RE` 正则要同步改,两边格式必须一致 |
| 评测哪道题 | `run_openagent.sh <terminal-bench-2 下的目录名>` |

## 常见问题速查

| 症状 | 原因与解法 |
|---|---|
| 改了代码但评测结果没变化 | 忘了第 2 步拷 jar 到 staging |
| `harbor: command not found` | `export PATH="$HOME/.local/bin:$PATH"` |
| clone/pull 报网络错 | GitHub 与 Docker Hub 需代理;Docker 代理栏必须填 `http://` 前缀 |
| `n_errored_trials > 0` | 看 `jobs/<...>/trial.log` 与容器 `/logs/agent/openagent.txt` 定位 |
| 容器里有孤儿 `largest-eigenval__*` 在跑 | harbor 主进程已死,`docker rm -f` 清掉后重跑 |
| token 数字感觉偏大 | 正常,`n_input_tokens` 是**每轮上下文累计**(含重复发送的历史),91% 靠缓存抵掉了 |
