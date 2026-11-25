(function () {
    const terminal = ['MERGED', 'SUCCESS', 'FAILED', 'TIMEOUT', 'CONFLICT', 'CANCELLED', 'INTERRUPTED'];
    const state = {
        page: 0,
        size: 20,
        total: 0,
        current: null,
        pollTimer: null,
        projects: {}
    };

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
        OB.el('refreshTasks').addEventListener('click', function () {
            loadTasks(0);
        });
        OB.el('resetFilters').addEventListener('click', function () {
            OB.el('typeFilter').value = '';
            OB.el('statusFilter').value = '';
            OB.el('projectFilter').value = '';
            OB.el('branchFilter').value = '';
            loadTasks(0);
        });
        ['typeFilter', 'statusFilter'].forEach(function (id) {
            OB.el(id).addEventListener('change', function () {
                loadTasks(0);
            });
        });
        ['projectFilter', 'branchFilter'].forEach(function (id) {
            OB.el(id).addEventListener('keydown', function (event) {
                if (event.key === 'Enter') {
                    loadTasks(0);
                }
            });
        });
        OB.el('prevPage').addEventListener('click', function () {
            if (state.page > 0) {
                loadTasks(state.page - 1);
            }
        });
        OB.el('nextPage').addEventListener('click', function () {
            if ((state.page + 1) * state.size < state.total) {
                loadTasks(state.page + 1);
            }
        });
        OB.el('cancelBtn').addEventListener('click', cancelTask);
        OB.el('copyTaskUrl').addEventListener('click', function () {
            if (state.current) {
                OB.copyUrl('#/task/' + state.current.id);
            }
        });
        OB.el('backToList').addEventListener('click', closeDetail);
        OB.el('loadMergeLog').addEventListener('click', function () {
            if (state.current) {
                openStepLogs(state.current.id, null, '自动合并日志');
            }
        });
        window.addEventListener('hashchange', route);
        OB.ensureRoute('tasks');
        try {
            const page = await OB.api('/api/projects?page=0&size=500');
            page.items.forEach(function (p) {
                state.projects[p.id] = p.name;
            });
        } catch (e) {
            // ignore
        }
        await loadTasks(0, false);
        await route();
    }

    async function route() {
        const hash = OB.route() || 'tasks';
        OB.ensureRoute(hash);
        const match = hash.match(/^task\/(\d+)/);
        if (match) {
            await openTask(Number(match[1]), false);
            return;
        }
        if (hash.startsWith('tasks')) {
            const params = parseQuery(hash);
            OB.el('typeFilter').value = params.get('type') || '';
            OB.el('statusFilter').value = params.get('status') || '';
            OB.el('projectFilter').value = params.get('keyword') || '';
            OB.el('branchFilter').value = params.get('branch') || '';
            const page = Number(params.get('page') || 0);
            await loadTasks(Number.isFinite(page) ? page : 0);
            return;
        }
        await loadTasks(0);
    }

    function parseQuery(hash) {
        const index = hash.indexOf('?');
        return new URLSearchParams(index >= 0 ? hash.substring(index + 1) : '');
    }

    function currentQuery() {
        const params = new URLSearchParams();
        const type = OB.el('typeFilter').value;
        const status = OB.el('statusFilter').value;
        const keyword = OB.el('projectFilter').value.trim();
        const branch = OB.el('branchFilter').value.trim();
        if (type) {
            params.set('type', type);
        }
        if (status) {
            params.set('status', status);
        }
        if (keyword) {
            params.set('keyword', keyword);
        }
        if (branch) {
            params.set('branch', branch);
        }
        params.set('page', state.page);
        return params.toString();
    }

    async function loadTasks(page, updateHash) {
        const type = OB.el('typeFilter').value;
        const status = OB.el('statusFilter').value;
        const keyword = OB.el('projectFilter').value.trim();
        const branch = OB.el('branchFilter').value.trim();
        let url = '/api/tasks?page=' + page + '&size=' + state.size;
        if (type) {
            url += '&type=' + encodeURIComponent(type);
        }
        if (status) {
            url += '&status=' + encodeURIComponent(status);
        }
        if (keyword) {
            url += '&keyword=' + encodeURIComponent(keyword);
        }
        if (branch) {
            url += '&branch=' + encodeURIComponent(branch);
        }
        try {
            const result = await OB.api(url);
            state.page = result.page;
            state.total = result.total;
            renderTasks(result.items);
            if (updateHash !== false) {
                OB.setRoute('tasks?' + currentQuery());
            }
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function durationText(start, end) {
        if (!start || !end) {
            return '-';
        }
        const seconds = Math.max(0, (new Date(end).getTime() - new Date(start).getTime()) / 1000);
        return seconds.toFixed(1) + ' 秒';
    }

    function renderTasks(items) {
        const body = OB.el('taskBody');
        if (!items.length) {
            body.innerHTML = '<tr><td colspan="9" class="hint">暂无任务</td></tr>';
        } else {
            body.innerHTML = items.map(function (t) {
                const projectName = state.projects[t.projectId] || ('#' + t.projectId);
                return '<tr data-id="' + t.id + '">'
                    + '<td>' + t.id + '</td>'
                    + '<td>' + OB.escapeHtml(t.taskType) + '</td>'
                    + '<td>' + OB.escapeHtml(projectName) + '</td>'
                    + '<td class="mono">' + OB.escapeHtml(t.sourceBranches || '') + '</td>'
                    + '<td class="mono">' + OB.escapeHtml(t.autoBranch || '') + '</td>'
                    + '<td><span class="badge">' + OB.escapeHtml(t.status) + '</span></td>'
                    + '<td>' + durationText(t.startedAt, t.finishedAt) + '</td>'
                    + '<td>' + OB.escapeHtml(OB.fmtTime(t.createdAt)) + '</td>'
                    + '<td class="step-actions"><button data-open="' + t.id + '">详情</button>'
                    + '<button data-copy="' + t.id + '">复制 URL</button></td></tr>';
            }).join('');
            Array.from(body.querySelectorAll('button[data-open]')).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    openTask(Number(btn.getAttribute('data-open')), true);
                });
            });
            Array.from(body.querySelectorAll('button[data-copy]')).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    OB.copyUrl('#/task/' + btn.getAttribute('data-copy'));
                });
            });
        }
        const from = state.total === 0 ? 0 : state.page * state.size + 1;
        const to = Math.min((state.page + 1) * state.size, state.total);
        OB.el('pageInfo').textContent = from + '-' + to + ' / ' + state.total;
        OB.el('prevPage').disabled = state.page <= 0;
        OB.el('nextPage').disabled = (state.page + 1) * state.size >= state.total;
    }

    async function openTask(id, updateHash) {
        if (updateHash) {
            OB.setRoute('task/' + id, true);
        }
        OB.el('detailLayout').style.display = '';
        OB.el('logBox').textContent = '';
        if (state.pollTimer) {
            clearTimeout(state.pollTimer);
        }
        await refreshTask(id);
        OB.el('detailLayout').scrollIntoView({ behavior: 'smooth' });
    }

    async function closeDetail() {
        OB.el('detailLayout').style.display = 'none';
        if (state.pollTimer) {
            clearTimeout(state.pollTimer);
        }
        await loadTasks(state.page, false);
        OB.setRoute('tasks?' + currentQuery());
    }

    async function refreshTask(id) {
        try {
            const detail = await OB.api('/api/tasks/' + id);
            state.current = detail.task;
            renderDetail(detail.task, detail.steps || []);
            if (terminal.indexOf(detail.task.status) < 0) {
                state.pollTimer = setTimeout(function () {
                    refreshTask(id);
                }, 1500);
            }
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function mergeStatus(task) {
        if (['PENDING', 'MERGING', 'PUSHING'].indexOf(task.status) >= 0) {
            return 'RUNNING';
        }
        if (task.status === 'CONFLICT') {
            return 'CONFLICT';
        }
        if (task.failedStage === 'MERGE') {
            return task.status || 'FAILED';
        }
        return 'SUCCESS';
    }

    function renderDetail(task, steps) {
        OB.el('dId').textContent = '#' + task.id;
        const completed = steps.filter(function (s) {
            return s.status === 'SUCCESS';
        }).length;
        const cancelBeforeExecute = ['CANCELLED', 'INTERRUPTED'].indexOf(task.status) >= 0
            && (!task.stage || ['MERGE', 'PUSH'].indexOf(task.stage) >= 0);
        const mergeDone = ['PENDING', 'MERGING', 'PUSHING', 'CONFLICT'].indexOf(task.status) < 0
            && !cancelBeforeExecute && task.failedStage !== 'MERGE';
        const progressDone = (mergeDone ? 1 : 0) + completed;
        const progressTotal = (task.stepTotal || 0) + 1;
        const rows = [
            ['类型', task.taskType],
            ['项目', state.projects[task.projectId] || task.projectId],
            ['操作人', task.createdByName || '系统'],
            ['来源分支', task.sourceBranches || '-'],
            ['目标分支', task.autoBranch || '-'],
            ['状态', task.status],
            ['阶段', task.stage || '-'],
            ['失败阶段', task.failedStage || '-'],
            ['失败步骤', task.failedStepIndex || '-'],
            ['步骤进度', progressDone + ' / ' + progressTotal],
            ['合并提交', task.autoCommitSha || '-'],
            ['信息', task.message || '-'],
            ['错误', task.errorMsg || '-'],
            ['创建', OB.fmtTime(task.createdAt)],
            ['开始', OB.fmtTime(task.startedAt)],
            ['合并完成', OB.fmtTime(task.mergeFinishedAt)],
            ['结束', OB.fmtTime(task.finishedAt)]
        ];
        OB.el('dInfo').innerHTML = rows.map(function (r) {
            return '<div class="k">' + OB.escapeHtml(r[0]) + '</div><div class="mono">'
                + OB.escapeHtml(String(r[1])) + '</div>';
        }).join('');
        const mergeRow = '<tr><td>0</td><td>自动合并</td><td>MERGE</td>'
            + '<td><span class="badge">' + OB.escapeHtml(mergeStatus(task)) + '</span></td>'
            + '<td>-</td><td>-</td><td>-</td>'
            + '<td>' + OB.escapeHtml(OB.fmtTime(task.startedAt)) + '</td>'
            + '<td>' + OB.escapeHtml(OB.fmtTime(task.mergeFinishedAt)) + '</td>'
            + '<td>' + durationText(task.startedAt, task.mergeFinishedAt || task.finishedAt) + '</td>'
            + '<td><button data-log="merge">日志</button></td></tr>';
        const stepRows = steps.map(function (s) {
            return '<tr><td>' + s.stepIndex + '</td>'
                + '<td>' + OB.escapeHtml(s.name || '') + '</td>'
                + '<td>' + OB.escapeHtml(s.stepType || '') + '</td>'
                + '<td><span class="badge">' + OB.escapeHtml(s.status || '') + '</span></td>'
                + '<td>' + (s.attempts || 0) + '</td>'
                + '<td>' + (s.resultCode === null || s.resultCode === undefined ? '-' : s.resultCode) + '</td>'
                + '<td>' + (s.exitCode === null || s.exitCode === undefined ? '-' : s.exitCode) + '</td>'
                + '<td>' + OB.escapeHtml(OB.fmtTime(s.startedAt)) + '</td>'
                + '<td>' + OB.escapeHtml(OB.fmtTime(s.finishedAt)) + '</td>'
                + '<td>' + durationText(s.startedAt, s.finishedAt) + '</td>'
                + '<td><button data-log="' + s.stepIndex + '">日志</button></td></tr>';
        }).join('');
        OB.el('stepBody').innerHTML = mergeRow + (stepRows || '<tr><td colspan="11" class="hint">无步骤</td></tr>');
        Array.from(OB.el('stepBody').querySelectorAll('button[data-log]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                const value = btn.getAttribute('data-log');
                openStepLogs(task.id, value === 'merge' ? null : Number(value),
                    value === 'merge' ? '自动合并日志' : ('步骤 ' + value + ' 日志'));
            });
        });
        const isTerminal = terminal.indexOf(task.status) >= 0;
        OB.el('cancelBtn').disabled = isTerminal;
        OB.el('cancelHint').textContent = isTerminal ? '任务已结束' : '取消将终止当前本地进程';
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

    async function cancelTask() {
        if (!state.current) {
            return;
        }
        if (!(await OB.confirm('确认取消任务 #' + state.current.id + '？', '取消任务'))) {
            return;
        }
        try {
            await OB.api('/api/tasks/' + state.current.id + '/cancel', { method: 'POST' });
            OB.toast('已请求取消');
            refreshTask(state.current.id);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    init();
})();
