const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3003;
const DATA_FILE = path.join(__dirname, 'sessions.json');

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
  res.json({ status: 'ok', service: 'WhereIAm Live Server', version: '1.3.3', activeSessions: sessions.size, staticAliases: staticAliases.size });
});

app.get('/live/:id', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.post('/api/sessions/:id', (req, res) => {
  const { id } = req.params;
  const { title, createdAt, expiresAt, staticId } = req.body;
  let session = sessions.get(id);
  if (!session) {
    session = {
      id,
      staticId: staticId || null,
      title: title || 'Live Hike',
      createdAt: createdAt || Date.now(),
      expiresAt: expiresAt || 0,
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
    session.ended = false;
  }

  if (staticId) {
    staticAliases.set(staticId, id);
  }

  saveSessions();
  res.json({ success: true, session });
});

app.post('/api/sessions/:id/points', (req, res) => {
  const { id } = req.params;
  const { points, current } = req.body;
  let session = sessions.get(id);
  if (!session) {
    session = {
      id,
      title: 'Live Track',
      createdAt: Date.now(),
      expiresAt: 0,
      ended: false,
      paused: false,
      current: null,
      points: []
    };
    sessions.set(id, session);
  }
  if (session.ended) return res.status(410).json({ error: 'Session ended' });
  if (session.expiresAt > 0 && Date.now() > session.expiresAt) {
    session.ended = true;
    saveSessions();
    return res.status(410).json({ error: 'Session expired' });
  }

  if (current) session.current = current;
  if (Array.isArray(points) && points.length > 0) {
    session.points.push(...points);
    if (session.points.length > 4000) session.points = session.points.slice(-4000);
  }
  saveSessions();
  res.json({ success: true, totalPoints: session.points.length });
});

app.get('/api/sessions/:id', (req, res) => {
  const { id } = req.params;
  let targetId = id;
  let isStaticLookup = false;

  if (staticAliases.has(id)) {
    targetId = staticAliases.get(id);
    isStaticLookup = true;
  }

  const session = sessions.get(targetId);

  // If looking up via static ID:
  if (isStaticLookup) {
    if (!session || session.ended || (session.expiresAt > 0 && Date.now() > session.expiresAt)) {
      return res.json({
        id,
        isStatic: true,
        active: false,
        ended: true,
        title: session ? session.title : 'Live Location',
        lastSeen: session ? (session.current?.t || session.endedAt || session.createdAt) : null,
        message: 'Host is currently offline. This personal live link will update automatically when a new live session begins.'
      });
    }
    return res.json({ ...session, isStatic: true, staticId: id });
  }

  if (!session) return res.status(404).json({ error: 'Session not found' });
  if (session.expiresAt > 0 && Date.now() > session.expiresAt) {
    session.ended = true;
  }
  res.json(session);
});

app.post('/api/sessions/:id/status', (req, res) => {
  const session = sessions.get(req.params.id);
  if (!session) return res.status(404).json({ error: 'Session not found' });
  const { status } = req.body;
  if (status === 'paused') {
    session.paused = true;
  } else if (status === 'active') {
    session.paused = false;
    session.ended = false;
  }
  saveSessions();
  res.json({ success: true, session });
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
  console.log(`WhereIAm Live Server running on port ${PORT}`);
});
