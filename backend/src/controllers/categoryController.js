import * as categoryModel from '../models/categoryModel.js'

export const listCategories = async (req, res) => {
  try {
    const categories = await categoryModel.listActive()
    return res.status(200).json({ message: 'Receipt categories retrieved.', data: categories })
  } catch (error) {
    console.error(error)
    return res.status(500).json({ error: 'Internal Server Error' })
  }
}
