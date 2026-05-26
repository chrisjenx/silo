/*
 * Demo data layer for the static Silo dashboard hosted on GitHub Pages.
 *
 * There is NO backend here. This patches window.fetch so the real admin
 * pages (copied from modules/server/src/main/resources/static/admin) render
 * against simulated, slowly drifting cache metrics. Counters advance on each
 * poll; reloading the page resets them. Nothing leaves the browser.
 */
(function () {
  // Seed values so the dashboard opens on a believably "warm" cache.
  let hits = 128_400;
  let misses = 18_900;
  let puts = 42_300;
  let evictions = 512;
  let entries = 41_788;
  let bytes = entries * 262_144; // ~250 KiB average entry

  // Advance the counters one polling interval and return the instantaneous
  // hit rate for THIS interval — feeding that (rather than the flat
  // cumulative rate) into the sparkline keeps it lively.
  function tick() {
    const dHits = 20 + Math.floor(Math.random() * 110);
    const dMiss = 2 + Math.floor(Math.random() * 18);
    const dPut = 4 + Math.floor(Math.random() * 36);
    const dEvict = Math.random() < 0.3 ? Math.floor(Math.random() * 12) : 0;
    hits += dHits;
    misses += dMiss;
    puts += dPut;
    evictions += dEvict;
    entries = Math.max(0, entries + dPut - dEvict);
    bytes = Math.max(
      0,
      bytes + dPut * (180_000 + Math.floor(Math.random() * 160_000)) - dEvict * 240_000,
    );
    return dHits / (dHits + dMiss);
  }

  function stats() {
    const inst = tick();
    return {
      entryCount: entries,
      bytesStored: bytes,
      hits,
      misses,
      puts,
      evictions,
      hitRate: Number(inst.toFixed(4)),
    };
  }

  const storage = {
    root: "/data",
    fsType: "ext4",
    maxBytes: 107_374_182_400,
    reservedFreeBytes: 5_368_709_120,
  };

  // Mirrors the server's GET /api/config shape so the dashboard's limits
  // panel renders the same caps it would against a real Silo.
  const config = {
    server: { port: 8080, host: "0.0.0.0" },
    storage: {
      root: "/data",
      maxBytes: 107_374_182_400,
      maxEntryBytes: 2_147_483_648,
      maxEntries: 1_000_000,
      reservedFreeBytes: 5_368_709_120,
      reservedFreeInodes: 100_000,
      verifySha256OnRead: false,
      allowUnsupportedFs: false,
    },
    eviction: { maxAgeDays: 30, maxDeletesPerCycle: 1_000 },
    auth: { anonymousRead: true, oidcEnabled: false },
  };

  function json(obj) {
    return new Response(JSON.stringify(obj), {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }

  const realFetch = typeof window.fetch === "function" ? window.fetch.bind(window) : null;

  window.fetch = function (input, init) {
    const url = typeof input === "string" ? input : (input && input.url) || "";
    if (url.endsWith("/api/stats")) return Promise.resolve(json(stats()));
    if (url.endsWith("/api/storage")) return Promise.resolve(json(storage));
    if (url.endsWith("/api/config")) return Promise.resolve(json(config));
    if (url.endsWith("/health") || url.endsWith("/ready")) {
      // about.html reads the version off the `server` response header.
      return Promise.resolve(new Response("OK", { status: 200, headers: { server: "silo/0.1.0-demo" } }));
    }
    return realFetch ? realFetch(input, init) : Promise.reject(new Error("offline demo: " + url));
  };

  // A small banner so visitors know the numbers are simulated.
  window.addEventListener("DOMContentLoaded", function () {
    const banner = document.createElement("div");
    banner.textContent = "demo · data is simulated · no backend";
    banner.style.cssText =
      "position:fixed;top:0;left:0;right:0;text-align:center;z-index:9;" +
      "font:12px ui-monospace,monospace;padding:4px 8px;" +
      "background:var(--silo-border);color:var(--silo-bg);";
    document.body.appendChild(banner);
    document.body.style.paddingTop = "2.4rem";
  });
})();
