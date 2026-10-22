import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.ULTRON_PORT || 8787);
const HOST = '127.0.0.1';
const MAX_FRAME_BYTES = 900_000;
const FRAME_INTERVAL_MS = 200;
let pairing = newPairing();
let pinAttempts = 0;
let pairedToken = null;
let latestFrame = null;
let lastFrameAt = 0;
let frameNumber = 0;

function newPairing() {
  pinAttempts = 0;
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
  const supplied = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  return !!pairedToken && supplied.length === pairedToken.length && crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(pairedToken));
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
      if (!pairedToken && Date.now() >= pairing.expiresAt) pairing = newPairing();
      return json(res, 200, { pin: pairedToken ? '' : pairing.pin, expiresAt: pairing.expiresAt, paired: !!pairedToken, frameNumber });
    }
    if (req.method === 'POST' && url.pathname === '/api/pair') {
      if (pairedToken) return json(res, 409, { error: 'Ein Handy ist bereits gekoppelt. Trenne es zuerst am PC.' });
      if (pinAttempts >= 8) return json(res, 429, { error: 'Zu viele Versuche. Bitte am PC einen neuen Code erzeugen.' });
      const body = JSON.parse((await readBody(req)).toString('utf8') || '{}');
      if (typeof body.pin !== 'string' || Date.now() >= pairing.expiresAt || body.pin !== pairing.pin) {
        pinAttempts++;
        if (pinAttempts >= 8) pairing = { pin: '', expiresAt: 0 };
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
