import { desc, eq } from 'drizzle-orm'
import { db } from '../db/db.js'
import { users } from '../db/schema.js'

const publicUserColumns = {
  id: users.id,
  username: users.username,
  displayName: users.displayName,
  role: users.role,
  createdAt: users.createdAt,
}

export const getByUsername = async (username, tx = db) => {
  const rows = await tx.select({
    id: users.id,
    username: users.username,
    displayName: users.displayName,
    role: users.role,
    passwordHash: users.passwordHash,
  }).from(users).where(eq(users.username, username)).limit(1)
  return rows[0] || null
}

export const getById = async (id, tx = db) => {
  const rows = await tx.select(publicUserColumns).from(users).where(eq(users.id, id)).limit(1)
  return rows[0] || null
}

export const listUsers = async (tx = db) => tx.select(publicUserColumns).from(users).orderBy(desc(users.createdAt))

export const createUser = async ({ username, displayName, passwordHash, role = 'USER' }, tx = db) => {
  const rows = await tx.insert(users).values({ username, displayName, passwordHash, role }).returning(publicUserColumns)
  return rows[0]
}
