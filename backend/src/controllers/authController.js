import bcrypt from 'bcrypt'
import * as userModel from '../models/userModel.js'
import { SALT_ROUNDS, INVALID_CREDENTIALS_MESSAGE } from '../constants/auth.js'
import { signToken } from '../middlewares/authMiddleware.js'
import { isNonEmptyString } from '../utils/validationHelpers.js'

export const register = async (req, res) => {
  try {
    const { username, password, displayName } = req.body || {}
    if (!isNonEmptyString(username) || !isNonEmptyString(password) || !isNonEmptyString(displayName)) {
      return res.status(400).json({ error: 'Username, password, and display name are required.' })
    }
    if (password.trim().length < 8) return res.status(400).json({ error: 'Password must be at least 8 characters.' })
    const normalizedUsername = username.trim().toLowerCase()
    if (await userModel.getByUsername(normalizedUsername)) return res.status(409).json({ error: 'Username is already registered.' })
    const passwordHash = await bcrypt.hash(password, SALT_ROUNDS)
    const user = await userModel.createUser({ username: normalizedUsername, displayName: displayName.trim(), passwordHash })
    return res.status(201).json({ message: 'User registered successfully.', data: user })
  } catch (error) {
    console.error(error)
    if (String(error.message).includes('UNIQUE')) return res.status(409).json({ error: 'Username is already registered.' })
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const login = async (req, res) => {
  try {
    const { username, password } = req.body || {}
    if (!isNonEmptyString(username) || !isNonEmptyString(password)) return res.status(400).json({ error: 'Username and password are required.' })
    const user = await userModel.getByUsername(username.trim().toLowerCase())
    if (!user || !(await bcrypt.compare(password, user.passwordHash))) return res.status(401).json({ error: INVALID_CREDENTIALS_MESSAGE })
    const token = signToken({ userId: user.id, username: user.username, role: user.role })
    const { passwordHash, ...safeUser } = user
    return res.status(200).json({ message: 'Login successful.', data: { token, user: safeUser } })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}

export const getCurrentUser = async (req, res) => {
  try {
    const user = await userModel.getById(res.locals.userId)
    if (!user) return res.status(404).json({ error: 'User not found.' })
    return res.status(200).json({ message: 'Current user retrieved.', data: user })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}
