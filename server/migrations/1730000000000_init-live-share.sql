-- Up Migration
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE live_sessions (
  id                     text PRIMARY KEY,
  static_id              text,
  write_token            text,
  title                  text        NOT NULL DEFAULT 'Live Session',
  created_at             timestamptz NOT NULL DEFAULT now(),
  expires_at             timestamptz,
  ended                  boolean     NOT NULL DEFAULT false,
  ended_at               timestamptz,
  paused                 boolean     NOT NULL DEFAULT false,
  personal_paused        boolean     NOT NULL DEFAULT false,
  random_paused          boolean     NOT NULL DEFAULT false,
  trail_visible          boolean     NOT NULL DEFAULT true,
  follow_guests          boolean     NOT NULL DEFAULT true,
  sync_interval_minutes  integer     NOT NULL DEFAULT 5 CHECK (sync_interval_minutes > 0),
  current_segment        integer     NOT NULL DEFAULT 0,
  current                jsonb,
  pauses                 jsonb       NOT NULL DEFAULT '[]'::jsonb,
  view_count             integer     NOT NULL DEFAULT 0,
  updated_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX live_sessions_static_id_idx ON live_sessions (static_id) WHERE static_id IS NOT NULL;

CREATE TABLE live_static_aliases (
  static_id   text PRIMARY KEY,
  session_id  text NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE live_points (
  session_id  text     NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
  segment     integer  NOT NULL,
  t_ms        bigint   NOT NULL,
  lat         double precision NOT NULL,
  lng         double precision NOT NULL,
  geom        geography(Point, 4326) NOT NULL,
  spd         real,
  alt         real,
  acc         real,
  extra       jsonb,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, segment, t_ms)
);

CREATE INDEX live_points_geom_idx ON live_points USING gist (geom);

CREATE TABLE live_guests (
  session_id   text NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
  guest_id     text NOT NULL,
  name         text,
  color_index  integer NOT NULL DEFAULT 0,
  first_seen   timestamptz NOT NULL DEFAULT now(),
  created_at   timestamptz NOT NULL DEFAULT now(),
  view_count   integer NOT NULL DEFAULT 1,
  lat          double precision,
  lng          double precision,
  acc          real,
  last_seen    timestamptz,
  PRIMARY KEY (session_id, guest_id)
);

CREATE TABLE live_view_events (
  id           text PRIMARY KEY,
  session_id   text NOT NULL REFERENCES live_sessions(id) ON DELETE CASCADE,
  t            timestamptz NOT NULL DEFAULT now(),
  guest_id     text,
  name         text,
  device       text,
  color_index  integer NOT NULL DEFAULT 0
);

CREATE INDEX live_view_events_session_t_idx ON live_view_events (session_id, t DESC);

-- Down Migration
DROP TABLE IF EXISTS live_view_events;
DROP TABLE IF EXISTS live_guests;
DROP TABLE IF EXISTS live_points;
DROP TABLE IF EXISTS live_static_aliases;
DROP TABLE IF EXISTS live_sessions;
