(function () {
    const tabs = ['environments', 'projects', 'steps', 'recycle', 'credentials'];
    const state = {
        credentials: [],
        projects: [],
        environments: [],
        stepConfigs: [],
        editingEnvId: null,
        editingProjectId: null
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
        Array.from(document.querySelectorAll('.tabs button')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                switchTab(btn.getAttribute('data-tab'), true);
            });
        });
        OB.el('envSave').addEventListener('click', saveEnvironment);
        OB.el('envCancelEdit').addEventListener('click', resetEnvForm);
        OB.el('projSave').addEventListener('click', saveProject);
        OB.el('projValidate').addEventListener('click', validateProject);
        OB.el('projCancelEdit').addEventListener('click', resetProjectForm);
        OB.el('stepSearch').addEventListener('click', loadStepConfigs);
        OB.el('stepKeyword').addEventListener('keydown', function (event) {
            if (event.key === 'Enter') {
                loadStepConfigs();
            }
        });
        OB.el('credAdd').addEventListener('click', addCredential);
        window.addEventListener('hashchange', route);
        await reloadAll();
        await route();
    }

    async function route() {
        const raw = OB.route();
        const hash = raw || 'environments';
        OB.ensureRoute(hash);
        const tab = tabs.indexOf(hash) >= 0 ? hash : 'environments';
        await switchTab(tab, false);
    }

    async function switchTab(tab, updateHash) {
        if (updateHash) {
            OB.setRoute(tab, true);
        }
        Array.from(document.querySelectorAll('.tabs button')).forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-tab') === tab);
        });
        tabs.forEach(function (name) {
            OB.el('tab-' + name).style.display = name === tab ? '' : 'none';
        });
        if (tab === 'steps') {
            await loadStepConfigs();
        }
        if (tab === 'recycle') {
            await loadRecycle();
        }
    }

    async function reloadAll() {
        await Promise.all([loadCredentials(), loadEnvironments(), loadProjects()]);
        fillSelects();
    }

    async function loadCredentials() {
        state.credentials = await OB.api('/api/credentials');
        OB.el('credBody').innerHTML = state.credentials.map(function (c) {
            return '<tr><td>' + c.id + '</td><td>' + OB.escapeHtml(c.name) + '</td>'
                + '<td>' + OB.escapeHtml(c.authType) + '</td>'
                + '<td>' + OB.escapeHtml(c.username || '') + '</td>'
                + '<td>' + OB.escapeHtml(c.lastTestResult || '-') + '</td>'
                + '<td><button class="danger" data-del-cred="' + c.id + '">删除</button></td></tr>';
        }).join('') || '<tr><td colspan="6" class="hint">暂无数据</td></tr>';
        Array.from(document.querySelectorAll('[data-del-cred]')).forEach(function (btn) {
            btn.addEventListener('click', async function () {
                if (!(await OB.confirm('确认删除该凭证？被引用时会被拒绝。', '删除凭证'))) {
                    return;
                }
                await del('/api/credentials/' + btn.getAttribute('data-del-cred'));
                await reloadAll();
            });
        });
    }

    async function loadEnvironments() {
        state.environments = await OB.api('/api/environments');
        OB.el('envBody').innerHTML = state.environments.map(function (e) {
            const enabled = e.enabled === false ? '<span class="badge off">禁用</span>' : '<span class="badge ok">启用</span>';
            const toggle = e.enabled === false ? '启用' : '禁用';
            return '<tr><td>' + e.id + '</td><td>' + OB.escapeHtml(e.name) + '</td>'
                + '<td class="mono">' + OB.escapeHtml(e.envKey || '') + '</td>'
                + '<td>' + OB.escapeHtml(e.description || '') + '</td>'
                + '<td>' + (e.sortNo || 0) + '</td>'
                + '<td>' + enabled + '</td>'
                + '<td class="step-actions">'
                + '<button data-edit-env="' + e.id + '">编辑</button>'
                + '<button data-toggle-env="' + e.id + '">' + toggle + '</button>'
                + '<button class="danger" data-del-env="' + e.id + '">删除</button></td></tr>';
        }).join('') || '<tr><td colspan="7" class="hint">暂无数据</td></tr>';
        Array.from(document.querySelectorAll('[data-edit-env]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                startEditEnv(Number(btn.getAttribute('data-edit-env')));
            });
        });
        Array.from(document.querySelectorAll('[data-toggle-env]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                toggleEnvironment(Number(btn.getAttribute('data-toggle-env')));
            });
        });
        Array.from(document.querySelectorAll('[data-del-env]')).forEach(function (btn) {
            btn.addEventListener('click', async function () {
                if (!(await OB.confirm('删除后不可恢复；存在项目步骤配置引用时会被拒绝。确认删除该环境？', '删除环境'))) {
                    return;
                }
                await del('/api/environments/' + btn.getAttribute('data-del-env'));
                await reloadAll();
                fillStepEnvFilter();
            });
        });
        fillStepEnvFilter();
    }

    function fillStepEnvFilter() {
        const select = OB.el('stepEnvFilter');
        const current = select.value;
        select.innerHTML = '<option value="">全部环境</option>' + state.environments.map(function (e) {
            return '<option value="' + e.id + '">' + OB.escapeHtml(e.name) + '</option>';
        }).join('');
        select.value = current;
    }

    function startEditEnv(id) {
        const env = state.environments.find(function (e) {
            return e.id === id;
        });
        if (!env) {
            return;
        }
        state.editingEnvId = id;
        OB.el('envFormTitle').textContent = '编辑环境 #' + id;
        OB.el('envName').value = env.name || '';
        OB.el('envKey').value = env.envKey || '';
        OB.el('envDesc').value = env.description || '';
        OB.el('envSort').value = env.sortNo || 0;
        OB.el('envVars').value = envVarsText(env.envJson);
        OB.el('envSave').textContent = '保存';
        OB.el('envCancelEdit').style.display = '';
        OB.el('envMsg').textContent = '';
    }

    function envVarsText(json) {
        if (!json) {
            return '';
        }
        try {
            const obj = JSON.parse(json);
            return Object.keys(obj).map(function (k) {
                return k + '=' + obj[k];
            }).join('\n');
        } catch (e) {
            return '';
        }
    }

    function resetEnvForm() {
        state.editingEnvId = null;
        OB.el('envFormTitle').textContent = '新增环境';
        OB.el('envName').value = '';
        OB.el('envKey').value = '';
        OB.el('envDesc').value = '';
        OB.el('envSort').value = 0;
        OB.el('envVars').value = '';
        OB.el('envSave').textContent = '新增';
        OB.el('envCancelEdit').style.display = 'none';
        OB.el('envMsg').textContent = '';
    }

    async function saveEnvironment() {
        const payload = {
            name: OB.el('envName').value.trim(),
            envKey: OB.el('envKey').value.trim(),
            description: OB.el('envDesc').value.trim(),
            env: parseEnv(OB.el('envVars').value),
            sortNo: Number(OB.el('envSort').value || 0)
        };
        try {
            if (state.editingEnvId) {
                const current = state.environments.find(function (e) {
                    return e.id === state.editingEnvId;
                });
                payload.enabled = current ? current.enabled : true;
                payload.targetHost = current ? current.targetHost : null;
                payload.targetPort = current ? current.targetPort : null;
                payload.targetUser = current ? current.targetUser : null;
                payload.targetCredentialId = current ? current.targetCredentialId : null;
                payload.knownHostsPolicy = current ? current.knownHostsPolicy : null;
                await OB.api('/api/environments/' + state.editingEnvId, {
                    method: 'PUT', body: JSON.stringify(payload)
                });
                OB.toast('已保存环境');
            } else {
                await OB.api('/api/environments', { method: 'POST', body: JSON.stringify(payload) });
                OB.toast('已新增环境');
            }
            resetEnvForm();
            await reloadAll();
        } catch (e) {
            OB.el('envMsg').textContent = e.message;
            OB.el('envMsg').className = 'error';
            OB.toast(e.message, true);
        }
    }

    async function toggleEnvironment(id) {
        const env = state.environments.find(function (e) {
            return e.id === id;
        });
        if (!env) {
            return;
        }
        try {
            await OB.api('/api/environments/' + id, {
                method: 'PUT',
                body: JSON.stringify({
                    name: env.name,
                    envKey: env.envKey,
                    description: env.description,
                    env: parseEnv(envVarsText(env.envJson)),
                    sortNo: env.sortNo,
                    enabled: env.enabled === false,
                    targetHost: env.targetHost,
                    targetPort: env.targetPort,
                    targetUser: env.targetUser,
                    targetCredentialId: env.targetCredentialId,
                    knownHostsPolicy: env.knownHostsPolicy
                })
            });
            await reloadAll();
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function loadProjects() {
        const page = await OB.api('/api/projects?page=0&size=500');
        state.projects = page.items;
        OB.el('projBody').innerHTML = state.projects.map(function (p) {
            const enabled = p.enabled === false ? '<span class="badge off">禁用</span>' : '<span class="badge ok">启用</span>';
            const toggle = p.enabled === false ? '启用' : '禁用';
            return '<tr><td>' + p.id + '</td><td>' + OB.escapeHtml(p.name) + '</td>'
                + '<td class="mono">' + OB.escapeHtml(p.repoUrl) + '</td>'
                + '<td>' + OB.escapeHtml(repoTypeLabel(p.repoType)) + '</td>'
                + '<td>' + OB.escapeHtml(p.baseBranch) + '</td>'
                + '<td>' + enabled + '</td>'
                + '<td class="step-actions">'
                + '<button data-edit-proj="' + p.id + '">编辑</button>'
                + '<button data-toggle-proj="' + p.id + '">' + toggle + '</button>'
                + '<button data-steps-proj="' + p.id + '">步骤详情</button>'
                + '<button class="danger" data-del-proj="' + p.id + '">删除</button></td></tr>';
        }).join('') || '<tr><td colspan="7" class="hint">暂无数据</td></tr>';
        Array.from(document.querySelectorAll('[data-edit-proj]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                startEditProject(Number(btn.getAttribute('data-edit-proj')));
            });
        });
        Array.from(document.querySelectorAll('[data-toggle-proj]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                toggleProject(Number(btn.getAttribute('data-toggle-proj')));
            });
        });
        Array.from(document.querySelectorAll('[data-steps-proj]')).forEach(function (btn) {
            btn.addEventListener('click', function () {
                OB.go('/steps.html#/steps/' + btn.getAttribute('data-steps-proj'));
            });
        });
        Array.from(document.querySelectorAll('[data-del-proj]')).forEach(function (btn) {
            btn.addEventListener('click', async function () {
                if (!(await OB.confirm('确认删除该项目？将进入回收站，30 天内可恢复。', '删除项目'))) {
                    return;
                }
                await del('/api/projects/' + btn.getAttribute('data-del-proj'));
                await reloadAll();
            });
        });
    }

    function repoTypeLabel(type) {
        return { FILE_PATH: '文件路径', GIT_HTTP: 'Git HTTP（预留）', GIT_SSH: 'Git SSH（预留）' }[type] || type || '-';
    }

    function startEditProject(id) {
        const project = state.projects.find(function (p) {
            return p.id === id;
        });
        if (!project) {
            return;
        }
        state.editingProjectId = id;
        OB.el('projFormTitle').textContent = '编辑项目 #' + id;
        OB.el('projName').value = project.name || '';
        OB.el('projRepo').value = project.repoUrl || '';
        OB.el('projRepoType').value = project.repoType || 'FILE_PATH';
        OB.el('projCredential').value = project.credentialId || '';
        OB.el('projBase').value = project.baseBranch || 'master';
        OB.el('projSave').textContent = '保存';
        OB.el('projCancelEdit').style.display = '';
        OB.el('projMsg').textContent = '';
    }

    function resetProjectForm() {
        state.editingProjectId = null;
        OB.el('projFormTitle').textContent = '新增项目';
        OB.el('projName').value = '';
        OB.el('projRepo').value = '';
        OB.el('projRepoType').value = 'FILE_PATH';
        OB.el('projCredential').value = '';
        OB.el('projBase').value = 'master';
        OB.el('projSave').textContent = '新增';
        OB.el('projCancelEdit').style.display = 'none';
        OB.el('projMsg').textContent = '';
    }

    async function saveProject() {
        const payload = {
            name: OB.el('projName').value.trim(),
            repoUrl: OB.el('projRepo').value.trim(),
            repoType: OB.el('projRepoType').value,
            credentialId: OB.el('projCredential').value ? Number(OB.el('projCredential').value) : null,
            baseBranch: OB.el('projBase').value.trim() || 'master'
        };
        try {
            if (state.editingProjectId) {
                await OB.api('/api/projects/' + state.editingProjectId, {
                    method: 'PUT', body: JSON.stringify(payload)
                });
                OB.toast('已保存项目');
            } else {
                await OB.api('/api/projects', { method: 'POST', body: JSON.stringify(payload) });
                OB.toast('已新增项目');
            }
            resetProjectForm();
            await reloadAll();
        } catch (e) {
            OB.el('projMsg').textContent = e.message;
            OB.el('projMsg').className = 'error';
            OB.toast(e.message, true);
        }
    }

    async function validateProject() {
        try {
            const result = await OB.api('/api/projects/validate', {
                method: 'POST',
                body: JSON.stringify({ repoUrl: OB.el('projRepo').value.trim() })
            });
            OB.el('projMsg').textContent = result.message;
            OB.el('projMsg').className = 'success';
        } catch (e) {
            OB.el('projMsg').textContent = e.message;
            OB.el('projMsg').className = 'error';
        }
    }

    async function toggleProject(id) {
        const project = state.projects.find(function (p) {
            return p.id === id;
        });
        if (!project) {
            return;
        }
        try {
            await OB.api('/api/projects/' + id + '/enabled', {
                method: 'PUT',
                body: JSON.stringify({ enabled: project.enabled === false })
            });
            await reloadAll();
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function loadStepConfigs() {
        const environmentId = OB.el('stepEnvFilter').value;
        const keyword = OB.el('stepKeyword').value.trim();
        let url = '/api/trigger-configs/list?';
        if (environmentId) {
            url += 'environmentId=' + environmentId + '&';
        }
        if (keyword) {
            url += 'keyword=' + encodeURIComponent(keyword) + '&';
        }
        try {
            state.stepConfigs = await OB.api(url);
            OB.el('stepConfigBody').innerHTML = state.stepConfigs.map(function (c) {
                const enabled = c.enabled === false
                    ? '<span class="badge off">禁用</span>' : '<span class="badge ok">启用</span>';
                return '<tr><td>' + OB.escapeHtml(c.projectName) + '</td>'
                    + '<td>' + OB.escapeHtml(c.environmentName) + '</td>'
                    + '<td>' + enabled + '</td>'
                    + '<td>' + (c.stepCount || 0) + '</td>'
                    + '<td>' + OB.escapeHtml(OB.fmtTime(c.updatedAt)) + '</td>'
                    + '<td class="step-actions">'
                    + '<button data-step-detail="' + c.id + '" data-project="' + c.projectId
                    + '" data-env="' + c.environmentId + '">步骤详情</button>'
                    + '<button data-copy-config="' + c.id + '" data-env="' + c.environmentId + '">复制配置</button>'
                    + '</td></tr>';
            }).join('') || '<tr><td colspan="6" class="hint">暂无配置</td></tr>';
            Array.from(document.querySelectorAll('[data-step-detail]')).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    OB.go('/steps.html#/steps/' + btn.getAttribute('data-project')
                        + '/' + btn.getAttribute('data-env'));
                });
            });
            Array.from(document.querySelectorAll('[data-copy-config]')).forEach(function (btn) {
                btn.addEventListener('click', function () {
                    copyConfig(Number(btn.getAttribute('data-copy-config')),
                        Number(btn.getAttribute('data-env')));
                });
            });
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function copyConfig(configId, sourceEnvId) {
        const candidates = state.environments.filter(function (e) {
            return e.enabled !== false;
        });
        if (!candidates.length) {
            OB.toast('暂无可用环境', true);
            return;
        }
        const target = await pickEnvironment('复制配置到环境', candidates, sourceEnvId);
        if (!target) {
            return;
        }
        try {
            await OB.api('/api/trigger-configs/' + configId + '/copy', {
                method: 'POST',
                body: JSON.stringify({ targetEnvironmentId: target })
            });
            OB.toast('复制成功');
            await loadStepConfigs();
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function pickEnvironment(title, environments, excludeId) {
        return new Promise(function (resolve) {
            const options = environments.filter(function (e) {
                return e.id !== excludeId;
            }).map(function (e) {
                return '<option value="' + e.id + '">' + OB.escapeHtml(e.name)
                    + ' (' + OB.escapeHtml(e.envKey || '') + ')</option>';
            }).join('');
            const overlay = document.createElement('div');
            overlay.className = 'modal-overlay';
            overlay.innerHTML = '<div class="modal-card">'
                + '<div class="modal-title">' + OB.escapeHtml(title) + '</div>'
                + '<select id="copyTargetEnv" style="width:100%">' + options + '</select>'
                + '<div class="modal-actions" style="margin-top:14px">'
                + '<button data-act="cancel">取消</button>'
                + '<button data-act="ok" class="primary">确定</button></div></div>';
            document.body.appendChild(overlay);
            overlay.querySelector('[data-act="cancel"]').addEventListener('click', function () {
                overlay.remove();
                resolve(null);
            });
            overlay.querySelector('[data-act="ok"]').addEventListener('click', function () {
                const value = Number(overlay.querySelector('#copyTargetEnv').value);
                overlay.remove();
                resolve(value || null);
            });
        });
    }

    async function loadRecycle() {
        try {
            const items = await OB.api('/api/projects/recycle');
            OB.el('recycleBody').innerHTML = items.map(function (p) {
                return '<tr><td>' + p.id + '</td><td>' + OB.escapeHtml(p.name) + '</td>'
                    + '<td class="mono">' + OB.escapeHtml(p.repoUrl) + '</td>'
                    + '<td>' + OB.escapeHtml(OB.fmtTime(p.deletedAt)) + '</td>'
                    + '<td class="step-actions">'
                    + '<button data-restore="' + p.id + '">恢复</button>'
                    + '<button class="danger" data-purge="' + p.id + '">彻底删除</button></td></tr>';
            }).join('') || '<tr><td colspan="5" class="hint">回收站为空</td></tr>';
            Array.from(document.querySelectorAll('[data-restore]')).forEach(function (btn) {
                btn.addEventListener('click', async function () {
                    if (!(await OB.confirm('确认恢复该项目及其步骤配置？', '恢复项目'))) {
                        return;
                    }
                    try {
                        await OB.api('/api/projects/' + btn.getAttribute('data-restore') + '/restore',
                            { method: 'POST' });
                        OB.toast('已恢复');
                        await loadRecycle();
                        await reloadAll();
                    } catch (e) {
                        OB.toast(e.message, true);
                    }
                });
            });
            Array.from(document.querySelectorAll('[data-purge]')).forEach(function (btn) {
                btn.addEventListener('click', async function () {
                    if (!(await OB.confirm('彻底删除后不可恢复，确认继续？', '彻底删除项目'))) {
                        return;
                    }
                    try {
                        await OB.api('/api/projects/' + btn.getAttribute('data-purge') + '/purge',
                            { method: 'DELETE' });
                        OB.toast('已彻底删除');
                        await loadRecycle();
                    } catch (e) {
                        OB.toast(e.message, true);
                    }
                });
            });
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    function fillSelects() {
        OB.el('projCredential').innerHTML = '<option value="">无</option>'
            + state.credentials.map(function (c) {
                return '<option value="' + c.id + '">' + OB.escapeHtml(c.name) + '</option>';
            }).join('');
        fillStepEnvFilter();
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

    async function addCredential() {
        try {
            await OB.api('/api/credentials', {
                method: 'POST',
                body: JSON.stringify({
                    name: OB.el('credName').value.trim(),
                    authType: OB.el('credType').value,
                    username: OB.el('credUsername').value.trim(),
                    secret: OB.el('credSecret').value
                })
            });
            OB.el('credName').value = '';
            OB.el('credUsername').value = '';
            OB.el('credSecret').value = '';
            await reloadAll();
            OB.toast('已新增凭证');
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    async function del(path) {
        try {
            await OB.api(path, { method: 'DELETE' });
            OB.toast('已删除');
        } catch (e) {
            OB.toast(e.message, true);
        }
    }

    init();
})();
