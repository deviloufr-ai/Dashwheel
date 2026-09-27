// Minimal CDP screenshotter: node shot.mjs <url> <out.png> <w> <h> [readyTitle]
import { spawn } from 'node:child_process';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [url, out, w = '1400', h = '1000', ready = 'READY', scale = '1'] = process.argv.slice(2);
const chrome = process.env.CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const port = 9300 + Math.floor(Math.random() * 500);
const proc = spawn(chrome, ['--headless=new', `--remote-debugging-port=${port}`, '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
  '--hide-scrollbars', '--allow-file-access-from-files', `--user-data-dir=${mkdtempSync(join(tmpdir(), 'cdp'))}`, `--window-size=${w},${h}`, 'about:blank']);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let targets;
for (let i = 0; i < 50; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); break; } catch { await sleep(200); } }
const page = targets.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pending = new Map();
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); } };
const send = (method, params = {}) => new Promise(r => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
await send('Emulation.setDeviceMetricsOverride', { width: +w, height: +h, deviceScaleFactor: +scale, mobile: false });
await send('Page.navigate', { url });
for (let i = 0; i < 120; i++) {
  const r = await send('Runtime.evaluate', { expression: 'document.title' });
  if (r?.result?.value === ready) break;
  await sleep(500);
}
await sleep(800);
const shot = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(out, Buffer.from(shot.data, 'base64'));
console.log('wrote', out);
ws.close(); proc.kill();
process.exit(0);
