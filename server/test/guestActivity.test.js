const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const {
  isGuestActiveAt,
  serializeGuestsList,
  GUEST_POLL_ACTIVE_WINDOW_MS,
  GUEST_POSITION_ACTIVE_WINDOW_MS,
} = require('../src/app');

describe('guestActivity windows', () => {
  it('treats guest as active when viewer polled within 3 minutes', () => {
    const now = 1_700_000_000_000;
    const guest = {
      id: 'g-1',
      name: 'Alice',
      lat: 50.0,
      lng: 19.0,
      t: now - 30 * 60_000, // stale GPS (30 min ago)
      lastPolled: now - (GUEST_POLL_ACTIVE_WINDOW_MS - 1_000),
    };
    assert.equal(isGuestActiveAt(guest, now), true);
  });

  it('treats guest as active when position updated within 15 minutes even without recent poll', () => {
    const now = 1_700_000_000_000;
    const guest = {
      id: 'g-2',
      name: 'Bob',
      lat: 50.0,
      lng: 19.0,
      t: now - (GUEST_POSITION_ACTIVE_WINDOW_MS - 5_000),
      lastPolled: now - 10 * 60_000,
    };
    assert.equal(isGuestActiveAt(guest, now), true);
  });

  it('marks guest inactive when both poll > 3m and position > 15m', () => {
    const now = 1_700_000_000_000;
    const guest = {
      id: 'g-3',
      name: 'Charlie',
      lat: 50.0,
      lng: 19.0,
      t: now - (GUEST_POSITION_ACTIVE_WINDOW_MS + 1_000),
      lastPolled: now - (GUEST_POLL_ACTIVE_WINDOW_MS + 1_000),
    };
    assert.equal(isGuestActiveAt(guest, now), false);
  });

  it('serializeGuestsList includes lastPolled and honors requireCoords', () => {
    const now = 1_700_000_000_000;
    const guestsMap = {
      g1: { id: 'g1', name: 'Alice', lat: 50.1, lng: 19.1, t: now - 60_000, lastPolled: now - 10_000 },
      g2: { id: 'g2', name: 'ViewerOnly', lat: null, lng: null, createdAt: now - 30_000, lastPolled: now - 5_000 },
    };

    const withCoordsOnly = serializeGuestsList(guestsMap, { requireCoords: true, nowMs: now });
    assert.equal(withCoordsOnly.length, 1);
    assert.equal(withCoordsOnly[0].lastPolled, now - 10_000);
    assert.equal(withCoordsOnly[0].isInactive, false);

    const allViews = serializeGuestsList(guestsMap, { requireCoords: false, nowMs: now });
    assert.equal(allViews.length, 2);
    assert.equal(allViews[1].isInactive, false);
  });
});
