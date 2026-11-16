import 'dotenv/config';
import http from 'node:http';
import crypto from 'node:crypto';
import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import rateLimit from 'express-rate-limit';
import jwt from 'jsonwebtoken';
import bcrypt from 'bcryptjs';
import pg from 'pg';
import { WebSocketServer } from 'ws';

const { Pool } = pg;
const app = express();
const server = http.createServer(app);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, ssl: process.env.PGSSL === 'true' ? { rejectUnauthorized: true } : undefined });
const production = process.env.NODE_ENV === 'production';
const jwtSecret = process.env.JWT_SECRET || '';
const otpSecret = process.env.OTP_HASH_SECRET || '';
if (production && (jwtSecret.length < 32 || otpSecret.length < 32)) throw new Error('JWT_SECRET et OTP_HASH_SECRET doivent contenir au moins 32 caractères.');
app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN === '*' && !production ? '*' : (process.env.CORS_ORIGIN || false) }));
app.use(express.json({ limit: '32kb' }));
app.use('/auth/', rateLimit({ windowMs: 15 * 60 * 1000, limit: 8, standardHeaders: true, legacyHeaders: false }));

const phoneOk = p => /^\+[1-9]\d{7,14}$/.test(p);
const hashOtp = (phone, code) => crypto.createHmac('sha256', otpSecret || 'local-dev-only').update(phone + ':' + code).digest('hex');
async function sendSms(phone, code) {
  const { TWILIO_ACCOUNT_SID: sid, TWILIO_AUTH_TOKEN: token, TWILIO_FROM_NUMBER: from } = process.env;
  if (!sid || !token || !from) {
    if (!production) { console.warn(`[DEV ONLY] OTP for ${phone}: ${code}`); return; }
    throw new Error('SMS_PROVIDER_NOT_CONFIGURED');
  }
  const body = new URLSearchParams({ To: phone, From: from, Body: `Votre code Goregram Perso : ${code}. Valable 5 minutes.` });
  const response = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${sid}/Messages.json`, {
    method: 'POST', headers: { Authorization: 'Basic ' + Buffer.from(sid + ':' + token).toString('base64'), 'Content-Type': 'application/x-www-form-urlencoded' }, body
  });
  if (!response.ok) throw new Error('SMS_SEND_FAILED');
}
const sign = user => jwt.sign({ sub: String(user.id), phone: user.phone_e164 }, jwtSecret || 'local-development-secret-change-me', { expiresIn: '30d', issuer: 'goregram-api' });
function auth(req, res, next) {
  try {
    const token = (req.headers.authorization || '').replace(/^Bearer /, '');
    req.user = jwt.verify(token, jwtSecret || 'local-development-secret-change-me', { issuer: 'goregram-api' });
    next();
  } catch { res.status(401).json({ error: 'UNAUTHORIZED' }); }
}
app.get('/health', async (_req, res) => {
  try { await pool.query('SELECT 1'); res.json({ ok: true, service: 'goregram-api' }); }
  catch { res.status(503).json({ ok: false }); }
});
app.post('/auth/request-otp', async (req, res) => {
  const phone = String(req.body?.phone || '');
  if (!phoneOk(phone)) return res.status(400).json({ error: 'PHONE_MUST_BE_E164' });
  const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
  const ttl = Math.min(Math.max(Number(process.env.OTP_TTL_SECONDS || 300), 60), 600);
  try {
    await sendSms(phone, code);
    await pool.query(`INSERT INTO phone_otps(phone_e164, code_hash, expires_at, attempts)
      VALUES($1,$2,NOW()+($3 || ' seconds')::interval,0)
      ON CONFLICT(phone_e164) DO UPDATE SET code_hash=EXCLUDED.code_hash, expires_at=EXCLUDED.expires_at, attempts=0, created_at=NOW()`,
      [phone, hashOtp(phone, code), String(ttl)]);
    res.json({ ok: true, expires_in: ttl });
  } catch (e) { console.error('OTP request failed:', e.message); res.status(503).json({ error: 'SMS_UNAVAILABLE' }); }
});
app.post('/auth/verify-otp', async (req, res) => {
  const phone = String(req.body?.phone || ''), code = String(req.body?.code || '');
  if (!phoneOk(phone) || !/^\d{6}$/.test(code)) return res.status(400).json({ error: 'INVALID_INPUT' });
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await client.query('SELECT * FROM phone_otps WHERE phone_e164=$1 FOR UPDATE', [phone]);
    const row = result.rows[0];
    if (!row || new Date(row.expires_at) < new Date() || row.attempts >= 5) {
      await client.query('DELETE FROM phone_otps WHERE phone_e164=$1', [phone]); await client.query('COMMIT');
      return res.status(400).json({ error: 'OTP_EXPIRED_OR_INVALID' });
    }
    if (hashOtp(phone, code) !== row.code_hash) {
      await client.query('UPDATE phone_otps SET attempts=attempts+1 WHERE phone_e164=$1', [phone]);
      await client.query('COMMIT'); return res.status(400).json({ error: 'OTP_EXPIRED_OR_INVALID' });
    }
    await client.query('DELETE FROM phone_otps WHERE phone_e164=$1', [phone]);
    const userResult = await client.query(`INSERT INTO users(phone_e164) VALUES($1)
      ON CONFLICT(phone_e164) DO UPDATE SET last_seen_at=NOW() RETURNING id, phone_e164, display_name, avatar_url`, [phone]);
    await client.query('COMMIT');
    const user = userResult.rows[0];
    res.json({ token: sign(user), user });
  } catch (e) { await client.query('ROLLBACK'); console.error(e); res.status(500).json({ error: 'SERVER_ERROR' }); }
  finally { client.release(); }
});
app.get('/me', auth, async (req, res) => {
  const r = await pool.query('SELECT id, phone_e164, display_name, avatar_url, created_at FROM users WHERE id=$1', [req.user.sub]);
  if (!r.rowCount) return res.status(404).json({ error: 'USER_NOT_FOUND' });
  res.json(r.rows[0]);
});
app.post('/messages', auth, async (req, res) => {
  const recipient = Number(req.body?.recipient_id), body = String(req.body?.body || '').trim();
  if (!Number.isSafeInteger(recipient) || recipient < 1 || !body || body.length > 10000) return res.status(400).json({ error: 'INVALID_MESSAGE' });
  const r = await pool.query('INSERT INTO messages(sender_id,recipient_id,body) SELECT $1,$2,$3 WHERE EXISTS(SELECT 1 FROM users WHERE id=$2) RETURNING id,sender_id,recipient_id,body,created_at', [req.user.sub, recipient, body]);
  if (!r.rowCount) return res.status(404).json({ error: 'RECIPIENT_NOT_FOUND' });
  broadcast(recipient, { type: 'message', message: r.rows[0] });
  res.status(201).json(r.rows[0]);
});
app.get('/messages/:userId', auth, async (req, res) => {
  const other = Number(req.params.userId);
  if (!Number.isSafeInteger(other) || other < 1) return res.status(400).json({ error: 'INVALID_USER' });
  const r = await pool.query(`SELECT id,sender_id,recipient_id,body,created_at,read_at FROM messages
    WHERE (sender_id=$1 AND recipient_id=$2) OR (sender_id=$2 AND recipient_id=$1)
    ORDER BY created_at DESC LIMIT 100`, [req.user.sub, other]);
  res.json(r.rows.reverse());
});
const wss = new WebSocketServer({ noServer: true });
const clients = new Map();
function broadcast(userId, data) {
  for (const ws of clients.get(String(userId)) || []) if (ws.readyState === 1) ws.send(JSON.stringify(data));
}
server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/ws') return socket.destroy();
  try {
    const token = url.searchParams.get('token') || '';
    const user = jwt.verify(token, jwtSecret || 'local-development-secret-change-me', { issuer: 'goregram-api' });
    wss.handleUpgrade(req, socket, head, ws => {
      const id = String(user.sub); if (!clients.has(id)) clients.set(id, new Set());
      clients.get(id).add(ws);
      ws.on('close', () => { clients.get(id)?.delete(ws); if (!clients.get(id)?.size) clients.delete(id); });
      ws.send(JSON.stringify({ type: 'connected' }));
    });
  } catch { socket.destroy(); }
});
const port = Number(process.env.PORT || 8080);
server.listen(port, () => console.log(`Goregram API listening on :${port}`));
