const express = require('express');
const cors = require('cors');
const path = require('path');
const { resolveGuestDisplayName } = require('../guestNaming');

function createApp({ repo, pool }) {
  const app = express();

  // Chrome Private Network Access
  app.use((req, res, next) => {
    res.setHeader('Access-Control-Allow-Private-Network', 'true');
    next();
  });
  app.use(cors());
  app.use(express.json({ limit: '2mb' }));
  app.use(express.static(path.join(__dirname, '../public')));

  // Auth helper
  async function checkWriteAuth(req, res, sessionId) {
    const token = req.headers['x-write-token'] || req.query.writeToken || (req.body && req.body.writeToken);
    const ok = await repo.verifyWriteToken(sessionId, token);
    if (!ok) {
      res.status(401).json({ error: 'Unauthorized: invalid write token' });
      return false;
    }
    return true;
  }

  // Healthcheck
  app.get('/health', async (req, res) => {
    try {
      if (pool) {
        await pool.query('SELECT 1');
      }
      res.json({
        status: 'ok',
        service: 'WhereAmI Live Server',
        version: '2.0.0',
        db: 'ok'
      });
    } catch (err) {
      res.status(503).json({
        status: 'error',
        service: 'WhereAmI Live Server',
        version: '2.0.0',
        db: 'disconnected',
        error: err.message
      });
    }
  });

  // Viewer page
  app.get('/live/:id', async (req, res, next) => {
    try {
      res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
      const targetId = await repo.resolveSessionId(req.params.id);
      const session = await repo.getSessionRow(targetId);
      if (session) {
        await repo.incrementViewCount(targetId);
      }
      res.sendFile(path.join(__dirname, '../public', 'index.html'));
    } catch (err) {
      next(err);
    }
  });

  // Rename
  app.post('/api/sessions/:id/rename', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const { title } = req.body;
      if (!title || typeof title !== 'string') {
        return res.status(400).json({ error: 'Invalid title' });
      }
      const updated = await repo.renameSession(id, title);
      if (!updated) return res.status(404).json({ error: 'Session not found' });
      res.json({ success: true, title: updated });
    } catch (err) {
      next(err);
    }
  });

  // Interval
  app.post('/api/sessions/:id/interval', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const { syncIntervalMinutes } = req.body;
      const mins = parseInt(syncIntervalMinutes, 10);
      if (mins <= 0) return res.status(400).json({ error: 'Invalid interval' });

      const updated = await repo.setSyncInterval(id, mins);
      if (!updated) return res.status(404).json({ error: 'Session not found' });
      res.json({ success: true, syncIntervalMinutes: updated });
    } catch (err) {
      next(err);
    }
  });

  // Upsert session
  app.post('/api/sessions/:id', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const session = await repo.upsertSession(id, req.body);
      res.json({ success: true, session });
    } catch (err) {
      next(err);
    }
  });

  // Reset trail
  app.post('/api/sessions/:id/reset-trail', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const newSegment = await repo.resetTrail(id);
      if (newSegment === null) return res.status(404).json({ error: 'Session not found' });
      res.json({ success: true, pastSegmentsCount: newSegment });
    } catch (err) {
      next(err);
    }
  });

  // View mode
  app.post('/api/sessions/:id/view-mode', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const { trailVisible } = req.body;
      if (trailVisible === undefined) return res.status(400).json({ error: 'trailVisible required' });
      const updated = await repo.setTrailVisible(id, trailVisible);
      if (updated === null) return res.status(404).json({ error: 'Session not found' });
      res.json({ success: true, trailVisible: updated });
    } catch (err) {
      next(err);
    }
  });

  // Post points & telemetry
  app.post('/api/sessions/:id/points', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const result = await repo.updateSessionCurrentAndPoints(id, req.body);
      if (result.ended) return res.status(410).json({ error: 'Session ended' });
      if (result.expired) return res.status(410).json({ error: 'Session expired' });

      const session = result.session;
      const inactivityThresholdMs = 5 * 60_000;
      const guestsMap = await repo.getGuests(id);
      const guestsList = [];

      if (session.followGuests !== false && guestsMap) {
        for (const [gId, g] of Object.entries(guestsMap)) {
          if (!g || typeof g.lat !== 'number' || typeof g.lng !== 'number') continue;
          const isInactive = (Date.now() - (g.t || 0)) > inactivityThresholdMs;
          const displayName = resolveGuestDisplayName(g, guestsMap, gId);
          guestsList.push({
            id: g.id || gId,
            name: displayName,
            lat: g.lat,
            lng: g.lng,
            acc: g.acc,
            t: g.t,
            colorIndex: typeof g.colorIndex === 'number' ? g.colorIndex : 0,
            isInactive: isInactive,
            firstSeen: g.firstSeen || g.createdAt || g.t,
            viewCount: g.viewCount || 1,
          });
        }
      }

      const viewLog = await repo.getViewLog(id, 200);

      res.json({
        success: true,
        totalPoints: result.totalPoints,
        viewCount: session.viewCount || 0,
        guests: guestsList,
        viewLog: viewLog,
      });
    } catch (err) {
      next(err);
    }
  });

  // Guest position update
  app.post('/api/sessions/:id/guests/:guestId', async (req, res, next) => {
    try {
      const { id, guestId } = req.params;
      const targetId = await repo.resolveSessionId(id);
      const { lat, lng, acc, name } = req.body;
      if (typeof lat !== 'number' || typeof lng !== 'number') {
        return res.status(400).json({ error: 'Invalid coordinates' });
      }

      const result = await repo.upsertGuestPosition(targetId, guestId, { lat, lng, acc, name });
      if (result.notFound) return res.status(404).json({ error: 'Session not found' });
      if (result.ended) return res.status(410).json({ error: 'Session ended' });
      if (result.disabled) return res.json({ success: false, disabled: true });

      res.json({ success: true, guest: result.guest });
    } catch (err) {
      next(err);
    }
  });

  // Guest delete
  app.delete('/api/sessions/:id/guests/:guestId', async (req, res, next) => {
    try {
      const { id, guestId } = req.params;
      const targetId = await repo.resolveSessionId(id);
      const removed = await repo.deleteGuest(targetId, guestId);
      res.json({ success: true, removed: removed || null });
    } catch (err) {
      next(err);
    }
  });

  // Guest settings
  app.post('/api/sessions/:id/guest-settings', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const { followGuests } = req.body;
      if (followGuests === undefined) return res.status(400).json({ error: 'followGuests required' });
      const updated = await repo.setFollowGuests(id, followGuests);
      if (updated === null) return res.status(404).json({ error: 'Session not found' });
      res.json({ success: true, followGuests: updated });
    } catch (err) {
      next(err);
    }
  });

  // Session details query
  app.get('/api/sessions/:id', async (req, res, next) => {
    try {
      res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
      const { id } = req.params;
      const targetId = await repo.resolveSessionId(id);
      const isStaticLookup = (targetId !== id) || id.startsWith('jh-');

      const guestId = req.query.guestId;
      const isPv = req.query.pv === '1';

      if (guestId && typeof guestId === 'string') {
        const userAgent = req.headers['user-agent'] || '';
        await repo.recordViewEvent(targetId, guestId, userAgent, isPv);
      }

      const session = await repo.getSessionView(targetId, { pointLimit: 4000 });

      if (isStaticLookup) {
        if (!session || session.ended || (session.expiresAt > 0 && Date.now() > session.expiresAt)) {
          return res.json({
            id,
            isStatic: true,
            active: false,
            ended: true,
            title: session ? session.title : 'Live Location',
            lastSeen: session ? (session.current?.t || session.endedAt || session.createdAt) : null,
            message: 'Host is currently offline. This personal live link will update automatically when a new live session begins.',
            viewCount: session ? (session.viewCount || 0) : 0,
          });
        }

        const isPersonalPaused = Boolean(session.personalPaused || session.paused);
        const { writeToken: _wt, ...safeSession } = session;
        return res.json({
          ...safeSession,
          followGuests: session.followGuests !== false,
          isStatic: true,
          staticId: id,
          active: !session.ended,
          paused: isPersonalPaused,
          viewCount: session.viewCount || 0,
        });
      }

      if (!session) return res.status(404).json({ error: 'Session not found' });
      if (session.expiresAt > 0 && Date.now() > session.expiresAt) {
        session.ended = true;
      }
      const isRandomPaused = Boolean(session.randomPaused || session.paused);
      const { writeToken: _wt, ...safeSession } = session;

      res.json({
        ...safeSession,
        followGuests: session.followGuests !== false,
        paused: isRandomPaused,
        viewCount: session.viewCount || 0,
      });
    } catch (err) {
      next(err);
    }
  });

  // Views list
  app.get('/api/sessions/:id/views', async (req, res, next) => {
    try {
      const { id } = req.params;
      const targetId = await repo.resolveSessionId(id);
      const session = await repo.getSessionRow(targetId);
      if (!session) return res.status(404).json({ error: 'Session not found' });

      const guestsMap = await repo.getGuests(targetId);
      const inactivityThresholdMs = 5 * 60_000;
      const guestsList = [];

      for (const [gId, g] of Object.entries(guestsMap)) {
        if (!g) continue;
        const isInactive = (Date.now() - (g.t || g.createdAt || 0)) > inactivityThresholdMs;
        const displayName = resolveGuestDisplayName(g, guestsMap, gId);
        guestsList.push({
          id: g.id || gId,
          name: displayName,
          lat: typeof g.lat === 'number' ? g.lat : null,
          lng: typeof g.lng === 'number' ? g.lng : null,
          acc: typeof g.acc === 'number' ? g.acc : null,
          t: g.t || g.createdAt || Date.now(),
          colorIndex: typeof g.colorIndex === 'number' ? g.colorIndex : 0,
          isInactive: isInactive,
          firstSeen: g.firstSeen || g.createdAt || g.t,
          viewCount: g.viewCount || 1,
        });
      }

      const viewLog = await repo.getViewLog(targetId, 200);

      res.json({
        success: true,
        viewCount: session.view_count || 0,
        viewLog: viewLog,
        guests: guestsList,
      });
    } catch (err) {
      next(err);
    }
  });

  // Status toggle
  app.post('/api/sessions/:id/status', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const { status, target } = req.body;
      const session = await repo.setStatus(id, { status, target });
      if (!session) return res.status(404).json({ error: 'Session not found' });

      res.json({
        success: true,
        session: {
          ...session,
          paused: session.paused,
          personalPaused: Boolean(session.personalPaused),
          randomPaused: Boolean(session.randomPaused),
        }
      });
    } catch (err) {
      next(err);
    }
  });

  // Extend
  app.post('/api/sessions/:id/extend', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      const additionalHours = parseFloat(req.body.additionalHours) || 0;
      const expiresAt = await repo.extendSession(id, additionalHours);
      if (expiresAt === null) return res.status(404).json({ error: 'Session not found' });
      res.json({ success: true, expiresAt });
    } catch (err) {
      next(err);
    }
  });

  // End
  app.post('/api/sessions/:id/end', async (req, res, next) => {
    try {
      const { id } = req.params;
      if (!(await checkWriteAuth(req, res, id))) return;

      await repo.endSession(id);
      res.json({ success: true });
    } catch (err) {
      next(err);
    }
  });

  // Central error handler
  app.use((err, req, res, _next) => {
    console.error('API Error:', err);
    res.status(500).json({ error: 'Internal server error', message: err.message });
  });

  return app;
}

module.exports = { createApp };
