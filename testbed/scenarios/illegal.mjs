// NYR-ItemGuard on a live server: a chest of hacked items (Sharpness 255, unbreakable, +1000 damage, a raised stack
// of totems, bedrock, a command block, a 1000-health spawn egg, a Strength 101 potion, a shulker box hiding bedrock) next to
// legal items, opened by a survival player; then quarantine and restore, a hacked sword dropped by a creative player and
// picked up, a join scan, and creative players left alone. Everything is read back from the players' own clients.

import { command, connectWhenUp, describe, quitAll, sleep, waitFor, gamerule } from '../lib/bots.mjs'

export const fix = 'illegal'
export const jarName = 'NYR-ItemGuard'
export const displayName = 'NYR Item Guard'

const newComponents = (mc) => {
  const [a, b, c = 0] = mc.split('.').map(Number)
  return a >= 26 || (a === 1 && (b > 21 || (b === 21 && c >= 5)))
}

const enchants = (mc, map) => {
  const inner = Object.entries(map).map(([k, v]) => `"minecraft:${k}":${v}`).join(',')
  return newComponents(mc) ? `{${inner}}` : `{levels:{${inner}}}`
}

const atLeast = (mc, version) => {
  const have = mc.split('.').map(Number)
  const want = version.split('.').map(Number)
  for (let i = 0; i < 3; i++) {
    if ((have[i] ?? 0) !== (want[i] ?? 0)) return (have[i] ?? 0) > (want[i] ?? 0)
  }
  return true
}

// The attribute_modifiers syntax changed three times: 1.21 named modifiers by id instead of uuid and name, 1.21.2 dropped
// the "generic." prefix from attribute names, and 1.21.5 made the component a plain list.
const damageModifier = (mc) => {
  if (newComponents(mc)) return '[{type:"minecraft:attack_damage",slot:"mainhand",id:"nyr:hack",amount:1000,operation:"add_value"}]'
  if (atLeast(mc, '1.21.2')) return '{modifiers:[{type:"minecraft:attack_damage",slot:"mainhand",id:"nyr:hack",amount:1000,operation:"add_value"}]}'
  if (atLeast(mc, '1.21')) return '{modifiers:[{type:"minecraft:generic.attack_damage",slot:"mainhand",id:"nyr:hack",amount:1000,operation:"add_value"}]}'
  return '{modifiers:[{type:"minecraft:generic.attack_damage",slot:"mainhand",uuid:[I;1,2,3,4],name:"hack",amount:1000,operation:"add_value"}]}'
}

/** slot -> [item, count, expectation]. mineflayer 4.39.0 cannot read a filled shulker box on 26.x, so it is left out there. */
function chestKit (mc) {
  const kit = [
    [`minecraft:diamond_sword[minecraft:enchantments=${enchants(mc, { sharpness: 255 })}]`, 1, 'sword'],
    ['minecraft:netherite_chestplate[minecraft:unbreakable={}]', 1, 'chestplate'],
    [`minecraft:netherite_sword[minecraft:attribute_modifiers=${damageModifier(mc)}]`, 1, 'damage'],
    ['minecraft:totem_of_undying[minecraft:max_stack_size=64]', 64, 'totems'],
    ['minecraft:bedrock', 64, 'bedrock'],
    ['minecraft:command_block', 1, 'command'],
    ['minecraft:cow_spawn_egg[minecraft:entity_data={id:"minecraft:cow",Health:1000f}]', 1, 'egg'],
    ['minecraft:potion[minecraft:potion_contents={custom_effects:[{id:"minecraft:strength",amplifier:100,duration:6000}]}]', 1, 'potion'],
    ['minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:bedrock",count:32}},{slot:1,item:{id:"minecraft:diamond",count:9}}]]', 1, 'shulker'],
    [`minecraft:diamond_sword[minecraft:enchantments=${enchants(mc, { sharpness: 5, looting: 3 })}]`, 1, 'legal-sword'],
    ['minecraft:ender_pearl', 16, 'legal-pearls'],
    [`minecraft:enchanted_book[minecraft:stored_enchantments=${enchants(mc, { mending: 1 })}]`, 1, 'legal-book']
  ]
  return Number(mc.split('.')[0]) >= 26 ? kit.map((entry) => (entry[2] === 'shulker' ? ['minecraft:shulker_box', 1, 'shulker-plain'] : entry)) : kit
}

