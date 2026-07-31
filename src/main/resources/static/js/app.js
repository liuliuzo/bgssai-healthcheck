/*
 * 看板前端脚本。
 *
 * 数据区完全由服务端 Thymeleaf 片段渲染，这里只负责三件事：
 *   1. 定时（或手动）拉取 /fragments/dashboard 并替换 DOM
 *   2. 在客户端做搜索与状态筛选
 *   3. 主题切换
 */
(function () {
    'use strict';

    var DASHBOARD_ID = 'dashboard';
    var THEME_KEY = 'bgssai.healthcheck.theme';
    var AUTO_KEY = 'bgssai.healthcheck.autoRefresh';

    var refreshSeconds = parseInt(document.body.dataset.refreshSeconds || '0', 10);
    var filterState = 'all';
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
            var matchesText = searchText === '' || (card.dataset.search || '').indexOf(searchText) !== -1;
            var show = matchesState && matchesText;
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

    // 单个应用的「重新检查」按钮：卡片会被整体替换，所以用事件委托
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
