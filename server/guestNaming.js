/**
 * Unique default guest nicknames: "Guest 1", "Guest 2", …
 * Numbers are the lowest unused positive integers among existing Guest-N names
 * (not Object.keys length), so deletions and colorIndex reuse never collide.
 */

function parseGuestNumber(name) {
  if (typeof name !== 'string') return null;
  const m = /^Guest\s+(\d+)$/i.exec(name.trim());
  return m ? Number(m[1]) : null;
}

function collectUsedGuestNumbers(guests, excludeGuestId) {
  const used = new Set();
  if (!guests || typeof guests !== 'object') return used;
  for (const [id, g] of Object.entries(guests)) {
    if (excludeGuestId && id === excludeGuestId) continue;
    const n = parseGuestNumber(g && g.name);
    if (n !== null && n > 0) used.add(n);
  }
  return used;
}

/** Lowest unused positive integer for a new "Guest N" label. */
function nextGuestNumber(guests, excludeGuestId) {
  const used = collectUsedGuestNumbers(guests, excludeGuestId);
  let n = 1;
  while (used.has(n)) n += 1;
  return n;
}

/**
 * Allocate default name + colorIndex for a newly registered guest.
 * colorIndex follows the Guest number so palette stays stable with the label.
 */
function allocateGuestIdentity(guests) {
  const n = nextGuestNumber(guests);
  return {
    name: `Guest ${n}`,
    colorIndex: (n - 1) % 8
  };
}

/** Display / repair name when a stored guest row is missing `name`. */
function resolveGuestDisplayName(guest, guests, guestId) {
  if (guest && typeof guest.name === 'string' && guest.name.trim()) {
    return guest.name.trim().slice(0, 30);
  }
  const n = nextGuestNumber(guests, guestId);
  return `Guest ${n}`;
}

module.exports = {
  parseGuestNumber,
  nextGuestNumber,
  allocateGuestIdentity,
  resolveGuestDisplayName
};
