const path = require('path');
const pgMigrate = require('node-pg-migrate');
const runner = pgMigrate.runner || pgMigrate.default || pgMigrate;

async function migrate(databaseUrl) {
  const dbUrl = databaseUrl || process.env.DATABASE_URL;
  if (!dbUrl) {
    throw new Error('DATABASE_URL is required to run migrations');
  }

  const migrationsDir = path.resolve(__dirname, '../../migrations');

  await runner({
    databaseUrl: dbUrl,
    dir: migrationsDir,
    direction: 'up',
    migrationsTable: 'pgmigrations',
    log: (msg) => {
      if (process.env.NODE_ENV !== 'test') {
        console.log(`[migrate] ${msg}`);
      }
    },
  });
}

module.exports = { migrate };
