const { describe, it, before, beforeEach, after } = require('node:test');
const assert = require('node:assert/strict');
const { Pool } = require('pg');
const { migrate } = require('../src/db/migrate');
const { createLiveSessionRepository } = require('../src/db/liveSessionRepository');

const testDbUrl = process.env.TEST_DATABASE_URL || 'postgres://whereami:whereami@127.0.0.1:5432/whereami';

describe('LiveSessionRepository Integration Tests', () => {
  let pool;
  let repo;

  before(async () => {
    pool = new Pool({ connectionString: testDbUrl });
    await migrate(testDbUrl);
    repo = createLiveSessionRepository(pool);
  });

  after(async () => {
    if (pool) await pool.end();
  });

  beforeEach(async () => {
    await pool.query('TRUNCATE TABLE live_view_events, live_guests, live_points, live_static_aliases, live_sessions CASCADE');
  });

  it('upserts session with writeToken and preserves it', async () => {
    const s = await repo.upsertSession('sess-1', {
      title: 'Morning Hike',
      staticId: 'jh-user1',
      syncIntervalMinutes: 3,
    });

    assert.equal(s.id, 'sess-1');
    assert.equal(s.title, 'Morning Hike');
    assert.equal(s.staticId, 'jh-user1');
    assert.equal(s.syncIntervalMinutes, 3);
    assert.ok(s.writeToken && s.writeToken.length > 10);

    const verified = await repo.verifyWriteToken('sess-1', s.writeToken);
    assert.equal(verified, true);

    const wrongToken = await repo.verifyWriteToken('sess-1', 'bad-token');
    assert.equal(wrongToken, false);
  });

  it('resolves session id via static link alias to newest session', async () => {
    await repo.upsertSession('sess-old', {
      title: 'Old Session',
      staticId: 'jh-shared',
      createdAt: 1000,
    });

    let resolved = await repo.resolveSessionId('jh-shared');
    assert.equal(resolved, 'sess-old');

    // Newer session registered with same staticId
    await repo.upsertSession('sess-new', {
      title: 'New Session',
      staticId: 'jh-shared',
      createdAt: 2000,
    });

    resolved = await repo.resolveSessionId('jh-shared');
    assert.equal(resolved, 'sess-new');
  });

  it('appends points deduplicating by t, keeps all in db, caps getSessionView', async () => {
    await repo.upsertSession('sess-points', { title: 'Points Test' });

    const batch1 = [
      { t: 100, lat: 49.85, lng: 19.28, spd: 5.0, alt: 400.0, acc: 3.0 },
      { t: 200, lat: 49.86, lng: 19.29, spd: 4.5, alt: 402.0, acc: 2.5 },
    ];
    await repo.appendPoints('sess-points', batch1, 0);

    // Re-insert same timestamps plus one new
    const batch2 = [
      { t: 200, lat: 49.86, lng: 19.29 }, // duplicate
      { t: 300, lat: 49.87, lng: 19.30, spd: 6.0 },
    ];
    await repo.appendPoints('sess-points', batch2, 0);

    const view = await repo.getSessionView('sess-points');
    assert.equal(view.points.length, 3);
    assert.equal(view.points[0].t, 100);
    assert.equal(view.points[1].t, 200);
    assert.equal(view.points[2].t, 300);

    // Verify limit works
    const limited = await repo.getSessionView('sess-points', { pointLimit: 2 });
    assert.equal(limited.points.length, 2);
    assert.equal(limited.points[0].t, 200);
    assert.equal(limited.points[1].t, 300);
  });

  it('resetTrail moves points into pastSegments and increments segment', async () => {
    await repo.upsertSession('sess-trail', { title: 'Trail Segments' });

    await repo.appendPoints('sess-trail', [
      { t: 10, lat: 49.81, lng: 19.21 },
      { t: 20, lat: 49.82, lng: 19.22 },
    ], 0);

    const newSeg = await repo.resetTrail('sess-trail');
    assert.equal(newSeg, 1);

    await repo.appendPoints('sess-trail', [
      { t: 30, lat: 49.83, lng: 19.23 },
    ], 1);

    const view = await repo.getSessionView('sess-trail');
    assert.equal(view.points.length, 1);
    assert.equal(view.points[0].t, 30);
    assert.equal(view.pastSegments.length, 1);
    assert.equal(view.pastSegments[0].length, 2);
  });

  it('allocates distinct guest identities under concurrency', async () => {
    await repo.upsertSession('sess-guest', { title: 'Guest Test' });

    const results = await Promise.all([
      repo.upsertGuestPosition('sess-guest', 'g-1', { lat: 49.1, lng: 19.1, acc: 5 }),
      repo.upsertGuestPosition('sess-guest', 'g-2', { lat: 49.2, lng: 19.2, acc: 4 }),
      repo.upsertGuestPosition('sess-guest', 'g-3', { lat: 49.3, lng: 19.3, acc: 6 }),
    ]);

    const names = results.map(r => r.guest.name);
    const uniqueNames = new Set(names);
    assert.equal(uniqueNames.size, 3);
  });
});
