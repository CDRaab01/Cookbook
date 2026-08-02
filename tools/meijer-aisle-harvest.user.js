// ==UserScript==
// @name         Cookbook — Meijer aisle harvest
// @namespace    https://cookbook.dragonflymedia.org/
// @version      1.0.0
// @description  Look up where your Cookbook shopping-list items sit at your Meijer, and import the aisles.
// @author       Cookbook
// @match        https://www.meijer.com/*
// @run-at       document-idle
// @grant        GM_xmlhttpRequest
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_deleteValue
// @grant        GM_registerMenuCommand
// @grant        GM_addStyle
// @connect      cookbook.dragonflymedia.org
// @connect      localhost
// @connect      127.0.0.1
// @noframes
// ==/UserScript==

/*
 * WHY THIS EXISTS, AND WHY IT IS A USERSCRIPT RATHER THAN A SERVER JOB
 * ────────────────────────────────────────────────────────────────────
 * meijer.com is a client-rendered SPA behind Akamai Bot Manager. The product HTML is an ~11 KB
 * shell with no aisle in it, so an HTTP client gets nothing; and a *headless* browser is refused
 * outright — a containerised Chromium got 403 Access Denied on /shopping/product/…, on
 * /shopping/search.html and even on /robots.txt, from the same IP and user agent as a real
 * session that had just read those pages fine. Only the automation is blocked.
 *
 * So this runs where the pages actually load: your own signed-out browser session, at human pace,
 * reading pages you can already read. It is not an evasion of that control — it is the thing the
 * control leaves permitted. If Meijer ever blocks this too, the correct response is to stop.
 *
 * The cost model is what makes it viable: an aisle is close to static, so each item is looked up
 * once and cached in Cookbook's `store_placements` forever. `GET /stores/{id}/unplaced` returns
 * only what has no home yet, so a second run after a big recipe add is short.
 *
 * DESIGN NOTES WORTH KEEPING
 * ──────────────────────────
 * • Event-driven, never timer-driven, for the page waits. Chrome clamps setTimeout in a
 *   background tab to roughly one call per minute — a fixed-sleep version of this stalled after
 *   its first item during development. iframe `load` events and MutationObserver are not
 *   throttled, so the harvest survives you switching tabs. The only setTimeout used for waiting
 *   is the politeness delay and a backstop timeout, both of which are *allowed* to be slow.
 * • Same-origin iframes, not tab navigation, so the page you are on is never hijacked and the
 *   run can be stopped at any point.
 * • The server supplies each item's `search_query` (see server/app/lists/search_terms.py). This
 *   script never re-implements that cleaning — same rule as `ItemOut.key`. It shows the query in
 *   an editable field, because a recipe-derived name like "crema or 3 tbsp sour cream" is a
 *   judgement call and a human glance is cheaper than any heuristic.
 * • Nothing is written to Cookbook until you press Import, and the import endpoint is itself
 *   idempotent and non-destructive (it cannot overwrite a placement you set by hand).
 */

