const { describe, it, before, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const request = require('supertest');
const { Pool } = require('pg');
const { migrate } = require('../src/db/migrate');
const { createLiveSessionRepository } = require('../src/db/liveSessionRepository');
const { createApp } = require('../src/app');

const testDbUrl = process.env.TEST_DATABASE_URL || 'postgres://whereami:whereami@127.0.0.1:6432/whereami';

describe('API End-to-End Tests', () => {
  let pool;
  let repo;
  let app;

  before(async () => {
    pool = new Pool({ connectionString: testDbUrl });
    await migrate(testDbUrl);
    repo = createLiveSessionRepository(pool);
    app = createApp({ repo, pool });
  });

  after(async () => {
    if (pool) await pool.end();
  });

  beforeEach(async () => {
    await pool.query('TRUNCATE TABLE live_view_events, live_guests, live_points, live_static_aliases, live_sessions CASCADE');
  });

  it('GET /health returns 200 with ok status and db info', async () => {
    const res = await request(app).get('/health');
    assert.equal(res.status, 200);
    assert.equal(res.body.status, 'ok');
    assert.equal(res.body.version, '2.0.1');
    assert.equal(res.body.db, 'ok');
  });

  it('POST /api/sessions/:id creates session and issues writeToken', async () => {
    const res = await request(app)
      .post('/api/sessions/session-123')
      .send({
        title: 'Beskid Trek',
        staticId: 'jh-beskidy',
        syncIntervalMinutes: 2,
      });

    assert.equal(res.status, 200);
    assert.equal(res.body.success, true);
    assert.equal(res.body.session.id, 'session-123');
    assert.equal(res.body.session.title, 'Beskid Trek');
    assert.ok(res.body.session.writeToken);

    // GET /api/sessions/:id should NOT leak writeToken to viewers
    const getRes = await request(app).get('/api/sessions/session-123');
    assert.equal(getRes.status, 200);
    assert.equal(getRes.body.writeToken, undefined);
  });

  it('POST /api/sessions/:id/points stores points and rejects invalid writeToken', async () => {
    const createRes = await request(app)
      .post('/api/sessions/session-abc')
      .send({ title: 'Test Hike' });
    const token = createRes.body.session.writeToken;

    // Bad token rejected
    const badRes = await request(app)
      .post('/api/sessions/session-abc/points')
      .set('x-write-token', 'wrong')
      .send({ points: [{ t: 100, lat: 49.5, lng: 19.5 }] });
    assert.equal(badRes.status, 401);

    // Valid token accepted
    const goodRes = await request(app)
      .post('/api/sessions/session-abc/points')
      .set('x-write-token', token)
      .send({
        current: { lat: 49.5, lng: 19.5, spd: 4.0, t: 100 },
        points: [{ t: 100, lat: 49.5, lng: 19.5, spd: 4.0 }]
      });
    assert.equal(goodRes.status, 200);
    assert.equal(goodRes.body.success, true);
    assert.equal(goodRes.body.totalPoints, 1);
  });

  it('GET /api/sessions/:staticId returns offline message when ended', async () => {
    await request(app)
      .post('/api/sessions/session-static')
      .send({ title: 'Host Live', staticId: 'jh-live' });

    // Mark ended
    await request(app)
      .post('/api/sessions/session-static/end');

    const res = await request(app).get('/api/sessions/jh-live');
    assert.equal(res.status, 200);
    assert.equal(res.body.isStatic, true);
    assert.equal(res.body.active, false);
    assert.equal(res.body.ended, true);
    assert.ok(res.body.message.includes('Host is currently offline'));
  });
});
