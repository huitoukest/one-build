(function () {
    const terminal = ['MERGED', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CONFLICT', 'CANCELLED', 'INTERRUPTED'];
    const state = {
        projects: [],
        project: null,
        branches: [],
        selected: new Set(),
        page: 0,
        size: 20,
        total: 0,
        environments: [],
        environmentId: null,
        busy: false,
        pollTimer: null,
        progress: null,
        currentTaskId: null,
        restoring: false
    };

    function selectedArray() {
        return Array.from(state.selected).sort();
    }

    async function init() {
        try {
            await OB.requireLogin();
        } catch (e) {
            return;
        }
        OB.el('logoutLink').addEventListener('click', function (e) {
            e.preventDefault();
            OB.logout();
        });
        OB.el('refreshProjects').addEventListener('click', loadProjects);
        OB.el('search').addEventListener('input', function () {
            clearTimeout(state.searchTimer);
            state.searchTimer = setTimeout(loadProjects, 250);
        });
        OB.el('refreshBranches').addEventListener('click', function () {
            loadBranches(0);
        });
        OB.el('prevPage').addEventListener('click', function () {
            if (state.page > 0) {
                loadBranches(state.page - 1);
            }
        });
        OB.el('nextPage').addEventListener('click', function () {
            if ((state.page + 1) * state.size < state.total) {
                loadBranches(state.page + 1);
            }
        });
        OB.el('deployBtn').addEventListener('click', submitTask);
        OB.el('conflictHelp').addEventListener('click', showConflictHelp);
        OB.el('copyBranch').addEventListener('click', function () {
            OB.copyText(OB.el('pAutoBranch').textContent || '');
        });
        window.addEventListener('hashchange', routeToProject);
        OB.ensureRoute('projects');
        await loadProjects();
        await routeToProject();
    }

    async function routeToProject() {
        const match = OB.route().match(/^projects\/(\d+)/);
        if (match) {
            await selectProject(Number(match[1]), false);
        }
    }

    async function loadProjects() {
        const keyword = encodeURIComponent(OB.el('search').value.trim());
        try {
            const page = await OB.api('/api/projects?keyword=' + keyword + '&page=0&size=100');
            state.projects = page.items;
            renderProjects();
            if (state.project) {
                const refreshed = state.projects.find(function (p) {
                    return p.id === state.project.id;
                });
                if (refreshed) {
                    state.project = refreshed;
                    renderDetail();
                }
            }
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function renderProjects() {
        const list = OB.el('projectList');
        if (!state.projects.length) {
            list.innerHTML = '<li class="hint">暂无项目，请到「管理」添加</li>';
            return;
        }
        list.innerHTML = state.projects.map(function (p) {
            const active = state.project && state.project.id === p.id ? ' active' : '';
            const disabled = p.enabled === false ? ' disabled' : '';
            const busy = p.busy ? '<span class="badge busy">忙碌</span>' : '';
            const off = p.enabled === false ? '<span class="badge off">已禁用</span>' : '';
            return '<li class="' + (active + disabled).trim() + '" data-id="' + p.id + '">'
                + '<div class="name">' + OB.escapeHtml(p.name) + busy + off + '</div>'
                + '<div class="meta">' + OB.escapeHtml(p.lastCommitAuthor || '-')
                + ' · ' + OB.escapeHtml(OB.fmtTime(p.lastActivityAt)) + '</div>'
                + '</li>';
        }).join('');
        Array.from(list.querySelectorAll('li[data-id]')).forEach(function (li) {
            li.addEventListener('click', function () {
                selectProject(Number(li.getAttribute('data-id')), true);
            });
        });
    }

    async function selectProject(id, updateHash) {
        let project = state.projects.find(function (p) {
            return p.id === id;
        });
        if (!project) {
            await loadProjects();
            project = state.projects.find(function (p) {
                return p.id === id;
            });
        }
        if (!project) {
            return;
        }
        if (updateHash) {
            OB.setRoute('projects/' + id, true);
        }
        state.project = project;
        state.selected.clear();
        state.environmentId = null;
        state.page = 0;
        state.currentTaskId = null;
        state.restoring = true;
        state.branches = [];
        state.environments = [];
        OB.el('emptyHint').style.display = 'none';
        OB.el('projectDetail').style.display = '';
        OB.el('taskStatus').style.display = 'none';
        OB.el('taskStatus').innerHTML = '';
        OB.el('conflictBox').innerHTML = '';
        renderProjects();
        renderDetail();
        renderSelected();
        renderBranches();
        renderEnvironments();
        updateButtons();
        const results = await Promise.all([
            loadAvailability(), loadActualBranch(), loadBranches(0),
            loadEnvironments(), loadOperators(), fetchLastSelection()
        ]);
        (results[5] || []).forEach(function (name) {
            if (name) {
                state.selected.add(name);
            }
        });
        state.restoring = false;
        renderSelected();
        renderBranches();
        updateButtons();
    }

    function renderDetail() {
        const p = state.project;
        if (!p) {
            return;
        }
        OB.el('pName').textContent = p.name;
        const enabledBadge = p.enabled === false
            ? '<span class="badge off">已禁用</span>'
            : '<span class="badge ok">启用中</span>';
        const busyBadge = p.busy ? '<span class="badge busy">忙碌</span>' : '';
        OB.el('pStatusLine').innerHTML = enabledBadge + busyBadge
            + ' <button id="copyPageUrl" class="btn">复制本页 URL</button>';
        OB.el('copyPageUrl').addEventListener('click', function () {
            OB.copyUrl('#/projects/' + p.id);
        });
        OB.el('pRepo').textContent = p.repoUrl;
        OB.el('pRepoType').textContent = repoTypeLabel(p.repoType);
        OB.el('pUpdated').textContent = OB.fmtTime(p.updatedAt);
        OB.el('pAuthor').textContent = p.lastCommitAuthor || '-';
        OB.el('pBase').textContent = p.baseBranch;
        OB.el('pLock').innerHTML = p.busy
            ? '<span class="badge busy">忙碌</span>'
            : '<span class="badge ok">空闲</span>';
        renderSelected();
        updateButtons();
    }

    function repoTypeLabel(type) {
        return { FILE_PATH: '文件路径', GIT_HTTP: 'Git HTTP（预留）', GIT_SSH: 'Git SSH（预留）' }[type] || type || '-';
    }

    function renderSelected() {
        const box = OB.el('selectedTags');
        if (!state.selected.size) {
            box.innerHTML = '<span class="hint">尚未选择来源分支</span>';
        } else {
            box.innerHTML = selectedArray().map(function (name) {
                return '<span class="tag">' + OB.escapeHtml(name)
                    + '<button data-remove="' + OB.escapeHtml(name) + '">×</button></span>';
            }).join('');
            Array.from(box.querySelectorAll('button[data-remove]')).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    state.selected.delete(btn.getAttribute('data-remove'));
                    renderSelected();
                    renderBranches();
                    updateButtons();
                });
            });
        }
    }

    function updateButtons() {
        const projectDisabled = !state.project || state.project.enabled === false;
        const selectedEnv = state.environments.find(function (e) {
            return e.environmentId === state.environmentId;
        });
        const envSelectable = !!selectedEnv && selectedEnv.selectable;
        const disabled = state.busy || projectDisabled || state.selected.size === 0
            || !state.environmentId || !envSelectable;
        OB.el('deployBtn').disabled = disabled;
        let hint = '';
        if (projectDisabled) {
            hint = '项目已禁用，只能查看';
        } else if (state.busy) {
            hint = '项目存在运行中任务';
        } else if (!state.environmentId) {
            hint = '请选择部署环境';
        } else if (!envSelectable) {
            hint = '该环境没有启用的步骤，无法部署';
        } else if (state.selected.size === 0) {
            hint = '请至少选择一个来源分支';
        }
        OB.el('deployHint').textContent = hint;
    }

    async function loadAvailability() {
        try {
            const info = await OB.api('/api/projects/' + state.project.id + '/availability');
            state.busy = !!info.busy;
            OB.el('pLock').innerHTML = info.busy
                ? '<span class="badge busy">忙碌 · 任务#' + OB.escapeHtml(info.taskId)
                + ' · ' + OB.escapeHtml(OB.fmtTime(info.startedAt)) + '</span>'
                : '<span class="badge ok">空闲</span>';
            if (info.busy && info.taskId) {
                state.currentTaskId = info.taskId;
                pollTask(info.taskId);
            }
            updateButtons();
        } catch (e) {
            // ignore
        }
    }

    async function loadActualBranch() {
        try {
            const info = await OB.api('/api/projects/' + state.project.id + '/actual-branch');
            OB.el('pAutoBranch').textContent = info.autoBranch || '-';
        } catch (e) {
            OB.el('pAutoBranch').textContent = '-';
        }
    }

    async function loadOperators() {
        try {
            const info = await OB.api('/api/projects/' + state.project.id + '/operators');
            const people = (info.recentSuccessPeople || []).join('、') || '-';
            OB.el('operatorsLine').textContent = '最后选入人：'
                + (info.lastSelector || '系统') + ' · 最近构建成功：' + people;
        } catch (e) {
            OB.el('operatorsLine').textContent = '';
        }
    }

    async function fetchLastSelection() {
        try {
            const info = await OB.api('/api/projects/' + state.project.id + '/last-selection');
            return info.sourceBranches || [];
        } catch (e) {
            return [];
        }
    }

    async function loadBranches(page) {
        try {
            const result = await OB.api('/api/projects/' + state.project.id
                + '/branches?page=' + page + '&size=' + state.size);
            state.branches = result.items;
            state.total = result.total;
            state.page = result.page;
            renderBranches();
        } catch (e) {
            OB.el('branchBody').innerHTML = '<tr><td colspan="4" class="error">'
                + OB.escapeHtml(e.message) + '</td></tr>';
        }
    }

    function renderBranches() {
        const body = OB.el('branchBody');
        if (!state.branches.length) {
            body.innerHTML = '<tr><td colspan="4" class="hint">无分支</td></tr>';
        } else {
            const projectDisabled = !state.project || state.project.enabled === false;
            body.innerHTML = state.branches.map(function (b) {
                const chosen = state.selected.has(b.name);
                let action;
                if (state.restoring) {
                    action = '<button disabled>加载中</button>';
                } else if (chosen) {
                    action = '<button disabled>已选入</button>';
                } else {
                    const overLimit = state.selected.size >= 20;
                    const disabled = projectDisabled || overLimit;
                    action = '<button data-branch="' + OB.escapeHtml(b.name) + '"'
                        + (disabled ? ' disabled' : '') + '>选入</button>';
                }
                return '<tr><td class="mono">' + OB.escapeHtml(b.name) + '</td>'
                    + '<td>' + OB.escapeHtml(OB.fmtTime(b.commitTime)) + '</td>'
                    + '<td>' + OB.escapeHtml(b.author) + '</td>'
                    + '<td>' + action + '</td></tr>';
            }).join('');
            Array.from(body.querySelectorAll('button[data-branch]')).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    state.selected.add(btn.getAttribute('data-branch'));
                    renderSelected();
                    renderBranches();
                    updateButtons();
                });
            });
        }
        const from = state.total === 0 ? 0 : state.page * state.size + 1;
        const to = Math.min((state.page + 1) * state.size, state.total);
        OB.el('pageInfo').textContent = from + '-' + to + ' / ' + state.total;
        OB.el('prevPage').disabled = state.page <= 0;
        OB.el('nextPage').disabled = (state.page + 1) * state.size >= state.total;
    }

    async function loadEnvironments() {
        try {
            state.environments = await OB.api('/api/projects/' + state.project.id + '/deploy-environments');
        } catch (e) {
            state.environments = [];
        }
        if (state.environmentId) {
            const selected = state.environments.find(function (e) {
                return e.environmentId === state.environmentId;
            });
            if (!selected || !selected.selectable) {
                state.environmentId = null;
            }
        }
        renderEnvironments();
    }

    function renderEnvironments() {
        const box = OB.el('envChips');
        if (!state.environments.length) {
            box.innerHTML = '<span class="hint">该项目尚未配置任何环境步骤</span>';
            return;
        }
        box.innerHTML = state.environments.map(function (env) {
            const active = state.environmentId === env.environmentId;
            const disabled = !env.selectable;
            const noStep = env.stepCount === 0 ? '（无启用步骤）' : '';
            const label = env.name + noStep + (active ? ' (当前选中)' : '');
            return '<button class="env-chip' + (active ? ' active' : '')
                + (disabled ? ' disabled' : '') + '" data-env="' + env.environmentId + '"'
                + (disabled ? ' disabled' : '') + '>'
                + OB.escapeHtml(label)
                + '<span class="env-key">' + OB.escapeHtml(env.envKey || '') + '</span></button>';
        }).join('');
        Array.from(box.querySelectorAll('button[data-env]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                if (btn.disabled) {
                    return;
                }
                state.environmentId = Number(btn.getAttribute('data-env'));
                renderEnvironments();
                updateButtons();
            });
        });
        if (!state.environmentId) {
            const first = state.environments.find(function (env) {
                return env.selectable;
            });
            if (first) {
                state.environmentId = first.environmentId;
                renderEnvironments();
                updateButtons();
            }
        }
    }

    async function submitTask() {
        if (!state.project || state.selected.size === 0 || !state.environmentId) {
            return;
        }
        const body = {
            projectId: state.project.id,
            environmentId: state.environmentId,
            sourceBranches: selectedArray()
        };
        try {
            const task = await OB.api('/api/trigger-tasks', { method: 'POST', body: JSON.stringify(body) });
            state.busy = true;
            state.currentTaskId = task.id;
            updateButtons();
            openProgress();
            pollTask(task.id);
        } catch (e) {
            if (e.code === 'PROJECT_BUSY' || (e.data && e.data.taskId)) {
                const data = e.data || {};
                OB.toast('项目忙碌：任务#' + data.taskId, true);
                if (data.taskId) {
                    state.currentTaskId = data.taskId;
                    openProgress();
                    pollTask(data.taskId);
                }
            } else {
                OB.toast(e.message, true);
            }
        }
    }

    function openProgress() {
        if (state.progress) {
            state.progress.close();
        }
        state.progress = OB.progress();
        state.progress.update(0, 1, '自动合并');
    }

    function closeProgress() {
        if (state.progress) {
            state.progress.close();
            state.progress = null;
        }
    }

    function pollTask(taskId) {
        if (state.pollTimer) {
            clearTimeout(state.pollTimer);
        }
        async function tick() {
            try {
                const detail = await OB.api('/api/tasks/' + taskId);
                const task = detail.task;
                renderTaskResult(task, detail.steps || []);
                updateProgress(task, detail.steps || []);
                renderConflict(task);
                if (terminal.indexOf(task.status) >= 0) {
                    state.busy = false;
                    closeProgress();
                    updateButtons();
                    await Promise.all([
                        loadAvailability(), loadActualBranch(), loadBranches(state.page),
                        loadProjects(), loadOperators()
                    ]);
                    return;
                }
            } catch (e) {
                // ignore
            }
            state.pollTimer = setTimeout(tick, 1500);
        }
        tick();
    }

    function mergeDone(task) {
        if (!task) {
            return false;
        }
        if (['PENDING', 'MERGING', 'PUSHING', 'CONFLICT'].indexOf(task.status) >= 0) {
            return false;
        }
        if (['CANCELLED', 'INTERRUPTED'].indexOf(task.status) >= 0
            && (!task.stage || ['MERGE', 'PUSH'].indexOf(task.stage) >= 0)) {
            return false;
        }
        return task.failedStage !== 'MERGE';
    }

    function progressLabel(task, steps) {
        if (!task) {
            return '准备中…';
        }
        if (['PENDING', 'MERGING'].indexOf(task.status) >= 0) {
            return '自动合并';
        }
        if (task.status === 'PUSHING') {
            return '推送目标分支';
        }
        if (task.status === 'PREPARING') {
            return '准备工作空间';
        }
        if (task.status === 'EXECUTING' || task.status === 'FAILED' || task.status === 'TIMEOUT'
            || task.status === 'CANCELLED') {
            const running = steps.find(function (s) {
                return s.status === 'RUNNING';
            });
            const failed = task.failedStepIndex === null || task.failedStepIndex === undefined
                ? null
                : steps.find(function (s) {
                    return s.stepIndex === task.failedStepIndex;
                });
            const current = running || failed;
            if (current) {
                return '步骤 ' + current.stepIndex + '：' + (current.name || '执行中');
            }
            return '执行步骤';
        }
        if (task.status === 'SUCCESS') {
            return '全部步骤成功';
        }
        return '任务结束';
    }

    function updateProgress(task, steps) {
        if (!state.progress) {
            return;
        }
        const total = (task.stepTotal || 0) + 1;
        const completed = steps.filter(function (s) {
            return s.status === 'SUCCESS';
        }).length;
        const done = (mergeDone(task) ? 1 : 0) + completed;
        state.progress.update(Math.min(done, total), total, progressLabel(task, steps));
    }

    function durationText(start, end) {
        if (!start || !end) {
            return '-';
        }
        const seconds = Math.max(0, (new Date(end).getTime() - new Date(start).getTime()) / 1000);
        return seconds.toFixed(1) + ' 秒';
    }

    function renderTaskResult(task, steps) {
        const box = OB.el('taskStatus');
        box.style.display = '';
        const mergeStatus = mergeDone(task) ? 'SUCCESS'
            : (task.status === 'CONFLICT' ? 'CONFLICT' : (task.failedStage === 'MERGE' ? (task.status || 'FAILED') : 'RUNNING'));
        const rows = [];
        rows.push('<tr><td>0</td><td>自动合并</td><td>MERGE</td>'
            + '<td><span class="badge">' + OB.escapeHtml(mergeStatus) + '</span></td>'
            + '<td>' + durationText(task.startedAt, task.mergeFinishedAt || task.finishedAt) + '</td>'
            + '<td><button data-log="merge">日志</button></td></tr>');
        steps.forEach(function (s) {
            rows.push('<tr><td>' + s.stepIndex + '</td>'
                + '<td>' + OB.escapeHtml(s.name || '') + '</td>'
                + '<td>' + OB.escapeHtml(s.stepType || '') + '</td>'
                + '<td><span class="badge">' + OB.escapeHtml(s.status || '') + '</span></td>'
                + '<td>' + durationText(s.startedAt, s.finishedAt) + '</td>'
                + '<td><button data-log="' + s.stepIndex + '">日志</button></td></tr>');
        });
        box.innerHTML = '<div>任务 #' + OB.escapeHtml(task.id) + ' · ' + OB.escapeHtml(task.taskType)
            + ' · 状态 <span class="badge" id="taskStatusBadge">'
            + OB.escapeHtml(task.status) + '</span></div>'
            + '<div class="hint">目标分支：' + OB.escapeHtml(task.autoBranch || '-')
            + ' · 操作人：' + OB.escapeHtml(task.createdByName || '系统') + '</div>'
            + (task.errorMsg ? '<div class="error mono">' + OB.escapeHtml(task.errorMsg) + '</div>' : '')
            + '<table><thead><tr><th>#</th><th>步骤</th><th>类型</th><th>状态</th><th>耗时</th><th>日志</th></tr></thead>'
            + '<tbody>' + rows.join('') + '</tbody></table>';
        Array.from(box.querySelectorAll('button[data-log]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                const value = btn.getAttribute('data-log');
                const stepIndex = value === 'merge' ? null : Number(value);
                openStepLogs(task.id, stepIndex, value === 'merge' ? '自动合并日志' : ('步骤 ' + value + ' 日志'));
            });
        });
    }

    async function openStepLogs(taskId, stepIndex, title) {
        try {
            let url = '/api/tasks/' + taskId + '/logs?limit=3000';
            if (stepIndex !== null && stepIndex !== undefined) {
                url += '&stepIndex=' + stepIndex;
            }
            const logs = await OB.api(url);
            const lines = logs.map(function (l) {
                return '[' + OB.fmtTime(l.createdAt) + '][' + (l.step || '') + '][' + (l.level || '') + '] '
                    + (l.content || '');
            });
            OB.openLogModal(title + ' · 任务 #' + taskId, lines.length ? lines : ['（暂无日志）']);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function renderConflict(task) {
        const box = OB.el('conflictBox');
        if (task.status !== 'CONFLICT') {
            box.innerHTML = '';
            return;
        }
        const branch = task.autoBranch || '<auto_branch>';
        box.innerHTML = '<div class="status-box">'
            + '<div class="error">合并冲突：' + OB.escapeHtml(task.errorMsg || '') + '</div>'
            + '<div class="hint">处理命令（在本地仓库执行，解决后回到本页重新部署）：</div>'
            + '<pre class="log">git fetch origin\n'
            + 'git checkout -B ' + OB.escapeHtml(branch) + ' origin/' + OB.escapeHtml(branch) + '\n'
            + '# 手动解决冲突后\ngit add .\ngit commit -m "resolve conflict"\n'
            + 'git push origin ' + OB.escapeHtml(branch) + '</pre>'
            + '<button id="retryMerge">重试合并</button></div>';
        const btn = OB.el('retryMerge');
        if (btn) {
            btn.addEventListener('click', function () {
                retryMerge();
            });
        }
    }

    async function retryMerge() {
        if (!state.project) {
            return;
        }
        try {
            const task = await OB.api('/api/merge-tasks', {
                method: 'POST',
                body: JSON.stringify({ projectId: state.project.id, sourceBranches: selectedArray() })
            });
            state.busy = true;
            state.currentTaskId = task.id;
            updateButtons();
            openProgress();
            pollTask(task.id);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function showConflictHelp() {
        OB.openLogModal('冲突怎么处理？', [
            '1. 在本地克隆仓库并获取目标分支：',
            '   git fetch origin',
            '   git checkout -B <目标分支> origin/<目标分支>',
            '',
            '2. 合并冲突的来源分支：',
            '   git merge origin/<来源分支>',
            '',
            '3. 手动编辑冲突文件，保留正确内容，然后：',
            '   git add .',
            '   git commit -m "resolve conflict"',
            '   git push origin <目标分支>',
            '',
            '4. 回到本页面，点击「重试合并」；工具会跳过已合入分支继续合并。',
            '说明：工具不会强制推送，人工提交会被保留。'
        ]);
    }

    init();
})();
