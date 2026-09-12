// ── De poortwachter van de vertalingen ──────────────────────────────────────
//
// Android klaagt niet over een ontbrekende vertaling: de tekst valt terug op values/ en de app
// draait gewoon door. Bij een Waalse machinist staat er dan één Nederlandse zin tussen alles wat
// wél Frans is, en dat merkt niemand tot hij belt.
//
// Wat hier nagekeken wordt:
//
//   1. vraagt de Kotlin-code alleen sleutels op die in values/strings.xml staan?
//   2. spreekt elke taal alle sleutels van het Nederlands?
//   3. dragen de vertalingen dezelfde plaatshouders — %1$s, %2$d?
//   4. staan er sleutels in values/ die nergens opgevraagd worden?
//
// Wat hier NIET nagekeken wordt: of de Kotlin compileert. Daar is Android Studio voor; deze proef
// draait op een machine zonder Java en zonder SDK, en dat hoort ze niet te verzwijgen.
//
//   node proef-talen.mjs
import fs from 'node:fs'
import path from 'node:path'

const RES = 'app/src/main/res'
const KOTLIN = 'app/src/main/java/be/mv3d/tablet'
const TALEN = ['fr', 'en', 'de', 'es']

/** De sleutels en teksten uit één strings.xml. */
function lees (pad) {
  if (!fs.existsSync(pad)) return null
  const bron = fs.readFileSync(pad, 'utf8')
  const uit = {}
  for (const m of bron.matchAll(/<string name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) uit[m[1]] = m[2]
  return uit
}

/** De plaatshouders in een tekst: %1$s, %2$d. Op volgorde, want %2$d vóór %1$s is ook fout. */
const plaats = (s) => (String(s).match(/%\d+\$[sd]/g) || []).join(',')

const basis = lees(path.join(RES, 'values/strings.xml'))
if (!basis) { console.error('values/strings.xml niet gevonden'); process.exit(1) }
const sleutels = Object.keys(basis)

let problemen = 0
const meld = (r) => { problemen++; console.log('  ' + r) }

console.log('\n── de talen ' + '─'.repeat(52))
for (const taal of TALEN) {
  const w = lees(path.join(RES, 'values-' + taal, 'strings.xml'))
  if (!w) { meld('values-' + taal + '/strings.xml ontbreekt'); continue }
  // app_name blijft met opzet ongeschreven: een merknaam vertaal je niet.
  const teDoen = sleutels.filter(k => k !== 'app_name')
  const mist = teDoen.filter(k => !(k in w))
  const teveel = Object.keys(w).filter(k => !(k in basis))
  const anders = teDoen.filter(k => k in w && plaats(basis[k]) !== plaats(w[k]))

  const staat = (!mist.length && !teveel.length && !anders.length) ? 'volledig'
    : (mist.length + teveel.length + anders.length) + ' te doen'
  console.log('  ' + taal + '  ' + Object.keys(w).length + '/' + teDoen.length + '  ' + staat)
  for (const k of mist) meld('    ' + taal + ' mist: ' + k)
  for (const k of teveel) meld('    ' + taal + ' kent een sleutel die values/ niet heeft: ' + k)
  for (const k of anders) meld('    ' + taal + ' ' + k + ' — plaatshouders verschillen: nl[' + plaats(basis[k]) + '] ' + taal + '[' + plaats(w[k]) + ']')
}

// ── wat de code opvraagt ────────────────────────────────────────────────────
console.log('\n── de code ' + '─'.repeat(53))
const gevraagd = new Set()
for (const naam of fs.readdirSync(KOTLIN).filter(f => f.endsWith('.kt'))) {
  const bron = fs.readFileSync(path.join(KOTLIN, naam), 'utf8')
  for (const m of bron.matchAll(/R\.string\.([a-z0-9_]+)/g)) gevraagd.add(m[1])
}
// Ook wat in het manifest en in de xml staat.
for (const pad of ['app/src/main/AndroidManifest.xml', RES + '/xml']) {
  if (!fs.existsSync(pad)) continue
  const bestanden = fs.statSync(pad).isDirectory()
    ? fs.readdirSync(pad).map(f => path.join(pad, f)) : [pad]
  for (const b of bestanden) {
    for (const m of fs.readFileSync(b, 'utf8').matchAll(/@string\/([a-z0-9_]+)/g)) gevraagd.add(m[1])
  }
}

const onbekend = [...gevraagd].filter(k => !(k in basis)).sort()
const ongebruikt = sleutels.filter(k => !gevraagd.has(k)).sort()

console.log('  ' + gevraagd.size + ' sleutels in de code · ' + sleutels.length + ' in values/')
for (const k of onbekend) meld('    de code vraagt een sleutel die niet bestaat: ' + k)
if (ongebruikt.length) console.log('  ' + ongebruikt.length + ' staan in values/ en worden nergens opgevraagd:\n      ' + ongebruikt.join(' '))

console.log('')
if (problemen) { console.log('  ' + problemen + ' ding(en) te doen.\n'); process.exit(1) }
console.log('  Alle vier de talen zijn volledig, en de code vraagt niets wat er niet in staat.')
console.log('  (Of de Kotlin compileert, zegt dit niet — dat doet Android Studio.)\n')
