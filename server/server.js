const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3003;
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
if (!fs.existsSync(DATA_DIR)) {
  try { fs.mkdirSync(DATA_DIR, { recursive: true }); } catch (_) {}
}
const DATA_FILE = path.join(DATA_DIR, 'sessions.json');

// Chrome Private Network Access: allow https pages (e.g. GH Pages) to fetch from http://127.0.0.1
// Must be before cors() so this header is included in the preflight response
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Private-Network', 'true');
  next();
});
app.use(cors());
app.use(express.json({ limit: '2mb' }));
app.use(express.static(path.join(__dirname, 'public')));

let sessions = new Map();
let staticAliases = new Map();

function loadSessions() {
  try {
    if (fs.existsSync(DATA_FILE)) {
      const raw = fs.readFileSync(DATA_FILE, 'utf8');
      const parsed = JSON.parse(raw);
      if (parsed.sessions && typeof parsed.sessions === 'object') {
        for (const [k, v] of Object.entries(parsed.sessions)) {
          sessions.set(k, v);
        }
        if (parsed.staticAliases && typeof parsed.staticAliases === 'object') {
          for (const [k, v] of Object.entries(parsed.staticAliases)) {
            staticAliases.set(k, v);
          }
        }
      } else {
        for (const [k, v] of Object.entries(parsed)) {
          sessions.set(k, v);
        }
      }
      console.log(`Loaded ${sessions.size} sessions and ${staticAliases.size} static aliases from disk.`);
    }
  } catch (e) {
    console.error('Error loading sessions:', e.message);
  }
}

function saveSessions() {
  try {
    const obj = {
      sessions: Object.fromEntries(sessions),
      staticAliases: Object.fromEntries(staticAliases)
    };
    fs.writeFileSync(DATA_FILE, JSON.stringify(obj, null, 2), 'utf8');
  } catch (e) {
    console.error('Error saving sessions:', e.message);
  }
}

loadSessions();

app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'WhereAmI Live Server', version: '1.3.3', activeSessions: sessions.size, staticAliases: staticAliases.size });
});

