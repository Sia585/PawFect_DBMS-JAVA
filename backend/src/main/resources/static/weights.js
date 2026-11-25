/* Pawfect weight tracking: dated measurements per pet, stored in grams, shown in kg / lb / g.
   Uses globals from app.js (data, petId, esc, date, today, modal, toast, render, empty) and PawfectSync. */
(() => {
  'use strict';
  const UNITS = { kg: { g: 1000, dp: 3 }, lb: { g: 453.59237, dp: 3 }, g: { g: 1, dp: 0 } };
  const MAX_GRAMS = 2000000, MIN_DATE = '1900-01-01';
  let unit = 'kg';
  try { const u = localStorage.getItem('pawfect-weight-unit'); if (UNITS[u]) unit = u; } catch (e) { /* storage unavailable */ }

  const toGrams = (v, u) => Math.round(v * UNITS[u].g);
  const fromGrams = (g, u) => g / UNITS[u].g;
  const trim = (x, dp) => String(Number(x.toFixed(dp)));                       // 12.500 -> "12.5"
  const fmt = (g, u = unit) => trim(fromGrams(g, u), UNITS[u].dp) + ' ' + u;
  const delta = d => (d > 0 ? '+' : d < 0 ? '−' : '') + fmt(Math.abs(d));
  const weightsOf = pid => (data.weights || []).filter(w => w.petId === pid).sort((a, b) => a.date.localeCompare(b.date));
  const validDate = s => /^\d{4}-\d{2}-\d{2}$/.test(s || '') && s >= MIN_DATE && s <= today() && !isNaN(new Date(s + 'T12:00:00'));

  /* The weight shown on the profile: the latest dated measurement wins; otherwise the original profile weight. */
  window.petWeightText = p => {
    const l = weightsOf(p.id);
    if (l.length) return fmt(l[l.length - 1].grams);
    const raw = parseFloat(p.weight);
    if (!(raw > 0)) return p.weight ? p.weight + ' kg' : '—';
    return fmt(toGrams(raw, 'kg'));
  };

  // ---- chart ----------------------------------------------------------------------------------------
  function chart(l, name) {
    const W = 640, H = 250, L = 56, R = 22, T = 22, B = 36, pw = W - L - R, ph = H - T - B;
    const xs = l.map(w => new Date(w.date + 'T12:00:00').getTime()), ys = l.map(w => fromGrams(w.grams, unit));
    let x0 = Math.min(...xs), x1 = Math.max(...xs); if (x1 === x0) x1 = x0 + 864e5;
    let y0 = Math.min(...ys), y1 = Math.max(...ys);
    const pad = (y1 - y0) * 0.2 || Math.max(y1 * 0.05, unit === 'g' ? 5 : 0.1);
    y0 = Math.max(0, y0 - pad); y1 += pad;
    const X = t => L + (t - x0) / (x1 - x0) * pw, Y = v => T + ph - (v - y0) / (y1 - y0) * ph;
    const range = y1 - y0, dp = unit === 'g' ? 0 : range >= 10 ? 0 : range >= 1 ? 1 : range >= 0.1 ? 2 : 3;
    const multiYear = new Date(x0).getFullYear() !== new Date(x1).getFullYear();
    const short = t => new Date(t).toLocaleDateString(undefined, multiYear ? { month: 'short', year: '2-digit' } : { month: 'short', day: 'numeric' });
    let grid = '';
    for (let i = 0; i < 4; i++) {
      const v = y0 + range * i / 3, y = Y(v).toFixed(1);
      grid += `<line x1="${L}" x2="${W - R}" y1="${y}" y2="${y}"/><text x="${L - 9}" y="${(+y + 3.5).toFixed(1)}" text-anchor="end">${trim(v, dp)}</text>`;
    }
    const ticks = [...new Set([0, Math.round((xs.length - 1) / 3), Math.round((xs.length - 1) * 2 / 3), xs.length - 1].map(i => xs[i]))].sort((a, b) => a - b);
    const labels = ticks.map((t, i) => `<text x="${X(t).toFixed(1)}" y="${H - 11}" text-anchor="${i === 0 ? 'start' : i === ticks.length - 1 ? 'end' : 'middle'}">${esc(short(t))}</text>`).join('');
    const pts = xs.map((t, i) => [X(t), Y(ys[i])]);
    const line = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
    const area = line + ` L${pts[pts.length - 1][0].toFixed(1)} ${T + ph} L${pts[0][0].toFixed(1)} ${T + ph} Z`;
    const dots = pts.map((p, i) => `<circle class="w-dot${i === pts.length - 1 ? ' last' : ''}" cx="${p[0].toFixed(1)}" cy="${p[1].toFixed(1)}" r="${i === pts.length - 1 ? 6 : 4.2}"><title>${esc(date(l[i].date))} · ${esc(fmt(l[i].grams))}</title></circle>`).join('');
    const lp = pts[pts.length - 1], below = pts.length > 1 && pts[pts.length - 2][1] < lp[1];   // line dips into the last point: label goes underneath
    const lastLabel = `<text class="w-last" x="${lp[0].toFixed(1)}" y="${(below ? lp[1] + 22 : lp[1] - 13).toFixed(1)}" text-anchor="${lp[0] > W - 90 ? 'end' : 'middle'}">${esc(fmt(l[l.length - 1].grams))}</text>`;
    const summary = `Weight trend for ${name}: ${fmt(l[0].grams)} on ${date(l[0].date)} to ${fmt(l[l.length - 1].grams)} on ${date(l[l.length - 1].date)}, ${l.length} measurements`;
    return `<svg class="w-chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(summary)}"><title>${esc(summary)}</title>
      <defs><linearGradient id="w-grad" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#456c53" stop-opacity=".28"/><stop offset="1" stop-color="#456c53" stop-opacity="0"/></linearGradient></defs>
      <g class="w-grid">${grid}</g><path d="${area}" fill="url(#w-grad)"/><path class="w-line" d="${line}"/>${dots}${lastLabel}<g class="w-axis">${labels}</g></svg>`;
  }

  // ---- panel on the pet profile -----------------------------------------------------------------------
  window.weightPanel = p => {
    const l = weightsOf(p.id), n = importCandidates(p.id).filter(c => c.status !== 'duplicate').length;
    const seg = `<div class="seg" role="group" aria-label="Weight unit">${Object.keys(UNITS).map(k => `<button type="button" class="${k === unit ? 'active' : ''}" aria-pressed="${k === unit}" data-w="unit" data-unit="${k}">${k}</button>`).join('')}</div>`;
    let body;
    if (!l.length) {
      const profile = parseFloat(p.weight) > 0 ? ` Their profile lists ${esc(petWeightText(p))}; add a dated weigh-in to start tracking changes.` : '';
      body = empty('⚖', 'No weigh-ins yet', `Add ${esc(p.name)}’s first measurement to start a weight trend.${profile}`,
        `<button class="btn primary" data-w="add" data-pet="${p.id}"><span class="plus">+</span> Add first weight</button>`);
    } else {
      const last = l[l.length - 1], prev = l[l.length - 2];
      const stats = `<div class="w-stats"><div class="w-stat"><small>Latest</small><b>${esc(fmt(last.grams))}</b><span>${esc(date(last.date))}</span></div>
        <div class="w-stat"><small>Change</small><b>${prev ? esc(delta(last.grams - prev.grams)) : '—'}</b><span>${prev ? 'since ' + esc(date(prev.date)) : 'Add another to compare'}</span></div>
        <div class="w-stat"><small>Weigh-ins</small><b>${l.length}</b><span>since ${esc(date(l[0].date))}</span></div></div>`;
      const rows = l.map((w, i) => `<tr><td class="strong">${esc(date(w.date))}</td><td>${esc(fmt(w.grams))}</td><td class="w-delta">${i ? esc(delta(w.grams - l[i - 1].grams)) : '—'}</td><td class="w-note">${esc(w.note || '')}</td>
        <td class="w-actions"><button class="link" data-w="edit" data-id="${esc(w.id)}" aria-label="Edit weight from ${esc(date(w.date))}">Edit</button><button class="link danger-link" data-w="delete" data-id="${esc(w.id)}" aria-label="Remove weight from ${esc(date(w.date))}">Remove</button></td></tr>`).reverse().join('');
      body = stats + (l.length > 1 ? chart(l, p.name) : `<p class="sub w-hint">Add one more weigh-in to see ${esc(p.name)}’s trend.</p>`)
        + `<div class="w-history"><table class="table w-table"><thead><tr><th>Date</th><th>Weight</th><th>Change</th><th>Note</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>`;
    }
    return `<div class="panel weight-panel" style="margin-bottom:14px"><div class="panel-head"><h2 class="panel-title">Weight</h2><div class="w-tools">${seg}${l.length ? `<button class="btn light" data-w="add" data-pet="${p.id}">+ Add weight</button>` : ''}</div></div>${body}${n ? `<div class="w-foot"><button class="link" data-w="import" data-pet="${p.id}">Import from care records (${n})</button><span class="stat-meta">Review before anything is added</span></div>` : ''}</div>`;
  };

  /* The change is shown at once but only announced as done once the server confirms it. If the save fails
     the change is undone, so the screen never shows a weight that is not in the database. A conflict keeps
     it on screen (the conflict dialog offers to download it). */
  function confirmed(request, message, undo) {
    request.then(r => {
      if (r === 'ok') toast(message);
      else if (r === 'failed') { undo(); render(); toast('Not saved, so the change was undone. ' + PawfectSync.lastError()); }
    });
  }

  // ---- add / edit / remove ---------------------------------------------------------------------------
  function weightModal(pid, id, pre = {}) {
    const old = id ? (data.weights || []).find(w => w.id === id) : null;
    const d = $('#weight-modal'), p = pet(pid);
    if (!p || (id && !old)) return;
    const u = unit, val = old ? trim(fromGrams(old.grams, u), UNITS[u].dp) : '';
    modal(d, old ? 'Edit weight' : 'Add a weight', `${esc(p.name)} · stored precisely in grams`,
      `<div class="field"><label>Date *</label><input type="date" name="date" required min="${MIN_DATE}" max="${today()}" value="${esc(old?.date || pre.date || today())}"></div>
       <div class="field"><label>Weight *</label><div class="w-input"><input type="number" name="value" required min="0" step="any" inputmode="decimal" value="${esc(val)}" placeholder="e.g. 4.2"><select name="unit" aria-label="Unit">${Object.keys(UNITS).map(k => `<option ${k === u ? 'selected' : ''}>${k}</option>`).join('')}</select></div></div>
       <div class="field full"><label>Note</label><textarea name="note" maxlength="500" placeholder="Optional, e.g. after breakfast, vet scale">${esc(old?.note || pre.note || '')}</textarea></div>
       <div class="field full"><p class="w-error" id="w-error" role="alert"></p></div>`,
      e => {
        e.preventDefault();
        const f = new FormData(e.currentTarget), v = parseFloat(f.get('value')), un = f.get('unit'), dt = f.get('date');
        const g = toGrams(v, un), err = $('#w-error');
        if (!validDate(dt)) { err.textContent = 'Choose a date between 1900 and today.'; return; }
        if (!(v > 0) || g < 1) { err.textContent = 'Enter a weight greater than zero.'; return; }
        if (g > MAX_GRAMS) { err.textContent = 'That weight is too large. The maximum is 2,000 kg.'; return; }
        const w = { id: old?.id || crypto.randomUUID(), petId: pid, date: dt, grams: g, note: f.get('note').trim() || null, sourceRecordId: old ? old.sourceRecordId : (pre.src || null) };
        data.weights = old ? data.weights.map(x => x.id === w.id ? w : x) : [...(data.weights || []), w];
        d.close(); render();
        confirmed(PawfectSync.weightRequest('PUT', '/api/weights/' + encodeURIComponent(w.id), { petId: w.petId, date: w.date, grams: w.grams, note: w.note, sourceRecordId: w.sourceRecordId }),
          old ? 'Weight updated' : 'Weight added',
          () => { data.weights = old ? data.weights.map(x => x.id === w.id ? old : x) : data.weights.filter(x => x.id !== w.id); });
      }, old ? 'Save changes' : 'Add weight');
  }

  function removeWeight(id) {
    const w = (data.weights || []).find(x => x.id === id), d = $('#confirm-modal');
    if (!w) return;
    modal(d, 'Remove this weight?', 'This can’t be undone.', `<p class="sub">${esc(fmt(w.grams))} on ${esc(date(w.date))} will be removed from ${esc(pet(w.petId)?.name || 'your pet')}’s weight history.</p>`, e => {
      e.preventDefault();
      data.weights = data.weights.filter(x => x.id !== id);
      d.close(); render();
      confirmed(PawfectSync.weightRequest('DELETE', '/api/weights/' + encodeURIComponent(id)), 'Weight removed', () => { data.weights = [...data.weights, w]; });
    }, 'Remove weight');
  }

  // ---- reviewable import of historical weight notes ---------------------------------------------------
  const UNIT_RE = /(\d+(?:\.\d+)?)\s*(kgs?|kilograms?|lbs?|pounds?|grams?|g)\b/gi;

  /* Returns {grams, value, unit} only for a clear single value; otherwise {reason}. Nothing ambiguous is guessed. */
  function parseWeightText(text) {
    const t = String(text || '');
    if (/\d,\d/.test(t)) return { reason: 'Uses a decimal comma, so the value is unclear' };
    const found = [...t.matchAll(UNIT_RE)];
    if (!found.length) return { reason: /\d/.test(t) ? 'A number was found but no unit (kg, lb or g)' : 'No weight found in the note' };
    const seen = new Map();
    for (const m of found) {
      const k = m[2].toLowerCase(), un = k.startsWith('k') ? 'kg' : (k.startsWith('l') || k.startsWith('p')) ? 'lb' : 'g';
      seen.set(toGrams(parseFloat(m[1]), un), { value: parseFloat(m[1]), unit: un });
    }
    if (seen.size > 1) return { reason: 'Several different weights in one note' };
    if (/\d+\.\d+/.test(t.replace(UNIT_RE, ' '))) return { reason: 'Contains other decimal numbers' };
    const [grams, info] = [...seen.entries()][0];
    if (grams < 1 || grams > 200000) return { reason: 'Outside a plausible pet weight (1 g to 200 kg)' };
    return { grams, value: info.value, unit: info.unit };
  }

  /* Weight-type care records of this pet that were not imported yet. status: ready | review | duplicate. */
  function importCandidates(pid) {
    const done = new Set((data.weights || []).map(w => w.sourceRecordId).filter(Boolean));
    return data.records.filter(r => r.petId === pid && r.type === 'Weight' && !done.has(r.id)).map(r => {
      const text = [r.title, r.notes].filter(Boolean).join(' — '), parsed = parseWeightText(text), c = { rec: r, text, ...parsed };
      if (!validDate(r.date)) return { ...c, status: 'review', reason: 'The care record has no valid date' };
      if (parsed.reason) return { ...c, status: 'review' };
      const dup = (data.weights || []).some(w => w.petId === pid && w.date === r.date && w.grams === parsed.grams);
      return { ...c, status: dup ? 'duplicate' : 'ready' };
    }).sort((a, b) => (a.rec.date || '').localeCompare(b.rec.date || ''));
  }

  function importModal(pid) {
    const d = $('#import-modal'), p = pet(pid), all = importCandidates(pid);
    if (!p) return;
    const ready = all.filter(c => c.status === 'ready'), review = all.filter(c => c.status === 'review'), dups = all.filter(c => c.status === 'duplicate');
    const readyRows = ready.map((c, i) => `<label class="w-import-row"><input type="checkbox" data-i="${i}" checked><span class="strong">${esc(date(c.rec.date))}</span><span class="w-note">${esc(c.text)}</span><b>${esc(trim(c.value, 3) + ' ' + c.unit)}</b></label>`).join('');
    const reviewRows = review.map(c => `<div class="w-import-row review"><span class="strong">${esc(c.rec.date ? date(c.rec.date) : 'No date')}</span><span class="w-note">${esc(c.text)}<small>${esc(c.reason)}</small></span><button type="button" class="link" data-w="add" data-pet="${pid}" data-date="${esc(validDate(c.rec.date) ? c.rec.date : '')}" data-note="${esc(c.text.slice(0, 500))}" data-src="${esc(c.rec.id)}">Enter manually</button></div>`).join('');
    d.innerHTML = `<form><div class="modal-head"><div><h2>Import weights from care records</h2><p>${esc(p.name)} · your care records are never changed</p></div><button class="close" type="button" data-close>×</button></div>
      <div class="modal-body"><p class="sub" style="margin:0 0 12px">Clear notes with one dated value are listed below and pre-selected. Anything ambiguous is never imported automatically.</p>
      ${ready.length ? `<h3 class="w-h3">Ready to import</h3><div class="w-import">${readyRows}</div>` : '<p class="sub">Nothing is clear enough to import automatically.</p>'}
      ${review.length ? `<h3 class="w-h3">Needs your review — not imported</h3><div class="w-import">${reviewRows}</div>` : ''}
      ${dups.length ? `<p class="sub">${dups.length} care record${dups.length > 1 ? 's' : ''} already match${dups.length > 1 ? '' : 'es'} an existing weight and ${dups.length > 1 ? 'are' : 'is'} skipped.</p>` : ''}</div>
      <div class="modal-foot"><button class="btn line" type="button" data-close>Cancel</button><button class="btn primary" type="submit" id="w-import-go">Import</button></div></form>`;
    d.showModal();
    const go = d.querySelector('#w-import-go'), boxes = [...d.querySelectorAll('input[type=checkbox]')];
    const refresh = () => { const n = boxes.filter(b => b.checked).length; go.textContent = n ? `Import ${n} selected` : 'Import'; go.disabled = !n; };
    boxes.forEach(b => b.onchange = refresh); refresh();
    d.querySelectorAll('[data-close]').forEach(b => b.onclick = () => d.close());
    d.querySelector('form').onsubmit = e => {
      e.preventDefault();
      const chosen = boxes.filter(b => b.checked).map(b => ready[+b.dataset.i]);
      if (!chosen.length) return;
      const items = chosen.map(c => ({ id: crypto.randomUUID(), petId: pid, date: c.rec.date, grams: c.grams, note: c.text.slice(0, 500), sourceRecordId: c.rec.id }));
      data.weights = [...(data.weights || []), ...items];
      d.close(); render();
      const ids = new Set(items.map(i => i.id));
      confirmed(PawfectSync.weightRequest('POST', '/api/weights/import', { items }), `Imported ${items.length} weight${items.length > 1 ? 's' : ''}`, () => { data.weights = data.weights.filter(x => !ids.has(x.id)); });
    };
  }

  document.addEventListener('click', e => {
    const x = e.target.closest('[data-w]');
    if (!x) return;
    const a = x.dataset.w;
    if (a === 'unit') { unit = x.dataset.unit; try { localStorage.setItem('pawfect-weight-unit', unit); } catch (err) { /* ignore */ } render(); }
    else if (a === 'add') { $('#import-modal').open && $('#import-modal').close(); weightModal(x.dataset.pet || petId, null, { date: x.dataset.date, note: x.dataset.note, src: x.dataset.src }); }
    else if (a === 'edit') weightModal(petId, x.dataset.id);
    else if (a === 'delete') removeWeight(x.dataset.id);
    else if (a === 'import') importModal(x.dataset.pet || petId);
  });

  window.PawfectWeights = { toGrams, fromGrams, parseWeightText, importCandidates };
})();
