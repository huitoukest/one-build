window.OB = (function () {
    const RETURN_TO_KEY = 'obReturnTo';

    function getCookie(name) {
        const match = document.cookie.match(new RegExp('(?:^|; )' + name.replace(/([.*+?^${}()|[\]\\])/g, '\\$1') + '=([^;]*)'));
        return match ? decodeURIComponent(match[1]) : '';
    }

    async function ensureCsrf() {
        try {
            await fetch('/api/auth/csrf', { credentials: 'same-origin' });
        } catch (e) {
            // ignore
        }
    }

    async function api(path, options) {
        const opts = options || {};
        const method = (opts.method || 'GET').toUpperCase();
        const headers = Object.assign({}, opts.headers || {});
        if (opts.body !== undefined && !headers['Content-Type']) {
            headers['Content-Type'] = 'application/json';
        }
        if (method !== 'GET' && method !== 'HEAD') {
            headers['X-XSRF-TOKEN'] = getCookie('XSRF-TOKEN');
        }
        const response = await fetch(path, Object.assign({}, opts, {
            method: method,
            headers: headers,
            credentials: 'same-origin'
        }));
        if (response.status === 401) {
            sessionStorage.setItem(RETURN_TO_KEY, location.pathname + location.hash);
            location.href = '/login.html';
            throw new Error('未登录');
        }
        const data = await response.json().catch(function () {
            return { code: response.status, message: response.statusText };
        });
        if (!response.ok || data.code !== 0) {
            const err = new Error(data.message || ('HTTP ' + response.status));
            err.code = data.code;
            err.data = data.data;
            throw err;
        }
        return data.data;
    }

    function el(id) {
        return document.getElementById(id);
    }

    function escapeHtml(value) {
        if (value === null || value === undefined) {
            return '';
        }
        return String(value).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function toast(message, isError) {
        let box = el('toast');
        if (!box) {
            box = document.createElement('div');
            box.id = 'toast';
            box.className = 'toast';
            document.body.appendChild(box);
        }
        box.textContent = message;
        box.className = 'toast show' + (isError ? ' toast-error' : '');
        clearTimeout(box._timer);
        box._timer = setTimeout(function () {
            box.className = 'toast';
        }, 4000);
    }

    function pad(value, size) {
        return String(value).padStart(size, '0');
    }

    function fmtTime(value) {
        if (!value) {
            return '-';
        }
        if (Array.isArray(value)) {
            return value.map(fmtTime).join('、');
        }
        const text = String(value);
        const match = text.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?/);
        if (!match) {
            return text;
        }
        const millis = match[7] ? match[7].padEnd(3, '0') : '000';
        return match[1] + '年' + Number(match[2]) + '月' + Number(match[3]) + '日-'
            + match[4] + '时' + match[5] + '分' + match[6] + '秒.' + millis + '毫秒';
    }

    function confirmModal(message, title) {
        return new Promise(function (resolve) {
            const overlay = document.createElement('div');
            overlay.className = 'modal-overlay';
            overlay.innerHTML = '<div class="modal-card">'
                + '<div class="modal-title">' + escapeHtml(title || '确认操作') + '</div>'
                + '<div class="modal-body">' + escapeHtml(message) + '</div>'
                + '<div class="modal-actions">'
                + '<button data-act="cancel">取消</button>'
                + '<button data-act="ok" class="danger">确定</button>'
                + '</div></div>';
            document.body.appendChild(overlay);
            overlay.querySelector('[data-act="cancel"]').addEventListener('click', function () {
                overlay.remove();
                resolve(false);
            });
            overlay.querySelector('[data-act="ok"]').addEventListener('click', function () {
                overlay.remove();
                resolve(true);
            });
        });
    }

    async function copyText(text) {
        try {
            await navigator.clipboard.writeText(text);
        } catch (e) {
            const area = document.createElement('textarea');
            area.value = text;
            document.body.appendChild(area);
            area.select();
            document.execCommand('copy');
            area.remove();
        }
        toast('已复制');
    }

    function copyUrl(hash) {
        const target = location.origin + location.pathname + (hash || location.hash || '');
        return copyText(target);
    }

    function progressModal() {
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay progress-overlay';
        overlay.innerHTML = '<div class="modal-card progress-card">'
            + '<div class="modal-title">执行中</div>'
            + '<div class="progress-track"><div class="progress-bar"></div></div>'
            + '<div class="progress-text" data-role="text">准备中…</div>'
            + '<div class="progress-count" data-role="count">0 / 0</div>'
            + '</div>';
        document.body.appendChild(overlay);
        const bar = overlay.querySelector('.progress-bar');
        const text = overlay.querySelector('[data-role="text"]');
        const count = overlay.querySelector('[data-role="count"]');
        return {
            update: function (done, total, label) {
                const safeTotal = total > 0 ? total : 1;
                const percent = Math.max(0, Math.min(100, Math.round(done * 100 / safeTotal)));
                bar.style.width = percent + '%';
                text.textContent = label || '执行中…';
                count.textContent = done + ' / ' + total;
            },
            close: function () {
                overlay.remove();
            }
        };
    }

    function highlightLog(text) {
        const escaped = escapeHtml(text);
        return escaped.replace(/\b(INFO|WARN|WARNING|ERROR|DEBUG|TRACE)\b/gi,
            '<span class="log-level">$1</span>');
    }

    function openLogModal(title, lines) {
        const overlay = document.createElement('div');
        overlay.className = 'modal-overlay';
        overlay.innerHTML = '<div class="modal-card log-card">'
            + '<div class="modal-title">' + escapeHtml(title) + '</div>'
            + '<pre class="log log-modal-body">' + highlightLog(lines.join('\n')) + '</pre>'
            + '<div class="modal-actions"><button data-act="close">关闭</button></div>'
            + '</div>';
        document.body.appendChild(overlay);
        const close = function () {
            overlay.remove();
        };
        overlay.querySelector('[data-act="close"]').addEventListener('click', close);
        overlay.addEventListener('click', function (event) {
            if (event.target === overlay) {
                close();
            }
        });
    }

    function route() {
        return location.hash.replace(/^#\/?/, '');
    }

    function setRoute(path, push) {
        const next = '#/' + String(path || '').replace(/^\/?/, '');
        if (location.hash === next) {
            return;
        }
        const url = location.pathname + location.search + next;
        if (push) {
            history.pushState(null, '', url);
        } else {
            history.replaceState(null, '', url);
        }
    }

    function ensureRoute(defaultPath) {
        if (!location.hash) {
            setRoute(defaultPath || '', false);
        }
    }

    function go(pathWithHash) {
        location.href = pathWithHash;
    }

    function query(name) {
        const queryPart = route().indexOf('?') >= 0 ? route().split('?')[1] : '';
        return new URLSearchParams(queryPart).get(name);
    }

    async function requireLogin() {
        await ensureCsrf();
        return api('/api/auth/me');
    }

    async function logout() {
        try {
            await api('/api/auth/logout', { method: 'POST' });
        } catch (e) {
            // ignore
        }
        location.href = '/login.html';
    }

    function returnTo() {
        return sessionStorage.getItem(RETURN_TO_KEY);
    }

    function clearReturnTo() {
        sessionStorage.removeItem(RETURN_TO_KEY);
    }

    return {
        getCookie: getCookie,
        ensureCsrf: ensureCsrf,
        api: api,
        el: el,
        escapeHtml: escapeHtml,
        toast: toast,
        fmtTime: fmtTime,
        confirm: confirmModal,
        copyText: copyText,
        copyUrl: copyUrl,
        progress: progressModal,
        highlightLog: highlightLog,
        openLogModal: openLogModal,
        route: route,
        setRoute: setRoute,
        ensureRoute: ensureRoute,
        go: go,
        query: query,
        requireLogin: requireLogin,
        logout: logout,
        returnTo: returnTo,
        clearReturnTo: clearReturnTo
    };
})();
