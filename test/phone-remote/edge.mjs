// A visible Edge for a test, and its whole end. Killing the process Node
// started is not enough on Windows: Edge's browser goes on, holding its
// profile and its debugging port, and the next run on that port would drive
// the old page. So a busy port is refused before anything starts, and the
// end is Browser.close over DevTools, then the process tree.
import { spawn, spawnSync } from 'node:child_process';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function answers(port) {
  try {
    const r = await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(800) });
    return r.ok ? await r.json() : null;
  } catch {
    return null;
  }
}

/** Edge on `port` with `profile`; throws if something already answers there. */
export async function launchEdge(edge, { port, profile, args = [] }) {
  if (await answers(port)) throw new Error(`port ${port} already has a browser on it: close it first`);
  const child = spawn(edge, [
    `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
    '--disable-features=CalculateNativeWinOcclusion', ...args, 'about:blank',
  ], { stdio: 'ignore' });
  let page = null;
  for (let i = 0; i < 80 && !page; i++) {
    await sleep(250);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      page = list.find((t) => t.type === 'page') || null;
    } catch { /* starting */ }
  }
  if (!page) throw new Error(`no page on port ${port}`);
  return {
    child,
    page,
    async close() {
      const v = await answers(port);
      if (v && v.webSocketDebuggerUrl) {
        try {
          const ws = new WebSocket(v.webSocketDebuggerUrl);
          await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; setTimeout(j, 2000); });
          ws.send(JSON.stringify({ id: 1, method: 'Browser.close' }));
          await sleep(500);
          try { ws.close(); } catch { /* gone */ }
        } catch { /* the tree below */ }
      }
      if (process.platform === 'win32' && child.pid) spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
      else try { child.kill('SIGKILL'); } catch { /* gone */ }
      for (let i = 0; i < 20 && (await answers(port)); i++) await sleep(250);
    },
  };
}
