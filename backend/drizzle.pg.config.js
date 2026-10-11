import 'dotenv/config'
import { defineConfig } from 'drizzle-kit'

const databaseUrl = process.env.DATABASE_URL
const sslMode = String(process.env.POSTGRES_SSL || 'verify-full').toLowerCase()
if (!['verify-full', 'require', 'disable'].includes(sslMode)) throw new Error('POSTGRES_SSL must be verify-full, require, or disable.')
const dbCredentials = databaseUrl
  ? (() => {
      const url = new URL(databaseUrl)
      return {
        host: url.hostname,
        port: Number(url.port || 5432),
        user: decodeURIComponent(url.username),
        password: decodeURIComponent(url.password),
        database: decodeURIComponent(url.pathname.replace(/^\//, '')),
        ssl: sslMode === 'disable' ? false : { rejectUnauthorized: true },
      }
    })()
  : { url: '' }

export default defineConfig({
  schema: './src/db/schema.pg.js',
  out: './drizzle/pg',
  dialect: 'postgresql',
  dbCredentials,
  verbose: true,
  strict: true,
})
