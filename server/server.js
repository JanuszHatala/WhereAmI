const { pool, close } = require('./src/db/pool');
const { migrate } = require('./src/db/migrate');
const { createApp } = require('./src/app');
const { createLiveSessionRepository } = require('./src/db/liveSessionRepository');

const PORT = process.env.PORT || 3003;

async function main() {
  if (process.env.DATABASE_URL) {
    console.log('Running database migrations...');
    await migrate();
  } else {
    console.warn('DATABASE_URL not set; skipping migrations.');
  }

  const repo = createLiveSessionRepository(pool);
  const app = createApp({ repo, pool });

  const server = app.listen(PORT, '0.0.0.0', () => {
    console.log(`WhereAmI Live Server running on port ${PORT}`);
  });

  const shutdown = async (signal) => {
    console.log(`Received ${signal}, shutting down gracefully...`);
    server.close(async () => {
      try {
        await close();
        console.log('Database pool closed.');
      } catch (e) {
        console.error('Error closing database pool:', e);
      } finally {
        process.exit(0);
      }
    });
  };

  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

main().catch((err) => {
  console.error('Fatal startup error:', err);
  process.exit(1);
});
