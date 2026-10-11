import 'dotenv/config'
import { db } from './db.js'
import * as categoryModel from '../models/categoryModel.js'

const CATEGORY_NAMES = [
  'Accounts&Finance', 'Admin', 'Catering', 'Engineering', 'HR', 'IT', 'Shared',
  'Technical Service', 'Warehouse',
]

for (const name of CATEGORY_NAMES) {
  await categoryModel.createIfMissing(name)
}

console.log(`Seeded ${CATEGORY_NAMES.length} receipt categories.`)
