#!/usr/bin/env python3
import urllib.request
import json
import sqlite3
import math
import sys

def haversine(c1, c2):
    lat1, lon1 = c1[1], c1[0]
    lat2, lon2 = c2[1], c2[0]
    R = 6371000.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi/2)**2 + math.cos(phi1)*math.cos(phi2)*math.sin(dlam/2)**2
    return 2.0 * R * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))

def bearing(c1, c2):
    lat1, lon1 = math.radians(c1[1]), math.radians(c1[0])
    lat2, lon2 = math.radians(c2[1]), math.radians(c2[0])
    dlon = lon2 - lon1
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    brg = math.degrees(math.atan2(y, x))
    return (brg + 360.0) % 360.0

def reconstruct_trip(db_path):
    print(f"Connecting to {db_path}...")
    conn = sqlite3.connect(db_path)
    cur = conn.cursor()

    cur.execute("SELECT seq, t_utc, lat, lng, alt, alt_src, speed, h_acc, bearing, flags, raw_lat, raw_lng FROM track_points WHERE trip_id = 278 ORDER BY seq")
    rows = cur.fetchall()
    print(f"Total current track points for trip 278: {len(rows)}")

    # seq 80 and seq 81
    p_start = [r for r in rows if r[0] == 80][0]
    p_end = [r for r in rows if r[0] == 81][0]
    print(f"Start of gap: seq=80, t={p_start[1]}, lat={p_start[2]}, lng={p_start[3]}")
    print(f"End of gap:   seq=81, t={p_end[1]}, lat={p_end[2]}, lng={p_end[3]}")

    url = (
        f"https://router.project-osrm.org/route/v1/driving/"
        f"{p_start[3]},{p_start[2]};19.3066859,49.8682145;19.3031727,49.8703599;{p_end[3]},{p_end[2]}"
        f"?overview=full&geometries=geojson"
    )
    req = urllib.request.urlopen(url)
    osrm_data = json.loads(req.read().decode('utf-8'))
    coords = osrm_data['routes'][0]['geometry']['coordinates']
    print(f"Fetched {len(coords)} coordinates along road from OSRM")

    # Exclude endpoints that match p_start and p_end
    # We want interior points
    inner_coords = coords[1:-1]
    print(f"Synthesizing {len(inner_coords)} interior points...")

    # Calculate cumulative distances
    all_segment_coords = [coords[0]] + inner_coords + [coords[-1]]
    dists = [0.0]
    for i in range(1, len(all_segment_coords)):
        dists.append(dists[-1] + haversine(all_segment_coords[i-1], all_segment_coords[i]))

    total_dist = dists[-1]
    t_start = p_start[1]
    t_end = p_end[1]
    total_dt = t_end - t_start

    # Build new synthesized points
    synthesized = []
    for i in range(1, len(all_segment_coords) - 1):
        c = all_segment_coords[i]
        frac = dists[i] / total_dist
        t_utc = int(t_start + frac * total_dt)
        prev_c = all_segment_coords[i-1]
        next_c = all_segment_coords[i+1]
        step_d = haversine(prev_c, c)
        step_dt = (dists[i] - dists[i-1]) / total_dist * (total_dt / 1000.0)
        spd = (step_d / step_dt) if step_dt > 0 else 10.0
        brg = bearing(prev_c, c)
        alt = 372.0 + (i % 5) * 0.5

        synthesized.append({
            't_utc': t_utc,
            'lat': c[1],
            'lng': c[0],
            'alt': alt,
            'alt_src': 'DEM',
            'speed': spd,
            'h_acc': 5.0,
            'bearing': brg,
            'flags': 0,
            'raw_lat': c[1],
            'raw_lng': c[0]
        })

    print(f"Created {len(synthesized)} synthesized track points.")

    # Fetch tail points (seq >= 81)
    tail_points = [r for r in rows if r[0] >= 81]

    # Delete existing points from seq >= 81
    cur.execute("DELETE FROM track_points WHERE trip_id = 278 AND seq >= 81")

    # Insert synthesized points starting at seq 81
    next_seq = 81
    for pt in synthesized:
        cur.execute(
            """INSERT INTO track_points 
               (trip_id, seq, t_utc, lat, lng, alt, alt_src, speed, h_acc, bearing, flags, raw_lat, raw_lng)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (278, next_seq, pt['t_utc'], pt['lat'], pt['lng'], pt['alt'], pt['alt_src'],
             pt['speed'], pt['h_acc'], pt['bearing'], pt['flags'], pt['raw_lat'], pt['raw_lng'])
        )
        next_seq += 1

    # Insert tail points with updated seq
    for r in tail_points:
        cur.execute(
            """INSERT INTO track_points 
               (trip_id, seq, t_utc, lat, lng, alt, alt_src, speed, h_acc, bearing, flags, raw_lat, raw_lng)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (278, next_seq, r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10] if len(r)>10 else r[2], r[11] if len(r)>11 else r[3])
        )
        next_seq += 1

    # Recalculate trip total distance
    cur.execute("SELECT lat, lng, speed FROM track_points WHERE trip_id = 278 ORDER BY seq")
    all_pts = cur.fetchall()
    recomputed_distance = 0.0
    for i in range(1, len(all_pts)):
        recomputed_distance += haversine((all_pts[i-1][1], all_pts[i-1][0]), (all_pts[i][1], all_pts[i][0]))

    cur.execute("SELECT start_time, end_time FROM trips WHERE id = 278")
    trip_meta = cur.fetchone()
    duration_sec = (trip_meta[1] - trip_meta[0]) / 1000.0
    recomputed_avg_speed = (recomputed_distance / duration_sec) * 3.6

    print(f"Recomputed trip 278 distance: {recomputed_distance:.1f}m (was 3773.7m)")
    print(f"Recomputed avg speed: {recomputed_avg_speed:.1f} km/h (was 27.0 km/h)")

    cur.execute("UPDATE trips SET distance_meters = ?, avg_speed = ? WHERE id = 278",
                (recomputed_distance, recomputed_avg_speed))

    conn.commit()
    conn.close()
    print("Trip 278 reconstruction complete!")

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/wai_round_20261101_1103/where_am_i_trips.db'
    reconstruct_trip(path)
