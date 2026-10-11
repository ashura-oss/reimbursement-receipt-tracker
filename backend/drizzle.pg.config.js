import 'dotenv/config'
import { defineConfig } from 'drizzle-kit'

export default defineConfig({
  schema: './src/db/schema.pg.js',
  out: './drizzle/pg',
  dialect: 'postgresql',
  dbCredentials: { url: process.env.DATABASE_URL || '' },
  verbose: true,
  strict: true,
})
