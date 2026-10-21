const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const {
  parseGuestNumber,
  nextGuestNumber,
  allocateGuestIdentity,
  resolveGuestDisplayName
} = require('./guestNaming');

describe('guestNaming', () => {
  it('parses Guest N labels', () => {
    assert.equal(parseGuestNumber('Guest 1'), 1);
    assert.equal(parseGuestNumber('guest 12'), 12);
    assert.equal(parseGuestNumber('Alice'), null);
    assert.equal(parseGuestNumber('Guest'), null);
  });

  it('assigns Guest 1 when session has no guests', () => {
    assert.deepEqual(allocateGuestIdentity({}), { name: 'Guest 1', colorIndex: 0 });
  });

  it('increments past existing Guest numbers', () => {
    const guests = {
      a: { name: 'Guest 1', colorIndex: 0 },
      b: { name: 'Guest 2', colorIndex: 1 }
    };
    assert.deepEqual(allocateGuestIdentity(guests), { name: 'Guest 3', colorIndex: 2 });
  });

  it('reuses the lowest free number after a deletion (not length+1 collision)', () => {
    // Guest 1 removed; Guest 2 remains. length+1 would wrongly mint another Guest 2.
    const guests = {
      b: { name: 'Guest 2', colorIndex: 1 }
    };
    assert.equal(nextGuestNumber(guests), 1);
    assert.deepEqual(allocateGuestIdentity(guests), { name: 'Guest 1', colorIndex: 0 });
  });

  it('ignores custom nicknames when finding the next Guest number', () => {
    const guests = {
      a: { name: 'Alice', colorIndex: 0 },
      b: { name: 'Guest 1', colorIndex: 1 }
    };
    assert.deepEqual(allocateGuestIdentity(guests), { name: 'Guest 2', colorIndex: 1 });
  });

  it('never suggests the same Guest N for two sequential allocations', () => {
    const guests = {};
    const first = allocateGuestIdentity(guests);
    guests.g1 = { name: first.name, colorIndex: first.colorIndex };
    const second = allocateGuestIdentity(guests);
    assert.notEqual(first.name, second.name);
    assert.equal(second.name, 'Guest 2');
  });

  it('resolveGuestDisplayName keeps stored name and repairs missing ones uniquely', () => {
    const guests = {
      a: { name: 'Guest 1' },
      b: { colorIndex: 0 } // missing name — must not become Guest 1
    };
    assert.equal(resolveGuestDisplayName(guests.a, guests, 'a'), 'Guest 1');
    assert.equal(resolveGuestDisplayName(guests.b, guests, 'b'), 'Guest 2');
  });
});
