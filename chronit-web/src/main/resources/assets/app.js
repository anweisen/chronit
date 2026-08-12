/* chronit — dashboard behaviour.
 *
 * Deliberately small and dependency-free. The server renders the page; this only does three things
 * the server cannot:
 *
 *   1. Ticks the "next run in 21h 6m" labels locally, so staying current costs no requests at all.
 *   2. Polls a tiny JSON snapshot and patches the handful of values that change, rather than
 *      reloading the page. Polling stops entirely while the tab is hidden.
 *   3. Runs actions through fetch so a click gives immediate feedback instead of a blind redirect.
 *
 * When the run history changes, the *server-rendered* fragment is fetched and swapped in. That way
 * there is only ever one place that knows how a run looks.
 */
(() => {
  'use strict';

  const POLL_INTERVAL_MS = 6000;
  const TICK_INTERVAL_MS = 1000;

  /* ------------------------------------------------------------------ theme */

  const THEME_KEY = 'chronit-theme';

  function applyTheme(theme) {
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.setAttribute('data-theme', theme);
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  }

  function currentTheme() {
    const stored = localStorage.getItem(THEME_KEY);
    if (stored) return stored;
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  document.addEventListener('click', (event) => {
    const toggle = event.target.closest('[data-theme-toggle]');
    if (!toggle) return;
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    localStorage.setItem(THEME_KEY, next);
    applyTheme(next);
  });

  /* ------------------------------------------------------------------ time */

  const UNITS = [
    ['d', 86400],
    ['h', 3600],
    ['m', 60],
    ['s', 1],
  ];

  /** "2h 14m" — two units is enough to be useful without being noisy. */
  function humanDuration(totalSeconds) {
    const seconds = Math.max(0, Math.floor(totalSeconds));
    if (seconds < 1) return 'now';
    const parts = [];
    let rest = seconds;
    for (const [suffix, size] of UNITS) {
      const amount = Math.floor(rest / size);
      if (amount > 0) {
        parts.push(amount + suffix);
        rest -= amount * size;
      }
      if (parts.length === 2) break;
    }
    return parts.join(' ');
  }

  function relativeLabel(iso) {
    const then = Date.parse(iso);
    if (Number.isNaN(then)) return '';
    const deltaSeconds = (then - Date.now()) / 1000;
    return deltaSeconds >= 0
      ? 'in ' + humanDuration(deltaSeconds)
      : humanDuration(-deltaSeconds) + ' ago';
  }

  function tickTimes(root = document) {
    root.querySelectorAll('time[data-relative]').forEach((element) => {
      const label = relativeLabel(element.getAttribute('datetime'));
      if (label && element.textContent !== label) element.textContent = label;
    });
  }

  /* ------------------------------------------------------------------ toasts */

  function toast(message, kind = '') {
    const host = document.querySelector('[data-toasts]');
    if (!host) return;

    const element = document.createElement('div');
    element.className = 'toast' + (kind ? ' toast--' + kind : '');
    element.textContent = message;
    host.appendChild(element);

    setTimeout(() => {
      element.classList.add('toast--leaving');
      element.addEventListener('animationend', () => element.remove(), { once: true });
    }, 4200);
  }

  /* ------------------------------------------------------------------ requests */

  async function requestJson(url, options = {}) {
    const response = await fetch(url, {
      credentials: 'same-origin',
      headers: { 'Accept': 'application/json' },
      ...options,
    });
    if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    }
    return response.json();
  }

  /* ------------------------------------------------------------------ state */

  const liveIndicator = document.querySelector('[data-live]');
  let lastRunsVersion = Number(document.body.dataset.runsVersion || '0');
  let failures = 0;

  function setLiveState(state) {
    if (liveIndicator) liveIndicator.dataset.state = state;
  }

  function applyJobState(jobs) {
    jobs.forEach((job) => {
      const card = document.querySelector('[data-job="' + CSS.escape(job.id) + '"]');
      if (!card) return;

      card.classList.toggle('job--running', job.running);

      const badge = card.querySelector('[data-job-status]');
      if (badge) {
        badge.hidden = !job.running;
      }

      const next = card.querySelector('[data-job-next]');
      if (next && job.nextRun && next.getAttribute('datetime') !== job.nextRun) {
        next.setAttribute('datetime', job.nextRun);
        next.textContent = relativeLabel(job.nextRun);
      }
    });
  }

  function applyAccountState(accounts) {
    accounts.forEach((account) => {
      const card = document.querySelector('[data-account="' + CSS.escape(account.id) + '"]');
      if (!card) return;

      card.classList.toggle('account--attention', !account.usable);

      const chip = card.querySelector('[data-account-state]');
      if (chip) {
        chip.textContent = account.state;
        chip.className = 'chip ' + (account.usable ? 'chip--ok' : 'chip--warn');
      }
      const detail = card.querySelector('[data-account-detail]');
      if (detail && detail.textContent !== account.detail) {
        detail.textContent = account.detail;
      }
    });
  }

  async function refreshRuns() {
    const host = document.querySelector('[data-runs]');
    if (!host) return;
    const response = await fetch('fragments/runs', { credentials: 'same-origin' });
    if (!response.ok) return;
    host.innerHTML = await response.text();
    tickTimes(host);
  }

  async function poll() {
    try {
      const state = await requestJson('api/state');
      failures = 0;
      setLiveState('live');

      applyJobState(state.jobs || []);
      applyAccountState(state.accounts || []);

      const summary = document.querySelector('[data-stat-attention]');
      if (summary) summary.textContent = state.accountsNeedingLogin;

      if (state.runsVersion !== lastRunsVersion) {
        lastRunsVersion = state.runsVersion;
        await refreshRuns();
      }
    } catch (error) {
      failures += 1;
      // One dropped poll is nothing; a run of them means the daemon is gone.
      setLiveState(failures > 2 ? 'offline' : 'stale');
    }
  }

  /* ------------------------------------------------------------------ actions */

  document.addEventListener('click', async (event) => {
    const trigger = event.target.closest('[data-run-job]');
    if (!trigger) return;
    event.preventDefault();

    const jobId = trigger.dataset.runJob;
    trigger.disabled = true;
    const original = trigger.textContent;
    trigger.textContent = 'Starting…';

    try {
      const result = await requestJson('api/jobs/' + encodeURIComponent(jobId) + '/run',
        { method: 'POST' });
      toast(result.message || ('Started ' + jobId), result.ok ? 'ok' : 'bad');
      await poll();
    } catch (error) {
      toast('Could not start ' + jobId + ': ' + error.message, 'bad');
    } finally {
      trigger.disabled = false;
      trigger.textContent = original;
    }
  });

  document.addEventListener('click', async (event) => {
    const copy = event.target.closest('[data-copy]');
    if (!copy) return;
    event.preventDefault();
    try {
      await navigator.clipboard.writeText(copy.dataset.copy);
      toast('Code copied', 'ok');
    } catch (error) {
      toast('Could not copy — select the code manually', 'bad');
    }
  });

  /* ------------------------------------------------------------------ login */

  /** The page sits two segments deep, so the API sibling is reached from the root. */
  function loginApiUrl(accountId) {
    return '../../api/accounts/' + encodeURIComponent(accountId) + '/login';
  }

  async function pollLogin(accountId) {
    const host = document.querySelector('[data-login-state]');
    if (!host) return;

    try {
      const state = await requestJson(loginApiUrl(accountId));
      renderLogin(host, state, accountId);
      if (state.state === 'WAITING' || state.state === 'STARTING') {
        setTimeout(() => pollLogin(accountId), 2000);
      }
    } catch (error) {
      host.innerHTML = '';
      const message = document.createElement('p');
      message.className = 'login__lead';
      message.textContent = 'Lost contact with chronit: ' + error.message;
      host.appendChild(message);
    }
  }

  /** Built with DOM calls rather than innerHTML so server-provided text cannot become markup. */
  function renderLogin(host, state, accountId) {
    host.innerHTML = '';

    if (state.state === 'STARTING') {
      const spinner = document.createElement('div');
      spinner.className = 'spinner';
      const lead = document.createElement('p');
      lead.className = 'login__lead';
      lead.textContent = 'Requesting a code from Microsoft…';
      host.append(spinner, lead);
      return;
    }

    if (state.state === 'WAITING') {
      const lead = document.createElement('p');
      lead.className = 'login__lead';
      lead.textContent = 'Open the link below and enter this code.';

      const code = document.createElement('div');
      code.className = 'code';
      code.textContent = state.userCode;

      const actions = document.createElement('div');
      actions.style.display = 'flex';
      actions.style.gap = '0.5rem';
      actions.style.justifyContent = 'center';
      actions.style.flexWrap = 'wrap';

      const open = document.createElement('a');
      open.className = 'btn btn--primary';
      open.href = state.directVerificationUri || state.verificationUri;
      open.target = '_blank';
      open.rel = 'noopener noreferrer';
      open.textContent = 'Open Microsoft sign-in';

      const copy = document.createElement('button');
      copy.className = 'btn';
      copy.type = 'button';
      copy.dataset.copy = state.userCode;
      copy.textContent = 'Copy code';

      actions.append(open, copy);

      const note = document.createElement('p');
      note.className = 'login__lead faint';
      note.textContent = 'Waiting for you to finish. The code expires '
        + relativeLabel(state.expiresAt) + '.';

      host.append(lead, code, actions, note);
      return;
    }

    const result = document.createElement('p');
    result.className = 'login__lead';
    if (state.state === 'DONE') {
      result.textContent = 'Signed in. ' + (state.message || '');
      const back = document.createElement('a');
      back.className = 'btn btn--primary';
      back.href = '../../';
      back.textContent = 'Back to dashboard';
      host.append(result, back);
    } else {
      result.textContent = state.message || 'Sign-in failed.';
      const retry = document.createElement('button');
      retry.className = 'btn';
      retry.type = 'button';
      retry.textContent = 'Try again';
      retry.addEventListener('click', () => startLogin(accountId));
      host.append(result, retry);
    }
  }

  async function startLogin(accountId) {
    const host = document.querySelector('[data-login-state]');
    if (host) renderLogin(host, { state: 'STARTING' }, accountId);
    try {
      await requestJson(loginApiUrl(accountId), { method: 'POST' });
      pollLogin(accountId);
    } catch (error) {
      toast('Could not start sign-in: ' + error.message, 'bad');
    }
  }

  /* ------------------------------------------------------------------ start */

  applyTheme(localStorage.getItem(THEME_KEY));
  tickTimes();
  setInterval(tickTimes, TICK_INTERVAL_MS);

  const loginAccount = document.body.dataset.loginAccount;
  if (loginAccount) {
    document.querySelector('[data-start-login]')?.addEventListener('click', () => startLogin(loginAccount));
    pollLogin(loginAccount);
  }

  if (document.body.dataset.dashboard === 'true') {
    let timer = null;

    function schedulePoll() {
      clearInterval(timer);
      timer = setInterval(poll, POLL_INTERVAL_MS);
    }

    // A hidden tab has nobody looking at it; polling it just burns the daemon's time.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) {
        clearInterval(timer);
        timer = null;
      } else {
        poll();
        schedulePoll();
      }
    });

    poll();
    schedulePoll();
  }
})();