(function () {
    'use strict';

    // ── Config ────────────────────────────────────────────────────────────────────────────────
    const K = {
        base: 'cb_base_url',
        access: 'cb_access_token',
        refresh: 'cb_refresh_token',
        storeId: 'cb_store_id',
        listId: 'cb_list_id',
        delay: 'cb_delay_ms',
    };
    const DEFAULT_BASE = 'https://cookbook.dragonflymedia.org';
    // Politeness gap between page loads. meijer.com's robots.txt carries a commented-out
    // `Request-rate: 1/10`; nothing enforces it, but it is the site's stated preference, so stay
    // in the same order of magnitude. Raise it, don't lower it.
    const DEFAULT_DELAY_MS = 2500;
    // How long to wait for a lazily-rendered widget before giving up on one page.
    const PAGE_TIMEOUT_MS = 25000;

    const cfg = {
        get base() { return (GM_getValue(K.base, DEFAULT_BASE) || DEFAULT_BASE).replace(/\/+$/, ''); },
        get delay() { return Number(GM_getValue(K.delay, DEFAULT_DELAY_MS)) || DEFAULT_DELAY_MS; },
    };

    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    // ── Cookbook API (GM_xmlhttpRequest, because meijer.com → cookbook is cross-origin) ───────

    function rawRequest(method, url, { headers = {}, body = null } = {}) {
        return new Promise((resolve, reject) => {
            GM_xmlhttpRequest({
                method, url, headers, data: body,
                onload: (r) => resolve(r),
                onerror: () => reject(new Error('Network error reaching Cookbook')),
                ontimeout: () => reject(new Error('Cookbook timed out')),
                timeout: 30000,
            });
        });
    }

    async function api(method, path, body, { retryOn401 = true } = {}) {
        const headers = { 'Content-Type': 'application/json' };
        const token = GM_getValue(K.access, '');
        if (token) headers.Authorization = `Bearer ${token}`;

        const res = await rawRequest(method, cfg.base + path, {
            headers, body: body === undefined ? null : JSON.stringify(body),
        });

        // One transparent refresh, then give up — a refresh loop on a revoked token is worse
        // than an honest "sign in again".
        if (res.status === 401 && retryOn401 && GM_getValue(K.refresh, '')) {
            if (await refreshToken()) return api(method, path, body, { retryOn401: false });
        }
        if (res.status < 200 || res.status >= 300) {
            let detail = res.responseText || '';
            try { detail = JSON.parse(detail).detail || detail; } catch (_) { /* plain text */ }
            throw new Error(`Cookbook ${res.status}: ${String(detail).slice(0, 200)}`);
        }
        return res.responseText ? JSON.parse(res.responseText) : null;
    }

    async function refreshToken() {
        try {
            const res = await rawRequest('POST', cfg.base + '/auth/refresh', {
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refresh_token: GM_getValue(K.refresh, '') }),
            });
            if (res.status !== 200) return false;
            const t = JSON.parse(res.responseText);
            GM_setValue(K.access, t.access_token);
            GM_setValue(K.refresh, t.refresh_token);
            return true;
        } catch (_) {
            return false;
        }
    }

    /**
     * Sign in and keep only the tokens.
     *
     * The password is used for exactly this one request and never stored — if the tokens are
     * later revoked you sign in again, which is the correct trade for a credential living in a
     * browser extension's storage.
     */
    async function login(email, password) {
        const res = await rawRequest('POST', cfg.base + '/auth/login', {
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password }),
        });
        if (res.status !== 200) throw new Error('Sign-in failed — check the email and password.');
        const t = JSON.parse(res.responseText);
        GM_setValue(K.access, t.access_token);
        GM_setValue(K.refresh, t.refresh_token);
    }

    // ── Reading meijer.com, event-driven ──────────────────────────────────────────────────────

    function openFrame(url) {
        return new Promise((resolve, reject) => {
            const f = document.createElement('iframe');
            f.style.cssText = 'position:fixed;left:-10000px;top:0;width:1280px;height:900px;border:0';
            let settled = false;
            f.addEventListener('load', () => { if (!settled) { settled = true; resolve(f); } });
            // A frame that never fires `load` must not hang the run forever.
            setTimeout(() => {
                if (!settled) { settled = true; f.remove(); reject(new Error('page load timed out')); }
            }, PAGE_TIMEOUT_MS);
            f.src = url;
            document.body.appendChild(f);
        });
    }

    /**
     * Resolve once `test(doc)` holds. Uses MutationObserver rather than polling because timers
     * are throttled in a background tab and observers are not — this is what lets the harvest
     * keep running while you do something else.
     */
    function waitFor(doc, test) {
        return new Promise((resolve) => {
            if (test(doc)) return resolve(true);
            const obs = new MutationObserver(() => {
                if (test(doc)) { obs.disconnect(); resolve(true); }
            });
            obs.observe(doc.documentElement, { childList: true, subtree: true, characterData: true });
            setTimeout(() => { obs.disconnect(); resolve(false); }, PAGE_TIMEOUT_MS);
        });
    }

    // These mirror server/app/retailers/meijer.py, and the duplication is deliberate and bounded:
    // this copy only has to be good enough to show you progress and hand over a *label*. The
    // server re-parses it with `normalize_aisle_label` on import, so "A|27", "a | 027" and
    // "Aisle A | 27" all land on the same StoreAisle regardless of what this regex produced. The
    // server stays the authority on what is actually stored.
    const UPC_IN_HREF = /\/(\d+)\.html/;
    const AISLE_RE = /Aisle\s+([A-Z])\s*\|\s*(\d+)/i;
    const SECTION_RE = /Section\s+(\d+)/i;
    // The site shows this placeholder while the location widget resolves. Distinguishing it from
    // a genuine no-aisle page is the difference between "retry later" and "this has no aisle".
    const PENDING_RE = /Finding\s+Aisle/i;

    async function firstSearchHit(query) {
        const f = await openFrame(`/shopping/search.html?text=${encodeURIComponent(query)}`);
        try {
            const doc = f.contentDocument;
            await waitFor(doc, (d) => d.querySelector('a[href*="/shopping/product/"]'));
            const a = [...doc.querySelectorAll('a[href*="/shopping/product/"]')]
                .find((x) => UPC_IN_HREF.test(x.getAttribute('href') || ''));
            if (!a) return null;
            return {
                upc: a.getAttribute('href').match(UPC_IN_HREF)[1],
                matchedName: (a.textContent || '')
                    .replace(/\s+/g, ' ')
                    .replace(/(Original price|Current price|Sale price|\$\d|\(\d+\)|\d+(\.\d+)? out of).*$/i, '')
                    .trim()
                    .slice(0, 120),
            };
        } finally {
            f.remove();
        }
    }

    async function locationFor(upc) {
        const f = await openFrame(`/shopping/product/x/${encodeURIComponent(upc)}.html`);
        try {
            const doc = f.contentDocument;
            const resolved = await waitFor(doc, (d) => SECTION_RE.test(d.body.innerText));
            const text = doc.body.innerText;
            if (!resolved && PENDING_RE.test(text)) return null; // still loading → retryable
            const a = text.match(AISLE_RE);
            const s = text.match(SECTION_RE);
            return {
                aisle: a ? `${a[1].toUpperCase()} | ${a[2]}` : null,
                section: s ? s[1] : null,
            };
        } finally {
            f.remove();
        }
    }

    // ── The run ───────────────────────────────────────────────────────────────────────────────

    const state = { rows: [], running: false, cancel: false };

    async function harvest(onProgress) {
        state.running = true;
        state.cancel = false;
        try {
            for (let i = 0; i < state.rows.length; i++) {
                if (state.cancel) break;
                const row = state.rows[i];
                if (!row.include || row.done) continue;
                row.status = 'looking up…';
                onProgress(i);
                try {
                    const hit = await firstSearchHit(row.query);
                    if (!hit) {
                        row.status = 'no search result';
                        row.done = true;
                    } else {
                        await sleep(cfg.delay);
                        const loc = await locationFor(hit.upc);
                        row.matchedName = hit.matchedName;
                        if (loc === null) {
                            row.status = 'page never resolved — try again';
                        } else {
                            row.aisle = loc.aisle;
                            row.section = loc.section;
                            row.status = loc.aisle ? `Aisle ${loc.aisle}` : 'no aisle (service counter?)';
                            row.done = true;
                        }
                    }
                } catch (e) {
                    row.status = `error: ${e.message}`;
                }
                onProgress(i);
                if (!state.cancel) await sleep(cfg.delay);
            }
        } finally {
            state.running = false;
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────────────────────

    GM_addStyle(`
      #cbh { position: fixed; right: 16px; bottom: 16px; z-index: 2147483000;
             font: 13px/1.45 system-ui, sans-serif; color: #14212e; }
      #cbh .cbh-fab { background: #c8102e; color: #fff; border: 0; border-radius: 999px;
             padding: 11px 18px; font-weight: 600; cursor: pointer;
             box-shadow: 0 3px 14px rgba(0,0,0,.28); }
      #cbh .cbh-panel { display: none; width: 460px; max-height: 78vh; overflow: auto;
             background: #fff; border: 1px solid #d7dee5; border-radius: 12px; padding: 14px;
             box-shadow: 0 10px 40px rgba(0,0,0,.22); }
      #cbh.open .cbh-panel { display: block; }
      #cbh.open .cbh-fab { display: none; }
      #cbh h3 { margin: 0 0 4px; font-size: 15px; }
      #cbh .cbh-sub { color: #5b6b7a; margin-bottom: 10px; }
      #cbh label { display: block; margin: 8px 0 3px; font-weight: 600; font-size: 12px; }
      #cbh input, #cbh select { width: 100%; box-sizing: border-box; padding: 6px 8px;
             border: 1px solid #c6d0d9; border-radius: 6px; font: inherit; }
      #cbh button { font: inherit; padding: 7px 12px; border-radius: 7px; cursor: pointer;
             border: 1px solid #c6d0d9; background: #f4f7f9; }
      #cbh button.primary { background: #c8102e; color: #fff; border-color: #c8102e; font-weight: 600; }
      #cbh button:disabled { opacity: .5; cursor: default; }
      #cbh table { width: 100%; border-collapse: collapse; margin-top: 8px; }
      #cbh td { padding: 3px 2px; border-bottom: 1px solid #eef2f5; vertical-align: middle; }
      #cbh td.q input { padding: 3px 5px; font-size: 12px; }
      #cbh .name { font-size: 11px; color: #5b6b7a; }
      #cbh .status { font-size: 11px; white-space: nowrap; text-align: right; color: #5b6b7a; }
      #cbh .status.ok { color: #146b3a; font-weight: 600; }
      #cbh .status.bad { color: #a3161c; }
      #cbh .row-actions { display: flex; gap: 8px; margin-top: 10px; align-items: center; }
      #cbh .msg { margin-top: 8px; padding: 7px 9px; border-radius: 6px; background: #f4f7f9; }
      #cbh .msg.err { background: #fdecea; color: #a3161c; }
      #cbh a.link { color: #0b6ab0; cursor: pointer; text-decoration: underline; font-size: 12px; }
    `);

    const host = document.createElement('div');
    host.id = 'cbh';
    host.innerHTML = `
      <button class="cbh-fab" title="Cookbook aisle harvest">🛒 Cookbook</button>
      <div class="cbh-panel"></div>`;
    document.body.appendChild(host);

    const panel = host.querySelector('.cbh-panel');
    host.querySelector('.cbh-fab').addEventListener('click', () => { host.classList.add('open'); renderStart(); });

    function close() { host.classList.remove('open'); }

    function msg(text, isErr) {
        return `<div class="msg${isErr ? ' err' : ''}">${esc(text)}</div>`;
    }

    function renderSettings(err) {
        panel.innerHTML = `
          <h3>Cookbook settings</h3>
          <div class="cbh-sub">Signs in once and stores only the tokens — never your password.</div>
          <label>Cookbook server</label><input id="cbh-base" value="${esc(cfg.base)}">
          <label>Email</label><input id="cbh-email" type="email" autocomplete="username">
          <label>Password</label><input id="cbh-pass" type="password" autocomplete="current-password">
          <label>Seconds between page loads</label>
          <input id="cbh-delay" type="number" min="1" step="0.5" value="${(cfg.delay / 1000)}">
          <div class="row-actions">
            <button class="primary" id="cbh-signin">Sign in</button>
            <button id="cbh-cancel">Close</button>
          </div>
          ${err ? msg(err, true) : ''}`;
        panel.querySelector('#cbh-cancel').addEventListener('click', close);
        panel.querySelector('#cbh-signin').addEventListener('click', async () => {
            const btn = panel.querySelector('#cbh-signin');
            btn.disabled = true; btn.textContent = 'Signing in…';
            GM_setValue(K.base, panel.querySelector('#cbh-base').value.trim());
            GM_setValue(K.delay, Math.max(1000, Number(panel.querySelector('#cbh-delay').value) * 1000));
            try {
                await login(panel.querySelector('#cbh-email').value.trim(),
                            panel.querySelector('#cbh-pass').value);
                renderStart();
            } catch (e) {
                renderSettings(e.message);
            }
        });
    }

    async function renderStart() {
        if (!GM_getValue(K.access, '')) return renderSettings();
        panel.innerHTML = `<h3>Cookbook aisle harvest</h3><div class="cbh-sub">Loading your stores…</div>`;
        let stores, lists;
        try {
            [stores, lists] = await Promise.all([api('GET', '/stores'), api('GET', '/lists')]);
        } catch (e) {
            panel.innerHTML = `<h3>Cookbook aisle harvest</h3>${msg(e.message, true)}
              <div class="row-actions"><button id="cbh-set">Settings</button><button id="cbh-x">Close</button></div>`;
            panel.querySelector('#cbh-set').addEventListener('click', () => renderSettings());
            panel.querySelector('#cbh-x').addEventListener('click', close);
            return;
        }
        if (!stores.length) {
            panel.innerHTML = `<h3>Cookbook aisle harvest</h3>
              ${msg('No stores yet. Add one in Cookbook (Settings → Manage stores) first — the aisles are imported into a store.')}
              <div class="row-actions"><button id="cbh-x">Close</button></div>`;
            panel.querySelector('#cbh-x').addEventListener('click', close);
            return;
        }
        const savedStore = GM_getValue(K.storeId, '');
        const savedList = GM_getValue(K.listId, '');
        panel.innerHTML = `
          <h3>Cookbook aisle harvest</h3>
          <div class="cbh-sub">Looks up each item on meijer.com and imports where it lives at this store.</div>
          <label>Store</label>
          <select id="cbh-store">${stores.map((s) => {
            const label = s.label ? `${s.name} — ${s.label}` : s.name;
            return `<option value="${esc(s.id)}"${s.id === savedStore ? ' selected' : ''}>${esc(label)}</option>`;
          }).join('')}</select>
          <label>Shopping list</label>
          <select id="cbh-list">${lists.map((l) =>
            `<option value="${esc(l.id)}"${l.id === savedList ? ' selected' : ''}>${esc(l.name)} (${l.unchecked_count} to buy)</option>`
          ).join('')}</select>
          <div class="row-actions">
            <button class="primary" id="cbh-load">Find items needing an aisle</button>
            <button id="cbh-set">Settings</button>
            <button id="cbh-x">Close</button>
          </div>`;
        panel.querySelector('#cbh-x').addEventListener('click', close);
        panel.querySelector('#cbh-set').addEventListener('click', () => renderSettings());
        panel.querySelector('#cbh-load').addEventListener('click', async () => {
            const storeId = panel.querySelector('#cbh-store').value;
            const listId = panel.querySelector('#cbh-list').value;
            GM_setValue(K.storeId, storeId);
            GM_setValue(K.listId, listId);
            await renderWorklist(storeId, listId);
        });
    }

    async function renderWorklist(storeId, listId) {
        panel.innerHTML = `<h3>Cookbook aisle harvest</h3><div class="cbh-sub">Asking Cookbook what still needs an aisle…</div>`;
        let work;
        try {
            work = await api('GET', `/stores/${storeId}/unplaced?list_id=${encodeURIComponent(listId)}`);
        } catch (e) {
            panel.innerHTML = `<h3>Cookbook aisle harvest</h3>${msg(e.message, true)}`;
            return;
        }
        if (!work.items.length) {
            panel.innerHTML = `<h3>Nothing to look up</h3>
              ${msg('Every unchecked item on this list already has an aisle at this store.')}
              <div class="row-actions"><button id="cbh-back">Back</button><button id="cbh-x">Close</button></div>`;
            panel.querySelector('#cbh-back').addEventListener('click', renderStart);
            panel.querySelector('#cbh-x').addEventListener('click', close);
            return;
        }

        // `search_query` comes from the server (lists/search_terms.py) so this script never
        // re-implements the cleaning. It stays editable because a recipe-derived name is a
        // judgement call — a human glance costs less than any heuristic.
        state.rows = work.items.map((i) => ({
            name: i.name,
            query: i.search_query || i.name,
            include: true,
            done: false,
            aisle: null,
            section: null,
            matchedName: null,
            status: '',
        }));

        const mins = Math.ceil((state.rows.length * 2 * cfg.delay + state.rows.length * 4000) / 60000);
        panel.innerHTML = `
          <h3>${state.rows.length} item${state.rows.length === 1 ? '' : 's'} to look up</h3>
          <div class="cbh-sub">Roughly ${mins} min. Edit any search term that looks wrong —
            these come from recipe lines, so some are messy. You can keep using other tabs.</div>
          <table id="cbh-rows"></table>
          <div class="row-actions">
            <button class="primary" id="cbh-run">Start</button>
            <button id="cbh-stop" disabled>Stop</button>
            <button id="cbh-import" disabled>Import</button>
            <a class="link" id="cbh-back">Back</a>
          </div>
          <div id="cbh-out"></div>`;

        const table = panel.querySelector('#cbh-rows');
        function paint() {
            table.innerHTML = state.rows.map((r, i) => `
              <tr>
                <td style="width:22px"><input type="checkbox" data-i="${i}" ${r.include ? 'checked' : ''}></td>
                <td class="q"><input data-q="${i}" value="${esc(r.query)}">
                    <div class="name">${esc(r.name)}${r.matchedName ? ` → ${esc(r.matchedName)}` : ''}</div></td>
                <td class="status ${r.aisle ? 'ok' : (/error|no /i.test(r.status) ? 'bad' : '')}">${esc(r.status)}</td>
              </tr>`).join('');
            table.querySelectorAll('input[type=checkbox]').forEach((cb) =>
                cb.addEventListener('change', () => { state.rows[+cb.dataset.i].include = cb.checked; }));
            table.querySelectorAll('input[data-q]').forEach((inp) =>
                inp.addEventListener('input', () => { state.rows[+inp.dataset.q].query = inp.value; }));
        }
        paint();

        const runBtn = panel.querySelector('#cbh-run');
        const stopBtn = panel.querySelector('#cbh-stop');
        const importBtn = panel.querySelector('#cbh-import');
        const out = panel.querySelector('#cbh-out');
        panel.querySelector('#cbh-back').addEventListener('click', renderStart);

        stopBtn.addEventListener('click', () => { state.cancel = true; stopBtn.disabled = true; });

        runBtn.addEventListener('click', async () => {
            runBtn.disabled = true; stopBtn.disabled = false; importBtn.disabled = true;
            await harvest(() => paint());
            runBtn.disabled = false; stopBtn.disabled = true;
            const found = state.rows.filter((r) => r.aisle).length;
            importBtn.disabled = state.rows.every((r) => !r.done);
            out.innerHTML = msg(`${found} of ${state.rows.length} resolved to an aisle. ` +
                `Press Import to send them to Cookbook — nothing has been saved yet.`);
        });

        importBtn.addEventListener('click', async () => {
            importBtn.disabled = true;
            // Only rows the harvest actually finished. An unfinished row must not be sent as
            // "no aisle" — the server treats that as a skip, and a skip you didn't mean is a
            // silent hole in your coverage.
            const observations = state.rows.filter((r) => r.done).map((r) => ({
                name: r.name,
                aisle: r.aisle,
                section: r.section,
                matched_name: r.matchedName,
            }));
            try {
                const res = await api('POST', `/stores/${storeId}/placements/import`, {
                    retailer: 'meijer',
                    retailer_store_id: meijerStoreId(),
                    observations,
                });
                out.innerHTML = msg(
                    `Imported: ${res.placed} placed, ${res.aisles_created} new aisle(s)` +
                    (res.skipped.length ? `, ${res.skipped.length} skipped (${res.skipped.slice(0, 4).join(', ')}${res.skipped.length > 4 ? '…' : ''})` : '') + '.');
            } catch (e) {
                out.innerHTML = msg(e.message, true);
                importBtn.disabled = false;
            }
        });
    }

    /** Which Meijer the site currently has selected — a one-value cookie, e.g. "138". */
    function meijerStoreId() {
        const m = document.cookie.match(/(?:^|;\s*)meijer-store=([^;]+)/);
        return m ? decodeURIComponent(m[1]).slice(0, 16) : null;
    }

    GM_registerMenuCommand('Cookbook: aisle harvest', () => { host.classList.add('open'); renderStart(); });
    GM_registerMenuCommand('Cookbook: sign out', () => {
        GM_deleteValue(K.access); GM_deleteValue(K.refresh);
        alert('Signed out of Cookbook.');
    });
})();
