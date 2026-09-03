/* chronit — console behaviour.
 *
 * Dependency-free, and deliberately small. The server renders the page; this does four things the
 * server cannot:
 *
 *   1. Holds one event stream open and applies what arrives. Nothing is polled: a job reaching the
 *      world, or being stopped, shows up in the moment it happens.
 *   2. Ticks the "in 21h 6m" labels locally, so staying current costs no requests at all.
 *   3. Runs actions through fetch, so a click gives immediate feedback rather than a blind
 *      redirect — and then says nothing about the result, because the stream will.
 *   4. Says, in the top bar, whether the stream is actually connected. On a page that no longer
 *      polls that is the one thing a reader cannot otherwise tell.
 *
 * Anything with a shape — a run, the summary at the top, a status mark — arrives as markup the
 * server rendered. There is no second description of the design language in here.
 */
(() => {
  'use strict';

  const TICK_INTERVAL_MS = 1000;
  const RECONNECT_BASE_MS = 1000;
  const RECONNECT_MAX_MS = 15000;

  const loginAccount = document.body.dataset.loginAccount;
  /** The sign-in page sits two segments deep; everything else is at the root. */
  const ROOT = loginAccount ? '../../' : './';

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
    let stored = null;
    try {
      stored = localStorage.getItem(THEME_KEY);
    } catch (error) {
      /* Private browsing. The preference is a convenience, not a requirement. */
    }
    if (stored) return stored;
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  document.addEventListener('click', (event) => {
    const toggle = event.target.closest('[data-theme-toggle]');
    if (!toggle) return;
    const next = currentTheme() === 'dark' ? 'light' : 'dark';
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch (error) {
      /* Ignored, as above. */
    }
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

  function labelFor(element) {
    const iso = element.getAttribute('datetime');
    // An elapsed clock reads "20s", not "20s ago": the label beside it already says "Running for".
    // A bare one is a headline number with its own words around it, so it takes no preposition.
    if (element.hasAttribute('data-elapsed')) {
      return humanDuration((Date.now() - Date.parse(iso)) / 1000);
    }
    if (element.hasAttribute('data-bare')) {
      return humanDuration((Date.parse(iso) - Date.now()) / 1000);
    }
    return relativeLabel(iso);
  }

  function tickTimes(root = document) {
    root.querySelectorAll('time[data-relative]').forEach((element) => {
      const label = labelFor(element);
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
      const error = new Error('HTTP ' + response.status);
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  /* ------------------------------------------------------------------ live stream */

  const linkState = document.querySelector('[data-live]');
  const linkWord = document.querySelector('[data-live-word]');
  let source = null;
  let attempts = 0;
  let reconnectTimer = null;

  function setLink(state) {
    if (linkState) linkState.dataset.state = state;
    if (linkWord) linkWord.textContent = state;
  }

  function eventsUrl() {
    // The account is passed so the very first frame already carries the sign-in state, rather
    // than the page showing a start button for a flow that is halfway through.
    return ROOT + 'events'
      + (loginAccount ? '?login=' + encodeURIComponent(loginAccount) : '');
  }

  function connect() {
    clearTimeout(reconnectTimer);
    setLink(attempts === 0 ? 'connecting' : 'reconnecting');

    source = new EventSource(eventsUrl());

    source.addEventListener('open', () => {
      attempts = 0;
      setLink('live');
    });

    source.addEventListener('state', (event) => {
      applyState(JSON.parse(event.data));
    });

    source.addEventListener('overview', (event) => {
      swap('[data-overview]', event.data);
    });

    source.addEventListener('runs', (event) => {
      swap('[data-runs]', event.data);
    });

    source.addEventListener('login', (event) => {
      const state = JSON.parse(event.data);
      const host = document.querySelector('[data-login-state]');
      if (host && (!loginAccount || state.account === loginAccount)) {
        renderLogin(host, state, state.account || loginAccount);
      }
    });

    source.addEventListener('error', () => {
      // A browser retrying on its own leaves the stream CONNECTING; it only reaches CLOSED when it
      // has given up, which is what an HTTP error status does. That is the case worth handling,
      // because the likeliest cause is a session that has expired.
      if (source && source.readyState === EventSource.CONNECTING) {
        setLink('reconnecting');
        return;
      }
      setLink('offline');
      if (source) source.close();
      source = null;
      scheduleReconnect();
    });
  }

  function scheduleReconnect() {
    attempts += 1;
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** (attempts - 1), RECONNECT_MAX_MS);
    reconnectTimer = setTimeout(async () => {
      // Ask a plain endpoint what happened. A 401 means the cookie is no longer good, and the
      // honest thing is to send the reader to the sign-in page rather than blink "offline" at
      // them for ever.
      try {
        await requestJson(ROOT + 'api/state');
      } catch (error) {
        if (error.status === 401) {
          location.reload();
          return;
        }
      }
      connect();
    }, delay);
  }

  /** Replaces a server-rendered region, keeping the disclosures the reader had open. */
  function swap(selector, html) {
    const host = document.querySelector(selector);
    if (!host || host.innerHTML === html) return;
    host.innerHTML = html;
    restoreDisclosures(host);
    tickTimes(host);
    enter(host);
  }

  /**
   * Plays the entrance animation on content that was just replaced.
   *
   * Removing the class and reading a layout property forces the browser to finish the old
   * animation before the new one is attached; without that read, adding a class that is already
   * there does nothing at all and the second swap in a row appears instantly.
   */
  function enter(element) {
    element.classList.remove('is-entering');
    void element.offsetWidth;
    element.classList.add('is-entering');
  }

  /* ------------------------------------------------------------------ applying state */

  function applyState(state) {
    (state.jobs || []).forEach(applyJob);
    (state.accounts || []).forEach(applyAccount);
  }

  function applyJob(job) {
    const card = document.querySelector('[data-job="' + CSS.escape(job.id) + '"]');
    if (!card) return;

    card.classList.toggle('is-running', job.running);

    const rail = card.querySelector('[data-job-rail]');
    if (rail && job.railClass) rail.className = 'row__rail rail ' + job.railClass;

    const status = card.querySelector('[data-job-status]');
    if (status && job.statusHtml && status.innerHTML !== job.statusHtml) {
      status.innerHTML = job.statusHtml;
      enter(status);
    }

    // Run and Stop share one slot: whichever applies is the one shown, and the other fades out of
    // it rather than being removed, so the row does not resize under the pointer.
    const run = card.querySelector('[data-when="idle"]');
    const cancel = card.querySelector('[data-when="running"]');
    if (run) run.classList.toggle('is-away', job.running);
    if (cancel) {
      cancel.classList.toggle('is-away', !job.running);
      cancel.disabled = !!job.cancelling;
      const label = cancel.querySelector('span');
      if (label) label.textContent = job.cancelling ? 'Stopping…' : 'Stop';
    }

    applyLiveLine(card, job);

    // While running, the datum counts up from the start; otherwise it counts down to the next
    // fire time. Retarget whichever one is in the DOM.
    const elapsed = card.querySelector('[data-job-elapsed]');
    if (elapsed && job.startedAt && elapsed.getAttribute('datetime') !== job.startedAt) {
      elapsed.setAttribute('datetime', job.startedAt);
      elapsed.textContent = humanDuration((Date.now() - Date.parse(job.startedAt)) / 1000);
    }
    const next = card.querySelector('[data-job-next]');
    if (next && job.nextRun && next.getAttribute('datetime') !== job.nextRun) {
      next.setAttribute('datetime', job.nextRun);
      next.textContent = relativeLabel(job.nextRun);
    }
  }

  function applyLiveLine(card, job) {
    const line = card.querySelector('[data-job-live]');
    if (!line) return;
    // A reveal, so it opens and closes its own height rather than blinking into existence.
    line.classList.toggle('is-shown', job.running);
    if (!job.running) return;

    const total = job.visitCount || 0;
    const index = job.visitIndex || 0;
    const fill = line.querySelector('.progress__fill');
    if (fill) {
      const percent = total > 0 ? Math.min(100, Math.max(0, ((index - 1) / total) * 100)) : 0;
      fill.style.width = percent + '%';
    }
    const bar = line.querySelector('[data-progress]');
    if (bar) {
      bar.setAttribute('aria-valuemax', String(total));
      bar.setAttribute('aria-valuenow', String(Math.max(index - 1, 0)));
    }

    const step = line.querySelector('[data-live-step]');
    if (step) {
      const attempt = job.attempt > 1 ? ', attempt ' + job.attempt : '';
      step.textContent = total > 0
        ? 'visit ' + Math.max(index, 1) + ' of ' + total + attempt
        : '';
    }
    const where = line.querySelector('[data-live-where]');
    if (where) {
      where.textContent = job.currentServer || '';
    }
  }

  function applyAccount(account) {
    const card = document.querySelector('[data-account="' + CSS.escape(account.id) + '"]');
    if (!card) return;

    card.classList.toggle('is-attention', !account.usable);

    const rail = card.querySelector('[data-account-rail]');
    if (rail && account.railClass) rail.className = 'row__rail rail ' + account.railClass;

    const status = card.querySelector('[data-account-status]');
    if (status && account.statusHtml && status.innerHTML !== account.statusHtml) {
      status.innerHTML = account.statusHtml;
      enter(status);
    }
    const detail = card.querySelector('[data-account-detail]');
    if (detail && detail.textContent !== account.detail) {
      detail.textContent = account.detail || '';
    }
    const username = card.querySelector('[data-account-username]');
    if (username) {
      const name = account.username || '—';
      if (username.textContent !== name) username.textContent = name;
    }
  }

  /* ------------------------------------------------------------------ actions */

  document.addEventListener('click', async (event) => {
    const trigger = event.target.closest('[data-run-job]');
    if (!trigger) return;
    event.preventDefault();

    const jobId = trigger.dataset.runJob;
    const label = trigger.querySelector('span');
    const original = label ? label.textContent : '';
    trigger.disabled = true;
    if (label) label.textContent = 'Starting…';

    try {
      const result = await requestJson(ROOT + 'api/jobs/' + encodeURIComponent(jobId) + '/run',
        { method: 'POST' });
      toast(result.message || ('Started ' + jobId), result.ok ? 'ok' : 'bad');
    } catch (error) {
      toast('Could not start ' + jobId + ': ' + error.message, 'bad');
    } finally {
      trigger.disabled = false;
      if (label) label.textContent = original;
    }
    // No refresh here on purpose: the run itself announces the change on the stream.
  });

  /* ------------------------------------------------------------------ stopping */

  const dialog = document.querySelector('[data-cancel-dialog]');
  let pendingCancel = null;

  document.addEventListener('click', (event) => {
    const trigger = event.target.closest('[data-cancel-job]');
    if (!trigger || trigger.disabled) return;
    event.preventDefault();

    pendingCancel = trigger.dataset.cancelJob;
    const subject = dialog?.querySelector('[data-cancel-subject]');
    if (subject) subject.textContent = pendingCancel;
    dialog?.showModal();
  });

  dialog?.querySelector('[data-cancel-dismiss]')?.addEventListener('click', () => {
    pendingCancel = null;
    dialog.close();
  });

  dialog?.addEventListener('click', (event) => {
    // Clicking the backdrop is a click on the dialog itself, outside its content box.
    if (event.target === dialog) {
      const box = dialog.getBoundingClientRect();
      const outside = event.clientX < box.left || event.clientX > box.right
        || event.clientY < box.top || event.clientY > box.bottom;
      if (outside) {
        pendingCancel = null;
        dialog.close();
      }
    }
  });

  dialog?.querySelector('[data-cancel-confirm]')?.addEventListener('click', async () => {
    if (!pendingCancel) return;
    const jobId = pendingCancel;
    pendingCancel = null;
    dialog.close();

    try {
      const result = await requestJson(ROOT + 'api/jobs/' + encodeURIComponent(jobId) + '/cancel',
        { method: 'POST' });
      toast(result.message || ('Stopping ' + jobId), 'ok');
    } catch (error) {
      // A 409 means it finished between opening the dialog and confirming, which is not a fault.
      toast('Could not stop ' + jobId + ' — it may have already finished', 'bad');
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

  /* ------------------------------------------------------------------ sign-in */

  /** Built with DOM calls rather than innerHTML so server-provided text cannot become markup. */
  function renderLogin(host, state, accountId) {
    const previous = host.dataset.shown;
    host.innerHTML = '';
    // Each step of the flow replaces the whole panel, so it settles in the same way every other
    // swapped region does — but only when the step actually changed, or a stream reconnect would
    // re-animate a code the reader is halfway through typing.
    if (previous !== state.state) {
      host.dataset.shown = state.state || '';
      enter(host);
    }

    if (state.state === 'IDLE') {
      // Nothing in progress. Offer to begin — this is the state a freshly opened page is in, and
      // omitting it is what previously made the page announce a failure that had not happened.
      const lead = document.createElement('p');
      lead.className = 'solo__lead';
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
      lead.className = 'solo__lead';
      lead.textContent = 'Requesting a code from Microsoft…';
      host.append(spinner, lead);
      return;
    }

    if (state.state === 'WAITING') {
      const lead = document.createElement('p');
      lead.className = 'solo__lead';
      lead.textContent = 'Enter this code on the Microsoft sign-in page.';

      const code = document.createElement('div');
      code.className = 'code';
      code.textContent = state.userCode;

      const actions = document.createElement('div');
      actions.className = 'solo__actions';

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
      note.className = 'solo__note';
      note.textContent = 'Waiting for you to finish. The code expires '
        + relativeLabel(state.expiresAt) + '.';

      host.append(lead, code, actions, note);
      return;
    }

    if (state.state === 'DONE') {
      const done = document.createElement('p');
      done.className = 'solo__lead';
      done.textContent = 'Signed in.' + (state.message ? ' ' + state.message : '');
      const back = document.createElement('a');
      back.className = 'btn btn--primary btn--lg';
      back.href = '../../';
      back.textContent = 'Back to dashboard';
      host.append(done, back);
      return;
    }

    const failed = document.createElement('p');
    failed.className = 'solo__lead is-bad';
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
    if (!start) return;
    event.preventDefault();
    startLogin(start.dataset.startLogin);
  });

  async function startLogin(accountId) {
    const host = document.querySelector('[data-login-state]');
    if (host) renderLogin(host, { state: 'STARTING' }, accountId);
    try {
      // The result is ignored: every step of the flow arrives on the stream from here on.
      await requestJson(ROOT + 'api/accounts/' + encodeURIComponent(accountId) + '/login',
        { method: 'POST' });
    } catch (error) {
      toast('Could not start sign-in: ' + error.message, 'bad');
    }
  }

  /* ------------------------------------------------------------------ start */

  applyTheme((() => {
    try {
      return localStorage.getItem(THEME_KEY);
    } catch (error) {
      return null;
    }
  })());
  restoreDisclosures();
  tickTimes();
  setInterval(tickTimes, TICK_INTERVAL_MS);

  if (document.body.dataset.dashboard === 'true' || loginAccount) {
    connect();

    // Coming back to a tab that was asleep: the browser may have quietly dropped the connection
    // while it was hidden, and reconnecting is cheaper than wondering.
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && !source) {
        attempts = 0;
        connect();
      }
    });
  }
})();
