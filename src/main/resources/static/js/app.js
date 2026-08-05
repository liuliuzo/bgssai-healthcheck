/*
 * 看板前端脚本。
 *
 * 数据区完全由服务端 Thymeleaf 片段渲染，这里只负责三件事：
 *   1. 定时（或手动）拉取 /fragments/dashboard 并替换 DOM
 *   2. 在客户端做搜索、状态与类型筛选
 *   3. 拉取 /fragments/apps/{id}/detail 塞进详情弹窗
 *   4. 主题切换
 *
 * 弹窗内容刻意不跟着定时刷新走：排障时看的是「刚才那一刻对端返回了什么」，
 * 内容在眼前被换掉只会让人分不清看的是哪一次探测。
 */
(function () {
    'use strict';

    var DASHBOARD_ID = 'dashboard';
    var THEME_KEY = 'bgssai.healthcheck.theme';
    var AUTO_KEY = 'bgssai.healthcheck.autoRefresh';

    var refreshSeconds = parseInt(document.body.dataset.refreshSeconds || '0', 10);
    var filterState = 'all';
    var filterType = 'all';
    var searchText = '';
    var remaining = refreshSeconds;
    var timerId = null;
    var inFlight = false;

    /* ---------- 主题 ---------- */

    function applyTheme(theme) {
        if (theme === 'light' || theme === 'dark') {
            document.documentElement.setAttribute('data-theme', theme);
        } else {
            document.documentElement.removeAttribute('data-theme');
        }
    }

    function currentTheme() {
        var stored = null;
        try {
            stored = localStorage.getItem(THEME_KEY);
        } catch (ignored) {
            /* 隐私模式下 localStorage 可能不可用 */
        }
        return stored;
    }

    function toggleTheme() {
        var prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
        var active = document.documentElement.getAttribute('data-theme') || (prefersDark ? 'dark' : 'light');
        var next = active === 'dark' ? 'light' : 'dark';
        applyTheme(next);
        try {
            localStorage.setItem(THEME_KEY, next);
        } catch (ignored) {
            /* 忽略写入失败 */
        }
    }

    applyTheme(currentTheme());

    /* ---------- 筛选 ---------- */

    function applyFilters() {
        var cards = document.querySelectorAll('.card');
        var visible = 0;
        cards.forEach(function (card) {
            var matchesState = filterState === 'all' || card.dataset.state === filterState;
            var matchesType = filterType === 'all' || card.dataset.type === filterType;
            var matchesText = searchText === '' || (card.dataset.search || '').indexOf(searchText) !== -1;
            var show = matchesState && matchesType && matchesText;
            card.hidden = !show;
            if (show) {
                visible++;
            }
        });

        // 组内全部被筛掉时隐藏整个分组
        document.querySelectorAll('.group').forEach(function (group) {
            var anyVisible = group.querySelector('.card:not([hidden])') !== null;
            group.hidden = !anyVisible;
        });

        var noMatch = document.getElementById('no-match');
        if (noMatch) {
            noMatch.hidden = !(cards.length > 0 && visible === 0);
        }
    }

    /* ---------- 数据刷新 ---------- */

    function swapDashboard(html) {
        var current = document.getElementById(DASHBOARD_ID);
        if (!current) {
            return;
        }
        var holder = document.createElement('div');
        holder.innerHTML = html.trim();
        var next = holder.firstElementChild;
        if (next) {
            current.replaceWith(next);
            applyFilters();
        }
    }

    function load(url, method) {
        if (inFlight) {
            return Promise.resolve();
        }
        inFlight = true;
        document.body.classList.add('is-loading');
        return fetch(url, {
            method: method || 'GET',
            headers: {'Accept': 'text/html'},
            cache: 'no-store'
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response.text();
        }).then(swapDashboard).catch(function (error) {
            // 巡检平台自身请求失败时不清空页面，保留上一次的结果
            console.warn('刷新看板失败：', error);
        }).finally(function () {
            inFlight = false;
            document.body.classList.remove('is-loading');
            remaining = refreshSeconds;
            renderCountdown();
        });
    }

    function renderCountdown() {
        var el = document.getElementById('countdown');
        if (!el) {
            return;
        }
        if (refreshSeconds <= 0) {
            el.textContent = '';
        } else if (!autoRefreshEnabled()) {
            el.textContent = '已暂停';
        } else {
            el.textContent = remaining + ' 秒后刷新';
        }
    }

    function autoRefreshEnabled() {
        var toggle = document.getElementById('auto-refresh');
        return toggle ? toggle.checked : false;
    }

    function tick() {
        if (!autoRefreshEnabled() || refreshSeconds <= 0 || document.hidden) {
            renderCountdown();
            return;
        }
        remaining--;
        if (remaining <= 0) {
            load('/fragments/dashboard');
        } else {
            renderCountdown();
        }
    }

    function startTimer() {
        if (timerId === null && refreshSeconds > 0) {
            timerId = window.setInterval(tick, 1000);
        }
    }

    /* ---------- 事件绑定 ---------- */

    var searchInput = document.getElementById('search');
    if (searchInput) {
        searchInput.addEventListener('input', function () {
            searchText = searchInput.value.trim().toLowerCase();
            applyFilters();
        });
    }

    var filters = document.getElementById('state-filters');
    if (filters) {
        filters.addEventListener('click', function (event) {
            var chip = event.target.closest('.chip');
            if (!chip) {
                return;
            }
            filters.querySelectorAll('.chip').forEach(function (item) {
                item.classList.toggle('is-active', item === chip);
            });
            filterState = chip.dataset.state;
            applyFilters();
        });
    }

    var typeFilters = document.getElementById('type-filters');
    if (typeFilters) {
        typeFilters.addEventListener('click', function (event) {
            var chip = event.target.closest('.chip');
            if (!chip) {
                return;
            }
            typeFilters.querySelectorAll('.chip').forEach(function (item) {
                item.classList.toggle('is-active', item === chip);
            });
            filterType = chip.dataset.type;
            applyFilters();
        });
    }

    var refreshNow = document.getElementById('refresh-now');
    if (refreshNow) {
        refreshNow.addEventListener('click', function () {
            refreshNow.disabled = true;
            load('/fragments/dashboard/refresh', 'POST').finally(function () {
                refreshNow.disabled = false;
            });
        });
    }

    var themeToggle = document.getElementById('theme-toggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', toggleTheme);
    }

    var autoToggle = document.getElementById('auto-refresh');
    if (autoToggle) {
        try {
            autoToggle.checked = localStorage.getItem(AUTO_KEY) !== 'off';
        } catch (ignored) {
            /* 忽略读取失败 */
        }
        autoToggle.addEventListener('change', function () {
            try {
                localStorage.setItem(AUTO_KEY, autoToggle.checked ? 'on' : 'off');
            } catch (ignored) {
                /* 忽略写入失败 */
            }
            remaining = refreshSeconds;
            renderCountdown();
        });
    }

    /* ---------- 详情弹窗 ---------- */

    function detailDialog() {
        return document.getElementById('detail-dialog');
    }

    function openDetail(id) {
        var dialog = detailDialog();
        var body = document.getElementById('detail-body');
        if (!dialog || !body) {
            return;
        }
        body.textContent = '加载中…';
        if (!dialog.open) {
            dialog.showModal();
        }
        // 片段已由服务端用 th:text 转义过，这里只负责把它挂上去
        fetch('/fragments/apps/' + encodeURIComponent(id) + '/detail', {
            headers: {'Accept': 'text/html'},
            cache: 'no-store'
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }
            return response.text();
        }).then(function (html) {
            body.innerHTML = html;
        }).catch(function (error) {
            body.textContent = '加载详情失败：' + error.message;
        });
    }

    document.addEventListener('click', function (event) {
        var trigger = event.target.closest('button[data-detail-id]');
        if (trigger) {
            openDetail(trigger.dataset.detailId);
            return;
        }
        var closer = event.target.closest('#detail-close');
        if (closer) {
            var dialog = detailDialog();
            if (dialog && dialog.open) {
                dialog.close();
            }
            return;
        }
        // 点到 dialog 元素本身即点在遮罩上，内容区是它的子元素
        if (event.target === detailDialog()) {
            detailDialog().close();
        }
    });

    // 单个目标的「重新检查」按钮：卡片会被整体替换，所以用事件委托
    document.addEventListener('click', function (event) {
        var button = event.target.closest('button[data-app-id]');
        if (!button) {
            return;
        }
        button.disabled = true;
        fetch('/api/apps/' + encodeURIComponent(button.dataset.appId) + '/refresh', {
            method: 'POST',
            headers: {'Accept': 'application/json'}
        }).then(function () {
            return load('/fragments/dashboard');
        }).catch(function (error) {
            console.warn('重新检查失败：', error);
        }).finally(function () {
            button.disabled = false;
        });
    });

    document.addEventListener('visibilitychange', function () {
        if (!document.hidden && autoRefreshEnabled() && refreshSeconds > 0) {
            load('/fragments/dashboard');
        }
    });

    applyFilters();
    renderCountdown();
    startTimer();
})();
