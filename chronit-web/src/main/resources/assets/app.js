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

  /* ------------------------------------------------------------------ disclosures */

  const OPEN_KEY = 'chronit-open';

  function openSet() {
    try {
      return new Set(JSON.parse(localStorage.getItem(OPEN_KEY) || '[]'));
    } catch (error) {
      return new Set();
    }
  }

  /**
   * Remembers which sections are expanded.
   *
   * Without this, anyone watching one job has to re-open it after every content swap, which is
   * exactly the audience most likely to have it open in the first place.
   */
  function restoreDisclosures(root = document) {
    const open = openSet();
    root.querySelectorAll('details[data-remember]').forEach((element) => {
      element.open = open.has(element.dataset.remember);
    });
  }

  document.addEventListener('toggle', (event) => {
    const element = event.target;
    if (!(element instanceof HTMLDetailsElement) || !element.dataset.remember) return;
    const open = openSet();
    if (element.open) {
      open.add(element.dataset.remember);
    } else {
      open.delete(element.dataset.remember);
    }
    try {
      localStorage.setItem(OPEN_KEY, JSON.stringify([...open]));
    } catch (error) {
      /* Private browsing: remembering is a convenience, not a requirement. */
    }
  }, true);

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
    restoreDisclosures(host);
    tickTimes(host);
  }

  async function poll() {
    try {
      const state = await requestJson('api/state');
      failures = 0;
      setLiveState('live');

      applyJobState(state.jobs || []);
      applyAccountState(state.accounts || []);

      const heroNext = document.querySelector('[data-hero-next]');
      const soonest = (state.jobs || []).filter((j) => j.nextRun)
        .sort((a, b) => Date.parse(a.nextRun) - Date.parse(b.nextRun))[0];
      if (heroNext && soonest && heroNext.getAttribute('datetime') !== soonest.nextRun) {
        heroNext.setAttribute('datetime', soonest.nextRun);
        heroNext.textContent = relativeLabel(soonest.nextRun);
      }

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

    if (state.state === 'IDLE') {
      // Nothing in progress. Offer to begin — this is the state a freshly opened page is in, and
      // omitting it is what previously made the page announce a failure that had not happened.
      const lead = document.createElement('p');
      lead.className = 'login__lead';
      lead.textContent = 'Microsoft will show a short code to enter on any device with a browser.';

      const start = document.createElement('button');
      start.className = 'btn btn--primary btn--lg';
      start.type = 'button';
      start.dataset.startLogin = accountId;
      start.textContent = 'Start sign-in';

      host.append(lead, start);
      return;
    }

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
      lead.textContent = 'Enter this code on the Microsoft sign-in page.';

      const code = document.createElement('div');
      code.className = 'code';
      code.textContent = state.userCode;

      const actions = document.createElement('div');
      actions.className = 'login__actions';

      const open = document.createElement('a');
      open.className = 'btn btn--primary btn--lg';
      open.href = state.directVerificationUri || state.verificationUri;
      open.target = '_blank';
      open.rel = 'noopener noreferrer';
      open.textContent = 'Open Microsoft sign-in';

      const copy = document.createElement('button');
      copy.className = 'btn btn--lg';
      copy.type = 'button';
      copy.dataset.copy = state.userCode;
      copy.textContent = 'Copy code';

      actions.append(open, copy);

      const note = document.createElement('p');
      note.className = 'login__note';
      note.textContent = 'Waiting for you to finish. The code expires '
        + relativeLabel(state.expiresAt) + '.';

      host.append(lead, code, actions, note);
      return;
    }

    if (state.state === 'DONE') {
      const done = document.createElement('p');
      done.className = 'login__lead';
      done.textContent = 'Signed in.' + (state.message ? ' ' + state.message : '');
      const back = document.createElement('a');
      back.className = 'btn btn--primary btn--lg';
      back.href = '../../';
      back.textContent = 'Back to dashboard';
      host.append(done, back);
      return;
    }

    const failed = document.createElement('p');
    failed.className = 'login__lead';
    failed.textContent = state.message || 'Sign-in failed.';
    const retry = document.createElement('button');
    retry.className = 'btn btn--lg';
    retry.type = 'button';
    retry.dataset.startLogin = accountId;
    retry.textContent = 'Try again';
    host.append(failed, retry);
  }

  document.addEventListener('click', (event) => {
    const start = event.target.closest('[data-start-login]');
    if (start) {
      event.preventDefault();
      startLogin(start.dataset.startLogin);
    }
  });

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
  restoreDisclosures();
  tickTimes();
  setInterval(tickTimes, TICK_INTERVAL_MS);

  const loginAccount = document.body.dataset.loginAccount;
  if (loginAccount) {
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
