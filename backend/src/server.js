'use strict';

require('dotenv').config();
const express = require('express');
const http = require('http');
const cors = require('cors');
const helmet = require('helmet');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const { Pool } = require('pg');
const { Server } = require('socket.io');
const rateLimit = require('express-rate-limit');
const { z } = require('zod');

const required = ['DATABASE_URL', 'JWT_SECRET'];
for (const key of required) {
  if (!process.env[key]) {
    console.error(`Missing required environment variable: ${key}`);
    process.exit(1);
  }
}
if (process.env.JWT_SECRET.length < 32) {
  console.error('JWT_SECRET must contain at least 32 characters.');
  process.exit(1);
}

const app = express();
const server = http.createServer(app);
const pool = new Pool({ connectionString: process.env.DATABASE_URL, ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: true } : undefined });
const origins = (process.env.CORS_ORIGINS || '').split(',').map(v => v.trim()).filter(Boolean);
const io = new Server(server, {
  cors: { origin: origins.length ? origins : false, methods: ['GET', 'POST'] }
});

app.disable('x-powered-by');
app.use(helmet());
app.use(cors({ origin: origins.length ? origins : false }));
app.use(express.json({ limit: '32kb' }));
app.use(rateLimit({ windowMs: 15 * 60 * 1000, limit: 300, standardHeaders: 'draft-7', legacyHeaders: false }));

const phoneSchema = z.string().regex(/^\+[1-9]\d{7,14}$/, 'Phone must use international E.164 format.');
const registerSchema = z.object({
  phone: phoneSchema,
  displayName: z.string().trim().min(2).max(80),
  password: z.string().min(10).max(128)
});
const loginSchema = z.object({ phone: phoneSchema, password: z.string().min(1).max(128) });
const messageSchema = z.object({ body: z.string().trim().min(1).max(10000) });

function tokenFor(user) {
  return jwt.sign({ sub: String(user.id) }, process.env.JWT_SECRET, { expiresIn: '7d', issuer: 'goregram-server', audience: 'goregram-app' });
}
function authFromToken(token) {
  return jwt.verify(token, process.env.JWT_SECRET, { issuer: 'goregram-server', audience: 'goregram-app' });
}
async function requireAuth(req, res, next) {
  try {
    const header = req.headers.authorization || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!token) return res.status(401).json({ error: 'Authentification requise.' });
    const payload = authFromToken(token);
    const result = await pool.query('SELECT id, phone_e164, display_name FROM users WHERE id = $1', [payload.sub]);
    if (!result.rowCount) return res.status(401).json({ error: 'Session invalide.' });
    req.user = result.rows[0];
    next();
  } catch {
    return res.status(401).json({ error: 'Jeton invalide ou expiré.' });
  }
}

app.get('/health', async (_req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ ok: true, service: 'goregram-server', database: 'connected' });
  } catch {
    res.status(503).json({ ok: false, service: 'goregram-server', database: 'unavailable' });
  }
});

app.post('/api/auth/register', rateLimit({ windowMs: 60 * 60 * 1000, limit: 10 }), async (req, res, next) => {
  try {
    const input = registerSchema.parse(req.body);
    const rounds = Math.min(14, Math.max(10, Number(process.env.BCRYPT_ROUNDS) || 12));
    const passwordHash = await bcrypt.hash(input.password, rounds);
    const result = await pool.query(
      'INSERT INTO users (phone_e164, display_name, password_hash) VALUES ($1, $2, $3) RETURNING id, phone_e164, display_name',
      [input.phone, input.displayName, passwordHash]
    );
    const user = result.rows[0];
    res.status(201).json({ user, token: tokenFor(user), notice: 'Compte Goregram créé. La vérification du numéro sera ajoutée avant ouverture publique.' });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    if (error.code === '23505') return res.status(409).json({ error: 'Ce numéro possède déjà un compte Goregram.' });
    next(error);
  }
});

