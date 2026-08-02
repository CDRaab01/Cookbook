# tools/

Small operator-run utilities that aren't part of the server or the app.

## `meijer-aisle-harvest.user.js`

A userscript that looks up where your shopping-list items sit at your Meijer and imports the
aisles into Cookbook, using **your own browser session**.

### Why a userscript and not a server job

meijer.com is a client-rendered SPA behind Akamai Bot Manager. Two independent walls:

- the product HTML is an ~11 KB shell with **no aisle in it** — the location arrives by XHR after
  hydration, so an `httpx.get` returns nothing however the headers are dressed up;
- an **automated browser is refused outright** — a containerised Chromium got `403 Access Denied`
  on `/shopping/product/…`, on `/shopping/search.html` and even on `/robots.txt`, from the same IP
  and user agent as a session that had just read those pages fine.

Only the automation is blocked. So this runs where the pages actually load: a real browser you are
signed into your own machine on, at human pace, reading pages you can already read. See
`server/app/retailers/meijer.py` for the full measurement.

It's affordable because an aisle is close to static — each item costs one lookup, then lives in
`store_placements` forever. `GET /stores/{id}/unplaced` returns only what has no home yet, so the
second run after a big recipe import is short.

### Install

1. Install [Tampermonkey](https://www.tampermonkey.net/) (or Violentmonkey) in Chrome.
2. Open the dashboard → **Utilities** → paste the raw URL of this file, or just open the `.user.js`
   file and confirm the install prompt.
3. Go to **meijer.com** and pick your store (top right) — the script reads the store from the
   site's own `meijer-store` cookie, so whatever store the site is set to is the one it imports for.
4. Click the **🛒 Cookbook** button, bottom right.

### First run

- **Settings** asks for your Cookbook server, email and password. It signs in once and stores
  **only the access and refresh tokens** — the password is used for that one request and never
  written to disk. "Cookbook: sign out" in the Tampermonkey menu clears them.
- Pick a **store** and a **shopping list**, then **Find items needing an aisle**.
- You get one row per item with an **editable search term**. These come from recipe lines, so some
  are messy ("crema or 3 tbsp sour cream + 1 tbsp milk"); the server pre-cleans them
  (`server/app/lists/search_terms.py`) but it is best-effort by design, and a glance is cheaper
  than any heuristic. Untick anything you don't want looked up.
- **Start.** Roughly 5 s per item. You can switch tabs — the waits are event-driven
  (`load` + `MutationObserver`), not timers, because Chrome throttles background-tab timers to
  about one call a minute and a sleep-based version stalls after the first item.
- **Stop** halts after the current item. Nothing has been sent to Cookbook yet.
- **Import** posts the finished rows. Only rows that actually completed are sent — an unfinished
  row is never reported as "no aisle", because the server treats that as a skip and a skip you
  didn't mean is a silent hole in your coverage.

### What the import can and can't do

The endpoint (`POST /stores/{id}/placements/import`) is **idempotent** and **never destructive**:
re-running reports `placed=0`, and it cannot delete an aisle, drop a placement, or overwrite one
you set by hand — a failed lookup lands in `skipped`. It never touches an item's `category` or
your `item_history`. See ARCHITECTURE.md § "Retailer aisle imports".

### Settings worth knowing

- **Seconds between page loads** — default 2.5 s. meijer.com's `robots.txt` carries a commented-out
  `Request-rate: 1/10`; nothing enforces it, but it is the site's stated preference, so stay in
  the same order of magnitude. **Raise it, don't lower it.**

### If it stops working

If Meijer starts refusing this too, the right response is to stop using it, not to make it look
less like itself. The alternative worth building instead is the **Kroger public API**, which
returns aisle/side/shelf per product per store under a free OAuth2 developer account — the
`stores.retailer` / `retailer_store_id` columns exist so a second chain can slot in.
