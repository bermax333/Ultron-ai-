import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.ULTRON_PORT || 8787);
const HOST = process.env.ULTRON_HOST || '0.0.0.0';
const MAX_FRAME_BYTES = 900_000;
const DEFAULT_MAX_VIDEO_BYTES = 20 * 1024 * 1024 * 1024;
const configuredMaxVideoBytes = Number(process.env.ULTRON_MAX_VIDEO_BYTES || DEFAULT_MAX_VIDEO_BYTES);
const MAX_VIDEO_BYTES = Number.isSafeInteger(configuredMaxVideoBytes) && configuredMaxVideoBytes > 0 ? configuredMaxVideoBytes : DEFAULT_MAX_VIDEO_BYTES;
const configuredRequestTimeout = Number(process.env.ULTRON_REQUEST_TIMEOUT_MS || 2 * 60 * 60 * 1000);
const REQUEST_TIMEOUT_MS = Number.isSafeInteger(configuredRequestTimeout) && configuredRequestTimeout >= 0 ? configuredRequestTimeout : 2 * 60 * 60 * 1000;
const MAX_MIC_CHUNK_BYTES = 64 * 1024;
const FRAME_INTERVAL_MS = 160;
const MEDIA_DIR = path.join(ROOT, 'media');
const VENDOR_FILES = new Set(['qrcode.min.js', 'three.module.js', 'OrbitControls.js', 'PLYLoader.js']);
const jobs = new Map();
let latestJobId = null;
let pinAttempts = 0;
let pinLockedUntil = 0;
let pairing = newPairing();
let pairedToken = null;
let latestFrame = null;
let lastFrameAt = 0;
let frameNumber = 0;
let latestMicChunk = null;
let micSequence = 0;
let lastMicAt = 0;

