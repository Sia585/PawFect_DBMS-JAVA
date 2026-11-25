/* Pawfect sync: sequential saves with revision protection.
   - Every save sends the revision it was based on; the server answers 409 if another tab saved first.
   - Saves run strictly one after another, so one tab never conflicts with itself.
   - After a conflict this tab stops saving and keeps its unsaved data. The user can download it
     or explicitly reload the latest server state. Nothing is merged or discarded automatically. */
(() => {
  'use strict';
  const clone = x => JSON.parse(JSON.stringify(x));
  let host = null, revision = null, queue = Promise.resolve(), blocked = false;

  const init = h => { host = h; };

  async function fetchState() {
    const r = await fetch('/api/state', { cache: 'no-store' });
    if (!r.ok) throw new Error('load failed');
    const s = await r.json();
    return { revision: s.revision, pets: s.pets || [], records: s.records || [] };
  }

  async function load() {
    const s = await fetchState();
    revision = s.revision;
    return s;
  }

  function save() {
    queue = queue.then(sendOnce).catch(() => {});
    return queue;
  }

  async function sendOnce() {
    if (blocked) return;                       // conflict pending: keep local data, send nothing
    if (revision === null) { host.toast('Pawfect has not loaded your data yet, so nothing was saved. Reload the page.'); return; }
    const snapshot = clone(host.get());
    let r;
    try {
      r = await fetch('/api/state', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseRevision: revision, pets: snapshot.pets, records: snapshot.records })
      });
    } catch (e) { host.toast('Could not save to SQLite. Check that Pawfect is running.'); return; }
    if (r.ok) { revision = (await r.json()).revision; return; }
    if (r.status === 409) { blocked = true; showConflict(); return; }
    let msg = ''; try { msg = (await r.json()).error || ''; } catch (e) { /* ignore */ }
    host.toast(msg || 'Could not save to SQLite. Check that Pawfect is running.');
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
    const blob = new Blob([JSON.stringify({ note: 'Unsaved Pawfect changes', exportedAt: new Date().toISOString(), pets: d.pets, records: d.records }, null, 2)], { type: 'application/json' });
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
        revision = s.revision; host.set({ pets: s.pets, records: s.records }); blocked = false;
        d.close(); host.render(); host.toast('Reloaded the latest data');
      } catch (e) { host.toast('Could not reach Pawfect. Your unsaved changes are still here.'); armed = false; reload.textContent = 'Reload latest data'; }
    };
    if (!d.open) d.showModal();
  }

  window.PawfectSync = { init, load, save };
})();