const componentOf = (item, type) => (item?.components ?? []).find((c) => c.type === type)
const plain = (line) => String(line ?? '').replace(/§./g, '')

async function openChest (bot, pos) {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
  const block = bot.blockAt(pos)
  const opened = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${bot.username}: the chest did not open (at ${bot.entity.position}, ` +
      `${bot.entity.position.distanceTo(pos.offset(0.5, 0.5, 0.5)).toFixed(1)} from it, block there ${bot.blockAt(pos)?.name}, ` +
      `holding ${bot.heldItem?.name ?? 'nothing'}, mode ${bot.game.gameMode}, vehicle ${bot.vehicle?.name ?? 'none'}, health ${bot.health}, client error ${bot.lastError ?? 'none'})`)), 8_000)
    bot.once('windowOpen', (window) => { clearTimeout(timer); resolve(window) })
  })
  await bot.activateBlock(block)
  const window = await opened
  await sleep(600)
  return window
}

export async function run ({ target, check, say }) {
  const mc = target.mc
  const port = target.port
  const op = await connectWhenUp(port, 'NyrOp')
  let kai = await connectWhenUp(port, 'Kai')
  try {
    await gamerule(op, 'sendCommandFeedback', true)
    await command(op, '/minecraft:gamemode survival Kai')
    await command(op, '/minecraft:gamemode survival NyrOp')
    // Its own ground, away from spawn.
    const groundY = Math.floor(kai.entity.position.y)
    await command(op, `/minecraft:tp Kai -39.5 ${groundY} -39.5`)
    await command(op, `/minecraft:tp NyrOp -39.5 ${groundY} -36.5`)
    await command(op, `/minecraft:fill -48 ${groundY} -48 -30 ${groundY + 3} -30 minecraft:air`)
    await sleep(1500)
    const base = kai.entity.position.floored()
    const chest = base.offset(2, 0, 0)
    await command(op, `/minecraft:setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`)
    const kit = chestKit(mc)
    // /item puts each stack straight into the chest. Folia has no /item, so there a creative op (never checked) gives
    // itself each stack and clicks it into place.
    await command(op, '/minecraft:gamemode creative NyrOp')
    await command(op, `/minecraft:tp NyrOp ${base.x + 0.5} ${base.y} ${base.z - 0.5} -90 20`)
    await command(op, `/minecraft:tp Kai ${base.x + 0.5} ${base.y} ${base.z + 1.5} -90 20`)
    await sleep(1000)
    for (let slot = 0; slot < kit.length; slot++) {
      const [item, count] = kit[slot]
      if (!target.folia) {
        const reply = await command(op, `/minecraft:item replace block ${chest.x} ${chest.y} ${chest.z} container.${slot} with ${item} ${count}`,
          /Replaced|Changed|Could not|Unknown|Expected|Invalid|error|can't|cannot/i)
        if (!/Replaced|Changed/i.test(reply)) say(`kit slot ${slot} was not placed: ${plain(reply)}`)
        continue
      }
      const name = item.replace(/^minecraft:/, '').replace(/[[].*$/, '')
      await command(op, '/minecraft:clear NyrOp')
      await command(op, `/minecraft:give NyrOp ${item} ${count}`)
      const given = await waitFor(() => op.inventory.items().find((i) => i.name === name), 5_000, `NyrOp to get ${name}`).catch(() => null)
      if (!given) {
        say(`kit slot ${slot} (${name}) was not given`)
        continue
      }
      const window = await openChest(op, chest)
      const from = window.slots.findIndex((it, index) => index >= window.inventoryStart && it?.name === name)
      await op.clickWindow(from, 0, 0)
      await sleep(150)
      await op.clickWindow(slot, 0, 0)
      await sleep(250)
      op.closeWindow(window)
      await sleep(250)
    }
    await command(op, '/minecraft:clear NyrOp')
    await command(op, `/minecraft:tp NyrOp ${base.x + 0.5} ${base.y} ${base.z - 2.5}`)
    await command(op, `/minecraft:tp Kai ${base.x + 0.5} ${base.y} ${base.z + 0.5} -90 20`)
    await sleep(1000)

    // 1. Kai opens the chest: every illegal stack is fixed or gone, every legal one untouched.
    const kaiFrom = kai.lines.length
    const opFrom = op.lines.length
    const window = await openChest(kai, chest)
    const slots = kit.map((_, i) => window.slots[i])
    const d = (i) => describe(slots[i], kai)
    check('Sharpness 255 becomes Sharpness 5', d(0)?.enchantments?.sharpness === 5, JSON.stringify(d(0)))
    check('the unbreakable chestplate can break again', slots[1]?.name === 'netherite_chestplate' && !componentOf(slots[1], 'unbreakable'), JSON.stringify(slots[1]?.components))
    const modifiers = JSON.stringify(componentOf(slots[2], 'attribute_modifiers')?.data ?? {})
    check('+1000 attack damage is stripped and the sword keeps its normal damage', slots[2]?.name === 'netherite_sword' && !/"(amount|value)":1000\b/.test(modifiers) &&
      (modifiers === '{}' || /"(amount|value)":7\b/.test(modifiers)), modifiers)
    check('64 totems in one stack become 1', slots[3]?.name === 'totem_of_undying' && slots[3]?.count === 1 && !componentOf(slots[3], 'max_stack_size'), JSON.stringify(d(3)))
    check('bedrock is taken', !slots[4], JSON.stringify(d(4)))
    check('the command block is taken', !slots[5], JSON.stringify(d(5)))
    check('the 1000-health spawn egg is taken', !slots[6], JSON.stringify(d(6)))
    const potionEffects = JSON.stringify(componentOf(slots[7], 'potion_contents')?.data ?? {})
    check('the Strength 101 potion loses its custom effect', slots[7]?.name === 'potion' && !/strength|customEffects":\[\{/i.test(potionEffects), potionEffects)
    // Items inside a container component arrive as numeric ids; name them through the client's registry.
    if (kit[8][2] === 'shulker') {
      const inside = (componentOf(slots[8], 'container')?.data?.contents ?? [])
        .filter((entry) => entry.itemCount > 0).map((entry) => `${entry.itemCount} ${kai.registry.items[entry.itemId]?.name ?? entry.itemId}`)
      check('the shulker box keeps its diamonds and loses its bedrock', slots[8]?.name === 'shulker_box' && inside.length === 1 && inside[0] === '9 diamond', inside.join(', '))
    }
    check('a Sharpness V Looting III sword is untouched', d(9)?.enchantments?.sharpness === 5 && d(9)?.enchantments?.looting === 3, JSON.stringify(d(9)))
    check('16 ender pearls are untouched', slots[10]?.name === 'ender_pearl' && slots[10]?.count === 16, JSON.stringify(d(10)))
    check('a Mending book is untouched', d(11)?.enchantments?.mending === 1, JSON.stringify(d(11)))
    kai.closeWindow(window)
    await sleep(800)
    const told = kai.lines.slice(kaiFrom).map(plain)
    check('Kai is told what happened', told.some((l) => /cannot exist in survival/.test(l)), told.join(' | '))
    const alerts = op.lines.slice(opFrom).map(plain)
    check('staff get one alert naming Kai and the quarantine', alerts.some((l) => /Kai: \d+ fixed, \d+ removed \(chest at /.test(l) && /quarantine/.test(l)), alerts.join(' | '))

    // 2. Quarantine: the list shows the taken stacks; restore gives bedrock back, marked so it stays.
    const header = await command(op, '/itemguard quarantine', /Quarantine: /)
    const count = Number(/(\d+) stack\(s\)/.exec(plain(header))?.[1] ?? 0)
    const expected = kit[8][2] === 'shulker' ? 5 : 4
    check(`quarantine holds the ${expected} stacks taken (63 totems, bedrock, command block, egg${expected === 5 ? ', shulker bedrock' : ''})`, count === expected, plain(header))
    await sleep(500)
    const bedrockLine = op.lines.map(plain).reverse().find((l) => /64 bedrock/.test(l))
    const id = bedrockLine?.trim().split(' ')[0]
    check('the 64 bedrock has a quarantine id', /^\d{12}-[0-9a-z]+$/.test(id ?? ''), bedrockLine)
    const restored = await command(op, `/itemguard restore ${id}`, /Gave you|No quarantined|already/)
    check('/itemguard restore gives the stack back', /Gave you 64 bedrock/.test(plain(restored)), plain(restored))
    await sleep(1000)
    const bedrockBack = () => op.inventory.items().filter((i) => i.name === 'bedrock').reduce((n, i) => n + i.count, 0)
    const scanned = await command(op, '/itemguard scan NyrOp', /Scanned NyrOp/)
    await sleep(800)
    check('restored bedrock is marked reviewed and survives a scan', bedrockBack() === 64 && /0 stack\(s\) changed/.test(plain(scanned)), `${bedrockBack()} bedrock; ${plain(scanned)}`)
    const again = await command(op, `/itemguard restore ${id}`, /Gave you|No quarantined|already/)
    check('an item cannot be given back twice', /already given back/.test(plain(again)), plain(again))

    // 3. A hacked sword dropped by a creative player is fixed when a survival player picks it up.
    await command(op, '/minecraft:gamemode creative NyrOp')
    await command(op, `/minecraft:give NyrOp minecraft:golden_sword[minecraft:enchantments=${enchants(mc, { sharpness: 200 })}] 1`)
    await sleep(800)
    await command(op, `/minecraft:tp NyrOp ${base.x + 0.5} ${base.y} ${base.z + 1.5} 180 0`)
    await sleep(600)
    const golden = await waitFor(() => op.inventory.items().find((i) => i.name === 'golden_sword'), 5_000, 'the golden sword')
    await op.tossStack(golden)
    // Wherever the throw landed, bring Kai to it once it rests, so the pickup is the one being tested.
    const dropped = await waitFor(() => Object.values(kai.entities).find((e) => e.name === 'item' && e.getDroppedItem?.()?.name === 'golden_sword' &&
      e.position.distanceTo(kai.entity.position) < 12), 6_000, 'the thrown sword').catch(() => null)
    await sleep(1500)
    if (dropped) await command(op, `/minecraft:tp Kai ${dropped.position.x.toFixed(2)} ${Math.floor(dropped.position.y)} ${dropped.position.z.toFixed(2)}`)
    const picked = await waitFor(() => kai.inventory.items().find((i) => i.name === 'golden_sword'), 10_000, 'Kai to pick up the sword').catch(() => null)
    check('the dropped Sharpness 200 sword reaches Kai as Sharpness 5', describe(picked, kai)?.enchantments?.sharpness === 5, JSON.stringify(describe(picked, kai)))

    // 4. Creative players are left alone: NyrOp keeps a barrier.
    await command(op, '/minecraft:give NyrOp minecraft:barrier 3')
    await sleep(600)
    await command(op, '/itemguard scan NyrOp', /Scanned NyrOp/)
    await sleep(600)
    check('a creative player keeps a barrier', op.inventory.items().some((i) => i.name === 'barrier'), op.inventory.items().map((i) => i.name).join(','))

    // 5. /itemguard inspect explains an item without changing it.
    await command(op, `/minecraft:give NyrOp minecraft:diamond_axe[minecraft:enchantments=${enchants(mc, { sharpness: 9 })},minecraft:unbreakable={}] 1`)
    await sleep(600)
    const axe = await waitFor(() => op.inventory.items().find((i) => i.name === 'diamond_axe'), 5_000, 'the axe')
    await op.equip(axe, 'hand')
    await sleep(300)
    const inspectFrom = op.lines.length
    await command(op, '/itemguard inspect', /over-enchanted|Nothing illegal/)
    await sleep(500)
    const inspected = op.lines.slice(inspectFrom).map(plain)
    check('/itemguard inspect lists over-enchanted and unbreakable', inspected.some((l) => /over-enchanted .*sharpness 9 is over its maximum of 5/.test(l)) &&
      inspected.some((l) => /unbreakable/.test(l)), inspected.join(' | '))
    check('inspect changes nothing', describe(op.inventory.items().find((i) => i.name === 'diamond_axe'), op)?.enchantments?.sharpness === 9, '')

    // 6. A join scan: bedrock given to Kai is gone when Kai comes back.
    await command(op, '/minecraft:give Kai minecraft:bedrock 10')
    await sleep(500)
    kai.quit()
    await sleep(1500)
    kai = await connectWhenUp(port, 'Kai')
    const joined = await waitFor(() => kai.lines.map(plain).find((l) => /cannot exist in survival/.test(l)), 10_000, 'the join scan message').catch(() => null)
    await sleep(500)
    check('bedrock in Kai\'s inventory is taken when Kai joins', joined !== null && !kai.inventory.items().some((i) => i.name === 'bedrock'),
      `${joined}; items ${kai.inventory.items().map((i) => i.name).join(',')}`)

    const status = await command(op, '/itemguard status', /Since start/)
    check('/itemguard status counts fixed and removed stacks', /fixed [1-9]\d* .*removed [1-9]/.test(plain(status)), plain(status))
  } finally {
    quitAll([op, kai])
    await sleep(500)
  }
}
