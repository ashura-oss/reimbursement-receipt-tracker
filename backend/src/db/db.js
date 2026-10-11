import 'dotenv/config'
import { createClient } from '@libsql/client'
import { drizzle as drizzleLibsql } from 'drizzle-orm/libsql'
import postgres from 'postgres'
import { drizzle as drizzlePostgres } from 'drizzle-orm/postgres-js'
import * as schema from './schema.js'

const provider = process.env.DATABASE_PROVIDER || 'sqlite'

if (!['sqlite', 'postgres'].includes(provider)) {
  throw new Error('DATABASE_PROVIDER must be either sqlite or postgres.')
}

export const databaseProvider = provider
export let client
export let db

export async function closeDatabase() {
  if (!client) return
  if (databaseProvider === 'postgres') {
    await client.end({ timeout: 5 })
    return
  }
  await client.close()
}

if (provider === 'postgres') {
  if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required when DATABASE_PROVIDER=postgres.')
  const postgresClient = postgres(process.env.DATABASE_URL, {
    ssl: process.env.POSTGRES_SSL === 'disable' ? false : 'require',
    max: Number(process.env.POSTGRES_MAX_CONNECTIONS || 5),
  })
  client = postgresClient
  db = drizzlePostgres(postgresClient, { schema })
} else {
  const url = process.env.DATABASE_URL || 'file:local.db'
  const authToken = process.env.DATABASE_AUTH_TOKEN || undefined
  const libsqlClient = createClient({ url, authToken })
  client = libsqlClient
  db = drizzleLibsql(libsqlClient, { schema })
}
