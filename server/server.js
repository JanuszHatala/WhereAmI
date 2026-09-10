const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3003;
const DATA_FILE = path.join(__dirname, 'sessions.json');

app.use(cors());
app.use(express.json({ limit: '2mb' }));
app.use(express.static(path.join(__dirname, 'public')));

let sessions = new Map();

function loadSessions() {
  try {
    if (fs.existsSync(DATA_FILE)) {
      const raw = fs.readFileSync(DATA_FILE, 'utf8');
      const parsed = JSON.parse(raw);
      for (const [k, v] of Object.entries(parsed)) {
        sessions.set(k, v);
      }
      console.log(`Loaded ${sessions.size} sessions from disk.`);
    }
  } catch (e) {
    console.error('Error loading sessions:', e.message);
  }
}

function saveSessions() {
  try {
    const obj = {};
    for (const [k, v] of sessions.entries()) {
      obj[k] = v;
    }
    fs.writeFileSync(DATA_FILE, JSON.stringify(obj, null, 2), 'utf8');
  } catch (e) {
    console.error('Error saving sessions:', e.message);
  }
}

loadSessions();

app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'WhereIAm Live Server', version: '1.3.1', activeSessions: sessions.size });
});

app.get('/live/:id', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.post('/api/sessions/:id', (req, res) => {
  const { id } = req.params;
  const { title, createdAt, expiresAt } = req.body;
  let session = sessions.get(id);
  if (!session) {
    session = {
      id,
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
    session.ended = false;
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
  const session = sessions.get(id);
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
  const additionalHours = parseInt(req.body.additionalHours) || 1;
  const baseTime = session.expiresAt > Date.now() ? session.expiresAt : Date.now();
  session.expiresAt = baseTime + additionalHours * 3600_000;
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
