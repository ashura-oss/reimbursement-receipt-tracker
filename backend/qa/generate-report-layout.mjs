import fs from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'

process.env.DATABASE_PROVIDER = 'sqlite'
process.env.DATABASE_URL = 'file:./report-layout-qa.db'
process.env.STORAGE_DIR = './report-layout-qa-storage'

const { reportInternals } = await import('../src/services/reportService.js')

function escapeXml(value) {
  return String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
}

function receiptSvg({ width, height, receiptNumber, itemCount }) {
  const lineGap = Math.max(42, Math.floor((height - 520) / itemCount))
  const lines = Array.from({ length: itemCount }, (_, index) => {
    const y = 440 + index * lineGap
    return `<text x="110" y="${y}" font-size="30" fill="#374151">Item ${String(index + 1).padStart(2, '0')} — Business expense</text><text x="${width - 260}" y="${y}" text-anchor="end" font-size="30" fill="#111827">${(index + 1).toFixed(2)}</text><path d="M 90 ${y + 25} H ${width - 90}" stroke="#d1d5db" stroke-width="2"/>`
  }).join('')
  return Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"><rect width="100%" height="100%" fill="#fff"/><rect x="20" y="20" width="${width - 40}" height="${height - 40}" fill="none" stroke="#1f2937" stroke-width="6"/><text x="90" y="145" font-size="48" font-family="Arial" font-weight="700" fill="#111827">SYNTHETIC RECEIPT</text><path d="M 90 185 H ${width - 90}" stroke="#374151" stroke-width="3"/><text x="90" y="270" font-size="28" font-family="Arial" fill="#374151">Date: 12 Oct 2026</text><text x="420" y="270" font-size="28" font-family="Arial" fill="#374151">Receipt: ${escapeXml(receiptNumber)}</text><text x="90" y="350" font-size="34" font-family="Arial" font-weight="700" fill="#111827">Items</text>${lines}<text x="90" y="${height - 120}" font-size="42" font-family="Arial" font-weight="700" fill="#111827">TOTAL SGD 123.45</text><text x="90" y="${height - 70}" font-size="24" font-family="Arial" fill="#6b7280">Synthetic image for layout QA only</text></svg>`)
}

const portraitBytes = await sharp(receiptSvg({ width: 950, height: 2200, receiptNumber: 'QA-PORTRAIT', itemCount: 16 })).png().toBuffer()
const landscapeBytes = await sharp(receiptSvg({ width: 2200, height: 950, receiptNumber: 'QA-LANDSCAPE', itemCount: 5 })).png().toBuffer()
const rows = [
  { id: 1, description: 'Synthetic portrait receipt', receiptDate: new Date('2026-10-12T00:00:00Z'), currency: 'SGD', amount: 123.45, sgdEquivalent: 123.45 },
  { id: 2, description: 'Synthetic landscape receipt', receiptDate: new Date('2026-10-12T00:00:00Z'), currency: 'USD', amount: 87.65, sgdEquivalent: 118.23 },
]
const receiptImages = new Map([
  [1, { bytes: portraitBytes, type: 'png' }],
  [2, { bytes: landscapeBytes, type: 'png' }],
])
const document = await reportInternals.buildDocx(rows, { title: 'Synthetic report layout QA claim' }, receiptImages)
const outputPath = path.resolve(process.argv[2] || 'work/report-qa/report-layout-qa.docx')
await fs.mkdir(path.dirname(outputPath), { recursive: true })
await fs.writeFile(outputPath, document)
console.log(`Wrote synthetic DOCX layout fixture to ${outputPath}`)
