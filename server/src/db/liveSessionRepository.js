const crypto = require('crypto');
const { allocateGuestIdentity, resolveGuestDisplayName, parseGuestNumber } = require('../../guestNaming');

function generateWriteToken() {
  return crypto.randomBytes(24).toString('base64url');
}

function rowToSession(row, points = [], pastSegments = []) {
  if (!row) return null;
  return {
    id: row.id,
    staticId: row.static_id || null,
    writeToken: row.write_token || undefined,
    title: row.title || 'Live Hike',
    createdAt: row.created_at ? new Date(row.created_at).getTime() : Date.now(),
    expiresAt: row.expires_at ? new Date(row.expires_at).getTime() : 0,
    trailVisible: Boolean(row.trail_visible),
    syncIntervalMinutes: parseInt(row.sync_interval_minutes, 10) || 5,
    followGuests: Boolean(row.follow_guests),
    ended: Boolean(row.ended),
    endedAt: row.ended_at ? new Date(row.ended_at).getTime() : undefined,
    paused: Boolean(row.paused),
    personalPaused: Boolean(row.personal_paused),
    randomPaused: Boolean(row.random_paused),
    currentSegment: row.current_segment || 0,
    current: row.current || null,
    points: points,
    pastSegments: pastSegments,
    pauses: Array.isArray(row.pauses) ? row.pauses : [],
    viewCount: row.view_count || 0,
  };
}

