import 'dotenv/config'

process.env.DATABASE_PROVIDER = 'postgres'
await import('./seed.js')
