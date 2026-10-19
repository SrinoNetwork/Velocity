// Seamless switch step 2 (no loading screen) e2e for the Srino Velocity fork. All local: SparklySlimePaper
// 26.1.2 with stable player entity ids on a (25894) and b (25895), PicoLimbo (25896) with other registries,
// fork proxy on 25890 with seamless-server-switch + seamless-no-loading-screen. A mineflayer 26.1 bot
// switches a -> b -> a -> limbo -> a -> b. "No login and no respawn packet" proves no loading screen.
// Run: /home/debian/.local/node22/bin/node /home/debian/scratch/seamless2-e2e/e2e.mjs
import path from 'node:path'
import { createRequire } from 'node:module'
import { execFileSync, spawn } from 'node:child_process'
import { Proc } from '/home/debian/dev/SrinoBans/test/e2e/lib/proc.mjs'

const require = createRequire('/home/debian/dev/SrinoBans/test/e2e/package.json')
const mineflayer = require('mineflayer')

const SC = '/home/debian/scratch/seamless2-e2e'
const JAVA = '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const paper = (x) => new Proc({
  name: `paper-${x}`, cwd: path.join(SC, `paper-${x}`), java: JAVA,
  jvm: ['-Xms512M', '-Xmx900M', '-XX:+UseG1GC', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dpaper.disableChannelLimit=true'],
  args: ['nogui'], memoryMax: '1400M', cpuQuota: '200%', logFile: path.join(SC, `logs/paper-${x}.log`)
})
const pa = paper('a')
const pb = paper('b')
const velocity = new Proc({
  name: 'velocity', cwd: path.join(SC, 'velocity'), java: JAVA,
  jvm: ['-Xms128M', '-Xmx384M', '-XX:+UseG1GC', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false'],
  args: [], memoryMax: '700M', cpuQuota: '200%', logFile: path.join(SC, 'logs/velocity.log')
})
let limbo = null

const results = []
const check = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' :: ' + detail : ''}`) }
const stableId = (uuid) => {
  const h = uuid.replace(/-/g, '')
  const msb = BigInt.asIntN(64, BigInt('0x' + h.slice(0, 16)))
  const lsb = BigInt.asIntN(64, BigInt('0x' + h.slice(16)))
  const u = (x) => BigInt.asUintN(64, x)
  const x = msb ^ BigInt.asIntN(64, u(msb) >> 32n) ^ lsb ^ BigInt.asIntN(64, u(lsb) >> 32n)
  return Number(0x40000000n | (BigInt.asIntN(32, x) & 0x3FFFFFFFn))
}

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
  await Promise.all([pa.waitFor(/Done \(/, 240000), pb.waitFor(/Done \(/, 240000)])
  velocity.start()
  await velocity.waitFor(/Done \(/, 60000)
  await sleep(1000)

  const packets = []
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25890, username: 'SeamBot', version: '26.1', auth: 'offline' })
  let ended = null
  bot.on('end', (r) => { ended = r ?? 'end' })
  bot.on('kicked', (r) => { ended = 'kicked: ' + JSON.stringify(r) })
  bot.on('error', (e) => { console.log('bot error', e.message) })
  bot._client.on('packet', (data, meta) => packets.push({ t: Date.now(), state: meta.state, name: meta.name, data }))
  await new Promise((resolve, reject) => { bot.once('spawn', resolve); setTimeout(() => reject(new Error('no spawn')), 60000) })
  check('joined a', !ended)
  const firstId = bot.entity.id
  check('a: stable entity id', firstId === stableId(bot.player.uuid), `${firstId} vs ${stableId(bot.player.uuid)}`)
  for (const p of [pa, pb]) { p.send('op SeamBot'); p.send('gamerule spawn_mobs false') }

  async function walk (label, proc) {
    if (proc) { proc.send('tp SeamBot 0.5 -60 0.5'); await sleep(1000) }
    await sleep(1500)
    const pos0 = bot.entity.position.clone()
    bot.setControlState('forward', true); bot.setControlState('jump', true); await sleep(2000)
    bot.setControlState('forward', false); bot.setControlState('jump', false); await sleep(800)
    const moved = bot.entity.position.distanceTo(pos0)
    check(`${label}: bot can walk`, moved > 1, `${moved.toFixed(2)} blocks from ${pos0.toString()} onGround=${bot.entity.onGround}`)
  }
  const after = (i) => packets.slice(i)
  const names = (i) => after(i).map((p) => p.name)
  const waitPacket = async (i, name, timeout = 15000) => {
    const end = Date.now() + timeout
    while (Date.now() < end) { if (after(i).some((p) => p.name === name)) return true; await sleep(100) }
    return false
  }

  // things the old server leaves on the client: an entity next to the bot, an effect, rain, objective
  async function leftovers (proc, tag) {
    for (const c of [`summon minecraft:armor_stand ~ ~ ~2 {Tags:["${tag}"],NoGravity:1b}`, 'effect give SeamBot minecraft:speed 600 1', 'weather rain',
      'scoreboard objectives add sbtest dummy', 'scoreboard objectives setdisplay sidebar sbtest']) proc.send(`execute as SeamBot at @s run ${c}`)
    await sleep(2000)
  }

  async function switchTo (target, mode) { // mode: 'noscreen' | 'reconfigure' | 'joingame'
    const i = packets.length
    const vc = velocity.cursor()
    const entitiesBefore = Object.values(bot.entities).filter((e) => e !== bot.entity).map((e) => e.id)
    const t0 = Date.now()
    bot.chat(`/server ${target}`)
    const gotPosition = await waitPacket(i, 'position', 30000)
    const tPos = Date.now() - t0
    await sleep(3000)
    const n = names(i)
    check(`${target}: still connected`, !ended, ended ?? '')
    const firstPos = after(i).find((p) => p.name === 'position')?.data
    check(`${target}: position from new server`, gotPosition, `${tPos} ms, ${JSON.stringify(firstPos?.position ?? firstPos)}`)
    const vlog = velocity.since(vc).join('\n')
    const seam = vlog.split('\n').filter((l) => /seamless|ERROR|Exception/.test(l)).join(' | ')
    if (mode === 'noscreen') {
      check(`${target}: no reconfiguration`, !n.includes('start_configuration'))
      check(`${target}: no login packet`, !n.includes('login'))
      check(`${target}: no respawn packet`, !n.includes('respawn'))
      check(`${target}: proxy logged no loading screen`, /no loading screen/.test(vlog), seam)
      check(`${target}: entity id kept`, bot.entity.id === firstId)
      const unloads = after(i).filter((p) => p.name === 'unload_chunk').length
      check(`${target}: old chunks forgotten`, unloads > 0, `${unloads} unload_chunk`)
      check(`${target}: new chunks arrived`, after(i).some((p) => p.name === 'map_chunk'))
      const destroyed = after(i).filter((p) => p.name === 'entity_destroy').flatMap((p) => p.data.entityIds)
      check(`${target}: old entities removed`, entitiesBefore.every((id) => destroyed.includes(id)), `before ${entitiesBefore.length}, destroyed ${destroyed.length}`)
      check(`${target}: old effect removed`, after(i).some((p) => p.name === 'remove_entity_effect' && p.data.entityId === firstId))
      check(`${target}: rain stopped`, after(i).some((p) => p.name === 'game_state_change' && p.data.reason === 2 || p.data?.reason === 'stop_raining'))
      check(`${target}: old objective removed`, after(i).some((p) => p.name === 'scoreboard_objective' && p.data.name === 'sbtest' && p.data.action === 1))
    } else if (mode === 'reconfigure') {
      check(`${target}: reconfigured`, n.includes('start_configuration'), seam)
    } else {
      check(`${target}: got login (loading screen kept)`, n.includes('login') && !n.includes('start_configuration'), seam)
    }
  }

  for (const p of [pa, pb]) p.send('setworldspawn 0 -60 0')
  bot.chat('/tp SeamBot 0 -60 0')
  await walk('a (first join)')
  await leftovers(pa, 'from_a')
  check('a: armor stand, effect, rain arrived', ['spawn_entity', 'entity_effect', 'game_state_change'].every((x) => names(0).includes(x)))
  await switchTo('b', 'noscreen')
  const listB = (await pb.command('list', 1000)).join(' ')
  check('b: server sees the player', /SeamBot/.test(listB), listB)
  await walk('b (no loading screen)', pb)
  const tpB = (await pb.command('data get entity SeamBot Pos', 1500)).join(' ')
  check('b: server follows the movement', /SeamBot has the following entity data/.test(tpB), tpB.slice(0, 160))
  bot.chat('hello from b')
  check('b: chat reaches the server', !!(await pb.waitFor(/hello from b/, 5000).catch(() => null)))
  await leftovers(pb, 'from_b') // a duplicate objective would kick if a's had not been removed
  check('b: same objective created again, still connected', !ended, ended ?? '')
  await switchTo('a', 'noscreen')
  await switchTo('limbo', 'reconfigure')
  await switchTo('a', 'reconfigure')
  await walk('a (after reconfiguration)')
  await switchTo('b', 'noscreen')
  await walk('b (no loading screen, 2nd)', pb)
  bot.quit()
  await sleep(1000)
  const errs = velocity.lines.map((l) => l.line).filter((l) => /ERROR|Exception/.test(l))
  check('velocity: no errors', errs.length === 0, errs.slice(0, 5).join(' | '))
  const perrs = [...pa.lines, ...pb.lines].map((l) => l.line).filter((l) => /SeamBot.*kicked|Exception/.test(l) || (/SeamBot lost connection/.test(l) && !/: Disconnected$/.test(l)))
  check('papers: no kicks or exceptions for the bot', perrs.length === 0, perrs.slice(0, 5).join(' | '))
} catch (e) {
  console.log('ABORT', e.stack)
  results.push({ name: 'abort', ok: false })
} finally {
  await cleanup()
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} passed`)
  process.exit(failed.length ? 1 : 0)
}
