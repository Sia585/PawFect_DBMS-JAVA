/* Pawfect pet photographs (browser side).
   - A chosen picture is resized in the browser (long edge <= 1024 px, JPEG 0.85, transparency on white) and shown
     as a preview inside the pet form. Nothing is uploaded until "Save" is pressed.
   - The server validates and re-encodes it again; pet.photo only changes once the server has confirmed.
   - Anywhere a pet is shown, avatar() returns the photo, or the emoji when there is none or it cannot load. */
(() => {
  'use strict';
  const MAX_SIDE = 1024, QUALITY = 0.85, MAX_FILE = 30 * 1024 * 1024;
  const esc = x => String(x ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const NAME = /^[0-9a-f]{32}\.jpg$/;

  /* Inner HTML for an avatar box: the photo (cover-cropped) or the fallback emoji. */
  function avatar(p, emoji, alt) {
    if (p && NAME.test(p.photo || '')) {
      return `<img class="pet-img" src="/photos/${p.photo}" alt="${esc(alt || '')}" data-fallback="${esc(emoji)}" decoding="async">`;
    }
    return esc(emoji);
  }

  // A photo that fails to load (file missing, restored without photos) quietly becomes the emoji again.
  document.addEventListener('error', e => {
    const img = e.target;
    if (img && img.tagName === 'IMG' && img.classList.contains('pet-img')) img.replaceWith(document.createTextNode(img.dataset.fallback || '🐾'));
  }, true);

  function loadImage(file) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file), img = new Image();
      img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('unreadable')); };
      img.src = url;
    });
  }

  /* File -> JPEG Blob. Rejects with an Error whose message is safe to show the person. */
  async function toJpeg(file) {
    if (!file) throw new Error('No picture chosen.');
    if (file.size > MAX_FILE) throw new Error('That picture is larger than 30 MB. Please pick a smaller one.');
    let src;
    try { src = await createImageBitmap(file, { imageOrientation: 'from-image' }); }
    catch (e) { try { src = await loadImage(file); } catch (e2) { throw new Error('That file is not a picture Pawfect can read. Try a JPEG or PNG.'); } }
    const w0 = src.width, h0 = src.height;
    if (!w0 || !h0) throw new Error('That file is not a picture Pawfect can read. Try a JPEG or PNG.');
    if (w0 < 8 || h0 < 8) throw new Error('That picture is too small.');
    const k = Math.min(1, MAX_SIDE / Math.max(w0, h0)), w = Math.max(1, Math.round(w0 * k)), h = Math.max(1, Math.round(h0 * k));
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const g = c.getContext('2d');
    g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h);
    g.imageSmoothingQuality = 'high';
    g.drawImage(src, 0, 0, w, h);
    if (src.close) src.close();
    const blob = await new Promise(r => c.toBlob(r, 'image/jpeg', QUALITY));
    if (!blob) throw new Error('Could not prepare that picture.');
    return blob;
  }

  /* The form field. Returns HTML; wire() then returns a controller the form reads when it is submitted. */
  function field() {
    return `<div class="field full photo-field"><label id="ph-label">Photo</label><div class="photo-edit" role="group" aria-labelledby="ph-label"><div class="pet-photo photo-preview" id="ph-prev"></div><div class="photo-actions"><div><button type="button" class="btn line" id="ph-choose">Choose photo…</button> <button type="button" class="link" id="ph-remove" hidden>Remove photo</button></div><small id="ph-hint">JPEG or PNG. It is resized and compressed for you.</small><small id="ph-msg" class="photo-msg" role="status"></small></div><input type="file" id="ph-file" accept="image/jpeg,image/png,image/webp,image/gif" hidden></div></div>`;
  }

  function wire(dialog, old, face) {
    const prev = dialog.querySelector('#ph-prev'), file = dialog.querySelector('#ph-file'), choose = dialog.querySelector('#ph-choose'),
      remove = dialog.querySelector('#ph-remove'), msg = dialog.querySelector('#ph-msg'), faceSel = dialog.querySelector('[name=face]');
    let blob = null, url = null, removed = false;
    const emoji = () => faceSel.value || face();
    const revoke = () => { if (url) { URL.revokeObjectURL(url); url = null; } };
    function paint() {
      if (blob) prev.innerHTML = `<img class="pet-img" src="${url}" alt="Preview of the chosen photo">`;
      else if (old?.photo && !removed) prev.innerHTML = avatar(old, emoji(), 'Current photo');
      else prev.textContent = emoji();
      remove.hidden = !(blob || (old?.photo && !removed));
      choose.textContent = blob || (old?.photo && !removed) ? 'Replace photo…' : 'Choose photo…';
    }
    choose.onclick = () => file.click();
    file.onchange = async () => {
      const f = file.files[0]; file.value = ''; msg.textContent = '';
      if (!f) return;
      try {
        const b = await toJpeg(f);
        revoke(); blob = b; url = URL.createObjectURL(b); removed = false;
        msg.textContent = `Ready: ${Math.round(b.size / 1024)} KB after resizing. It is saved when you press Save.`;
      } catch (e) { msg.textContent = e.message; }
      paint();
    };
    remove.onclick = () => { revoke(); blob = null; removed = !!old?.photo; msg.textContent = removed ? 'The photo is removed when you press Save.' : ''; paint(); };
    faceSel.addEventListener('change', () => { if (!blob && (!old?.photo || removed)) paint(); });
    dialog.addEventListener('close', revoke, { once: true });
    paint();
    return { result: () => ({ blob, remove: removed && !blob }) };
  }

  window.PawfectPhotos = { avatar, field, wire, toJpeg };
})();
