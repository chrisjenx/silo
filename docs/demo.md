---
title: Demo
nav_order: 2
---

# Live demo

Below is the **real** Silo admin dashboard — the same static pages the server
ships at `/admin` — running here against a simulated data layer. There is no
backend: `fetch` is mocked client-side, the counters drift on the dashboard's
own 5-second poll, and reloading resets them. Nothing leaves your browser.

<p>
  <a href="demo/dashboard.html" target="_blank" rel="noopener"><strong>Open the dashboard in a new tab ▸</strong></a>
</p>

<iframe src="demo/dashboard.html"
        title="Silo admin dashboard (simulated data)"
        loading="lazy"
        style="width:100%;height:540px;border:1px solid #444;border-radius:4px;background:#0a0e0a;"></iframe>

From the [demo home screen](demo/index.html) you can switch between the
**phosphor**, **amber**, and **paper** themes (the choice persists in
`localStorage`), and the [about page](demo/about.html) shows the version banner.

> The numbers here are illustrative. To see your own cache, run Silo (see the
> [home page](index.md)) and open `http://localhost:8080/admin`.
