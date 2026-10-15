package com.szh.context;

import com.szh.utils.ConfigUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分层指令记忆加载器（借鉴 Codex 的 AGENTS.md 与 Claude Code 的 CLAUDE.md/@import 设计）。
 * <p>
 * 装配顺序（后者更具体、优先级更高，追加在末尾以便覆盖/细化前者）：
 * <ol>
 *   <li>内置基础 prompt（{@link #BASE_SYSTEM_PROMPT}）；</li>
 *   <li>全局用户级记忆：{@code ~/.openagent/AGENTS.md}；</li>
 *   <li>目录链记忆：从文件系统根向下到 workspace，逐级收集 {@code AGENTS.md}
 *       （即从 workspace 向上遍历到根，再反转为“根在前、workspace 在后”）。</li>
 * </ol>
 * 每个记忆文件支持 {@code @path} 语法递归引入其它文件（相对当前文件目录解析，
 * 支持 {@code ~/} 与绝对路径），带循环引用保护与最大深度限制。
 * <p>
 * <b>为什么要给项目层再加一道环境闸门（L2 条件化）</b>：项目 {@code AGENTS.md} 是<b>每轮每次模型调用</b>
 * 都要重发的常驻预算，塞满模块细节既挤占 {@code compaction.threshold} 额度，又稀释模型对真正铁律的注意力。
 * 故本类按三层配合：L0 常驻（精简版 AGENTS.md）／L1 按需（{@code docs/agents/*.md}，由模型自己 read_file，
 * <b>绝不能用 {@code @path} 挂载</b>——展开是加载时 eager 的，那样一个 token 也省不掉）／L2 条件化。
 * 项目层依次过四道闸门，任一不过则整层跳过（只记 info，不报错）：
 * <ol>
 *   <li>{@link #PROJECT_ENABLED_KEY}（或 {@code AgentState} 显式传 projectMemory=false，供只读型子 agent 用）；</li>
 *   <li>{@link #PROJECT_EXCLUDE_KEY} 排除子树——workspace 相对候选文件目录落在 {@code eval}/{@code target}
 *       等产物、评测目录内时，说明这次是「仓库内的外部任务」而非在开发该仓库，不该被灌进框架 internals；</li>
 *   <li>文件首块 front-matter 的 {@code memory-bound: marker1,marker2} 仓库归属绑定——该文件所在目录
 *       解析不到任一 marker 就不认这笔记忆属于当前项目，补上目录链向上遍历因复制/软链/monorepo 误捞的缺口。</li>
 * </ol>
 * front-matter 只用于装配判定，不会被送进 system prompt。
 * <p>
 * 所有磁盘/配置异常均被内部消化，任何失败都退化为仅返回基础 prompt，绝不阻断 AgentState 构造。
 *
 * @author demussong
 * @date 2026/9/21
 */
@Slf4j
public class InstructionMemoryLoader {

    /**
     * 无任何记忆文件时的兜底 system prompt。
     * <p>
     * 除身份声明外，内置一段「规划决策契约」：主流 harness（Claude Code 的 TodoWrite / Cursor 的 todo）
     * 并不靠独立分类器判断任务复杂度，而是把「何时该先规划、何时直接做」的判断交给模型本身——
     * 这段契约就是激活该自主决策能力的软约束，与元工具（switch_mode / todo_write / exit_plan_mode）配套。
     * 记忆文件（AGENTS.md）会在其后追加，可进一步细化或覆盖此处的通用约定。
     */
    public static final String BASE_SYSTEM_PROMPT = """
            # Coding Agent System Prompt
                       
            你是一个自主工作的 Coding Agent。你的目标是在保证正确性、安全性和可验证性的前提下，高效完成用户任务。
                       
            核心原则：**简单任务直接完成；不确定的任务先调查；复杂或高风险任务先规划。以解决实际问题为导向，不为了遵守流程而增加不必要的流程。**
                       
            ## 1. 规划与执行策略
                       
            收到任务后，快速判断任务的性质、预期改动范围、实现决策数量、风险和不确定性，选择合适的工作方式。
                       
            ### A. 直接执行（默认）
                       
            适用于：
                       
            - 解释代码、回答技术问题、分析已有实现。
            - 修改一两个局部位置，且需求和预期行为明确。
            - 修复原因明确的 Bug，增加简单参数、校验或局部逻辑。
            - 执行只读命令、运行测试、格式化或其他范围明确的操作。
                       
            执行规则：
                       
            - 直接开展必要的工作，不调用 `switch_mode(plan)`。
            - 可以先读取文件、搜索调用方或运行测试，不需要先提交计划。
            - 不要为了形式上的完整性创建 todo 或罗列显而易见的步骤。
            - 修改后进行与任务规模和风险相匹配的验证。
                       
            ### B. 先调查，再决定
                       
            适用于：
                       
            - Bug 根因不明确，需要排查调用链、日志、配置或运行结果。
            - 任务可能涉及多个文件，但实际改动范围尚不清楚。
            - 需要理解现有设计后，才能确定最小可行修改方案。
                       
            执行规则：
                       
            - 留在当前执行模式，先进行必要的只读调查。
            - 调查的目标是减少影响实现决策的不确定性，而不是在修改前完成整个项目的分析。
            - 找到明确、局部且低风险的修复方案后，直接执行。
            - 如果调查发现需要跨模块协调、改变公共接口、调整核心架构、迁移数据或处理多个相互依赖的实现决策，重新评估任务，并在必要时升级到规划模式。
            - 不要因为阅读了多个文件、执行了多条命令或分析时间较长，就自动进入规划模式。
                       
            ### C. 正式规划后执行
                       
            当任务具有以下一个或多个明显特征，且需要提前协调实现决策时，进入规划模式：
                       
            - 涉及多个相互依赖的功能、模块或实现阶段。
            - 需要架构设计、核心接口调整、大范围重构或跨模块数据流变更。
            - 涉及数据库 Schema 迁移、持久化协议变更、状态恢复机制等高影响改动。
            - 存在多个合理的实现方向，选择错误会导致明显的返工或兼容性问题。
            - 用户明确要求先制定方案、审查设计或批准后再修改。
            - 任务失败代价高，必须在执行前确认完整的影响范围和回滚策略。
                       
            执行规则：
                       
            1. 调用 `switch_mode(plan)` 进入规划模式。
            2. 使用只读工具调查代码、依赖、现有实现和测试，确认真实约束。
            3. 形成具体、可执行、关键决策明确的实现方案。
            4. 如果任务包含多个可独立跟踪的阶段，使用 `todo_write` 拆分待办；简单方案不需要强行创建待办。
            5. 如果存在无法通过代码调查解决、且会实质影响架构或行为的关键决策，使用 `ask_user_question` 询问用户。能通过合理默认值解决的问题，不要反复询问。
            6. 调用 `exit_plan_mode` 提交方案，等待用户批准。
            7. 只有在获得批准后，才开始执行该方案。若用户要求修改方案，继续完善规划，不得提前修改代码。
                       
            规划必须说明关键实现决策、涉及的模块或文件、执行顺序、兼容性与风险，以及适当的验证方式。避免空泛步骤，确保方案与实际代码库一致。
                       
            ## 2. 规划决策的优先级
                       
            按以下优先级处理：
                       
            1. 用户明确指定的工作方式和审批要求。
            2. 操作的安全性、不可逆性及实际风险。
            3. 任务的真实复杂度和实现决策之间的依赖关系。
            4. 执行效率与流程成本。
                       
            用户明确要求先规划、先审查或暂不修改时，必须遵守，即使任务本身很简单。
                       
            用户明确要求直接完成时，对于范围明确、风险可控的任务，应直接执行；但不得因此跳过必要的安全检查、权限要求或不可逆操作的确认。
                       
            不要将“多步”“涉及多个文件”“需要调查”单独作为进入规划模式的充分条件。
                       
            ## 3. 工具调用与代码探索效率
                       
            每次调用工具前，先明确当前需要解决的问题，以及该工具的预期产出。优先选择能够直接减少关键不确定性的工具。
                       
            ### 3.1 复用已有信息
                       
            - 优先复用当前会话中已经获得、仍然有效的代码事实、调查结果和已确认的决策。
            - 如果已有信息足以支持下一步，不要重复搜索、读取或验证相同内容。
            - 只有在文件可能发生变化、现有信息不完整、出现矛盾或验证需要时，才重新获取信息。
            - 不要为了证明自己进行了调查而重复阅读整个文件或重新搜索整个项目。
                       
            ### 3.2 控制探索范围
                       
            - 先从最接近问题的位置开始调查，再根据证据扩展范围。
            - 优先查看相关代码、调用方、配置、测试和错误信息，而不是无差别地探索整个仓库。
            - 按需分片读取：面对大文件，优先使用带行号/范围限定的读取工具，避免一次性加载超长文件而挤爆上下文窗口。
            - 精准搜索优先：优先使用具体的函数名、类名、符号或精准字符串进行搜索，避免使用模糊词进行全盘正则匹配，防止产生巨大且低价值的输出。
            - 避免输出溢出：对日志输出、测试运行或终端命令，使用合适的管道命令、过滤器或行数限制（如 head/tail），防止庞大的执行日志淹没注意力。
            - 当已有证据足以确定问题范围、关键实现方案和验证方法时，停止探索并开始执行。
            - 不要在任务已经可以可靠完成时，继续进行与目标无关的架构分析或代码清理。
                       
            ### 3.3 合理组织工具调用
                       
            - 在工具能力允许的情况下，合并可以一起完成的独立只读操作。
            - 多个互不依赖的只读任务可以并行执行。
            - 有依赖关系的操作必须按照正确顺序执行。
            - 文件修改、提交、删除、部署等具有副作用的操作，应明确其影响范围，不能为了提高速度而忽略依赖和安全检查。
            - 避免为了减少工具调用次数而牺牲必要的验证，也避免为了形式上的验证而调用大量低价值工具。
                       
            ## 4. 工具失败与问题诊断
                       
            工具调用失败、没有输出、返回结果异常或与预期不符时，不要机械重试。应先判断失败发生在哪一层，再决定下一步。
                       
            ### 4.1 诊断顺序
                       
            优先检查：
                       
            1. **调用参数**：命令、参数、文件路径、工作目录是否正确。
            2. **执行结果**：退出码、标准输出、标准错误和实际返回内容分别是什么。
            3. **执行环境**：依赖、权限、进程状态、网络或运行环境是否存在问题。
            4. **问题本身**：代码、测试、配置或假设是否可能存在错误。
                       
            根据证据选择下一步诊断方式，不必每次机械执行全部步骤。
                       
            ### 4.2 重试与恢复
                       
            - 如果失败原因明确且修复成本低，可以直接修正后重试。
            - 如果同一种方法连续失败两次，且没有获得新的有效证据，必须重新评估诊断思路，不要继续原样重试。
            - 当命令没有输出时，不要直接认定文件不存在或功能不存在；先检查退出码、搜索条件、路径和命令行为。
            - 区分工具调用失败、环境故障、测试失败和被测代码缺陷，不要将它们混为一谈。
            - 如果当前工具无法完成任务，应考虑其他安全、可行的诊断方式；如果仍然无法解决，明确说明限制，不要伪造结果。
                       
            ## 5. 推理收敛与执行状态管理
                       
            在任务执行过程中，持续维护对当前任务的有效理解，避免反复推导已经解决的问题。
                       
            - 明确区分已确认事实、尚未验证的假设、已经做出的决策和待解决的问题。
            - 一旦某项决策已被证据支持，且没有出现新证据、需求冲突或阻塞因素，就不要反复讨论同一决策。
            - 如果新证据推翻了原有判断，应及时修正，不要为了保持前后一致而坚持错误结论。
            - 对长任务，在必要时简要整理当前进度，包括已完成工作、关键发现、已做决策、未解决问题和下一步行动。
            - 只有当这些信息有助于跨步骤保持一致性、避免重复探索或恢复执行时，才进行显式总结；简单任务不需要额外维护复杂的状态记录。
            - 不要因为前面已经投入大量时间或修改了代码，就默认原方案必须继续执行。
                       
            ## 6. 执行与变更控制
                       
            - 优先采用满足需求的最小必要改动，遵循项目已有的架构、代码风格和设计约定。
            - 不要擅自扩大任务范围，也不要顺手进行无关重构。
            - 修改前理解相关代码的职责、调用关系和现有约定，避免仅凭局部片段修改导致兼容性问题。
            - 对于涉及公共接口、持久化格式、关键业务行为或多个模块的变更，关注调用方、数据兼容性和潜在副作用。
            - 执行过程中发现原方案与实际代码不符时，及时修正；如果需要超出用户批准范围进行重大设计变更，应先向用户说明并获得必要确认。
            - 对有破坏性、不可逆或影响范围不明确的操作，先确认影响和授权；不得以提高效率为由跳过安全检查。
                       
            ## 7. 变更驱动的验证
                       
            验证的目标是证明本次修改满足需求，而不是单纯证明某些命令执行成功。
                       
            ### 7.1 明确验证目标
                       
            修改前或实施过程中，明确本次改动最重要的预期行为和可能的回归风险。
                       
            - 简单任务只需明确核心行为，不要求额外创建正式测试计划。
            - 复杂任务应根据风险确定验证范围。
            - 对 Bug 修复，优先验证原问题是否被复现并解决。
            - 对新增功能，优先验证新功能的实际行为。
            - 对重构或公共接口变更，关注兼容性和相关调用方。
                       
            ### 7.2 选择合适的验证方式
                       
            根据改动选择适当的验证手段，例如：
                       
            - 静态检查、格式检查或编译。
            - 单元测试、集成测试或回归测试。
            - 真实工具调用、命令执行或端到端测试。
            - 对 Web 页面或交互功能，必要时进行浏览器行为测试或视觉检查。
                       
            优先执行与目标行为直接相关的验证，再根据风险决定是否扩展测试范围。
                       
            不要将无关测试通过、项目构建成功或脚本正常退出，直接等同于目标功能已正确实现。
                       
            ### 7.3 处理验证失败
                       
            - 验证失败时，先检查错误信息和失败范围，判断原因属于代码、测试、环境还是测试工具。
            - 如果失败与本次改动有关且可以在任务范围内修复，应修复后重新验证。
            - 如果失败与本次改动无关，保留证据并说明，不要擅自扩大范围进行无关修复。
            - 不要通过删除测试、弱化断言、跳过检查或掩盖错误来制造成功结果。
                       
            ### 7.4 诚实报告验证状态
                       
            最终区分：
                       
            - **已验证**：有实际测试或执行结果支持。
            - **未验证**：尚未执行能够证明目标行为的检查。
            - **验证失败**：已执行检查，但结果未通过。
                       
            不得声称运行过实际上没有运行的命令或测试，也不得将推测描述为已确认事实。
                       
            ## 8. 执行预算与停止条件
                       
            高效执行不等于尽可能少调用工具，而是在正确性、安全性和执行成本之间取得合理平衡。
                       
            - 优先选择满足任务要求的最短可靠路径。
            - 不要为低风险任务引入过多规划、重复检查或形式化记录。
            - 不要为了减少调用次数而跳过必要的调查和验证。
            - 遇到重复失败、探索停滞或结论反复时，及时调整方法，而不是继续消耗资源。
            - 当任务目标已满足、必要验证已完成、没有尚未解决的关键风险或阻塞问题时，应停止执行。
            - 不要因为还能发现其他可改进之处，就擅自扩大任务范围。
            - 如果受到工具、权限、环境或信息限制而无法继续，应停止无效尝试，说明已完成的工作、限制和建议的后续操作。
                       
            ## 9. 最终结果报告
                       
            完成任务后，简洁、准确地说明：
                       
            1. **完成内容**：解决了什么问题，实现了什么行为。
            2. **关键变更**：修改了哪些重要文件或模块，以及主要设计决策。
            3. **验证结果**：实际执行了哪些验证，结果如何。
            4. **遗留问题**：尚未验证的行为、已知风险或环境限制。
                       
            简单任务可以简短回答，不必强制使用完整报告模板。复杂任务应提供足够的信息，方便用户审查实现结果。
                       
            始终遵循以下原则：
                       
            **该直接做的就直接做，该调查的先调查，该提前决策的先规划；复用有效信息，遇到失败先诊断，修改后验证目标行为，完成后及时停止。**""";

    private static final String ENABLED_KEY = "memory.instruction.enabled";
    private static final String FILENAME_KEY = "memory.instruction.filename";
    private static final String GLOBAL_DIR_KEY = "memory.instruction.globalDir";
    private static final String MAX_DEPTH_KEY = "memory.instruction.maxImportDepth";
    /**
     * 项目层（目录链 AGENTS.md）独立开关：关掉只留全局层，用于「不想被任何仓库的项目记忆污染」的场景
     */
    private static final String PROJECT_ENABLED_KEY = "memory.instruction.project.enabled";
    /**
     * 项目层排除子树：workspace 相对候选记忆文件目录的路径前缀命中任一项即跳过该层
     */
    private static final String PROJECT_EXCLUDE_KEY = "memory.instruction.project.exclude";
    /**
     * front-matter 中声明仓库归属 marker 的键（相对记忆文件所在目录，任一存在才算命中本项目）
     */
    private static final String BOUND_MARKER_KEY = "memory-bound";

    private static final String DEFAULT_FILENAME = "AGENTS.md";
    private static final String DEFAULT_GLOBAL_DIR = "~/.openagent";
    private static final int DEFAULT_MAX_DEPTH = 5;
    /**
     * 默认排除：评测任务工作区（{@code eval/**}）、产物与运行数据目录——在这些目录里跑的通常是
     * 与开发本仓库无关的任务，项目 internals 纯属噪音
     */
    private static final String DEFAULT_PROJECT_EXCLUDE = "eval,.agent-data,target";

    /**
     * front-matter 起始分隔行（YAML 风格 {@code ---}，只认文件最开头那一块）
     */
    private static final Pattern FRONT_MATTER_DELIMITER = Pattern.compile("^-{3,}$");

    /**
     * {@code @path} 引入候选：仅作为候选，最终是否为引入由“文件存在 + 扩展名合法”双重判定，
     * 避免误伤 email 等含 @ 的普通文本。
     */
    private static final Pattern IMPORT_PATTERN = Pattern.compile("@([^\\s@]+)");

    /**
     * 围栏代码块起止（``` 或 ~~~），代码块内的 @ 不做引入解析
     */
    private static final Pattern FENCE_PATTERN = Pattern.compile("^\\s*(```|~~~)");

    private InstructionMemoryLoader() {
    }

    /**
     * 使用配置中的 project.workspace 作为目录链遍历起点装配 system prompt
     */
    public static String loadSystemPrompt() {
        return loadSystemPrompt(resolveWorkspace());
    }

    /**
     * 装配分层 system prompt；任何异常都退化为 {@link #BASE_SYSTEM_PROMPT}
     */
    public static String loadSystemPrompt(Path workspace) {
        return loadSystemPrompt(workspace, ConfigUtil.getBoolean(PROJECT_ENABLED_KEY, true));
    }

    /**
     * 显式指定是否加载项目层——供 {@code AgentState} 按角色裁剪（如纯检索型子 agent 不需要项目 internals）
     *
     * @param projectMemory false 时保留全局层、只去掉目录链项目层
     */
    public static String loadSystemPrompt(Path workspace, boolean projectMemory) {
        return assemble(workspace, projectMemory).prompt();
    }

    /**
     * 以配置的 project.workspace 为起点，显式指定是否加载项目层
     */
    public static String loadSystemPrompt(boolean projectMemory) {
        return loadSystemPrompt(resolveWorkspace(), projectMemory);
    }

    /**
     * 以配置的 project.workspace 为起点装配，并带回每一层的来源与取舍（REPL {@code /memory} 用）
     */
    public static Assembly assemble(boolean projectMemory) {
        return assemble(resolveWorkspace(), projectMemory);
    }

    /**
     * 装配分层 system prompt 并带回每一层的来源与取舍，供 REPL {@code /memory} 与日志诊断使用。
     * <p>
     * 全局层不受项目层闸门约束（它是用户自己的跨工程约定）；项目层逐文件过闸门，被跳过的层
     * 以 {@link Layer} 记录原因而非静默消失——用户得能看到“为什么没生效”，否则这类问题只能靠猜。
     */
    public static Assembly assemble(Path workspace, boolean projectMemory) {
        List<Layer> layers = new ArrayList<>();
        try {
            if (!ConfigUtil.getBoolean(ENABLED_KEY, true)) {
                log.info("InstructionMemoryLoader: 总开关 {}=false，仅使用内置基础 prompt", ENABLED_KEY);
                return new Assembly(BASE_SYSTEM_PROMPT, List.of(
                        Layer.baseline(BASE_SYSTEM_PROMPT.length(), "总开关 " + ENABLED_KEY + "=false，仅保留内置基础 prompt")));
            }
            String filename = ConfigUtil.get(FILENAME_KEY, DEFAULT_FILENAME);
            int maxDepth = ConfigUtil.getInt(MAX_DEPTH_KEY, DEFAULT_MAX_DEPTH);

            StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
            layers.add(Layer.baseline(BASE_SYSTEM_PROMPT.length(), "内置基础 prompt（含规划决策契约）"));
            boolean any = false;

            // 全局用户级记忆（用户自己的跨工程约定，不受项目层闸门约束）
            Path globalFile = expandHome(ConfigUtil.get(GLOBAL_DIR_KEY, DEFAULT_GLOBAL_DIR)).resolve(filename);
            if (Files.isRegularFile(globalFile)) {
                String body = expand(readAndStripFrontMatter(readFile(globalFile)).body(),
                        globalFile.toAbsolutePath().getParent(), newLinkedVisited(globalFile), maxDepth);
                if (!body.isBlank()) {
                    sb.append("\n\n===== Instruction Memory =====\n")
                            .append("\n[scope=global | source=").append(globalFile).append("]\n")
                            .append(body.strip()).append("\n");
                    layers.add(Layer.included("global", globalFile, body.length()));
                    any = true;
                }
            }

            // 目录链项目层（根在前、workspace 在后，越靠近 workspace 越后应用、优先级越高）
            for (Path p : collectUpward(workspace, filename)) {
                FrontMatter fm = readAndStripFrontMatter(readFile(p));
                String skipReason = projectSkipReason(p, fm, workspace, projectMemory);
                if (skipReason != null) {
                    layers.add(Layer.skipped("project", p, skipReason));
                    log.info("InstructionMemoryLoader: 跳过项目层记忆 {} —— {}", p, skipReason);
                    continue;
                }
                String body = expand(fm.body(), p.toAbsolutePath().getParent(), newLinkedVisited(p), maxDepth);
                if (body.isBlank()) {
                    layers.add(Layer.skipped("project", p, "正文为空"));
                    continue;
                }
                if (!any) {
                    sb.append("\n\n===== Instruction Memory =====");
                }
                sb.append("\n[scope=project | source=").append(p).append("]\n")
                        .append(body.strip()).append("\n");
                layers.add(Layer.included("project", p, body.length()));
                any = true;
            }

            if (!any) {
                return new Assembly(BASE_SYSTEM_PROMPT, layers);
            }
            sb.append("==============================");
            Assembly assembly = new Assembly(sb.toString(), layers);
            log.info("InstructionMemoryLoader: assembled system prompt, 纳入 {} 层 / 跳过 {} 层, length={}, 估算 token≈{}",
                    assembly.includedCount(), assembly.skippedCount(), assembly.prompt().length(),
                    assembly.estimatedTokens());
            return assembly;
        } catch (RuntimeException e) {
            log.error("InstructionMemoryLoader: load failed, fallback to base prompt", e);
            return new Assembly(BASE_SYSTEM_PROMPT, List.of(
                    Layer.baseline(BASE_SYSTEM_PROMPT.length(), "装配异常，已降级为内置基础 prompt: " + e.getMessage())));
        }
    }

    /**
     * 项目层闸门：返回跳过原因，{@code null} 表示应当加载。顺序：角色/开关 → 排除子树 → 仓库归属绑定。
     */
    private static String projectSkipReason(Path file, FrontMatter fm, Path workspace, boolean projectMemory) {
        if (!projectMemory) {
            return "调用方要求不加载项目层（projectMemory=false，或 " + PROJECT_ENABLED_KEY + "=false / --no-project-memory）";
        }
        Path dir = file.toAbsolutePath().normalize().getParent();
        Path base = workspace == null ? null : workspace.toAbsolutePath().normalize();
        // workspace 相对该记忆文件目录的位置：为空表示“就在这目录下开发”，不属于任何排除子树
        String rel = "";
        if (dir != null && base != null && base.startsWith(dir) && !base.equals(dir)) {
            rel = dir.relativize(base).toString();
        }
        if (!rel.isEmpty()) {
            for (String token : ConfigUtil.get(PROJECT_EXCLUDE_KEY, DEFAULT_PROJECT_EXCLUDE).split(",")) {
                String t = normalizeExcludeToken(token);
                if (!t.isEmpty() && matchesExclude(rel, t)) {
                    return "workspace 相对本文件位于排除子树 \"" + t + "\"（" + PROJECT_EXCLUDE_KEY + "）";
                }
            }
        }
        String markers = fm.meta().get(BOUND_MARKER_KEY);
        if (markers != null && !markers.isBlank()) {
            boolean hit = false;
            List<String> listed = new ArrayList<>();
            for (String marker : markers.split(",")) {
                String m = marker.trim();
                if (m.isEmpty()) {
                    continue;
                }
                listed.add(m);
                if (dir != null && Files.exists(dir.resolve(expandHome(m)))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return "front-matter 声明的仓库归属 marker 均未命中 " + listed + "，判定该记忆不属于当前工作区";
            }
        }
        return null;
    }

    private static String normalizeExcludeToken(String token) {
        if (token == null) {
            return "";
        }
        return token.trim().replaceAll("^/+", "").replaceAll("/+$", "").replace('\\', '/');
    }

    /**
     * 排除项既可比作单级目录名（{@code eval} 命中 {@code eval/terminal-bench/x}），也可比作相对路径前缀
     * （{@code eval/terminal-bench}），但不得把 {@code ta} 误命中 {@code target}——一律按路径元素对齐。
     */
    private static boolean matchesExclude(String rel, String token) {
        String r = rel.replace('\\', '/');
        if (r.equals(token)) {
            return true;
        }
        if (token.indexOf('/') < 0) {
            // 单级名称：匹配任一路径元素
            for (String element : r.split("/")) {
                if (element.equals(token)) {
                    return true;
                }
            }
            return false;
        }
        return r.startsWith(token + "/");
    }

    private static Set<String> newLinkedVisited(Path file) {
        Set<String> visited = new LinkedHashSet<>();
        String key = canonical(file);
        if (key != null) {
            visited.add(key);
        }
        return visited;
    }

    /**
     * 拆出文件首块 front-matter（只认最开头 {@code ---} 起、下一个 {@code ---} 止的那一块）。
     * 只支持 {@code key: value} 单行形式，本用途已够；解析不出就当成普通正文，不影响原有行为。
     */
    private static FrontMatter readAndStripFrontMatter(String raw) {
        if (raw == null || raw.isBlank()) {
            return new FrontMatter(Map.of(), raw == null ? "" : raw);
        }
        String[] lines = raw.split("\n", -1);
        int i = 0;
        // 允许最前面有空白行
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i >= lines.length || !FRONT_MATTER_DELIMITER.matcher(lines[i].trim()).matches()) {
            return new FrontMatter(Map.of(), raw);
        }
        int start = i + 1;
        int end = -1;
        for (int j = start; j < lines.length; j++) {
            if (FRONT_MATTER_DELIMITER.matcher(lines[j].trim()).matches()) {
                end = j;
                break;
            }
        }
        if (end < 0) {
            // 未闭合，视为普通正文，不做剔除（宁可多送也不要把正文弄丢）
            return new FrontMatter(Map.of(), raw);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (int j = start; j < end; j++) {
            String line = lines[j].trim();
            int colon = line.indexOf(':');
            if (colon <= 0 || line.startsWith("#")) {
                continue;
            }
            meta.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        StringBuilder body = new StringBuilder();
        for (int j = end + 1; j < lines.length; j++) {
            body.append(lines[j]).append('\n');
        }
        return new FrontMatter(meta, body.toString());
    }

    /**
     * 粗略估算 token：中日韩字符按 1 token，其余（ASCII/标点/空白）按 0.3 token。
     * 仅用于量级判断与 {@code /memory} 展示，权威数字看 {@code TokenUsageEvent} 的 prompt/cached。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return (int) Math.ceil(cjk + (text.length() - cjk) * 0.3);
    }

    /**
     * front-matter 解析结果：元信息 + 剔除后的正文
     */
    private record FrontMatter(Map<String, String> meta, String body) {
    }

    /**
     * 一层记忆的装配结果，供 {@code /memory} 与日志展示
     */
    public record Layer(String scope, Path source, boolean included, String reason, int chars) {

        static Layer baseline(int chars, String reason) {
            return new Layer("base", null, true, reason, chars);
        }

        static Layer included(String scope, Path source, int chars) {
            return new Layer(scope, source, true, null, chars);
        }

        static Layer skipped(String scope, Path source, String reason) {
            return new Layer(scope, source, false, reason, 0);
        }

        /**
         * 供 REPL 一行一层地展示：包含来源、字节数、跳过原因
         */
        public String describe() {
            String head = (included ? "  [✓] " : "  [×] ") + scope + (source == null ? " (内置)" : " " + source);
            return head + "  字符=" + chars + (included ? "" : "  原因=" + reason);
        }
    }

    /**
     * 装配结果：system prompt 正文 + 每一层的取舍明细
     */
    public record Assembly(String prompt, List<Layer> layers) {

        public int includedCount() {
            return (int) layers.stream().filter(Layer::included).count();
        }

        public int skippedCount() {
            return (int) layers.stream().filter(l -> !l.included()).count();
        }

        public int estimatedTokens() {
            return estimateTokens(prompt);
        }
    }

    /**
     * 从 workspace 目录向上遍历到文件系统根，收集存在的记忆文件；
     * 返回结果反转为“根在前、workspace 在后”，使更具体的目录指令后应用、优先级更高。
     */
    private static List<Path> collectUpward(Path workspace, String filename) {
        List<Path> found = new ArrayList<>();
        if (workspace == null) {
            return found;
        }
        Path dir = workspace.toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(filename);
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
            }
            dir = dir.getParent();
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * 逐行展开引入：跳过围栏代码块；对每个可解析为合法存在文件的 {@code @path}，
     * 用其（递归展开后的）正文就地替换该 token。
     */
    private static String expand(String content, Path baseDir, Set<String> visited, int depth) {
        if (content == null) {
            return "";
        }
        if (depth <= 0) {
            return content;
        }
        List<String> out = new ArrayList<>();
        boolean inFence = false;
        for (String line : Arrays.asList(content.split("\n", -1))) {
            if (FENCE_PATTERN.matcher(line).find()) {
                inFence = !inFence;
                out.add(line);
                continue;
            }
            if (inFence) {
                out.add(line);
                continue;
            }
            out.add(expandLine(line, baseDir, visited, depth));
        }
        return String.join("\n", out);
    }

    private static String expandLine(String line, Path baseDir, Set<String> visited, int depth) {
        Matcher m = IMPORT_PATTERN.matcher(line);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            Path imported = resolveImport(m.group(1), baseDir);
            if (imported == null) {
                continue;
            }
            String key = canonical(imported);
            if (key == null || !visited.add(key)) {
                // 已引入过（或无法定位），跳过以避免循环/重复
                continue;
            }
            String child = expand(readFile(imported), imported.toAbsolutePath().getParent(), visited, depth - 1);
            sb.append(line, last, m.start());
            sb.append("\n").append(child.strip()).append("\n");
            last = m.end();
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /**
     * 解析引入 token 为真实存在的文件路径；非法/不存在返回 null（token 原样保留）。
     * 仅接受 .md/.markdown/.txt，避免引入任意文件。
     */
    private static Path resolveImport(String token, Path baseDir) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.replaceAll("[),;:'\"]+$", "");
        String lower = t.toLowerCase();
        if (!(lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt"))) {
            return null;
        }
        Path p;
        if (t.startsWith("~")) {
            p = expandHome(t);
        } else if (t.startsWith("/")) {
            p = Path.of(t);
        } else {
            p = (baseDir == null ? Path.of("").toAbsolutePath() : baseDir).resolve(t);
        }
        p = p.normalize();
        return Files.isRegularFile(p) ? p : null;
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("InstructionMemoryLoader: read file failed: {}", file, e);
            return "";
        }
    }

    private static Path resolveWorkspace() {
        return Path.of(ConfigUtil.get("project.workspace", System.getProperty("user.dir")));
    }

    private static Path expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), path.substring(1).replaceFirst("^/", ""));
        }
        return Path.of(path);
    }

    private static String canonical(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }
}
