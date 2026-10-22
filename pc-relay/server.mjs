import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.ULTRON_PORT || 8787);
const HOST = '127.0.0.1';
const MAX_FRAME_BYTES = 900_000;
const MAX_VIDEO_BYTES = 2 * 1024 * 1024 * 1024;
const FRAME_INTERVAL_MS = 160;
const MEDIA_DIR = path.join(ROOT, 'media');
const jobs = new Map();
let pinAttempts = 0;
let pinLockedUntil = 0;
let pairing = newPairing();
let pairedToken = null;
let latestFrame = null;
let lastFrameAt = 0;
let frameNumber = 0;

function newPairing() {
  pinAttempts = 0;
  pinLockedUntil = 0;
  return { pin: crypto.randomInt(100000, 1000000).toString(), expiresAt: Date.now() + 5 * 60_000 };
}
function json(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'content-length': Buffer.byteLength(body) });
  res.end(body);
}
function readBody(req, limit = 16_384) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > limit) { reject(Object.assign(new Error('Request too large'), { status: 413 })); req.destroy(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}
function isTailscaleRequest(req) {
  return typeof req.headers['tailscale-user-login'] === 'string';
}
function isLocal(req) {
  const ip = req.socket.remoteAddress || '';
  const loopback = ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1';
  return loopback && !isTailscaleRequest(req);
}
function authorized(req) {
  const supplied = (req.headers.authorization || '').replace(/^Bearer\\s+/i, '');
  return !!pairedToken && supplied.length === pairedToken.length && crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(pairedToken));
}
function run(command, args, onProgress) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let tail = '';
    child.stderr.on('data', chunk => {
      tail = (tail + chunk.toString('utf8')).slice(-6000);
      onProgress?.(tail.trim().split('\\n').slice(-1)[0] || '');
    });
    child.on('error', error => reject(new Error(`Programm ${command} nicht gefunden oder nicht startbar: ${error.message}`)));
    child.on('close', code => code === 0 ? resolve() : reject(new Error(`${command} wurde mit Exit-Code ${code} beendet. ${tail.slice(-1200)}`)));
  });
}
async function reconstruct(job) {
  const work = path.join(MEDIA_DIR, job.id);
  const images = path.join(work, 'images');
  await fsp.mkdir(images, { recursive: true });
  job.state = 'frames'; job.message = 'Videoframes werden vorbereitet …';
  await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-i', job.videoPath, '-vf', "fps=1/2,scale='min(1920,iw)':-2", '-q:v', '2', path.join(images, 'frame-%06d.jpg'), '-y'], message => { if (message) job.message = message; });
  const extracted = await fsp.readdir(images);
  if (extracted.length < 8) throw new Error('Zu wenige Frames. Nimm ein längeres Video mit mehreren überlappenden Blickwinkeln auf.');
  job.state = 'reconstructing'; job.message = `${extracted.length} Frames: COLMAP rekonstruiert die Szene …`;
  await run(process.env.ULTRON_COLMAP || 'colmap', ['automatic_reconstructor', '--workspace_path', work, '--image_path', images, '--data_type', 'VIDEO', '--quality', 'MEDIUM', '--mesher', 'POISSON'], message => { if (message) job.message = message.slice(0, 240); });
  const candidates = [
    path.join(work, 'dense', '0', 'meshed-poisson.ply'),
    path.join(work, 'dense', 'meshed-poisson.ply'),
    path.join(work, 'dense', 'fused.ply')
  ];
  let modelPath = null;
  for (const candidate of candidates) {
    try { await fsp.access(candidate); modelPath = candidate; break; } catch {}
  }
  if (!modelPath) throw new Error('COLMAP hat keine exportierbare Mesh-Datei erzeugt. Prüfe die Aufnahme und COLMAP-Ausgabe.');
  const modelName = path.basename(modelPath);
  job.state = 'done'; job.message = '3D-Rekonstruktion fertig.'; job.modelName = modelName;
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || '127.0.0.1'}`);
    if (req.method === 'GET' && url.pathname === '/') {
      const html = fs.readFileSync(path.join(ROOT, 'index.html'));
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'content-length': html.length });
      res.end(html); return;
    }
    if (req.method === 'GET' && url.pathname === '/api/pairing') {
      if (!isLocal(req)) return json(res, 403, { error: 'Pair code is shown only on the PC.' });
      if (pinLockedUntil && Date.now() >= pinLockedUntil) { pinLockedUntil = 0; pairing = newPairing(); }
      if (!pairedToken && !pinLockedUntil && Date.now() >= pairing.expiresAt) pairing = newPairing();
      return json(res, 200, { pin: pairedToken || pinLockedUntil ? '' : pairing.pin, expiresAt: pairing.expiresAt, locked: !!pinLockedUntil, paired: !!pairedToken, frameNumber });
    }
    if (req.method === 'POST' && url.pathname === '/api/pair') {
      if (!isTailscaleRequest(req)) return json(res, 403, { error: 'Use the private Tailscale Serve URL to pair.' });
      if (pairedToken) return json(res, 409, { error: 'Ein Handy ist bereits gekoppelt. Trenne es zuerst am PC.' });
      if (pinAttempts >= 8) return json(res, 429, { error: 'Zu viele Versuche. Bitte am PC einen neuen Code erzeugen.' });
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      if (typeof body.pin !== 'string' || Date.now() >= pairing.expiresAt || body.pin !== pairing.pin) {
        pinAttempts++;
        if (pinAttempts >= 8) { pairing = { pin: '', expiresAt: 0 }; pinLockedUntil = Date.now() + 5 * 60_000; }
        return json(res, pinAttempts >= 8 ? 429 : 401, { error: pinAttempts >= 8 ? 'Code gesperrt. Erzeuge am PC einen neuen Code.' : 'Code falsch oder abgelaufen. Bitte den aktuellen Code am PC eingeben.' });
      }
      pairedToken = crypto.randomBytes(32).toString('hex');
      pairing = { pin: '', expiresAt: 0 };
      return json(res, 200, { token: pairedToken, paired: true });
    }
    if (req.method === 'POST' && url.pathname === '/api/unpair') {
      if (!isLocal(req)) return json(res, 403, { error: 'Unpairing is available on the PC only.' });
      pairedToken = null; pairing = newPairing(); latestFrame = null; frameNumber = 0;
      return json(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url.pathname === '/api/frame') {
      if (!isTailscaleRequest(req)) return json(res, 403, { error: 'Use the private Tailscale Serve URL to stream.' });
      if (!authorized(req)) return json(res, 401, { error: 'Not paired.' });
      if (!req.headers['content-type']?.startsWith('image/jpeg')) return json(res, 415, { error: 'Send a JPEG frame.' });
      if (Date.now() - lastFrameAt < FRAME_INTERVAL_MS) { req.resume(); return json(res, 429, { error: 'Frame rate limited.' }); }
      const frame = await readBody(req, MAX_FRAME_BYTES);
      if (!frame.length) return json(res, 400, { error: 'Empty frame.' });
      latestFrame = frame; lastFrameAt = Date.now(); frameNumber++;
      return json(res, 200, { ok: true, frameNumber, receivedAt: lastFrameAt });
    }
    if (req.method === 'GET' && url.pathname === '/api/frame') {
      if (!isLocal(req)) return json(res, 403, { error: 'Live view is available on the PC only.' });
      if (!latestFrame) return json(res, 404, { error: 'Noch kein Bild empfangen.' });
      res.writeHead(200, { 'content-type': 'image/jpeg', 'cache-control': 'no-store', 'content-length': latestFrame.length });
      res.end(latestFrame); return;
    }
    if (req.method === 'GET' && url.pathname === '/api/status') {
      if (!isLocal(req)) return json(res, 403, { error: 'Status is available on the PC only.' });
      return json(res, 200, { paired: !!pairedToken, frameNumber, lastFrameAt });
    }
    if (req.method === 'POST' && url.pathname === '/api/video') {
      if (!isLocal(req)) return json(res, 403, { error: 'Video upload is available from the PC page only.' });
      if (!req.headers['content-type']?.startsWith('video/')) return json(res, 415, { error: 'Bitte eine MP4- oder MOV-Videodatei auswählen.' });
      await fsp.mkdir(MEDIA_DIR, { recursive: true });
      const id = crypto.randomUUID();
      const videoPath = path.join(MEDIA_DIR, `${id}.mp4`);
      let size = 0;
      const limiter = new Transform({ transform(chunk, _encoding, callback) {
        size += chunk.length;
        callback(size > MAX_VIDEO_BYTES ? Object.assign(new Error('Video größer als 2 GB.'), { status: 413 }) : null, chunk);
      } });
      await pipeline(req, limiter, fs.createWriteStream(videoPath, { flags: 'wx' }));
      jobs.set(id, { id, state: 'uploaded', message: 'Video bereit.', videoPath, createdAt: Date.now() });
      return json(res, 201, { id, state: 'uploaded' });
    }
    if (req.method === 'POST' && url.pathname === '/api/reconstruct') {
      if (!isLocal(req)) return json(res, 403, { error: '3D-Aufträge können nur lokal am PC gestartet werden.' });
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      const job = jobs.get(body.id);
      if (!job) return json(res, 404, { error: 'Video nicht gefunden.' });
      if (job.state !== 'uploaded') return json(res, 409, { error: 'Dieser Auftrag wurde bereits gestartet.' });
      job.state = 'queued';
      reconstruct(job).catch(error => { job.state = 'error'; job.message = error.message; });
      return json(res, 202, { id: job.id, state: job.state });
    }
    if (req.method === 'GET' && url.pathname.startsWith('/api/reconstruct/')) {
      if (!isLocal(req)) return json(res, 403, { error: '3D-Status ist nur am PC verfügbar.' });
      const id = url.pathname.split('/').pop();
      const job = jobs.get(id);
      if (!job) return json(res, 404, { error: 'Auftrag nicht gefunden.' });
      return json(res, 200, { id, state: job.state, message: job.message, modelName: job.modelName || '' });
    }
    if (req.method === 'GET' && url.pathname.startsWith('/api/model/')) {
      if (!isLocal(req)) return json(res, 403, { error: '3D-Modelle können nur lokal am PC geladen werden.' });
      const id = url.pathname.split('/').pop();
      const job = jobs.get(id);
      if (!job || job.state !== 'done') return json(res, 404, { error: 'Modell noch nicht verfügbar.' });
      const modelPath = path.join(MEDIA_DIR, id, 'dense', '0', 'meshed-poisson.ply');
      let actualPath = modelPath;
      try { await fsp.access(actualPath); } catch { actualPath = path.join(MEDIA_DIR, id, 'dense', 'meshed-poisson.ply'); }
      const stat = await fsp.stat(actualPath);
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': stat.size, 'content-disposition': `attachment; filename="${job.modelName || 'ultron-model.ply'}"`, 'cache-control': 'no-store' });
      fs.createReadStream(actualPath).pipe(res); return;
    }
    if (req.method === 'POST' && url.pathname === '/api/rotate-code') {
      if (!isLocal(req)) return json(res, 403, { error: 'Code rotation is available on the PC only.' });
      pairedToken = null; latestFrame = null; frameNumber = 0; pairing = newPairing();
      return json(res, 200, { pin: pairing.pin, expiresAt: pairing.expiresAt, paired: false });
    }
    json(res, 404, { error: 'Not found.' });
  } catch (error) {
    if (!res.headersSent) json(res, error.status || 500, { error: error.message || 'Server error.' });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`ULTRON PC Relay läuft lokal auf http://${HOST}:${PORT}`);
  console.log('Zum sicheren Fernzugriff Tailscale Serve im privaten Tailnet verwenden.');
  console.log(`Pairing-Code: ${pairing.pin} (5 Minuten gültig)`);
});
