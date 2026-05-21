// Silo design-token theme picker. Persists choice in localStorage so
// the user keeps their preferred theme across reloads. Themes:
//   phosphor (default) · amber · paper
(function () {
    const KEY = 'silo-theme';
    const ALLOWED = new Set(['phosphor', 'amber', 'paper']);
    const stored = (typeof localStorage !== 'undefined' && localStorage.getItem(KEY)) || 'phosphor';
    const theme = ALLOWED.has(stored) ? stored : 'phosphor';
    document.documentElement.setAttribute('data-theme', theme);

    window.siloSetTheme = function (next) {
        if (!ALLOWED.has(next)) return;
        document.documentElement.setAttribute('data-theme', next);
        try { localStorage.setItem(KEY, next); } catch (e) { /* ignore */ }
    };
})();