function createLiveSessionRepository(pool) {
  async function resolveSessionId(idOrStatic) {
    if (!idOrStatic) return null;
    const clean = idOrStatic.trim();

    // 1. Check static aliases
    const aliasRes = await pool.query(
      'SELECT session_id FROM live_static_aliases WHERE static_id = $1',
      [clean]
    );
    if (aliasRes.rows.length > 0) {
      return aliasRes.rows[0].session_id;
    }

    // 2. Fallback: check sessions with static_id (newest created first)
    const staticRes = await pool.query(
      'SELECT id FROM live_sessions WHERE static_id = $1 ORDER BY created_at DESC LIMIT 1',
      [clean]
    );
    if (staticRes.rows.length > 0) {
      const sId = staticRes.rows[0].id;
      // Cache in aliases table
      await pool.query(
        'INSERT INTO live_static_aliases (static_id, session_id) VALUES ($1, $2) ON CONFLICT (static_id) DO UPDATE SET session_id = $2, updated_at = now()',
        [clean, sId]
      );
      return sId;
    }

    return clean;
  }

  async function getSessionRow(sessionId) {
    const res = await pool.query('SELECT * FROM live_sessions WHERE id = $1', [sessionId]);
    return res.rows[0] || null;
  }

  async function verifyWriteToken(sessionId, tokenHeader) {
    if (!tokenHeader) return true; // Optional for backward compatibility with older clients
    const row = await getSessionRow(sessionId);
    if (!row || !row.write_token) return true; // If no token set on session, pass
    return row.write_token === tokenHeader.trim();
  }

  async function upsertSession(id, data = {}) {
    const {
      title,
      createdAt,
      expiresAt,
      staticId,
      trailVisible,
      syncIntervalMinutes,
      followGuests,
    } = data;

    const createdAtDate = createdAt ? new Date(createdAt) : new Date();
    const expiresAtDate = expiresAt && expiresAt > 0 ? new Date(expiresAt) : null;
    const generatedToken = generateWriteToken();

    const query = `
      INSERT INTO live_sessions (
        id, static_id, write_token, title, created_at, expires_at, trail_visible,
        syncInterval_minutes, follow_guests, ended, paused, pauses, view_count, updated_at
      ) VALUES (
        $1, $2, $3, $4, $5, $6, $7, $8, $9, false, false, '[]'::jsonb, 0, now()
      )
      ON CONFLICT (id) DO UPDATE SET
        title = COALESCE($4, live_sessions.title),
        expires_at = CASE WHEN $10::boolean THEN $6 ELSE live_sessions.expires_at END,
        static_id = COALESCE($2, live_sessions.static_id),
        trail_visible = CASE WHEN $11::boolean THEN $7 ELSE live_sessions.trail_visible END,
        sync_interval_minutes = CASE WHEN $12::boolean THEN $8 ELSE live_sessions.sync_interval_minutes END,
        follow_guests = CASE WHEN $13::boolean THEN $9 ELSE live_sessions.follow_guests END,
        ended = false,
        updated_at = now()
      RETURNING *;
    `;

    // Adjust column name typos if any in query: sync_interval_minutes
    const fixedQuery = query.replace('syncInterval_minutes', 'sync_interval_minutes');

    const res = await pool.query(fixedQuery, [
      id,
      staticId || null,
      generatedToken,
      title || 'Live Hike',
      createdAtDate,
      expiresAtDate,
      trailVisible !== undefined ? Boolean(trailVisible) : true,
      parseInt(syncIntervalMinutes, 10) || 5,
      followGuests !== undefined ? Boolean(followGuests) : true,
      expiresAt !== undefined,
      trailVisible !== undefined,
      syncIntervalMinutes !== undefined,
      followGuests !== undefined,
    ]);

    const sessionRow = res.rows[0];

    if (staticId) {
      await pool.query(
        'INSERT INTO live_static_aliases (static_id, session_id) VALUES ($1, $2) ON CONFLICT (static_id) DO UPDATE SET session_id = $2, updated_at = now()',
        [staticId, id]
      );
    }

    return rowToSession(sessionRow);
  }

  async function getPointsForSession(sessionId, currentSegment, pointLimit = 4000) {
    // Current segment points
    const currentRes = await pool.query(
      `SELECT t_ms, lat, lng, spd, alt, acc, extra
       FROM live_points
       WHERE session_id = $1 AND segment = $2
       ORDER BY t_ms DESC
       LIMIT $3`,
      [sessionId, currentSegment, pointLimit]
    );

    const points = currentRes.rows
      .map(r => ({
        t: Number(r.t_ms),
        lat: r.lat,
        lng: r.lng,
        spd: r.spd !== null ? r.spd : undefined,
        alt: r.alt !== null ? r.alt : undefined,
        acc: r.acc !== null ? r.acc : undefined,
        ...(r.extra || {})
      }))
      .reverse();

    // Past segments
    let pastSegments = [];
    if (currentSegment > 0) {
      const pastRes = await pool.query(
        `SELECT segment, t_ms, lat, lng, spd, alt, acc, extra
         FROM live_points
         WHERE session_id = $1 AND segment < $2
         ORDER BY segment ASC, t_ms ASC`,
        [sessionId, currentSegment]
      );

      const segmentMap = new Map();
      for (const r of pastRes.rows) {
        if (!segmentMap.has(r.segment)) segmentMap.set(r.segment, []);
        segmentMap.get(r.segment).push({
          t: Number(r.t_ms),
          lat: r.lat,
          lng: r.lng,
          spd: r.spd !== null ? r.spd : undefined,
          alt: r.alt !== null ? r.alt : undefined,
          acc: r.acc !== null ? r.acc : undefined,
          ...(r.extra || {})
        });
      }
      pastSegments = Array.from(segmentMap.values());
    }

    return { points, pastSegments };
  }

  async function getSessionView(sessionId, { pointLimit = 4000 } = {}) {
    const row = await getSessionRow(sessionId);
    if (!row) return null;

    const { points, pastSegments } = await getPointsForSession(sessionId, row.current_segment, pointLimit);
    return rowToSession(row, points, pastSegments);
  }

  async function appendPoints(sessionId, pointsArray, currentSegment = 0) {
    if (!Array.isArray(pointsArray) || pointsArray.length === 0) return 0;

    const tArr = [];
    const latArr = [];
    const lngArr = [];
    const spdArr = [];
    const altArr = [];
    const accArr = [];
    const extraArr = [];

    for (const p of pointsArray) {
      if (!p || typeof p.lat !== 'number' || typeof p.lng !== 'number' || typeof p.t !== 'number') {
        continue;
      }
      tArr.push(BigInt(p.t));
      latArr.push(p.lat);
      lngArr.push(p.lng);
      spdArr.push(typeof p.spd === 'number' ? p.spd : null);
      altArr.push(typeof p.alt === 'number' ? p.alt : null);
      accArr.push(typeof p.acc === 'number' ? p.acc : null);

      const { t: _t, lat: _lat, lng: _lng, spd: _spd, alt: _alt, acc: _acc, ...extra } = p;
      extraArr.push(Object.keys(extra).length > 0 ? JSON.stringify(extra) : null);
    }

    if (tArr.length === 0) return 0;

    const res = await pool.query(
      `INSERT INTO live_points (session_id, segment, t_ms, lat, lng, geom, spd, alt, acc, extra)
       SELECT
         $1,
         $2,
         u.t_ms,
         u.lat,
         u.lng,
         ST_SetSRID(ST_MakePoint(u.lng, u.lat), 4326)::geography,
         u.spd,
         u.alt,
         u.acc,
         u.extra::jsonb
       FROM UNNEST(
         $3::bigint[],
         $4::double precision[],
         $5::double precision[],
         $6::real[],
         $7::real[],
         $8::real[],
         $9::text[]
       ) AS u(t_ms, lat, lng, spd, alt, acc, extra)
       ON CONFLICT (session_id, segment, t_ms) DO NOTHING`,
      [sessionId, currentSegment, tArr, latArr, lngArr, spdArr, altArr, accArr, extraArr]
    );

    return res.rowCount;
  }

  async function updateSessionCurrentAndPoints(sessionId, updateData) {
    const {
      title,
      staticId,
      followGuests,
      isPaused,
      current,
      pauses,
      points,
    } = updateData;

    let row = await getSessionRow(sessionId);
    if (!row) {
      // Auto-create session if not present
      await upsertSession(sessionId, { title: title || 'Live Session' });
      row = await getSessionRow(sessionId);
    }

    if (row.ended) {
      return { ended: true };
    }

    if (row.expires_at && Date.now() > new Date(row.expires_at).getTime()) {
      await pool.query('UPDATE live_sessions SET ended = true, ended_at = now() WHERE id = $1', [sessionId]);
      return { expired: true };
    }

    const currentSegment = row.current_segment || 0;

    // Append points
    if (Array.isArray(points) && points.length > 0) {
      await appendPoints(sessionId, points, currentSegment);
    }

    // Update metadata
    const sets = ['updated_at = now()'];
    const vals = [sessionId];
    let idx = 2;

    if (title && typeof title === 'string' && title.trim()) {
      sets.push(`title = $${idx++}`);
      vals.push(title.trim());
    }
    if (staticId) {
      sets.push(`static_id = $${idx++}`);
      vals.push(staticId);
      await pool.query(
        'INSERT INTO live_static_aliases (static_id, session_id) VALUES ($1, $2) ON CONFLICT (static_id) DO UPDATE SET session_id = $2, updated_at = now()',
        [staticId, sessionId]
      );
    }
    if (followGuests !== undefined) {
      sets.push(`follow_guests = $${idx++}`);
      vals.push(Boolean(followGuests));
    }
    if (isPaused !== undefined) {
      sets.push(`paused = $${idx++}`);
      vals.push(Boolean(isPaused));
    }
    if (current) {
      sets.push(`current = $${idx++}::jsonb`);
      vals.push(JSON.stringify(current));
    }
    if (Array.isArray(pauses)) {
      sets.push(`pauses = $${idx++}::jsonb`);
      vals.push(JSON.stringify(pauses));
    }

    const q = `UPDATE live_sessions SET ${sets.join(', ')} WHERE id = $1 RETURNING *`;
    const res = await pool.query(q, vals);
    const updatedRow = res.rows[0];

    // Count points in current segment
    const countRes = await pool.query(
      'SELECT count(*) FROM live_points WHERE session_id = $1 AND segment = $2',
      [sessionId, currentSegment]
    );
    const totalPoints = parseInt(countRes.rows[0].count, 10);

    return {
      session: rowToSession(updatedRow),
      totalPoints,
    };
  }

  async function resetTrail(sessionId) {
    const row = await getSessionRow(sessionId);
    if (!row) return null;

    const countRes = await pool.query(
      'SELECT count(*) FROM live_points WHERE session_id = $1 AND segment = $2',
      [sessionId, row.current_segment]
    );
    const count = parseInt(countRes.rows[0].count, 10);

    if (count > 0) {
      const res = await pool.query(
        'UPDATE live_sessions SET current_segment = current_segment + 1, pauses = $2::jsonb, updated_at = now() WHERE id = $1 RETURNING current_segment',
        [sessionId, '[]']
      );
      return res.rows[0].current_segment;
    }
    return row.current_segment;
  }

  async function renameSession(sessionId, title) {
    const res = await pool.query(
      'UPDATE live_sessions SET title = $2, updated_at = now() WHERE id = $1 RETURNING title',
      [sessionId, title.trim()]
    );
    return res.rows[0] ? res.rows[0].title : null;
  }

  async function setSyncInterval(sessionId, minutes) {
    const res = await pool.query(
      'UPDATE live_sessions SET sync_interval_minutes = $2, updated_at = now() WHERE id = $1 RETURNING sync_interval_minutes',
      [sessionId, minutes]
    );
    return res.rows[0] ? res.rows[0].sync_interval_minutes : null;
  }

  async function setTrailVisible(sessionId, trailVisible) {
    const res = await pool.query(
      'UPDATE live_sessions SET trail_visible = $2, updated_at = now() WHERE id = $1 RETURNING trail_visible',
      [sessionId, Boolean(trailVisible)]
    );
    return res.rows[0] ? res.rows[0].trail_visible : null;
  }

  async function setFollowGuests(sessionId, followGuests) {
    const res = await pool.query(
      'UPDATE live_sessions SET follow_guests = $2, updated_at = now() WHERE id = $1 RETURNING follow_guests',
      [sessionId, Boolean(followGuests)]
    );
    return res.rows[0] ? res.rows[0].follow_guests : null;
  }

  async function setStatus(sessionId, { status, target }) {
    const isPaused = status === 'paused';
    let q;
    let vals;

    if (target === 'personal') {
      q = 'UPDATE live_sessions SET personal_paused = $2, updated_at = now() WHERE id = $1 RETURNING *';
      vals = [sessionId, isPaused];
    } else if (target === 'random') {
      q = 'UPDATE live_sessions SET random_paused = $2, updated_at = now() WHERE id = $1 RETURNING *';
      vals = [sessionId, isPaused];
    } else {
      if (!isPaused) {
        q = 'UPDATE live_sessions SET paused = false, personal_paused = false, random_paused = false, ended = false, updated_at = now() WHERE id = $1 RETURNING *';
        vals = [sessionId];
      } else {
        q = 'UPDATE live_sessions SET paused = true, updated_at = now() WHERE id = $1 RETURNING *';
        vals = [sessionId];
      }
    }

    const res = await pool.query(q, vals);
    return res.rows[0] ? rowToSession(res.rows[0]) : null;
  }

  async function extendSession(sessionId, additionalHours) {
    const row = await getSessionRow(sessionId);
    if (!row) return null;

    let newExpiresAtMs = 0;
    if (!row.expires_at) {
      if (additionalHours > 0) {
        newExpiresAtMs = Date.now() + additionalHours * 3600_000;
      }
    } else {
      const baseTime = new Date(row.expires_at).getTime() > Date.now() ? new Date(row.expires_at).getTime() : Date.now();
      newExpiresAtMs = Math.max(Date.now() + 60000, baseTime + additionalHours * 3600_000);
    }

    const dateVal = newExpiresAtMs > 0 ? new Date(newExpiresAtMs) : null;
    const res = await pool.query(
      'UPDATE live_sessions SET expires_at = $2, ended = false, updated_at = now() WHERE id = $1 RETURNING expires_at',
      [sessionId, dateVal]
    );

    return res.rows[0] && res.rows[0].expires_at ? new Date(res.rows[0].expires_at).getTime() : 0;
  }

  async function endSession(sessionId) {
    await pool.query(
      'UPDATE live_sessions SET ended = true, paused = false, ended_at = now(), updated_at = now() WHERE id = $1',
      [sessionId]
    );
  }

  async function incrementViewCount(sessionId) {
    await pool.query('UPDATE live_sessions SET view_count = view_count + 1 WHERE id = $1', [sessionId]);
  }

  async function getGuests(sessionId) {
    const res = await pool.query(
      'SELECT guest_id, name, color_index, first_seen, created_at, view_count, lat, lng, acc, last_seen FROM live_guests WHERE session_id = $1',
      [sessionId]
    );
    const map = {};
    for (const r of res.rows) {
      map[r.guest_id] = {
        id: r.guest_id,
        name: r.name,
        colorIndex: r.color_index,
        firstSeen: r.first_seen ? new Date(r.first_seen).getTime() : Date.now(),
        createdAt: r.created_at ? new Date(r.created_at).getTime() : Date.now(),
        viewCount: r.view_count,
        lat: r.lat,
        lng: r.lng,
        acc: r.acc,
        t: r.last_seen ? new Date(r.last_seen).getTime() : undefined,
      };
    }
    return map;
  }

  async function upsertGuestPosition(sessionId, guestId, { lat, lng, acc, name }) {
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      // Lock session row to serialize guest allocation
      const sessRes = await client.query('SELECT * FROM live_sessions WHERE id = $1 FOR UPDATE', [sessionId]);
      if (sessRes.rows.length === 0) {
        await client.query('ROLLBACK');
        return { notFound: true };
      }
      const sessionRow = sessRes.rows[0];
      if (sessionRow.ended) {
        await client.query('ROLLBACK');
        return { ended: true };
      }
      if (sessionRow.follow_guests === false) {
        await client.query('ROLLBACK');
        return { disabled: true };
      }

      const guestsRes = await client.query('SELECT * FROM live_guests WHERE session_id = $1', [sessionId]);
      const currentGuests = {};
      for (const r of guestsRes.rows) {
        currentGuests[r.guest_id] = {
          name: r.name,
          colorIndex: r.color_index,
        };
      }

      let existing = guestsRes.rows.find(r => r.guest_id === guestId);
      let guestName;
      let colorIndex;
      const now = new Date();

      if (!existing) {
        const identity = allocateGuestIdentity(currentGuests);
        guestName = (typeof name === 'string' && name.trim()) ? name.trim().slice(0, 30) : identity.name;
        colorIndex = identity.colorIndex;

        await client.query(
          `INSERT INTO live_guests (session_id, guest_id, name, color_index, first_seen, created_at, view_count, lat, lng, acc, last_seen)
           VALUES ($1, $2, $3, $4, $5, $5, 1, $6, $7, $8, $5)`,
          [sessionId, guestId, guestName, colorIndex, now, lat, lng, acc || null]
        );
      } else {
        if (typeof name === 'string' && name.trim()) {
          guestName = name.trim().slice(0, 30);
        } else if (!existing.name) {
          guestName = resolveGuestDisplayName(existing, currentGuests, guestId);
        } else {
          guestName = existing.name;
        }

        if (typeof existing.color_index !== 'number') {
          const n = parseGuestNumber(guestName);
          colorIndex = n !== null ? (n - 1) % 8 : 0;
        } else {
          colorIndex = existing.color_index;
        }

        await client.query(
          `UPDATE live_guests
           SET name = $3, color_index = $4, lat = $5, lng = $6, acc = $7, last_seen = $8
           WHERE session_id = $1 AND guest_id = $2`,
          [sessionId, guestId, guestName, colorIndex, lat, lng, acc || null, now]
        );
      }

      await client.query('COMMIT');

      return {
        guest: {
          id: guestId,
          name: guestName,
          colorIndex: colorIndex,
          lat,
          lng,
          acc: typeof acc === 'number' ? acc : null,
          t: now.getTime(),
          firstSeen: existing && existing.first_seen ? new Date(existing.first_seen).getTime() : now.getTime(),
          viewCount: existing ? existing.view_count : 1,
        }
      };
    } catch (e) {
      await client.query('ROLLBACK');
      throw e;
    } finally {
      client.release();
    }
  }

  async function deleteGuest(sessionId, guestId) {
    const res = await pool.query(
      'DELETE FROM live_guests WHERE session_id = $1 AND guest_id = $2 RETURNING guest_id',
      [sessionId, guestId]
    );
    return res.rows.length > 0 ? res.rows[0].guest_id : null;
  }

  async function recordViewEvent(sessionId, guestId, userAgent, isPv) {
    if (!guestId) return;
    const cleanGuestId = guestId.trim().slice(0, 64);
    if (!cleanGuestId) return;

    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const guestsRes = await client.query('SELECT * FROM live_guests WHERE session_id = $1', [sessionId]);
      const currentGuests = {};
      for (const r of guestsRes.rows) {
        currentGuests[r.guest_id] = { name: r.name, colorIndex: r.color_index };
      }

      let existing = guestsRes.rows.find(r => r.guest_id === cleanGuestId);
      let gName;
      let gColor = 0;

      if (!existing) {
        const identity = allocateGuestIdentity(currentGuests);
        gName = identity.name;
        gColor = identity.colorIndex;
        await client.query(
          `INSERT INTO live_guests (session_id, guest_id, name, color_index, first_seen, created_at, view_count)
           VALUES ($1, $2, $3, $4, now(), now(), 1)`,
          [sessionId, cleanGuestId, gName, gColor]
        );
      } else {
        gName = existing.name || resolveGuestDisplayName(existing, currentGuests, cleanGuestId);
        gColor = typeof existing.color_index === 'number' ? existing.color_index : 0;
        if (isPv) {
          await client.query(
            'UPDATE live_guests SET view_count = view_count + 1 WHERE session_id = $1 AND guest_id = $2',
            [sessionId, cleanGuestId]
          );
        }
      }

      if (isPv) {
        let device = 'Web Browser';
        if (/iPhone/i.test(userAgent)) device = 'iPhone';
        else if (/iPad/i.test(userAgent)) device = 'iPad';
        else if (/Android/i.test(userAgent)) device = 'Android';
        else if (/Macintosh|Mac OS X/i.test(userAgent)) device = 'Mac';
        else if (/Windows/i.test(userAgent)) device = 'Windows';
        else if (/Linux/i.test(userAgent)) device = 'Linux';

        const eventId = `${Date.now()}-${Math.random().toString(36).substr(2, 6)}`;
        await client.query(
          `INSERT INTO live_view_events (id, session_id, t, guest_id, name, device, color_index)
           VALUES ($1, $2, now(), $3, $4, $5, $6)`,
          [eventId, sessionId, cleanGuestId, gName, device, gColor]
        );
      }

      await client.query('COMMIT');
    } catch (e) {
      await client.query('ROLLBACK');
      throw e;
    } finally {
      client.release();
    }
  }

  async function getViewLog(sessionId, limit = 200) {
    const res = await pool.query(
      `SELECT e.id, e.t, e.guest_id, e.name, e.device, e.color_index, g.lat, g.lng, g.acc
       FROM live_view_events e
       LEFT JOIN live_guests g ON e.session_id = g.session_id AND e.guest_id = g.guest_id
       WHERE e.session_id = $1
       ORDER BY e.t DESC
       LIMIT $2`,
      [sessionId, limit]
    );

    return res.rows.map(r => ({
      id: r.id,
      t: new Date(r.t).getTime(),
      guestId: r.guest_id,
      name: r.name || 'Guest',
      device: r.device,
      colorIndex: r.color_index || 0,
      lat: typeof r.lat === 'number' ? r.lat : null,
      lng: typeof r.lng === 'number' ? r.lng : null,
      acc: typeof r.acc === 'number' ? r.acc : null,
    }));
  }

  return {
    resolveSessionId,
    getSessionRow,
    verifyWriteToken,
    upsertSession,
    getSessionView,
    appendPoints,
    updateSessionCurrentAndPoints,
    resetTrail,
    renameSession,
    setSyncInterval,
    setTrailVisible,
    setFollowGuests,
    setStatus,
    extendSession,
    endSession,
    incrementViewCount,
    getGuests,
    upsertGuestPosition,
    deleteGuest,
    recordViewEvent,
    getViewLog,
  };
}

module.exports = {
  createLiveSessionRepository,
};