function formatGigabytes(bytes) {
  return (bytes / (1024 ** 3)).toFixed(1) + ' GB';
}
function rejectDeclaredOversize(req, res) {
  const contentLength = Number(req.headers['content-length'] || 0);
  if (!Number.isFinite(contentLength) || contentLength <= MAX_VIDEO_BYTES) return false;
  req.resume();
  json(res, 413, { error: 'Video ist ' + formatGigabytes(contentLength) + ' groß. Limit: ' + formatGigabytes(MAX_VIDEO_BYTES) + '. Limit mit ULTRON_MAX_VIDEO_BYTES anpassen.' });
  return true;
}
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
function isLoopbackRequest(req) {
  const ip = (req.socket.remoteAddress || '').toLowerCase();
  return ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1';
}
function isPrivateIPv4(ip) {
  const octets = ip.split('.').map(Number);
  if (octets.length !== 4 || octets.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return false;
  return octets[0] === 10 ||
    (octets[0] === 192 && octets[1] === 168) ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31);
}
function isLanRequest(req) {
  let ip = (req.socket.remoteAddress || '').toLowerCase();
  if (ip.startsWith('::ffff:')) ip = ip.slice(7);
  if (isPrivateIPv4(ip)) return true;
  return ip.startsWith('fc') || ip.startsWith('fd');
}
function isTrustedRelay(req) {
  return isTailscaleRequest(req) || isLanRequest(req);
}
function isLocal(req) {
  return isLoopbackRequest(req) && !isTailscaleRequest(req);
}
function getLanUrls() {
  const addresses = [];
  for (const records of Object.values(os.networkInterfaces())) {
    for (const record of records || []) {
      if (record.internal || (record.family !== 'IPv4' && record.family !== 4) || !isPrivateIPv4(record.address)) continue;
      const url = `http://${record.address}:${PORT}`;
      if (!addresses.includes(url)) addresses.push(url);
    }
  }
  return addresses;
}
function authorized(req) {
  const supplied = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  return !!pairedToken && supplied.length === pairedToken.length && crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(pairedToken));
}
function run(command, args, onProgress) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let tail = '';
    child.stderr.on('data', chunk => {
      tail = (tail + chunk.toString('utf8')).slice(-6000);
      onProgress?.(tail.trim().split('\n').slice(-1)[0] || '');
    });
    child.on('error', error => reject(new Error(`Programm ${command} nicht gefunden oder nicht startbar: ${error.message}`)));
    child.on('close', code => code === 0 ? resolve() : reject(new Error(`${command} wurde mit Exit-Code ${code} beendet. ${tail.slice(-1200)}`)));
  });
}
async function findSparseModelDirectory(work) {
  const sparseRoot = path.join(work, 'sparse');
  const directories = [sparseRoot];
  try {
    const children = await fsp.readdir(sparseRoot, { withFileTypes: true });
    directories.push(...children.filter(entry => entry.isDirectory()).map(entry => path.join(sparseRoot, entry.name)));
  } catch {}
  let best = null;
  let bestSize = -1;
  for (const directory of directories) {
    for (const name of ['points3D.bin', 'points3D.txt']) {
      try {
        const stat = await fsp.stat(path.join(directory, name));
        if (stat.isFile() && stat.size > bestSize) { best = directory; bestSize = stat.size; }
      } catch {}
    }
  }
  return best;
}
async function createSparsePreview(work) {
  const sparseDirectory = await findSparseModelDirectory(work);
  if (!sparseDirectory) throw new Error('COLMAP hat kein ausgerichtetes 3D-Punktmodell gespeichert.');
  const previewPath = path.join(work, 'ultron-punktwolke.ply');
  await run(process.env.ULTRON_COLMAP || 'colmap', ['model_converter', '--input_path', sparseDirectory, '--output_path', previewPath, '--output_type', 'PLY']);
  const stat = await fsp.stat(previewPath);
  if (!stat.isFile() || stat.size < 100) throw new Error('COLMAP hat eine leere 3D-Punktwolke exportiert.');
  return previewPath;
}
async function reconstruct(job) {
  const work = path.join(MEDIA_DIR, job.id);
  const images = path.join(work, 'images');
  await fsp.mkdir(images, { recursive: true });
  job.state = 'frames'; job.message = 'Videoframes werden vorbereitet …';
  await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-i', job.videoPath, '-map', '0:v:0', '-an', '-sn', '-dn', '-vf', "fps=1,scale='min(1600,iw)':-2", '-q:v', '2', path.join(images, 'frame-%06d.jpg'), '-y'], message => { if (message) job.message = message; });
  const extracted = await fsp.readdir(images);
  if (extracted.length < 8) throw new Error('Zu wenige Frames. Nimm ein längeres Video mit mehreren überlappenden Blickwinkeln auf.');
  job.state = 'reconstructing'; job.message = extracted.length + ' Frames: COLMAP rekonstruiert die Szene …';
  let reconstructionError = null;
  try {
    await run(process.env.ULTRON_COLMAP || 'colmap', ['automatic_reconstructor', '--workspace_path', work, '--image_path', images, '--data_type', 'VIDEO', '--quality', 'MEDIUM', '--mesher', 'POISSON'], message => { if (message) job.message = message.slice(0, 240); });
  } catch (error) {
    reconstructionError = error;
    job.message = 'Dichtes Mesh nicht verfügbar. Ich prüfe, ob eine 3D-Punktwolke erstellt wurde …';
  }
  const candidates = [
    path.join(work, 'dense', '0', 'meshed-poisson.ply'),
    path.join(work, 'dense', 'meshed-poisson.ply')
  ];
  let modelPath = null;
  for (const candidate of candidates) {
    try { await fsp.access(candidate); modelPath = candidate; break; } catch {}
  }
  if (modelPath) {
    job.state = 'done'; job.message = '3D-Mesh fertig.'; job.modelName = path.basename(modelPath); job.modelType = 'mesh'; job.modelPath = modelPath;
    return;
  }
  try {
    job.message = 'Erzeuge eine 3D-Punktwolke aus der Kamerabewegung …';
    modelPath = await createSparsePreview(work);
    job.state = 'done';
    job.modelName = path.basename(modelPath);
    job.modelType = 'pointcloud';
    job.modelPath = modelPath;
    job.message = reconstructionError
      ? '3D-Punktwolke fertig. Ein dichtes Mesh war auf diesem PC nicht verfügbar; die Punktwolke lässt sich in der Ansicht drehen und herunterladen.'
      : '3D-Punktwolke fertig. COLMAP konnte aus diesem Video keine geschlossene Mesh-Fläche erzeugen.';
  } catch (fallbackError) {
    const denseMessage = reconstructionError ? reconstructionError.message : 'COLMAP hat keine Mesh-Datei erzeugt.';
    throw new Error(denseMessage + ' Zusätzlich konnte keine 3D-Punktwolke exportiert werden: ' + fallbackError.message);
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || '127.0.0.1'}`);
    if (req.method === 'GET' && url.pathname.startsWith('/vendor/')) {
      const name = url.pathname.slice('/vendor/'.length);
      if (!VENDOR_FILES.has(name)) return json(res, 404, { error: 'Not found.' });
      const script = await fsp.readFile(path.join(ROOT, 'vendor', name));
      res.writeHead(200, { 'content-type': 'application/javascript; charset=utf-8', 'cache-control': 'public, max-age=86400', 'content-length': script.length });
      res.end(script); return;
    }
    if (req.method === 'GET' && url.pathname === '/') {
      const html = fs.readFileSync(path.join(ROOT, 'index.html'));
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'content-length': html.length });
      res.end(html); return;
    }
    if (req.method === 'GET' && url.pathname === '/api/pairing') {
      if (!isLocal(req)) return json(res, 403, { error: 'Pair code is shown only on the PC.' });
      if (pinLockedUntil && Date.now() >= pinLockedUntil) { pinLockedUntil = 0; pairing = newPairing(); }
      if (!pairedToken && !pinLockedUntil && Date.now() >= pairing.expiresAt) pairing = newPairing();
      return json(res, 200, { pin: pairedToken || pinLockedUntil ? '' : pairing.pin, expiresAt: pairing.expiresAt, locked: !!pinLockedUntil, paired: !!pairedToken, frameNumber, lanUrls: getLanUrls() });
    }
    if (req.method === 'POST' && url.pathname === '/api/pair') {
      if (!isTrustedRelay(req)) return json(res, 403, { error: 'Zum Koppeln bitte dasselbe private WLAN oder Tailscale verwenden.' });
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
      pairedToken = null; pairing = newPairing(); latestFrame = null; frameNumber = 0; latestMicChunk = null; micSequence = 0;
      return json(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url.pathname === '/api/frame') {
      if (!isTrustedRelay(req)) return json(res, 403, { error: 'Nur Verbindungen aus dem privaten WLAN oder Tailscale sind erlaubt.' });
      if (!authorized(req)) return json(res, 401, { error: 'Not paired.' });
      if (!req.headers['content-type']?.startsWith('image/jpeg')) return json(res, 415, { error: 'Send a JPEG frame.' });
      if (Date.now() - lastFrameAt < FRAME_INTERVAL_MS) { req.resume(); return json(res, 429, { error: 'Frame rate limited.' }); }
      const frame = await readBody(req, MAX_FRAME_BYTES);
      if (!frame.length) return json(res, 400, { error: 'Empty frame.' });
      latestFrame = frame; lastFrameAt = Date.now(); frameNumber++;
      return json(res, 200, { ok: true, frameNumber, receivedAt: lastFrameAt });
    }
    if (req.method === 'POST' && url.pathname === '/api/mic') {
      if (!isTrustedRelay(req)) return json(res, 403, { error: 'Mikrofonstream nur im privaten WLAN oder über Tailscale erlaubt.' });
      if (!authorized(req)) return json(res, 401, { error: 'Handy ist nicht gekoppelt.' });
      if (!req.headers['content-type']?.startsWith('audio/wav')) return json(res, 415, { error: 'Mikrofon sendet kein WAV-Audio.' });
      const chunk = await readBody(req, MAX_MIC_CHUNK_BYTES);
      if (chunk.length < 44) return json(res, 400, { error: 'Leerer Mikrofonabschnitt.' });
      latestMicChunk = chunk; lastMicAt = Date.now(); micSequence++;
      return json(res, 200, { ok: true, sequence: micSequence, receivedAt: lastMicAt });
    }
    if (req.method === 'GET' && url.pathname === '/api/mic/latest') {
      if (!isLocal(req)) return json(res, 403, { error: 'Mikrofonwiedergabe ist nur auf dem PC verfügbar.' });
      const after = Number(url.searchParams.get('after') || 0);
      if (!latestMicChunk || !Number.isFinite(after) || micSequence <= after) {
        res.writeHead(204, { 'cache-control': 'no-store' }); res.end(); return;
      }
      res.writeHead(200, { 'content-type': 'audio/wav', 'cache-control': 'no-store', 'content-length': latestMicChunk.length, 'x-ultron-mic-sequence': String(micSequence) });
      res.end(latestMicChunk); return;
    }
    if (req.method === 'GET' && url.pathname === '/api/frame') {
      if (!isLocal(req)) return json(res, 403, { error: 'Live view is available on the PC only.' });
      if (!latestFrame) return json(res, 404, { error: 'Noch kein Bild empfangen.' });
      res.writeHead(200, { 'content-type': 'image/jpeg', 'cache-control': 'no-store', 'content-length': latestFrame.length });
      res.end(latestFrame); return;
    }
    if (req.method === 'GET' && url.pathname === '/api/status') {
      if (!isLocal(req)) return json(res, 403, { error: 'Status is available on the PC only.' });
      return json(res, 200, { paired: !!pairedToken, frameNumber, lastFrameAt, micSequence, lastMicAt });
    }
    if (req.method === 'POST' && url.pathname === '/api/video') {
      if (!isLocal(req)) return json(res, 403, { error: 'Video upload is available from the PC page only.' });
      if (!req.headers['content-type']?.startsWith('video/')) return json(res, 415, { error: 'Bitte eine MP4- oder MOV-Videodatei auswählen.' });
      if (rejectDeclaredOversize(req, res)) return;
      await fsp.mkdir(MEDIA_DIR, { recursive: true });
      const id = crypto.randomUUID();
      const videoPath = path.join(MEDIA_DIR, `${id}.mp4`);
      let size = 0;
      const limiter = new Transform({ transform(chunk, _encoding, callback) {
        size += chunk.length;
        callback(size > MAX_VIDEO_BYTES ? Object.assign(new Error('Video überschreitet das Upload-Limit von ' + formatGigabytes(MAX_VIDEO_BYTES) + '.'), { status: 413 }) : null, chunk);
      } });
      await pipeline(req, limiter, fs.createWriteStream(videoPath, { flags: 'wx' }));
      jobs.set(id, { id, state: 'uploaded', message: 'Video bereit.', videoPath, createdAt: Date.now() }); latestJobId = id;
      return json(res, 201, { id, state: 'uploaded' });
    }
    if (req.method === 'POST' && url.pathname === '/api/video-phone') {
      if (!isTrustedRelay(req) || !authorized(req)) return json(res, 401, { error: 'Handy ist nicht gekoppelt oder nicht im privaten WLAN/Tailscale.' });
      if (!req.headers['content-type']?.startsWith('video/')) return json(res, 415, { error: 'Bitte eine MP4- oder MOV-Videodatei senden.' });
      if (rejectDeclaredOversize(req, res)) return;
      await fsp.mkdir(MEDIA_DIR, { recursive: true });
      const id = crypto.randomUUID();
      const videoPath = path.join(MEDIA_DIR, `${id}.mp4`);
      let size = 0;
      const limiter = new Transform({ transform(chunk, _encoding, callback) {
        size += chunk.length;
        callback(size > MAX_VIDEO_BYTES ? Object.assign(new Error('Video größer als 2 GB.'), { status: 413 }) : null, chunk);
      } });
      await pipeline(req, limiter, fs.createWriteStream(videoPath, { flags: 'wx' }));
      const job = { id, state: 'queued', message: 'Video empfangen, starte die 3D-Rekonstruktion …', videoPath, createdAt: Date.now() };
      jobs.set(id, job); latestJobId = id;
      reconstruct(job).catch(error => { job.state = 'error'; job.message = error.message; });
      return json(res, 202, { id, state: job.state });
    }
    if (req.method === 'GET' && url.pathname === '/api/3d/latest') {
      if (!isLocal(req)) return json(res, 403, { error: '3D-Aufträge sind nur am PC sichtbar.' });
      const job = latestJobId ? jobs.get(latestJobId) : null;
      return json(res, 200, job ? { id: job.id, state: job.state, message: job.message, modelName: job.modelName || '', modelType: job.modelType || '' } : { state: 'idle', message: 'Noch kein 3D-Video empfangen.' });
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
      if (!isLocal(req) && !(isTrustedRelay(req) && authorized(req))) return json(res, 403, { error: '3D-Status ist nur am PC und für das gekoppelte Handy verfügbar.' });
      const id = url.pathname.split('/').pop();
      const job = jobs.get(id);
      if (!job) return json(res, 404, { error: 'Auftrag nicht gefunden.' });
      return json(res, 200, { id, state: job.state, message: job.message, modelName: job.modelName || '', modelType: job.modelType || '' });
    }
    if (req.method === 'GET' && url.pathname.startsWith('/api/model/')) {
      if (!isLocal(req)) return json(res, 403, { error: '3D-Modelle können nur lokal am PC geladen werden.' });
      const id = url.pathname.split('/').pop();
      const job = jobs.get(id);
      if (!job || job.state !== 'done') return json(res, 404, { error: 'Modell noch nicht verfügbar.' });
      const actualPath = job.modelPath;
      const stat = await fsp.stat(actualPath);
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': stat.size, 'content-disposition': `attachment; filename="${job.modelName || 'ultron-model.ply'}"`, 'cache-control': 'no-store' });
      fs.createReadStream(actualPath).pipe(res); return;
    }
    if (req.method === 'POST' && url.pathname === '/api/rotate-code') {
      if (!isLocal(req)) return json(res, 403, { error: 'Code rotation is available on the PC only.' });
      pairedToken = null; latestFrame = null; frameNumber = 0; latestMicChunk = null; micSequence = 0; pairing = newPairing();
      return json(res, 200, { pin: pairing.pin, expiresAt: pairing.expiresAt, paired: false });
    }
    json(res, 404, { error: 'Not found.' });
  } catch (error) {
    if (!res.headersSent) json(res, error.status || 500, { error: error.message || 'Server error.' });
  }
});

server.requestTimeout = REQUEST_TIMEOUT_MS;
server.listen(PORT, HOST, () => {
  console.log(`ULTRON PC Relay läuft auf Port ${PORT}.`);
  console.log('Lokales Dashboard: http://127.0.0.1:' + PORT);
  const lanUrls = getLanUrls();
  if (lanUrls.length) console.log('Handy im selben WLAN: ' + lanUrls.join(' · '));
  console.log('Für Verbindungen außerhalb des WLANs Tailscale Serve verwenden.');
  console.log(`Pairing-Code: ${pairing.pin} (5 Minuten gültig)`);
});