app.post('/api/auth/login', rateLimit({ windowMs: 15 * 60 * 1000, limit: 10 }), async (req, res, next) => {
  try {
    const input = loginSchema.parse(req.body);
    const result = await pool.query('SELECT id, phone_e164, display_name, password_hash FROM users WHERE phone_e164 = $1', [input.phone]);
    const user = result.rows[0];
    if (!user || !(await bcrypt.compare(input.password, user.password_hash))) {
      return res.status(401).json({ error: 'Numéro ou mot de passe incorrect.' });
    }
    res.json({ user: { id: user.id, phone_e164: user.phone_e164, display_name: user.display_name }, token: tokenFor(user) });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

app.get('/api/me', requireAuth, (req, res) => res.json({ user: req.user }));

app.post('/api/contacts/lookup', requireAuth, async (req, res, next) => {
  try {
    const input = z.object({ phone: phoneSchema }).parse(req.body);
    const result = await pool.query(
      'SELECT id, display_name FROM users WHERE phone_e164 = $1 AND id <> $2',
      [input.phone, req.user.id]
    );
    // This searches Goregram accounts only; it does not query Telegram or disclose whether a number has a Telegram account.
    res.json({ contact: result.rows[0] || null, network: 'goregram' });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

app.post('/api/conversations/direct', requireAuth, async (req, res, next) => {
  const client = await pool.connect();
  try {
    const input = z.object({ userId: z.coerce.number().int().positive() }).parse(req.body);
    if (input.userId === Number(req.user.id)) return res.status(400).json({ error: 'Impossible de créer une conversation avec soi-même.' });
    await client.query('BEGIN');
    const target = await client.query('SELECT id FROM users WHERE id = $1', [input.userId]);
    if (!target.rowCount) {
      await client.query('ROLLBACK');
      return res.status(404).json({ error: 'Ce numéro ne possède pas encore de compte Goregram.' });
    }
    const existing = await client.query(
      `SELECT c.id FROM conversations c
       JOIN conversation_members a ON a.conversation_id = c.id AND a.user_id = $1
       JOIN conversation_members b ON b.conversation_id = c.id AND b.user_id = $2
       WHERE c.kind = 'direct'
       AND (SELECT COUNT(*) FROM conversation_members m WHERE m.conversation_id = c.id) = 2
       LIMIT 1`,
      [req.user.id, input.userId]
    );
    let conversationId;
    if (existing.rowCount) conversationId = existing.rows[0].id;
    else {
      const created = await client.query(
        "INSERT INTO conversations (kind, created_by) VALUES ('direct', $1) RETURNING id",
        [req.user.id]
      );
      conversationId = created.rows[0].id;
      await client.query('INSERT INTO conversation_members (conversation_id, user_id) VALUES ($1, $2), ($1, $3)', [conversationId, req.user.id, input.userId]);
    }
    await client.query('COMMIT');
    res.status(200).json({ conversationId });
  } catch (error) {
    await client.query('ROLLBACK');
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  } finally { client.release(); }
});

app.get('/api/conversations/:id/messages', requireAuth, async (req, res, next) => {
  try {
    const id = z.coerce.number().int().positive().parse(req.params.id);
    const member = await pool.query('SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2', [id, req.user.id]);
    if (!member.rowCount) return res.status(403).json({ error: 'Accès refusé à cette conversation.' });
    const result = await pool.query(
      'SELECT id, conversation_id, sender_id, body, created_at FROM messages WHERE conversation_id = $1 ORDER BY created_at DESC LIMIT 50',
      [id]
    );
    res.json({ messages: result.rows.reverse() });
  } catch (error) { next(error); }
});

app.post('/api/conversations/:id/messages', requireAuth, async (req, res, next) => {
  try {
    const id = z.coerce.number().int().positive().parse(req.params.id);
    const input = messageSchema.parse(req.body);
    const member = await pool.query('SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2', [id, req.user.id]);
    if (!member.rowCount) return res.status(403).json({ error: 'Accès refusé à cette conversation.' });
    const result = await pool.query(
      'INSERT INTO messages (conversation_id, sender_id, body) VALUES ($1, $2, $3) RETURNING id, conversation_id, sender_id, body, created_at',
      [id, req.user.id, input.body]
    );
    const message = result.rows[0];
    io.to(`conversation:${id}`).emit('message:new', message);
    res.status(201).json({ message });
  } catch (error) {
    if (error instanceof z.ZodError) return res.status(400).json({ error: error.issues[0].message });
    next(error);
  }
});

io.use((socket, next) => {
  try {
    const token = socket.handshake.auth && socket.handshake.auth.token;
    if (!token) return next(new Error('Authentification requise.'));
    socket.user = authFromToken(token);
    next();
  } catch { next(new Error('Jeton invalide ou expiré.')); }
});
io.on('connection', socket => {
  socket.on('conversation:join', async conversationId => {
    const id = Number(conversationId);
    if (!Number.isSafeInteger(id) || id < 1) return;
    const member = await pool.query('SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2', [id, socket.user.sub]);
    if (member.rowCount) socket.join(`conversation:${id}`);
  });
});

app.use((err, _req, res, _next) => {
  console.error(err);
  if (err instanceof z.ZodError) return res.status(400).json({ error: err.issues[0].message });
  res.status(500).json({ error: 'Erreur interne du serveur.' });
});

const port = Number(process.env.PORT) || 4000;
server.listen(port, () => console.log(`Goregram API listening on port ${port}`));
process.on('SIGTERM', async () => { server.close(); await pool.end(); process.exit(0); });
