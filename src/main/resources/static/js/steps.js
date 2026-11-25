(function () {
    const state = {
        projects: [],
        environments: [],
        configs: [],
        projectId: null,
        environmentId: null,
        configId: null,
        configEnabled: true,
        steps: [],
        editingStepIndex: null,
        editingStepEnabled: true,
        tree: { available: false, path: '', parent: null, root: '', entries: [] }
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
        OB.el('projectSelect').addEventListener('change', function () {
            applyProject(Number(OB.el('projectSelect').value), null, true);
        });
        OB.el('environmentSelect').addEventListener('change', function () {
            applyProject(state.projectId, Number(OB.el('environmentSelect').value), true);
        });
        OB.el('loadConfig').addEventListener('click', function () {
            applyProject(Number(OB.el('projectSelect').value),
                Number(OB.el('environmentSelect').value) || null, true);
        });
        OB.el('configSave').addEventListener('click', saveConfig);
        OB.el('configToggle').addEventListener('click', toggleConfig);
        OB.el('configDelete').addEventListener('click', deleteConfig);
        OB.el('stepSave').addEventListener('click', saveStep);
        OB.el('stepCancelEdit').addEventListener('click', resetStepForm);
        OB.el('stepNew').addEventListener('click', function () {
            resetStepForm();
            OB.el('stName').focus();
        });
        OB.el('copyStepUrl').addEventListener('click', function () {
            OB.copyUrl(location.hash || '#/steps');
        });
        OB.el('backAdmin').addEventListener('click', function () {
            OB.go('/admin.html#/steps');
        });
        OB.el('treeUp').addEventListener('click', function () {
            loadTree(state.tree.parent === null ? '' : state.tree.parent);
        });
        OB.el('treeRefresh').addEventListener('click', function () {
            loadTree(state.tree.path || '');
        });
        OB.el('copyRelPath').addEventListener('click', function () {
            OB.copyText(state.tree.path || '.');
        });
        OB.el('copyAbsPath').addEventListener('click', function () {
            if (!state.tree.available || !state.tree.root) {
                OB.toast('工作空间尚未初始化', true);
                return;
            }
            const relative = (state.tree.path || '').replace(/\//g, '\\');
            const root = state.tree.root.replace(/[\\/]+$/, '');
            OB.copyText(relative ? root + '\\' + relative : root);
        });
        OB.el('useWorkdir').addEventListener('click', useTreeAsWorkdir);
        OB.el('useTreeWorkdir').addEventListener('click', useTreeAsWorkdir);
        window.addEventListener('hashchange', route);
        await loadSelections();
        await route();
    }

    async function loadSelections() {
        try {
            const page = await OB.api('/api/projects?page=0&size=500');
            state.projects = page.items;
            state.environments = await OB.api('/api/environments');
        } catch (e) {
            OB.toast(e.message, true);
            return;
        }
        OB.el('projectSelect').innerHTML = '<option value="">请选择项目</option>'
            + state.projects.map(function (p) {
                return '<option value="' + p.id + '">' + OB.escapeHtml(p.name)
                    + (p.enabled === false ? '（已禁用）' : '') + '</option>';
            }).join('');
        OB.el('environmentSelect').innerHTML = '<option value="">请选择环境</option>'
            + state.environments.map(function (e) {
                return '<option value="' + e.id + '">' + OB.escapeHtml(e.name)
                    + (e.enabled === false ? '（已禁用）' : '') + '</option>';
            }).join('');
    }

    async function route() {
        const hash = OB.route() || 'steps';
        OB.ensureRoute('steps');
        const match = hash.match(/^steps(?:\/(\d+))?(?:\/(\d+))?/);
        if (!match || !match[1]) {
            renderEmpty('请选择项目与环境');
            return;
        }
        const projectId = Number(match[1]);
        const environmentId = match[2] ? Number(match[2]) : null;
        if (state.projectId === projectId && state.environmentId === environmentId) {
            return;
        }
        await applyProject(projectId, environmentId, false);
    }

    async function applyProject(projectId, environmentId, pushRoute) {
        const project = state.projects.find(function (p) {
            return p.id === projectId;
        });
        if (!project) {
            renderEmpty('项目不存在或已被删除');
            return;
        }
        state.projectId = projectId;
        OB.el('projectSelect').value = String(projectId);
        resetStepForm();
        await loadOverview();
        let envId = environmentId;
        if (envId && !state.environments.some(function (e) { return e.id === envId; })) {
            envId = null;
        }
        if (!envId) {
            envId = defaultEnvironmentId();
        }
        state.environmentId = envId || null;
        OB.el('environmentSelect').value = envId ? String(envId) : '';
        await loadTree('', false);
        if (envId) {
            await loadConfig(projectId, envId, pushRoute);
        } else {
            state.configId = null;
            state.steps = [];
            OB.el('configMsg').textContent = '请先在“管理 → 环境”中新增环境';
            renderAll();
            if (pushRoute) {
                OB.setRoute('steps/' + projectId, true);
            }
        }
    }

    function defaultEnvironmentId() {
        const fromConfig = state.configs.find(function (c) {
            return state.environments.some(function (e) { return e.id === c.environmentId; });
        });
        if (fromConfig) {
            return fromConfig.environmentId;
        }
        const enabled = state.environments.find(function (e) {
            return e.enabled !== false;
        });
        return enabled ? enabled.id : (state.environments[0] ? state.environments[0].id : null);
    }

    async function loadOverview() {
        try {
            state.configs = await OB.api('/api/trigger-configs/list?projectId=' + state.projectId);
        } catch (e) {
            state.configs = [];
        }
        renderOverview();
    }

    function renderOverview() {
        const box = OB.el('projectOverview');
        if (!state.projectId) {
            box.innerHTML = '';
            return;
        }
        if (!state.configs.length) {
            box.innerHTML = '<span class="hint">本项目暂无步骤配置，选择环境后新增步骤即可自动创建</span>';
            return;
        }
        box.innerHTML = state.configs.map(function (c) {
            const active = c.environmentId === state.environmentId ? ' active' : '';
            const off = c.enabled === false ? '（已禁用）' : '';
            return '<button type="button" class="env-chip' + active + '" data-overview-env="'
                + c.environmentId + '">' + OB.escapeHtml(c.environmentName) + off
                + ' · ' + (c.stepCount || 0) + ' 步</button>';
        }).join('');
        Array.from(box.querySelectorAll('[data-overview-env]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                applyProject(state.projectId, Number(btn.getAttribute('data-overview-env')), true);
            });
        });
    }

    async function loadConfig(projectId, environmentId, pushRoute) {
        try {
            const data = await OB.api('/api/trigger-configs?projectId=' + projectId
                + '&environmentId=' + environmentId);
            state.configId = data.config ? data.config.id : null;
            state.configEnabled = data.config ? data.config.enabled !== false : true;
            state.steps = data.steps || [];
            OB.el('overallTimeout').value = data.config
                ? data.config.overallTimeoutSeconds : 3600;
            OB.el('configMsg').textContent = data.config
                ? '配置已存在，可直接编辑步骤' : '尚未创建配置，新增步骤时会自动创建';
            OB.el('configMsg').className = 'hint';
        } catch (e) {
            state.configId = null;
            state.steps = [];
            OB.el('configMsg').textContent = e.message;
            OB.el('configMsg').className = 'error';
        }
        renderAll();
        OB.setRoute('steps/' + projectId + '/' + environmentId, !!pushRoute);
    }

    function renderAll() {
        renderOverview();
        renderConfigState();
        renderSteps();
    }

    function renderConfigState() {
        const badge = OB.el('configState');
        if (!state.configId) {
            badge.textContent = '未创建';
            badge.className = 'badge off';
        } else {
            badge.textContent = state.configEnabled ? '启用' : '禁用';
            badge.className = 'badge ' + (state.configEnabled ? 'ok' : 'off');
        }
        OB.el('configToggle').textContent = state.configEnabled ? '禁用配置' : '启用配置';
        OB.el('configToggle').disabled = !state.configId;
        OB.el('configDelete').disabled = !state.configId;
        OB.el('configSave').disabled = !state.projectId || !state.environmentId;
    }

    function renderEmpty(message) {
        state.projectId = null;
        state.environmentId = null;
        state.configId = null;
        state.steps = [];
        state.tree = { available: false, path: '', parent: null, root: '', entries: [] };
        OB.el('projectSelect').value = '';
        OB.el('environmentSelect').value = '';
        OB.el('configMsg').textContent = message || '请选择项目与环境';
        OB.el('stepCount').textContent = '';
        OB.el('stepBody').innerHTML = '<tr><td colspan="8" class="hint">请选择项目与环境后加载</td></tr>';
        renderOverview();
        renderConfigState();
        renderTree();
        resetStepForm();
    }

    function stepPayload(step, enabled) {
        return {
            name: step.name,
            stepType: step.stepType || 'SCRIPT',
            command: step.command,
            shell: step.shell,
            workdir: step.workdir,
            env: step.env || {},
            timeoutSeconds: step.timeoutSeconds,
            pollIntervalSeconds: step.pollIntervalSeconds,
            enabled: enabled === undefined ? step.enabled : enabled
        };
    }

    function resetStepForm() {
        state.editingStepIndex = null;
        state.editingStepEnabled = true;
        OB.el('stepFormTitle').textContent = '新增 SCRIPT 步骤';
        OB.el('stName').value = '';
        OB.el('stWorkdir').value = '.';
        OB.el('stShell').value = 'auto';
        OB.el('stTimeout').value = 600;
        OB.el('stPoll').value = 2;
        OB.el('stEnabled').value = 'true';
        OB.el('stCommand').value = '';
        OB.el('stEnv').value = '';
        OB.el('stepSave').textContent = '新增步骤';
        OB.el('stepCancelEdit').style.display = 'none';
        OB.el('stepMsg').textContent = '';
    }

    function startEditStep(index) {
        const step = state.steps.find(function (s) {
            return s.stepIndex === index;
        });
        if (!step) {
            return;
        }
        state.editingStepIndex = index;
        state.editingStepEnabled = step.enabled !== false;
        OB.el('stepFormTitle').textContent = '编辑步骤 #' + index;
        OB.el('stName').value = step.name || '';
        OB.el('stWorkdir').value = step.workdir || '.';
        OB.el('stShell').value = step.shell || 'auto';
        OB.el('stTimeout').value = step.timeoutSeconds || 600;
        OB.el('stPoll').value = step.pollIntervalSeconds || 0;
        OB.el('stEnabled').value = step.enabled === false ? 'false' : 'true';
        OB.el('stCommand').value = step.command || '';
        OB.el('stEnv').value = envText(step.env);
        OB.el('stepSave').textContent = '保存步骤';
        OB.el('stepCancelEdit').style.display = '';
        OB.el('stCommand').focus();
    }

    function parseEnv(text) {
        const env = {};
        (text || '').split('\n').forEach(function (line) {
            const idx = line.indexOf('=');
            if (idx > 0) {
                env[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
            }
        });
        return env;
    }

    function envText(env) {
        if (!env) {
            return '';
        }
        return Object.keys(env).map(function (k) {
            return k + '=' + env[k];
        }).join('\n');
    }

    async function ensureConfig() {
        if (state.configId) {
            return state.configId;
        }
        const config = await OB.api('/api/trigger-configs', {
            method: 'POST',
            body: JSON.stringify({
                projectId: state.projectId,
                environmentId: state.environmentId,
                overallTimeoutSeconds: Number(OB.el('overallTimeout').value || 3600),
                enabled: true
            })
        });
        state.configId = config.id;
        state.configEnabled = config.enabled !== false;
        await loadOverview();
        return config.id;
    }

    async function saveConfig() {
        if (!state.projectId || !state.environmentId) {
            OB.toast('请选择项目与环境', true);
            return;
        }
        try {
            const body = JSON.stringify({
                projectId: state.projectId,
                environmentId: state.environmentId,
                overallTimeoutSeconds: Number(OB.el('overallTimeout').value || 3600),
                enabled: state.configId ? state.configEnabled : true
            });
            if (state.configId) {
                await OB.api('/api/trigger-configs/' + state.configId, { method: 'PUT', body: body });
            } else {
                await OB.api('/api/trigger-configs', { method: 'POST', body: body });
            }
            OB.toast('配置已保存');
            await loadOverview();
            await loadConfig(state.projectId, state.environmentId, false);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function toggleConfig() {
        if (!state.configId) {
            return;
        }
        try {
            await OB.api('/api/trigger-configs/' + state.configId, {
                method: 'PUT',
                body: JSON.stringify({
                    projectId: state.projectId,
                    environmentId: state.environmentId,
                    overallTimeoutSeconds: Number(OB.el('overallTimeout').value || 3600),
                    enabled: !state.configEnabled
                })
            });
            await loadOverview();
            await loadConfig(state.projectId, state.environmentId, false);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function deleteConfig() {
        if (!state.configId) {
            return;
        }
        if (!(await OB.confirm('删除后步骤配置不可恢复；存在历史任务引用时会被拒绝。', '删除配置'))) {
            return;
        }
        try {
            await OB.api('/api/trigger-configs/' + state.configId, { method: 'DELETE' });
            OB.toast('已删除配置');
            await loadOverview();
            await loadConfig(state.projectId, state.environmentId, false);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function saveStep() {
        if (!state.projectId || !state.environmentId) {
            OB.toast('请选择项目与环境', true);
            return;
        }
        const payload = {
            name: OB.el('stName').value.trim() || 'step',
            stepType: 'SCRIPT',
            command: OB.el('stCommand').value,
            shell: OB.el('stShell').value,
            workdir: OB.el('stWorkdir').value.trim() || '.',
            timeoutSeconds: Number(OB.el('stTimeout').value || 600),
            pollIntervalSeconds: Number(OB.el('stPoll').value || 0),
            env: parseEnv(OB.el('stEnv').value),
            enabled: OB.el('stEnabled').value === 'true'
        };
        try {
            const configId = await ensureConfig();
            if (state.editingStepIndex !== null) {
                await OB.api('/api/trigger-configs/' + configId + '/steps/'
                    + state.editingStepIndex, { method: 'PUT', body: JSON.stringify(payload) });
                OB.toast('已保存步骤');
            } else {
                await OB.api('/api/trigger-configs/' + configId + '/steps',
                    { method: 'POST', body: JSON.stringify(payload) });
                OB.toast('已新增步骤');
            }
            resetStepForm();
            await loadOverview();
            await loadConfig(state.projectId, state.environmentId, false);
        } catch (e) {
            OB.el('stepMsg').textContent = e.message;
            OB.el('stepMsg').className = 'error';
            OB.toast(e.message, true);
        }
    }

    function renderSteps() {
        const body = OB.el('stepBody');
        const enabledCount = state.steps.filter(function (s) {
            return s.enabled !== false;
        }).length;
        OB.el('stepCount').textContent = state.projectId && state.environmentId
            ? '共 ' + state.steps.length + ' 步（启用 ' + enabledCount + ' 步）' : '';
        if (!state.projectId) {
            body.innerHTML = '<tr><td colspan="8" class="hint">请选择项目与环境后加载</td></tr>';
            return;
        }
        if (!state.steps.length) {
            body.innerHTML = '<tr><td colspan="8" class="hint">暂无步骤，填写下方表单并点击“新增步骤”</td></tr>';
            return;
        }
        body.innerHTML = state.steps.map(function (s) {
            const enabled = s.enabled === false
                ? '<span class="badge off">禁用</span>' : '<span class="badge ok">启用</span>';
            const toggle = s.enabled === false ? '启用' : '禁用';
            return '<tr><td>' + s.stepIndex + '</td>'
                + '<td>' + OB.escapeHtml(s.name || '') + '</td>'
                + '<td><span class="mono command-cell" data-command="' + s.stepIndex + '">'
                + OB.escapeHtml(s.command || '') + '</span></td>'
                + '<td class="mono">' + OB.escapeHtml(s.workdir || '.') + '</td>'
                + '<td>' + (s.timeoutSeconds || 0) + 's / ' + (s.pollIntervalSeconds || 0) + 's</td>'
                + '<td>' + enabled + '</td>'
                + '<td>' + OB.escapeHtml(s.updatedBy || s.createdBy || '系统') + '<br>'
                + OB.escapeHtml(OB.fmtTime(s.updatedAt || s.createdAt)) + '</td>'
                + '<td class="step-actions">'
                + '<button data-edit-step="' + s.stepIndex + '">编辑</button>'
                + '<button data-toggle-step="' + s.stepIndex + '">' + toggle + '</button>'
                + '<button data-test-step="' + s.stepIndex + '">测试</button>'
                + '<button data-up-step="' + s.stepIndex + '">上移</button>'
                + '<button data-down-step="' + s.stepIndex + '">下移</button>'
                + '<button class="danger" data-del-step="' + s.stepIndex + '">删除</button>'
                + '</td></tr>';
        }).join('');
        Array.from(body.querySelectorAll('[data-command]')).forEach(function (cell) {
            cell.addEventListener('click', function () {
                const index = Number(cell.getAttribute('data-command'));
                const step = state.steps.find(function (s) {
                    return s.stepIndex === index;
                });
                OB.openLogModal('命令详情 · 步骤 #' + index, [step ? (step.command || '') : '']);
            });
        });
        Array.from(body.querySelectorAll('[data-edit-step]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                startEditStep(Number(btn.getAttribute('data-edit-step')));
            });
        });
        Array.from(body.querySelectorAll('[data-toggle-step]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                toggleStep(Number(btn.getAttribute('data-toggle-step')));
            });
        });
        Array.from(body.querySelectorAll('[data-test-step]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                testStep(Number(btn.getAttribute('data-test-step')));
            });
        });
        Array.from(body.querySelectorAll('[data-up-step]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                moveStep(Number(btn.getAttribute('data-up-step')), -1);
            });
        });
        Array.from(body.querySelectorAll('[data-down-step]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                moveStep(Number(btn.getAttribute('data-down-step')), 1);
            });
        });
        Array.from(body.querySelectorAll('[data-del-step]')).forEach(function (btn) {
            btn.addEventListener('click', async function () {
                const index = Number(btn.getAttribute('data-del-step'));
                if (!(await OB.confirm('确认删除步骤 #' + index + '？', '删除步骤'))) {
                    return;
                }
                try {
                    await OB.api('/api/trigger-configs/' + state.configId + '/steps/' + index,
                        { method: 'DELETE' });
                    OB.toast('已删除');
                    await loadOverview();
                    await loadConfig(state.projectId, state.environmentId, false);
                } catch (e) {
                    OB.toast(e.message, true);
                }
            });
        });
    }

    async function toggleStep(index) {
        const step = state.steps.find(function (s) {
            return s.stepIndex === index;
        });
        if (!step) {
            return;
        }
        try {
            await OB.api('/api/trigger-configs/' + state.configId + '/steps/' + index, {
                method: 'PUT',
                body: JSON.stringify(stepPayload(step, step.enabled === false))
            });
            await loadOverview();
            await loadConfig(state.projectId, state.environmentId, false);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function moveStep(index, delta) {
        const ordered = state.steps.slice();
        const position = ordered.findIndex(function (s) {
            return s.stepIndex === index;
        });
        const target = position + delta;
        if (position < 0 || target < 0 || target >= ordered.length) {
            return;
        }
        const temp = ordered[position];
        ordered[position] = ordered[target];
        ordered[target] = temp;
        try {
            await OB.api('/api/trigger-configs/' + state.configId + '/steps/order', {
                method: 'PUT',
                body: JSON.stringify({ stepIds: ordered.map(function (s) { return s.id; }) })
            });
            OB.toast('顺序已更新');
            await loadConfig(state.projectId, state.environmentId, false);
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function testStep(index) {
        OB.el('stepMsg').textContent = '测试中…';
        OB.el('stepMsg').className = 'hint';
        try {
            const result = await OB.api('/api/trigger-configs/' + state.configId + '/steps/'
                + index + '/test', { method: 'POST' });
            OB.el('stepMsg').textContent = '结果: ' + (result.success ? '成功' : '失败')
                + ' 退出码=' + result.exitCode;
            OB.el('stepMsg').className = result.success ? 'success' : 'error';
            if (result.excerpt) {
                OB.openLogModal('步骤 #' + index + ' 测试输出', String(result.excerpt).split('\n'));
            }
        } catch (e) {
            OB.el('stepMsg').textContent = e.message;
            OB.el('stepMsg').className = 'error';
        }
    }

    async function loadTree(path) {
        if (!state.projectId) {
            state.tree = { available: false, path: '', parent: null, root: '', entries: [] };
            renderTree();
            return;
        }
        try {
            const query = path ? '?path=' + encodeURIComponent(path) : '';
            state.tree = await OB.api('/api/projects/' + state.projectId
                + '/workspace-tree' + query);
        } catch (e) {
            state.tree = {
                available: false, path: path || '', parent: null, root: '',
                entries: [], reason: e.message
            };
        }
        renderTree();
    }

    function renderTree() {
        const list = OB.el('treeList');
        const breadcrumb = OB.el('treeBreadcrumb');
        const message = OB.el('treeMsg');
        const tree = state.tree || {};
        if (!tree.available) {
            list.innerHTML = '';
            breadcrumb.textContent = '';
            message.textContent = tree.reason || '工作空间尚未初始化，请先执行一次部署';
            return;
        }
        const segments = (tree.path || '').split('/').filter(function (s) { return s; });
        let current = '';
        const crumbs = ['<a href="#" data-tree-path="">工作空间</a>'].concat(segments.map(function (seg) {
            current = current ? current + '/' + seg : seg;
            return '<a href="#" data-tree-path="' + current + '">' + OB.escapeHtml(seg) + '</a>';
        }));
        breadcrumb.innerHTML = crumbs.join(' / ');
        Array.from(breadcrumb.querySelectorAll('[data-tree-path]')).forEach(function (link) {
            link.addEventListener('click', function (event) {
                event.preventDefault();
                loadTree(link.getAttribute('data-tree-path'));
            });
        });
        if (!tree.entries || !tree.entries.length) {
            list.innerHTML = '<li class="hint">空目录</li>';
        } else {
            list.innerHTML = tree.entries.map(function (entry) {
                const cls = entry.type === 'DIR' ? 'dir' : 'file';
                const icon = entry.type === 'DIR' ? '📁' : '📄';
                return '<li class="tree-item ' + cls + '" data-tree-entry="'
                    + OB.escapeHtml(entry.path) + '" data-tree-type="' + entry.type + '">'
                    + icon + ' ' + OB.escapeHtml(entry.name) + '</li>';
            }).join('');
            Array.from(list.querySelectorAll('[data-tree-entry]')).forEach(function (item) {
                item.addEventListener('click', function () {
                    const path = item.getAttribute('data-tree-entry');
                    if (item.getAttribute('data-tree-type') === 'DIR') {
                        loadTree(path);
                    } else {
                        OB.copyText(path);
                    }
                });
            });
        }
        message.textContent = '相对路径: ' + (tree.path || '.') + '（点击文件可复制路径）';
    }

    function useTreeAsWorkdir() {
        if (!state.tree.available) {
            OB.toast('工作空间尚未初始化，请先执行一次部署', true);
            return;
        }
        OB.el('stWorkdir').value = state.tree.path || '.';
    }

    init();
})();
