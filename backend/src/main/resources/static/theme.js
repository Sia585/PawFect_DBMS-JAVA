/* Pawfect theme: Light, Dark or System. Loaded in <head> and run immediately, so the right theme is set
   before the first paint (no flash). The choice lives in localStorage ('pawfect-theme'); System follows the OS. */
(() => {
  'use strict';
  const KEY = 'pawfect-theme', MODES = ['light', 'system', 'dark'];
  const media = window.matchMedia ? matchMedia('(prefers-color-scheme: dark)') : null;
  const COLORS = { light: '#f7f6f2', dark: '#0d130f' };

  const stored = () => { try { const m = localStorage.getItem(KEY); return MODES.includes(m) ? m : 'system'; } catch (e) { return 'system'; } };
  let mode = stored();

  function apply() {
    const dark = mode === 'dark' || (mode === 'system' && !!media && media.matches);
    const root = document.documentElement;
    root.dataset.theme = dark ? 'dark' : 'light';
    root.dataset.themeMode = mode;
    const meta = document.querySelector('meta[name=theme-color]');
    if (meta) meta.content = dark ? COLORS.dark : COLORS.light;
    document.querySelectorAll('.theme-seg button').forEach(b => {
      const on = b.dataset.themeChoice === mode;
      b.classList.toggle('active', on);
      b.setAttribute('aria-checked', on ? 'true' : 'false');
      b.tabIndex = on ? 0 : -1;
    });
  }

  function set(next) {
    if (!MODES.includes(next)) return;
    mode = next;
    try { localStorage.setItem(KEY, mode); } catch (e) { /* private mode: the choice lasts for this page only */ }
    apply();
  }

  apply();                                                       // runs before the body exists: sets the attribute only
  if (media) (media.addEventListener ? media.addEventListener('change', apply) : media.addListener(apply));
  window.addEventListener('storage', e => { if (e.key === KEY) { mode = stored(); apply(); } });   // other tabs follow

  document.addEventListener('DOMContentLoaded', () => {
    const seg = document.querySelector('.theme-seg');
    if (seg) {
      seg.addEventListener('click', e => { const b = e.target.closest('[data-theme-choice]'); if (b) set(b.dataset.themeChoice); });
      seg.addEventListener('keydown', e => {                      // radio-group arrow keys
        const i = MODES.indexOf(mode), d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
        if (!d) return;
        e.preventDefault(); set(MODES[(i + d + MODES.length) % MODES.length]);
        seg.querySelector('.active')?.focus();
      });
    }
    apply();
  });

  window.PawfectTheme = { set, get: () => mode };
})();
