/* Pawfect sync: sequential saves with revision protection.
   - Every save sends the revision it was based on; the server answers 409 if another tab saved first.
   - Saves run strictly one after another, so one tab never conflicts with itself.
   - After a conflict this tab stops saving and keeps its unsaved data. The user can download it
     or explicitly reload the latest server state. Nothing is merged or discarded automatically. */
(() => {
  'use strict';
  const clone = x => JSON.parse(JSON.stringify(x));
  let host = null, revision = null, queue = Promise.resolve(), blocked = false, removeWeights = false, lastError = '';

  const init = h => { host = h; };

  /* A persistent banner for pet/care-record saves that failed: the data is still on screen but NOT in SQLite. */
  function banner(show) {
    let b = document.getElementById('sync-banner');
    if (!b && show) {
      b = document.createElement('div');
      b.id = 'sync-banner'; b.setAttribute('role', 'alert');
      b.innerHTML = '<span>Some changes are not saved yet. Pawfect could not reach its database.</span><button type="button" class="btn light" id="sync-retry">Retry now</button>';
      document.body.append(b);
      b.querySelector('#sync-retry').onclick = () => save();
    }
    if (b) b.hidden = !show;
  }

  async function fetchState() {
    const r = await fetch('/api/state', { cache: 'no-store' });
    if (!r.ok) throw new Error('load failed');
    const s = await r.json();
    return { revision: s.revision, pets: s.pets || [], records: s.records || [], weights: s.weights || [] };
  }

  async function load() {
    const s = await fetchState();
    revision = s.revision;
    return s;
  }

  /* removeWeights: set only by the confirmed "remove pet" action. It lets the server delete the removed
     pet's weight measurements too; without it the server refuses to delete a pet that has any. */
  function save(opts) {
    if (opts && opts.removeWeights) removeWeights = true;
    queue = queue.then(() => send('PUT', '/api/state', () => {
      const d = clone(host.get());
      const body = { baseRevision: revision, pets: d.pets, records: d.records };
      if (removeWeights) body.removeWeightsOfDeletedPets = true;
      return body;
    }, () => { removeWeights = false; })).then(r => { if (r === 'ok') banner(false); else if (r === 'failed') banner(true); }).catch(() => {});
    return queue;
  }

  /* Weight changes use their own endpoints and share the same queue, revision and conflict handling. */
  /* Resolves to 'ok', 'conflict' (kept on screen, see the dialog) or 'failed' (the caller undoes the change). */
  function weightRequest(method, path, fields) {
    const p = queue.then(() => send(method, method === 'DELETE' ? path + '?baseRevision=' + revision : path,
      method === 'DELETE' ? null : () => Object.assign({ baseRevision: revision }, fields))).catch(() => 'failed');
    queue = p;
    return p;
  }

  /* Photos use their own endpoints (raw JPEG body) and share the same queue, revision and conflict handling.
     method PUT uploads blob, DELETE removes. Resolves to { status: 'ok' | 'conflict' | 'failed', photo: file name or null }. */
  function photoRequest(method, petId, blob) {
    const p = queue.then(async () => {
      let photo = null;
      const status = await send(method, '/api/pets/' + encodeURIComponent(petId) + '/photo?baseRevision=' + revision, null, j => { photo = j.photo || null; }, method === 'PUT' ? blob : null);
      return { status, photo };
    }).catch(() => ({ status: 'failed', photo: null }));
    queue = p;
    return p;
  }

  async function send(method, url, buildBody, onSuccess, blob) {
    if (blocked) return 'conflict';                       // conflict pending: keep local data, send nothing
    if (revision === null) { lastError = 'Pawfect has not loaded your data yet, so nothing was saved. Reload the page.'; host.toast(lastError); return 'failed'; }
    // DELETE builds its revision into the URL when queued, so refresh it here in case earlier queue items moved it.
    url = url.replace(/baseRevision=\d+/, 'baseRevision=' + revision);
    let r;
    try {
      r = await fetch(url, blob
        ? { method, headers: { 'Content-Type': blob.type || 'image/jpeg' }, body: blob }
        : buildBody
          ? { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(buildBody()) }
          : { method });
    } catch (e) { lastError = 'Could not save to SQLite. Check that Pawfect is running.'; host.toast(lastError); return 'failed'; }
    if (r.ok) { const j = await r.json(); revision = j.revision; if (onSuccess) onSuccess(j); return 'ok'; }
    if (r.status === 409) {
      let code = ''; try { code = (await r.clone().json()).code; } catch (e) { /* ignore */ }
      if (code === 'STALE_REVISION') { blocked = true; showConflict(); return 'conflict'; }
    }
    let msg = ''; try { msg = (await r.json()).error || ''; } catch (e) { /* ignore */ }
    lastError = msg || 'Could not save to SQLite. Check that Pawfect is running.';
    host.toast(lastError);
    return 'failed';
  }

  const h = (tag, attrs = {}, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) k === 'class' ? (e.className = v) : e.setAttribute(k, v);
    for (const k of kids) e.append(k);
    return e;
  };

  function download() {
    const d = host.get();
    const stamp = new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);
    const blob = new Blob([JSON.stringify({ note: 'Unsaved Pawfect changes', exportedAt: new Date().toISOString(), pets: d.pets, records: d.records, weights: d.weights || [] }, null, 2)], { type: 'application/json' });
    const a = h('a', { href: URL.createObjectURL(blob), download: 'pawfect-unsaved-' + stamp + '.json' });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  function showConflict() {
    const d = document.getElementById('conflict-modal');
    d.oncancel = e => e.preventDefault();      // Esc must not dismiss an unresolved conflict
    const reload = h('button', { class: 'btn danger', id: 'cf-reload' }, 'Reload latest data');
    d.replaceChildren(
      h('div', { class: 'modal-head' }, h('div', {}, h('h2', {}, 'Pawfect was updated elsewhere'), h('p', {}, 'Saving is paused so nothing gets overwritten.'))),
      h('div', { class: 'modal-body' },
        h('p', {}, 'Another tab or window saved changes after you opened this page. ',
          h('b', {}, 'Your latest changes have not been saved'), ', but they are still on this page.'),
        h('p', { class: 'sub' }, 'Download them first if you want to keep them. Reloading replaces what you see here with the latest saved data.')),
      h('div', { class: 'modal-foot' }, h('button', { class: 'btn primary', id: 'cf-download' }, 'Download my unsaved changes'), reload));
    d.querySelector('#cf-download').onclick = download;
    let armed = false;
    reload.onclick = async () => {
      if (!armed) { armed = true; reload.textContent = 'Click again to discard my unsaved changes'; return; }
      try {
        const s = await fetchState();
        revision = s.revision; host.set({ pets: s.pets, records: s.records, weights: s.weights }); blocked = false; removeWeights = false; banner(false);
        d.close(); host.render(); host.toast('Reloaded the latest data');
      } catch (e) { host.toast('Could not reach Pawfect. Your unsaved changes are still here.'); armed = false; reload.textContent = 'Reload latest data'; }
    };
    if (!d.open) d.showModal();
  }

  window.PawfectSync = { init, load, save, weightRequest, photoRequest, lastError: () => lastError };
})();
