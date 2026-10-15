/*
 * 本地 Trace 查看器前端：原生 JS 单页，无框架、无构建、无 CDN。
 *
 * 刻意做成「哑渲染」：turn/run/round 的分组、工具调用配对、token 汇总全部由后端
 * SessionViewBuilder 算好，这里只负责渲染与交互（懒加载上下文、关键字过滤、自动刷新）。
 * 理由：同一份展示模型要给网页、导出、单测三处消费，逻辑只能有一份。
 */
(function () {
    'use strict';

    var REFRESH_MS = 3000;

    /**
     * 单 turn 轮数超过该值则默认折叠（点击 turn 头展开），防止长会话一次性渲染上千个节点
     */
    var COLLAPSE_ROUNDS = 30;

    var state = {
        meta: null,
        sessions: [],
        sessionId: null,
        view: null,
        // 当前详情对应的「事件数:末尾时间」签名，轮询时据此判断是否需要重拉
        signature: null,
        openKeys: [],
        collapsedTurns: [],
        // 上下文按 (session|run|round) 缓存，避免反复点开重复请求
        ctxCache: {},
        busy: false
    };

    var el = {};

    /* ==================== 入口 ==================== */

    document.addEventListener('DOMContentLoaded', function () {
        ['meta', 'sessionList', 'sidebarFoot', 'sessionHead', 'timeline', 'filterTip',
            'rawBox', 'rawTip', 'toast'].forEach(function (id) {
            el[id] = $(id);
        });
        el.sessionSearch = $('sessionSearch');
        el.stepSearch = $('stepSearch');
        el.autoRefresh = $('autoRefresh');

        $('btnReload').addEventListener('click', function () { loadAll(true); });
        $('btnExpand').addEventListener('click', function () { setAllDetails(true); });
        $('btnCollapse').addEventListener('click', function () { setAllDetails(false); });
        el.sessionSearch.addEventListener('input', renderSessions);
        el.stepSearch.addEventListener('input', applyFilter);
        el.autoRefresh.addEventListener('change', scheduleRefresh);
        $('btnRaw').addEventListener('click', loadRaw);
        $('btnTheme').addEventListener('click', toggleTheme);
        window.addEventListener('hashchange', onHashChange);

        // 后台标签页不轮询：本地工具也要避免无谓的磁盘扫描
        document.addEventListener('visibilitychange', scheduleRefresh);

        syncThemeButton();
        loadAll(false);
        scheduleRefresh();
    });

    /* ==================== 主题 ==================== */

    /**
     * 主题存在 <html data-theme> 上，首屏由 index.html 的内联脚本先定好（防闪烁），
     * 这里只负责后续切换与按钮文案同步。localStorage 可能被浏览器禁用，故读写都要 try。
     */
    function toggleTheme() {
        var next = currentTheme() === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        try { localStorage.setItem('trace-theme', next); } catch (e) { /* 隐私模式等：仅本次会话生效 */ }
        syncThemeButton();
    }

    function currentTheme() {
        return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    }

    /** 按钮文案显示「点了会切到哪」，而不是当前主题，减少一次反直觉点击 */
    function syncThemeButton() {
        var btn = $('btnTheme');
        if (btn) { btn.textContent = currentTheme() === 'dark' ? '☀ 浅色' : '☾ 深色'; }
    }

    function loadAll(force) {
        loadMeta();
        loadSessions(force);
        var route = parseHash();
        if (route.sessionId && route.sessionId !== state.sessionId) {
            selectSession(route.sessionId);
        } else if (!route.sessionId && !state.sessionId && state.sessions.length) {
            // 首次进入默认打开最近一个非子会话，省一次点击
            var first = state.sessions.filter(function (s) { return !s.sub; })[0] || state.sessions[0];
            location.hash = '#/s/' + encodeURIComponent(first.sessionId);
        }
    }

    /* ==================== 数据加载 ==================== */

    function loadMeta() {
        get('/api/meta').then(function (meta) {
            state.meta = meta;
            var parts = [];
            parts.push('引擎 ' + meta.storeEngine);
            parts.push('会话 ' + meta.sessionCount);
            parts.push('快照 ' + (meta.snapshotEnabled ? 'on' : 'off'));
            parts.push('工作区 ' + meta.workspace);
            el.meta.textContent = parts.join('  ·  ');
            el.sidebarFoot.textContent = '会话目录 ' + (meta.sessionDir || '（非 FILE 引擎）')
                + '\n快照目录 ' + meta.snapshotDir
                + (meta.memoryWarning ? '\n⚠ ' + meta.memoryWarning : '');
        }).catch(noop);
    }

    function loadSessions(force) {
        if (state.busy) { return; }
        state.busy = true;
        get('/api/sessions').then(function (data) {
            state.sessions = data.sessions || [];
            renderSessions();
            if (!state.sessionId) { return; }
            if (force || !state.view) { loadSession(state.sessionId); return; }
            // 轮询：只有选中会话本身变了（事件数/末尾时间）才重拉详情，并用 openKeys 保留已展开的上下文
            var entry = find(state.sessions, state.sessionId);
            var signature = entry ? (entry.events + ':' + entry.endTime) : null;
            if (signature && signature !== state.signature) {
                state.openKeys = collectOpenKeys();
                state.collapsedTurns = collectCollapsedTurns();
                loadSession(state.sessionId);
            }
        }).catch(function (e) {
            el.sessionList.innerHTML = '<div class="empty">加载会话失败：' + esc(e.message) + '</div>';
        }).then(function () { state.busy = false; });
    }

    function find(list, sessionId) {
        for (var i = 0; i < list.length; i++) {
            if (list[i].sessionId === sessionId) { return list[i]; }
        }
        return null;
    }

    function collectOpenKeys() {
        var keys = [];
        Array.prototype.forEach.call(el.timeline.querySelectorAll('details[data-ctx]'), function (node) {
            if (node.open) { keys.push(node.getAttribute('data-ctx')); }
        });
        return keys;
    }

    function collectCollapsedTurns() {
        var ids = [];
        Array.prototype.forEach.call(el.timeline.querySelectorAll('.turn.collapsed'), function (node) {
            ids.push(node.getAttribute('data-turn'));
        });
        return ids;
    }

    function selectSession(sessionId) {
        state.sessionId = sessionId;
        state.ctxCache = {};
        state.view = null;
        state.signature = null;
        state.openKeys = [];
        state.collapsedTurns = [];
        el.stepSearch.value = '';
        el.rawBox.textContent = '';
        el.timeline.innerHTML = '<div class="hint">加载中…</div>';
        loadSession(sessionId);
    }

    function loadSession(sessionId) {
        get('/api/sessions/' + encodeURIComponent(sessionId)).then(function (view) {
            state.view = view;
            var entry = find(state.sessions, sessionId);
            state.signature = entry ? (entry.events + ':' + entry.endTime) : null;
            renderHead(view);
            renderTimeline(view);
            restoreOpenState();
            applyFilter();
            restoreRoute();
        }).catch(function (e) {
            el.sessionHead.innerHTML = '<div class="head-title">' + esc(sessionId) + '</div>'
                + '<div class="head-note">会话读取失败：' + esc(e.message) + '</div>';
            el.timeline.innerHTML = '';
        });
    }

    /* ==================== 会话列表 ==================== */

    function renderSessions() {
        var keyword = (el.sessionSearch.value || '').trim().toLowerCase();
        var html = [];
        var shown = 0;
        state.sessions.forEach(function (s) {
            var hay = (s.sessionId + ' ' + (s.lastUserInput || '')).toLowerCase();
            if (keyword && hay.indexOf(keyword) < 0) { return; }
            shown++;
            var title = s.lastUserInput || '（无用户输入）';
            var rec = recorded(s.tokenRounds);
            html.push('<div class="item' + (s.sessionId === state.sessionId ? ' active' : '') + '"'
                + ' data-id="' + esc(s.sessionId) + '">'
                + '<div class="item-title">' + esc(title) + '</div>'
                + '<div class="item-sub mono">' + esc(short(s.sessionId, 22)) + ' · ' + fmtTime(s.endTime) + '</div>'
                + '<div class="item-badges">'
                + badge((s.turns || 0) + ' turn', '')
                + badge((s.runs || 0) + ' run', '')
                + badge(s.events + ' 事件', '')
                + badge(rec ? ('tok ' + num(s.totalTokens)) : 'tok 未记录', rec ? 'snap' : 'none')
                + badge(bytes(s.bytes), '')
                + (s.sub ? badge('子 agent', 'sub') : '')
                + '</div></div>');
        });
        if (!shown) {
            el.sessionList.innerHTML = '<div class="empty">没有匹配的会话。</div>';
            return;
        }
        el.sessionList.innerHTML = html.join('');
        Array.prototype.forEach.call(el.sessionList.querySelectorAll('.item'), function (node) {
            node.addEventListener('click', function () {
                location.hash = '#/s/' + encodeURIComponent(node.getAttribute('data-id'));
            });
        });
    }

    function badge(text, cls) {
        return '<span class="badge ' + cls + '">' + esc(text) + '</span>';
    }

    /* ==================== 会话概览 ==================== */

    function renderHead(view) {
        var s = view.summary || {};
        var meta = state.meta || {};
        var html = [];
        html.push('<div class="head-title mono">' + esc(view.sessionId)
            + (isSub(view.sessionId) ? ' ' + badge('子 agent', 'sub') : '') + '</div>');
        html.push('<div class="head-grid">');
        var rec = recorded(s.tokenRounds);
        html.push(stat(s.turns, 'turn'), stat(s.runs, 'run'), stat(s.events, '事件'));
        html.push(statTok(s.totalTokens, '总 token', '输入 + 输出，所有轮次累加', rec));
        html.push(statTok(s.promptTokens, '输入 token', 'prompt：本轮送给模型的完整上下文大小（含 system prompt 与历史）', rec));
        html.push(statTok(s.completionTokens, '输出 token', 'completion：模型生成的内容长度', rec));
        html.push(statTok(s.cachedTokens, '缓存命中', 'cached：命中服务端 KV 缓存的输入 token，直接影响计费与延迟', rec));
        html.push(stat(span(s.startTime, s.endTime), '时间跨度'));
        html.push('<div class="stat"><b>' + (s.tokenRounds || 0) + '</b><i>已记 token 轮数</i></div>');
        html.push('</div>');
        if (!rec) {
            html.push('<div class="head-note">token 未记录：本会话跑在 TOKEN_USAGE 事件落库能力上线之前（「—」= 未记录，数字 0 = 真的为 0）。</div>');
        }
        html.push('<div class="note">引擎 ' + esc(view.storeEngine || meta.storeEngine || '-')
            + ' · 上下文快照 ' + (view.snapshotEnabled ? '开启' : '关闭')
            + ' · 工作区 ' + esc(meta.workspace || '-')
            + ' · 工具结果全文仅当前工作区可见</div>');
        el.sessionHead.innerHTML = html.join('');
    }

    function stat(value, label) {
        return '<div class="stat"><b>' + esc(String(value == null ? 0 : value)) + '</b><i>' + esc(label) + '</i></div>';
    }

    /**
     * token 专用指标块：「未记录」与「值为 0」必须区分——没有 TOKEN_USAGE 事件（能力上线前的老会话）才显 —，
     * 记录了但确实为 0（如本次未命中缓存）要如实显示 0，否则用户会误以为数据丢了
     */
    function statTok(value, label, tip, rec) {
        return '<div class="stat" title="' + esc(tip) + '"><b>' + (rec ? num(value) : '—')
            + '</b><i>' + esc(label) + '</i></div>';
    }

    /**
     * 是否真的记录过 token：拿「轮数计数」而不是拿数值判真假
     */
    function recorded(tokenRounds) {
        return tokenRounds != null && Number(tokenRounds) > 0;
    }

    /* ==================== 时间线 ==================== */

    function renderTimeline(view) {
        var turns = view.turns || [];
        if (!turns.length) {
            el.timeline.innerHTML = '<div class="empty">该会话没有事件。</div>';
            return;
        }
        var html = [];
        turns.forEach(function (turn) {
            // 长会话（真实案例一轮就有 140+ round）默认折叠，避免首次渲染几千个节点卡顿
            var roundTotal = (turn.runs || []).reduce(function (n, r) { return n + (r.rounds || []).length; }, 0);
            html.push('<section class="turn' + (roundTotal > COLLAPSE_ROUNDS ? ' collapsed' : '')
                + '" data-turn="' + esc(turn.turnId) + '">');
            html.push('<div class="turn-head">'
                + '<span class="tag-turn">' + esc(turn.turnId) + '</span>'
                + '<span class="tag-input">' + esc(clip(turn.userInput || '（无用户输入）', 160)) + '</span>'
                + '<span class="tag-num">' + roundTotal + ' 轮 · '
                + (recorded(turn.tokenRounds) ? num(turn.totalTokens) + ' tok · ' : 'tok 未记录 · ')
                + fmtDur(turn.durationMs) + ' · ' + fmtTime(turn.startTime) + '</span>'
                + '</div>');
            (turn.runs || []).forEach(function (run) {
                html.push(renderRun(run));
            });
            html.push('</section>');
        });
        el.timeline.innerHTML = html.join('');
        bindTurns();
        bindRounds(view);
    }

    /**
     * turn 头点击折叠/展开该轮全部 run（默认展开，长会话里可以只留关心的那一轮）
     */
    function bindTurns() {
        Array.prototype.forEach.call(el.timeline.querySelectorAll('.turn-head'), function (head) {
            head.addEventListener('click', function () {
                head.parentNode.classList.toggle('collapsed');
            });
        });
    }

    function renderRun(run) {
        var html = [];
        html.push('<div class="run">'
            + '<div class="run-head"><span>run</span>'
            + '<span class="rid mono">' + esc(short(run.runId, 18)) + '</span>'
            + '<span class="chip time">' + (run.roundCount || 0) + ' 轮</span>'
            + '<span class="chip time">' + fmtDur(run.durationMs) + '</span>'
            + '<span class="chip token" title="本 run 累加；「tok 未记录」= 该会话跑在 TOKEN_USAGE 落库上线前">'
            + (recorded(run.tokenRounds) ? num(run.totalTokens) + ' tok' : 'tok 未记录') + '</span>'
            + '<span class="fa"></span></div>');
        (run.rounds || []).forEach(function (round) {
            html.push(renderRound(run, round));
        });
        if (run.finalAnswer) {
            html.push('<div class="step k-runCompleted"><span class="t"></span><span class="tag">FINAL</span>'
                + '<span class="body"><span class="text clip">' + esc(run.finalAnswer) + '</span></span></div>');
        }
        html.push('</div>');
        return html.join('');
    }

    function renderRound(run, round) {
        var key = roundKey(run.runId, round.round);
        var tokens = round.tokens;
        var ctx = round.contextSize;
        var html = [];
        // 该轮是否真的发起过模型调用：有 CALL_MODEL_FINISHED 步骤或 TOKEN_USAGE 才算。
        // round 0 只有 RUN_STARTED + USER_INPUT，从未 model.call，本就没有「送给模型的上下文」，
        // 若还展示会走事件流重建路径、用查看器自己的 workspace 现拼 system prompt，误导成串了别项目的记忆。
        var hasModelCall = tokens != null || (round.steps || []).some(function (s) { return s.kind === 'model'; });
        html.push('<div class="round" data-key="' + esc(key) + '" data-run="' + esc(run.runId)
            + '" data-round="' + round.round + '">');

        // 头部指标行：耗时 / token / 上下文规模 / 快照来源 / 各类标记
        html.push('<div class="round-head">');
        html.push('<span class="no">round ' + round.round + '</span>');
        html.push('<span class="chip time">' + fmtDur(round.durationMs) + '</span>');
        if (round.modelMs != null) {
            html.push('<span class="chip time">model ' + fmtDur(round.modelMs) + '</span>');
        }
        html.push(tokens
            ? '<span class="chip token" title="输入 prompt（送给模型的上下文）· 输出 completion · 合计 total · 缓存命中 cached">'
            + '输入 ' + num(tokens.promptTokens) + ' · 输出 ' + num(tokens.completionTokens)
            + ' · 合计 ' + num(tokens.totalTokens)
            + ' · 缓存 ' + num(tokens.cachedTokens) + '</span>'
            : '<span class="chip warn" title="该轮没有 TOKEN_USAGE 事件（能力上线前的历史会话，或模型调用报错未返回 usage）">token 未记录</span>');
        html.push(ctx
            ? '<span class="chip ctx">上下文 ' + ctx.messages + ' 条 · 估算 ' + num(ctx.estimatedTokens) + '</span>'
            : '<span class="chip ctx">上下文规模未记录</span>');
        if (round.tools && round.tools.length) {
            html.push('<span class="chip">工具 ' + round.tools.length + '</span>');
        }
        roundFlags(round.steps || []).forEach(function (f) { html.push('<span class="chip">' + f + '</span>'); });
        // 未发起模型调用的轮次（如 round 0）不标注快照来源：它既无快照也无意义，标了反而误导。
        if (hasModelCall) {
            html.push('<span class="chip ' + (round.hasSnapshot ? 'snap' : 'none') + '">'
                + (round.hasSnapshot ? '快照' : '事件流重建') + '</span>');
        }
        html.push('</div>');

        html.push('<div class="round-body">');
        // ① 上下文：懒加载，点开才请求（一次会话详情响应刻意不带正文，避免几十 MB）
        //    仅在该轮真的发起过模型调用时才给入口：round 0 从未 model.call，无上下文可还原。
        if (hasModelCall) {
            html.push('<details class="sec" data-ctx="' + esc(key) + '"><summary>本轮送给模型的上下文</summary>'
                + '<div class="sec-in"><div class="hint">展开后加载…</div></div></details>');
        }
        // ② 执行步骤
        html.push('<details class="sec" open><summary>执行步骤（' + (round.steps || []).length + '）</summary>'
            + '<div class="sec-in">' + (round.steps || []).map(function (s) { return stepHtml(s); }).join('')
            + '</div></details>');
        html.push('</div></div>');
        return html.join('');
    }

    function roundFlags(steps) {
        var flags = [];
        steps.forEach(function (s) {
            if (s.kind === 'verify') { flags.push(s.passed ? '验证 ✓' : '验证 ✗'); }
            if (s.kind === 'compact') { flags.push('压缩'); }
            if (s.kind === 'todo') { flags.push('TODO'); }
            if (s.kind === 'mode') { flags.push('模式 ' + (s.mode || '-')); }
        });
        return flags;
    }

    /* ==================== 步骤渲染 ==================== */

    function stepHtml(s) {
        var body = '';
        switch (s.kind) {
            case 'runStarted':
                body = '<span class="note">run 开始</span>';
                break;
            case 'user':
                body = text(s.text);
                break;
            case 'reasoning':
                body = text(s.text);
                break;
            case 'model':
                body = text(s.text) + calls(s.toolCalls);
                break;
            case 'tokens':
                body = tokenLine(s);
                break;
            case 'toolStarted':
                body = '<div class="args mono">' + esc(s.code) + ' ' + esc(clip(s.args, 400)) + '</div>';
                break;
            case 'toolDone':
                body = '<div class="args mono">' + esc(s.code) + '</div>' + result(s.output, s.callId);
                break;
            case 'verify':
                body = '<div>' + (s.passed ? '<span style="color:var(--model)">通过</span> ' : '<span style="color:var(--done)">未通过</span> ')
                    + '<span class="mono">' + esc(s.command || '') + '</span>'
                    + (s.exitCode == null ? '' : ' exit=' + s.exitCode) + '</div>' + text(s.detail);
                break;
            case 'compact':
                body = '<div class="note">L1 摘要检查点：' + s.beforeMessages + ' 条 → ' + s.afterMessages
                    + ' 条，保留尾部 ' + s.keepRecentCount + ' 条</div>' + text(s.summary);
                break;
            case 'todo':
                body = (s.todos || []).map(function (t) {
                    return '<div class="todo-item todo-' + esc(t.status || '') + '">['
                        + esc(t.status || '') + '] ' + esc(t.content || '') + '</div>';
                }).join('') || '<span class="note">清单已清空</span>';
                break;
            case 'mode':
                body = '<span class="mono">switch → ' + esc(s.mode || '-') + '</span>';
                break;
            case 'runCompleted':
                body = text(s.result);
                break;
            default:
                body = text(s.payload);
        }
        var label = s.kind === 'runStarted' ? 'RUN START' : s.kind === 'runCompleted' ? 'RUN DONE'
            : s.kind === 'toolDone' ? 'TOOL DONE' : s.kind === 'toolStarted' ? 'TOOL CALL'
            : s.kind === 'model' ? 'MODEL' : s.kind === 'reasoning' ? 'REASONING'
            : s.kind === 'user' ? 'USER' : s.kind === 'tokens' ? 'TOKENS'
            : s.kind === 'verify' ? 'VERIFY' : s.kind === 'compact' ? 'COMPACT'
            : s.kind === 'todo' ? 'TODO' : s.kind === 'mode' ? 'MODE' : 'OTHER';
        return '<div class="step k-' + esc(s.kind) + '" data-searchable>'
            + '<span class="t">' + fmtClock(s.time) + '</span>'
            + '<span class="tag">' + label + '</span>'
            + '<span class="body">' + body + '</span></div>';
    }

    function tokenLine(s) {
        var t = s.tokens || {};
        var c = s.contextSize || {};
        return '<span class="chip token">输入（prompt）' + num(t.promptTokens)
            + ' · 输出（completion）' + num(t.completionTokens)
            + ' · 合计 ' + num(t.totalTokens)
            + ' · 缓存命中 ' + num(t.cachedTokens) + '</span>'
            + '<span class="chip ctx"> 上下文 ' + num(c.messages) + ' 条 / 估算 ' + num(c.estimatedTokens)
            + ' · 模型耗时 ' + fmtDur(s.elapsedMs) + '</span>';
    }

    function calls(list) {
        if (!list || !list.length) { return ''; }
        return '<div class="call-list">' + list.map(function (c) {
            return '<span class="chip">' + esc(c.code || '?') + '</span>';
        }).join('') + '</div>' + list.map(function (c) {
            return '<div class="args mono" data-searchable>' + esc(c.id || '') + ' ' + esc(clip(c.args, 600)) + '</div>';
        }).join('');
    }

    /**
     * 工具结果：超长输出在上下文里只是存根，全文落盘到 {workspace}/.agent-data/tool-results/...
     * 检测到 result_id= 就给一个按需拉取按钮，避免把几百 KB 塞进会话详情响应。
     */
    function result(output, callId) {
        if (!output) { return ''; }
        var html = text(output, true);
        var m = String(output).match(/result_id=([A-Za-z0-9_-]+)/);
        var id = m ? m[1] : callId;
        if (id) {
            html += '<div><button type="button" class="mini" data-result="' + esc(id) + '">查看完整结果</button></div>';
        }
        return html;
    }

    function text(value, collapsible) {
        if (value == null || value === '') { return ''; }
        var raw = String(value);
        var cls = collapsible && raw.length > 600 ? 'text clip' : 'text';
        return '<div class="' + cls + '" data-searchable>' + esc(raw) + '</div>';
    }

    /**
     * 自动刷新重拉后复原交互状态：之前展开的上下文重新展开（正文命中 ctxCache 不会再请求），
     * 之前手动折叠的 turn 保持折叠，避免轮询把页面打回原形
     */
    function restoreOpenState() {
        (state.openKeys || []).forEach(function (key) {
            var node = el.timeline.querySelector('details[data-ctx="' + cssEscape(key) + '"]');
            if (node) { node.open = true; }
        });
        (state.collapsedTurns || []).forEach(function (turnId) {
            var node = el.timeline.querySelector('.turn[data-turn="' + cssEscape(turnId) + '"]');
            if (node) { node.classList.add('collapsed'); }
        });
    }

    /* ==================== 上下文懒加载 ==================== */

    function bindRounds(view) {
        Array.prototype.forEach.call(el.timeline.querySelectorAll('details[data-ctx]'), function (node) {
            node.addEventListener('toggle', function () {
                if (!node.open) { return; }
                var key = node.getAttribute('data-ctx');
                var box = node.querySelector('.sec-in');
                if (box.getAttribute('data-loaded') === key) { return; }
                var cached = state.ctxCache[key];
                if (cached) { box.innerHTML = cached; box.setAttribute('data-loaded', key); return; }
                var parts = key.split('|');
                box.innerHTML = '<div class="hint">加载上下文…</div>';
                get('/api/sessions/' + encodeURIComponent(view.sessionId) + '/context'
                    + '?runId=' + encodeURIComponent(parts[0]) + '&round=' + encodeURIComponent(parts[1]))
                    .then(function (data) {
                        var html = contextHtml(data);
                        state.ctxCache[key] = html;
                        box.innerHTML = html;
                        box.setAttribute('data-loaded', key);
                        bindResultButtons(box);
                        applyFilter();
                    })
                    .catch(function (e) {
                        box.innerHTML = '<div class="head-note">上下文加载失败：' + esc(e.message) + '</div>';
                    });
            });
        });
        Array.prototype.forEach.call(el.timeline.querySelectorAll('button[data-result]'), bindResultButton);
    }

    function contextHtml(data) {
        var html = [];
        var source = data.source === 'snapshot'
            ? '<span class="badge snap">快照（精确）</span>'
            : data.source === 'rebuilt' ? '<span class="badge">事件流重建（近似）</span>'
            : '<span class="badge none">无数据</span>';
        html.push('<div class="msg-head">' + source
            + '<span class="tk">' + (data.messageCount || 0) + ' 条 · 估算 '
            + num(data.estimatedTokens) + ' token</span></div>');
        if (data.note) { html.push('<div class="note">' + esc(data.note) + '</div>'); }
        if (data.tools && data.tools.length) {
            html.push('<div class="call-list" title="本轮模型可见工具">'
                + data.tools.map(function (t) { return '<span class="chip">' + esc(t) + '</span>'; }).join('')
                + '</div>');
        }
        (data.messages || []).forEach(function (m, i) { html.push(messageHtml(m, i)); });
        if (!(data.messages || []).length) { html.push('<div class="hint">没有消息。</div>'); }
        return html.join('');
    }

    function messageHtml(m, index) {
        var kind = (m.kind || 'OTHER').toUpperCase();
        var html = [];
        html.push('<div class="msg m-' + esc(kind) + '" data-searchable>');
        html.push('<div class="msg-head"><span class="role">' + esc(String(index) + '. ' + (m.role || kind)) + '</span>'
            + (m.toolResultCode ? '<span class="chip">← ' + esc(m.toolResultCode) + '</span>' : '')
            + (m.callId ? '<span class="tk mono">' + esc(short(m.callId, 20)) + '</span>' : '')
            + '<span class="tk">≈' + num(m.estimatedTokens) + '</span></div>');
        if (m.content) { html.push(text(m.content, true)); }
        var list = m.toolCalls || [];
        if (list.length) {
            html.push('<div class="call-list">' + list.map(function (c) {
                return '<span class="chip">' + esc(c.code || '?') + '</span>';
            }).join('') + '</div>');
            html.push(list.map(function (c) {
                return '<div class="args mono">' + esc(c.id || '') + ' ' + esc(clip(c.args, 500)) + '</div>';
            }).join(''));
        } else if (m.toolCode) {
            html.push('<div class="args mono">' + esc(m.toolCode) + ' ' + esc(clip(m.toolArgs, 500)) + '</div>');
        }
        if (!m.content && !list.length && !m.toolCode) { html.push('<div class="note">（空消息）</div>'); }
        html.push('</div>');
        return html.join('');
    }

    function bindResultButtons(node) {
        Array.prototype.forEach.call(node.querySelectorAll('button[data-result]'), bindResultButton);
    }

    function bindResultButton(btn) {
        if (btn.getAttribute('data-bound')) { return; }
        btn.setAttribute('data-bound', '1');
        btn.addEventListener('click', function () {
            var callId = btn.getAttribute('data-result');
            btn.textContent = '加载中…';
            get('/api/tool-result?session=' + encodeURIComponent(state.sessionId)
                + '&callId=' + encodeURIComponent(callId)).then(function (data) {
                btn.parentNode.innerHTML = '<div class="text clip mono">' + esc(data.content || '')
                    + '</div><div class="note">共 ' + data.totalLines + ' 行（callId=' + esc(callId) + '）</div>';
                applyFilter();
            }).catch(function (e) {
                btn.textContent = '查看完整结果';
                toast(e.message);
            });
        });
    }

    /**
     * 原始事件分页（调试用）：直接拿 payload 原文，便于确认展示与落库是否一致
     */
    function loadRaw() {
        if (!state.sessionId) { return; }
        var offset = parseInt($('rawOffset').value || '0', 10);
        var limit = parseInt($('rawLimit').value || '100', 10);
        $('rawTip').textContent = '加载中…';
        get('/api/sessions/' + encodeURIComponent(state.sessionId)
            + '/events?offset=' + offset + '&limit=' + limit).then(function (data) {
            el.rawBox.textContent = (data.events || []).map(function (e) {
                return '#' + (offset + data.events.indexOf(e)) + ' [' + e.type + '] '
                    + e.turnId + '/r' + e.round + ' ' + fmtClock(e.time) + '\n    ' + e.payload;
            }).join('\n');
            $('rawTip').textContent = '共 ' + data.total + ' 条，当前 [' + data.offset + ', '
                    + (data.offset + (data.events || []).length) + ')，返回 ' + (data.events || []).length + ' 条';
        }).catch(function (e) {
            $('rawTip').textContent = '加载失败：' + e.message;
        });
    }

    /* ==================== 过滤 / 展开 / 路由 ==================== */

    function applyFilter() {
        var kw = (el.stepSearch.value || '').trim().toLowerCase();
        var matched = 0;
        Array.prototype.forEach.call(el.timeline.querySelectorAll('.turn'), function (turn) {
            var turnHit = 0;
            Array.prototype.forEach.call(turn.querySelectorAll('.round'), function (round) {
                var roundHit = 0;
                Array.prototype.forEach.call(round.querySelectorAll('[data-searchable]'), function (node) {
                    var hit = !kw || node.textContent.toLowerCase().indexOf(kw) >= 0;
                    node.classList.toggle('hidden', !hit && !!kw);
                    if (hit) { roundHit++; }
                });
                round.classList.toggle('hidden', !!kw && roundHit === 0);
                if (roundHit) { turnHit++; }
            });
            turn.classList.toggle('hidden', !!kw && turnHit === 0);
            if (turnHit) { matched += turnHit; }
            if (kw) { turn.classList.remove('collapsed'); }
        });
        el.filterTip.textContent = kw ? ('命中 ' + matched + ' 处') : '';
    }

    function setAllDetails(open) {
        Array.prototype.forEach.call(document.querySelectorAll('details'), function (node) {
            node.open = open;
        });
    }

    function parseHash() {
        var hash = location.hash.replace(/^#\/?/, '');
        var parts = hash.split('/');
        // 形如 s/{sessionId} 或 s/{sessionId}/r/{runId}/{round}
        if (parts[0] === 's' && parts[1]) {
            return { sessionId: decodeURIComponent(parts[1]), runId: parts[3] ? decodeURIComponent(parts[3]) : null,
                round: parts[4] };
        }
        return { sessionId: null };
    }

    function onHashChange() {
        var route = parseHash();
        if (!route.sessionId) { return; }
        if (route.sessionId !== state.sessionId) {
            selectSession(route.sessionId);
            renderSessions();
        } else {
            restoreRoute();
        }
    }

    /**
     * 深链 #/s/{id}/r/{runId}/{round}：点开对应轮的上下文并滚动到位
     */
    function restoreRoute() {
        var route = parseHash();
        if (!route.runId) { return; }
        var node = el.timeline.querySelector('.round[data-run="' + cssEscape(route.runId)
            + '"][data-round="' + route.round + '"]');
        if (!node) { return; }
        node.querySelectorAll('details').forEach(function (d) { d.open = true; });
        node.scrollIntoView({ block: 'start' });
    }

    /* ==================== 自动刷新 ==================== */

    function scheduleRefresh() {
        if (state.timer) {
            clearInterval(state.timer);
            state.timer = null;
        }
        if (!el.autoRefresh || !el.autoRefresh.checked) { return; }
        state.timer = setInterval(function () {
            if (document.hidden) { return; }
            loadSessions(false);
        }, REFRESH_MS);
    }

    /* ==================== 小工具 ==================== */

    function $(id) { return document.getElementById(id); }

    function get(url) {
        return fetch(url, { cache: 'no-store' }).then(function (resp) {
            return resp.text().then(function (body) {
                var data = {};
                try { data = body ? JSON.parse(body) : {}; } catch (e) { /* 保留原始错误文本 */ }
                if (!resp.ok) { throw new Error((data && data.error) || ('HTTP ' + resp.status)); }
                return data;
            });
        });
    }

    function noop() {}

    function esc(value) {
        if (value == null) { return ''; }
        return String(value).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function cssEscape(value) {
        return String(value).replace(/["\\]/g, '\\$&');
    }

    function short(value, size) {
        if (!value) { return '-'; }
        return value.length > size ? value.substring(0, size) + '…' : value;
    }

    function clip(value, size) {
        if (value == null) { return ''; }
        var text = String(value);
        return text.length > size ? text.substring(0, size) + ' …(共 ' + text.length + ' 字符)' : text;
    }

    function num(value) {
        if (value == null) { return '0'; }
        return Number(value).toLocaleString('en-US');
    }

    function bytes(value) {
        var n = Number(value || 0);
        if (!n) { return '0 B'; }
        if (n < 1024) { return n + ' B'; }
        if (n < 1024 * 1024) { return (n / 1024).toFixed(1) + ' KB'; }
        return (n / 1024 / 1024).toFixed(1) + ' MB';
    }

    function fmtDur(ms) {
        var n = Number(ms || 0);
        if (n < 1000) { return n + 'ms'; }
        if (n < 60000) { return (n / 1000).toFixed(1) + 's'; }
        return Math.floor(n / 60000) + 'm' + Math.round((n % 60000) / 1000) + 's';
    }

    function pad(n) { return n < 10 ? '0' + n : String(n); }

    function fmtClock(ts) {
        if (!ts) { return ''; }
        var d = new Date(Number(ts));
        return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    function fmtTime(ts) {
        if (!ts) { return '-'; }
        var d = new Date(Number(ts));
        return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
    }

    function span(start, end) {
        if (!start || !end) { return '-'; }
        return fmtDur(end - start);
    }

    function isSub(sessionId) {
        return String(sessionId || '').indexOf('-sub-') >= 0;
    }

    function roundKey(runId, round) {
        return runId + '|' + round;
    }

    function toast(message) {
        el.toast.textContent = message;
        el.toast.classList.add('on');
        setTimeout(function () { el.toast.classList.remove('on'); }, 3500);
    }
})();
