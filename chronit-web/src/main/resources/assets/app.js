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
      const wanted = open.has(element.dataset.remember);
      if (element.open === wanted) return;
      // The restored state is computed once with transitions off — reading a layout property is
      // what forces that — so the chevron arrives already turned. Otherwise a page coming back
      // with three sections open spins three chevrons at a reader who changed nothing.
      element.classList.add('is-settling');
      element.open = wanted;
      void element.offsetWidth;
      element.classList.remove('is-settling');
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

  /* ------------------------------------------------------------------ unfolding */

  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const unfolding = new WeakMap();
  let motion = null;

  /**
   * Motion lives in the stylesheet; these are read once rather than kept as a second copy here.
   *
   * Reading them lazily, on the first unfold, keeps a forced style computation out of page load.
   */
  function motionTokens() {
    if (motion) return motion;
    const root = getComputedStyle(document.documentElement);
    const number = (name, fallback) => {
      const value = parseFloat(root.getPropertyValue(name));
      return Number.isFinite(value) ? value : fallback;
    };
    motion = {
      shortest: number('--unfold-min', 190),
      longest: number('--unfold-max', 400),
      span: number('--unfold-span', 900),
      ease: root.getPropertyValue('--ease-unfold').trim() || 'ease',
    };
    return motion;
  }

  /** The box a <details> shows and hides: the fold that follows its summary. */
  function foldOf(details) {
    const summary = details.querySelector(':scope > summary');
    const fold = summary ? summary.nextElementSibling : null;
    return fold && fold.classList.contains('fold') ? fold : null;
  }

  /**
   * Plays a disclosure open or closed over the height of its fold.
   *
   * A <details> switches its content between `display: none` and displayed, and neither a
   * transition nor a keyframe can carry a height of `auto` across that, so the height is measured
   * here and played with the Web Animations API.
   *
   * Exactly one property moves, on a box that carries no padding of its own. An earlier version
   * animated the padded body directly, which meant animating its padding too — `height: 0` on a
   * border-box element still leaves its padding standing — and that put the content on a second,
   * slower journey of its own: the first line drifted down as the section opened, and the last
   * stretch of the animation grew nothing but empty padding. Hence `.fold`, a bare clip, with the
   * padded body inside it holding still.
   *
   * Both directions are driven from the click rather than from `toggle`, so opening measures in
   * the same task as the click. Left to `toggle`, which the browser queues, a frame can paint the
   * section at full height before the animation has anything to say about it.
   *
   * @return true when it took the toggle on, false when the browser should handle it
   */
  function unfold(details, opening) {
    const fold = foldOf(details);
    if (!fold || reduced.matches || typeof fold.animate !== 'function') return false;

    // Where it is now, before anything changes: zero when closed, and mid-flight when the reader
    // changed their mind, so a reversal continues from what is on screen.
    const from = details.open ? fold.getBoundingClientRect().height : 0;
    if (opening) details.open = true;

    const previous = unfolding.get(details);
    if (previous) previous.animation.cancel();

    // Measured with the previous animation cancelled, so this is the fold's own height.
    const to = opening ? fold.getBoundingClientRect().height : 0;

    // A single duration makes a short section feel slow and a tall one feel thrown, so the time
    // scales with the distance actually travelled.
    const tokens = motionTokens();
    const reach = Math.min(1, Math.abs(to - from) / tokens.span);
    const duration = tokens.shortest + (tokens.longest - tokens.shortest) * reach;

    details.classList.add('is-unfolding');
    const animation = fold.animate(
      [{ height: from + 'px' }, { height: to + 'px' }],
      { duration, easing: tokens.ease });

    unfolding.set(details, { animation, opening });
    animation.finished.then(() => {
      // A finished animation settles inside the frame's animation step, before style and paint, so
      // dropping `open` here closes the section in the same frame the height reached zero rather
      // than letting one frame of full-height content through.
      if (!opening) details.open = false;
      details.classList.remove('is-unfolding');
      unfolding.delete(details);
    }, () => {
      /* Cancelled by the next click, which owns the section from here on. */
    });
    return true;
  }

  document.addEventListener('click', (event) => {
    const summary = event.target.closest('summary');
    if (!summary) return;
    const details = summary.parentElement;
    if (!(details instanceof HTMLDetailsElement)) return;
    // Mid-flight, the click reverses what is playing; otherwise it does the obvious thing.
    const playing = unfolding.get(details);
    const opening = playing ? !playing.opening : !details.open;
    if (unfold(details, opening)) event.preventDefault();
  });

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
      swap('[data-overview]', event.data, false);
    });

    source.addEventListener('attention', (event) => {
      swap('[data-attention]', event.data);
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

  /**
   * Writes server-rendered markup into an element, but only when the server actually sent
   * something different.
   *
   * The comparison is against the last markup *received*, kept on the element, rather than
   * against its current `innerHTML`. Reading innerHTML back does not return what was written:
   * the browser re-serialises on parse — the single quotes in the inline SVGs come back as double
   * — and `tickTimes` rewrites every relative label a moment later, so "3 Sep, 19:06" is already
   * "2 hours ago" by the time anything could be compared. Every one of those differences read as
   * a change, which is why an update carrying identical data still animated.
   *
   * Kept in a WeakMap rather than a data attribute: the run history is twenty kilobytes of
   * markup, and parking a copy of it in the DOM to compare against would be worse than the
   * problem.
   *
   * The first payload is reported apart from the rest. The stream opens by sending the state as it
   * stands, which is the state the server had already rendered into the page — nothing on screen
   * changes, so animating it makes a page that has only just loaded appear to change its mind. A
   * region is only ever animated against something this script has seen before.
   *
   * @return SAME, FIRST or CHANGED
   */
  const rendered = new WeakMap();

  const SAME = 'same';
  const FIRST = 'first';
  const CHANGED = 'changed';

  function applyHtml(element, html) {
    const previous = rendered.get(element);
    if (previous === html) return SAME;
    rendered.set(element, html);
    element.innerHTML = html;
    return previous === undefined ? FIRST : CHANGED;
  }

  /**
   * Replaces a server-rendered region, keeping the disclosures the reader had open.
   *
   * @param animate false for a region that ticks rather than appears. The summary at the top holds
   *                a clock, and a number that fades every time it changes is a number nobody can
   *                read; things that arrive get the entrance, things that count do not.
   */
  function swap(selector, html, animate = true) {
    const host = document.querySelector(selector);
    if (!host) return;
    const written = applyHtml(host, html);
    if (written === SAME) return;
    restoreDisclosures(host);
    tickTimes(host);
    if (animate && written === CHANGED) enter(host);
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
    if (status && job.statusHtml && applyHtml(status, job.statusHtml) === CHANGED) {
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
      // Whole percent, over the same denominator Ui.progress uses. The width is transitioned, so
      // a fraction of a percent between what the server drew and what is computed here is not a
      // rounding difference nobody sees — it is the bar sliding on a page that has just loaded.
      const done = Math.max(index - 1, 0);
      const percent = Math.min(100, Math.floor(done * 100 / Math.max(total, 1)));
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
    if (status && account.statusHtml && applyHtml(status, account.statusHtml) === CHANGED) {
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
    // re-animate a code the reader is halfway through typing. The first frame is a step the server
    // has already drawn, so it is written without the entrance too.
    if (previous !== state.state) {
      const first = previous === undefined;
      host.dataset.shown = state.state || '';
      if (!first) enter(host);
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
