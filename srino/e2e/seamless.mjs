// Seamless server switch e2e for the Srino Velocity fork. All local: Paper a (25894), Paper b (25895), both 1.21.11
// with the same registries; PicoLimbo (25896) with different registries; fork proxy on 25890 with
// seamless-server-switch = true. A mineflayer 1.21.11 bot switches a -> b -> a -> limbo -> a -> b and every
// clientbound packet name is recorded, so "no start_configuration" proves a skipped reconfiguration.
// Run: /home/debian/.local/node22/bin/node /home/debian/scratch/seamless-e2e/e2e.mjs
import path from 'node:path'
import { createRequire } from 'node:module'
import { execFileSync, spawn } from 'node:child_process'
import { Proc } from '/home/debian/dev/SrinoBans/test/e2e/lib/proc.mjs'

const require = createRequire('/home/debian/dev/SrinoBans/test/e2e/package.json')
const mineflayer = require('mineflayer')

const SC = '/home/debian/scratch/seamless-e2e'
const JAVA = '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'
const JAVA_VELOCITY = '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const paper = (x) => new Proc({
  name: `paper-${x}`, cwd: path.join(SC, `paper-${x}`), java: JAVA,
  jvm: ['-Xms384M', '-Xmx640M', '-XX:+UseG1GC', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dpaper.disableChannelLimit=true'],
  args: ['nogui'], memoryMax: '1100M', cpuQuota: '200%', logFile: path.join(SC, `logs/paper-${x}.log`)
})
const pa = paper('a')
const pb = paper('b')
const velocity = new Proc({
  name: 'velocity', cwd: path.join(SC, 'velocity'), java: JAVA_VELOCITY,
  jvm: ['-Xms128M', '-Xmx384M', '-XX:+UseG1GC', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false'],
  args: [], memoryMax: '700M', cpuQuota: '200%', logFile: path.join(SC, 'logs/velocity.log')
})
let limbo = null

const results = []
const check = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' :: ' + detail : ''}`) }

async function cleanup () {
  try { execFileSync('docker', ['rm', '-f', 'seamless-limbo'], { stdio: 'ignore' }) } catch {}
  await Promise.all([velocity.stop('end', 15000), pa.stop('stop', 40000), pb.stop('stop', 40000)].map((p) => p.catch(() => {})))
}
process.on('SIGINT', async () => { await cleanup(); process.exit(130) })

try {
  try { execFileSync('docker', ['rm', '-f', 'seamless-limbo'], { stdio: 'ignore' }) } catch {}
  limbo = spawn('docker', ['run', '--rm', '--name', 'seamless-limbo', '--network', 'host', '--memory', '64m', '-v', `${SC}/limbo:/usr/src/app`,
    'ghcr.io/quozul/picolimbo:latest', 'pico_limbo', '-c', 'limbo.toml', '--skip-banner'], { stdio: 'ignore' })
  pa.start(); pb.start()
  await Promise.all([pa.waitFor(/Done \(/, 180000), pb.waitFor(/Done \(/, 180000)])
  velocity.start()
  await velocity.waitFor(/Done \(/, 60000)
  await sleep(1000)

  const packets = []
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25890, username: 'SeamBot', version: '1.21.11', auth: 'offline' })
  let ended = null
  bot.on('end', (r) => { ended = r ?? 'end' })
  bot.on('kicked', (r) => { ended = 'kicked: ' + JSON.stringify(r) })
  bot.on('error', (e) => { console.log('bot error', e.message) })
  bot._client.on('packet', (data, meta) => packets.push({ t: Date.now(), state: meta.state, name: meta.name, data }))
  await new Promise((resolve, reject) => { bot.once('spawn', resolve); setTimeout(() => reject(new Error('no spawn')), 30000) })
  check('joined a', !ended)

  const after = (i) => packets.slice(i)
  const names = (i) => after(i).map((p) => p.name)
  const waitPacket = async (i, name, timeout = 15000) => {
    const end = Date.now() + timeout
    while (Date.now() < end) { if (after(i).some((p) => p.name === name)) return true; await sleep(100) }
    return false
  }

  async function leftovers (proc) {
    for (const c of ['scoreboard objectives add sbtest dummy', 'scoreboard objectives setdisplay sidebar sbtest', 'scoreboard players set SeamBot sbtest 5',
      'team add tmtest', 'team join tmtest SeamBot', 'bossbar add e2e:bar "E2E"', 'bossbar set e2e:bar players SeamBot']) proc.send(c)
    await sleep(1500)
  }

  async function switchTo (target, expectSeamless, leftoversFrom) {
    const i = packets.length
    const vc = velocity.cursor()
    bot.chat(`/server ${target}`)
    const gotLogin = await waitPacket(i, 'login', 20000)
    await sleep(3000)
    const n = names(i)
    const reconfigured = n.includes('start_configuration')
    check(`${target}: got join game`, gotLogin)
    check(`${target}: ${expectSeamless ? 'no reconfiguration' : 'reconfigured (fallback)'}`, reconfigured === !expectSeamless, `packets: ${[...new Set(n)].slice(0, 25).join(',')}`)
    check(`${target}: still connected`, !ended, ended ?? '')
    const vlog = velocity.since(vc).join('\n')
    check(`${target}: proxy log`, expectSeamless ? /skipped reconfiguration/.test(vlog) : /reconfiguring/.test(vlog), vlog.split('\n').filter((l) => /seamless|ERROR|Exception/.test(l)).join(' | '))
    if (expectSeamless) {
      check(`${target}: respawn after join game`, n.indexOf('respawn') > n.indexOf('login'))
      check(`${target}: position from new server`, after(i).some((p) => p.name === 'position'))
      if (leftoversFrom) {
        const sb = after(i).filter((p) => p.name === 'scoreboard_objective' && p.data.name === 'sbtest')
        const tm = after(i).filter((p) => p.name === 'teams' && p.data.team === 'tmtest')
        const bb = after(i).filter((p) => p.name === 'boss_bar')
        check(`${target}: old objective removed`, sb.some((p) => p.data.action === 1), JSON.stringify(sb.map((p) => p.data.action)))
        check(`${target}: old team removed`, tm.some((p) => p.data.mode === 1 || p.data.mode === 'remove'), JSON.stringify(tm.map((p) => p.data.mode)))
        check(`${target}: old boss bar removed`, bb.some((p) => p.data.action === 1), JSON.stringify(bb.map((p) => p.data.action)))
      }
    }
    if (bot.entity) check(`${target}: bot entity id from new join game`, bot.entity.id === after(i).filter((p) => p.name === 'login').pop()?.data.entityId)
  }

  await leftovers(pa)
  check('a: objective/team/bar arrived', ['scoreboard_objective', 'teams', 'boss_bar'].every((x) => names(0).includes(x)))
  await switchTo('b', true, 'a')
  const listB = (await pb.command('list', 1000)).join(' ')
  check('b: server sees the player', /SeamBot/.test(listB), listB)
  await leftovers(pb) // would kick on a duplicate objective/team if the proxy had not removed a's
  check('b: same names created again, still connected', !ended, ended ?? '')
  bot.chat('hello from b')
  check('b: chat reaches the server', !!(await pb.waitFor(/hello from b/, 5000).catch(() => null)))
  await switchTo('a', true, 'b')
  await switchTo('limbo', false)
  await switchTo('a', false)
  await switchTo('b', true)
  bot.quit()
  await sleep(1000)
  const errs = velocity.lines.map((l) => l.line).filter((l) => /ERROR|Exception/.test(l))
  check('velocity: no errors', errs.length === 0, errs.slice(0, 5).join(' | '))
} catch (e) {
  console.log('ABORT', e.stack)
  results.push({ name: 'abort', ok: false })
} finally {
  await cleanup()
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} passed`)
  process.exit(failed.length ? 1 : 0)
}