app.get('/live/:id', (req, res) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  const { id } = req.params;
  let targetId = id;
  if (staticAliases.has(id)) {
    targetId = staticAliases.get(id);
  } else {
    for (const [sId, sess] of sessions.entries()) {
      if (sess.staticId === id) {
        targetId = sId;
        break;
      }
    }
  }
  const session = sessions.get(targetId);
  if (session) {
    session.viewCount = (session.viewCount || 0) + 1;
    saveSessions();
  }
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.post('/api/sessions/:id/rename', (req, res) => {
  const session = sessions.get(req.params.id);
  if (!session) return res.status(404).json({ error: 'Session not found' });
  const { title } = req.body;
  if (title && typeof title === 'string') {
    session.title = title.trim();
    saveSessions();
  }
  res.json({ success: true, title: session.title });
});

app.post('/api/sessions/:id/interval', (req, res) => {
  const session = sessions.get(req.params.id);
  if (!session) return res.status(404).json({ error: 'Session not found' });
  const { syncIntervalMinutes } = req.body;
  const mins = parseInt(syncIntervalMinutes, 10);
  if (mins > 0) {
    session.syncIntervalMinutes = mins;
    saveSessions();
  }
  res.json({ success: true, syncIntervalMinutes: session.syncIntervalMinutes });
});

app.post('/api/sessions/:id', (req, res) => {
  const { id } = req.params;
  const { title, createdAt, expiresAt, staticId, trailVisible, syncIntervalMinutes } = req.body;
  let session = sessions.get(id);
  if (!session) {
    session = {
      id,
      staticId: staticId || null,
      title: title || 'Live Hike',
      createdAt: createdAt || Date.now(),
      expiresAt: expiresAt || 0,
      trailVisible: trailVisible !== undefined ? Boolean(trailVisible) : true,
      syncIntervalMinutes: parseInt(syncIntervalMinutes, 10) || 5,
      ended: false,
      paused: false,
      current: null,
      points: []
    };
    sessions.set(id, session);
  } else {
    if (title) session.title = title;
    if (expiresAt !== undefined) session.expiresAt = expiresAt;
    if (staticId) session.staticId = staticId;
    if (trailVisible !== undefined) session.trailVisible = Boolean(trailVisible);
    if (syncIntervalMinutes !== undefined) session.syncIntervalMinutes = parseInt(syncIntervalMinutes, 10) || session.syncIntervalMinutes || 5;
    session.ended = false;
  }

  if (staticId) {
    staticAliases.set(staticId, id);
  }

  saveSessions();
  res.json({ success: true, session });
});

app.post('/api/sessions/:id/view-mode', (req, res) => {
  const session = sessions.get(req.params.id);
  if (!session) return res.status(404).json({ error: 'Session not found' });
  const { trailVisible } = req.body;
  if (trailVisible !== undefined) {
    session.trailVisible = Boolean(trailVisible);
  }
  saveSessions();
  res.json({ success: true, trailVisible: session.trailVisible });
});

app.post('/api/sessions/:id/points', (req, res) => {
  const { id } = req.params;
  const { points, current, staticId } = req.body;
  let session = sessions.get(id);
  if (!session) {
    session = {
      id,
      title: req.body.title || 'Live Session',
      createdAt: Date.now(),
      expiresAt: 0,
      trailVisible: true,
      syncIntervalMinutes: 5,
      ended: false,
      paused: false,
      current: null,
      points: []
    };
    sessions.set(id, session);
  }

  // Update title if provided (keeps server in sync with app renames via periodic sync)
  if (req.body.title && typeof req.body.title === 'string' && req.body.title.trim()) {
    session.title = req.body.title.trim();
  }

  if (staticId) {
    session.staticId = staticId;
    staticAliases.set(staticId, id);
  }

  if (session.ended) return res.status(410).json({ error: 'Session ended' });
  if (session.expiresAt > 0 && Date.now() > session.expiresAt) {
    session.ended = true;
    saveSessions();
    return res.status(410).json({ error: 'Session expired' });
  }

  if (req.body.isPaused !== undefined) session.paused = Boolean(req.body.isPaused);
  if (current) session.current = current;
  if (Array.isArray(points) && points.length > 0) {
    const existingTimestamps = new Set(session.points.map(p => p.t));
    for (const p of points) {
      if (!existingTimestamps.has(p.t)) {
        session.points.push(p);
        existingTimestamps.add(p.t);
      }
    }
    session.points.sort((a, b) => a.t - b.t);
    if (session.points.length > 4000) session.points = session.points.slice(-4000);
  }
  saveSessions();
  res.json({ success: true, totalPoints: session.points.length, viewCount: session.viewCount || 0 });
});

app.get('/api/sessions/:id', (req, res) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  const { id } = req.params;
  let targetId = id;
  let isStaticLookup = false;

  if (staticAliases.has(id)) {
    targetId = staticAliases.get(id);
    isStaticLookup = true;
  } else {
    // Fallback: check if any session has staticId == id
    for (const [sId, sess] of sessions.entries()) {
      if (sess.staticId === id) {
        targetId = sId;
        isStaticLookup = true;
        staticAliases.set(id, sId);
        break;
      }
    }
  }

  const session = sessions.get(targetId);

  // If looking up via static ID (or starts with static format like 'jh-'):
  if (isStaticLookup || id.startsWith('jh-')) {
    if (!session || session.ended || (session.expiresAt > 0 && Date.now() > session.expiresAt)) {
      return res.json({
        id,
        isStatic: true,
        active: false,
        ended: true,
        title: session ? session.title : 'Live Location',
        lastSeen: session ? (session.current?.t || session.endedAt || session.createdAt) : null,
        message: 'Host is currently offline. This personal live link will update automatically when a new live session begins.',
        viewCount: session ? (session.viewCount || 0) : 0
      });
    }
    const isPersonalPaused = Boolean(session.personalPaused || session.paused);
    return res.json({
      ...session,
      isStatic: true,
      staticId: id,
      active: !session.ended,
      paused: isPersonalPaused,
      viewCount: session.viewCount || 0
    });
  }

  if (!session) return res.status(404).json({ error: 'Session not found' });
  if (session.expiresAt > 0 && Date.now() > session.expiresAt) {
    session.ended = true;
  }
  const isRandomPaused = Boolean(session.randomPaused || session.paused);
  res.json({
    ...session,
    paused: isRandomPaused,
    viewCount: session.viewCount || 0
  });
});

app.post('/api/sessions/:id/status', (req, res) => {
  const session = sessions.get(req.params.id);
  if (!session) return res.status(404).json({ error: 'Session not found' });
  const { status, target } = req.body;
  const isPaused = status === 'paused';
  if (target === 'personal') {
    session.personalPaused = isPaused;
  } else if (target === 'random') {
    session.randomPaused = isPaused;
  } else {
    session.paused = isPaused;
    if (!isPaused) {
      session.personalPaused = false;
      session.randomPaused = false;
      session.ended = false;
    }
  }
  saveSessions();
  res.json({
    success: true,
    session: {
      ...session,
      paused: session.paused,
      personalPaused: Boolean(session.personalPaused),
      randomPaused: Boolean(session.randomPaused)
    }
  });
});

app.post('/api/sessions/:id/extend', (req, res) => {
  const session = sessions.get(req.params.id);
  if (!session) return res.status(404).json({ error: 'Session not found' });
  const additionalHours = parseFloat(req.body.additionalHours) || 0;
  if (session.expiresAt <= 0) {
    if (additionalHours > 0) {
      session.expiresAt = Date.now() + additionalHours * 3600_000;
    }
  } else {
    const baseTime = session.expiresAt > Date.now() ? session.expiresAt : Date.now();
    session.expiresAt = Math.max(Date.now() + 60000, baseTime + additionalHours * 3600_000);
  }
  session.ended = false;
  saveSessions();
  res.json({ success: true, expiresAt: session.expiresAt });
});

app.post('/api/sessions/:id/end', (req, res) => {
  const session = sessions.get(req.params.id);
  if (session) {
    session.ended = true;
    session.paused = false;
    session.endedAt = Date.now();
    saveSessions();
  }
  res.json({ success: true });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`WhereAmI Live Server running on port ${PORT}`);
});
