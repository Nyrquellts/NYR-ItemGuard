NYR Item Guard @version@
====================

Hacked, duped and admin-only items are fixed or taken away as soon as they turn up: a Sharpness 255 sword, an
unbreakable chestplate, a +1000 damage sword, 64 totems in one stack, a stack of bedrock, a spawn egg carrying entity
data. Nothing is destroyed for good: every stack taken is kept in quarantine, and staff can give it back.

Items other plugins made are left alone by default, so custom weapons, crate rewards and menu items keep working.


In this download
----------------
  NYR-ItemGuard-@version@.jar
      the plugin
  defaults/config.yml
      the settings the plugin writes on its first start, for reference
  THIRD-PARTY-NOTICES.txt
      the one open-source library inside the jar (FoliaLib, MIT) and its licence


Requirements
------------
  - Java 21 or newer (Minecraft 26 servers need the Java they ship for).
  - A Paper, Purpur, Folia or Spigot server. No other plugin is needed.

  Tested live on Paper 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.11 and 26.1.2, Purpur 1.21.11, Folia
  1.21.11 and Spigot 1.21.11, with a chest of hacked items opened by a survival player, quarantine and restore, a hacked
  item dropped and picked up, and a join scan. On Paper 26.2 it was tested to start and load cleanly.


Installing
----------
  1. Put NYR-ItemGuard-@version@.jar in the plugins folder and start (or restart) the server.
  2. Before changing any check to remove, hold the items your server gives out and run /itemguard inspect.
  3. Adjust plugins/NYR-ItemGuard/config.yml, then run /itemguard reload.


What is checked
---------------
  check                            default   what it does
  unobtainable                     remove    creative-only items: bedrock, barrier, command blocks, structure blocks,
                                             light, debug stick, end portal frame, reinforced deepslate, budding
                                             amethyst, infested blocks and more (unobtainable-items)
  overstacked                      fix       more in one stack than the item allows: a full stack stays, the rest goes
                                             to quarantine
  stack-size-component             fix       a stack size raised above the item's own
  over-enchanted                   fix       levels over the game's maximum are lowered to it (max-levels raises caps)
  enchantment-not-for-this-item    fix       Sharpness on a stick and the like; books are not checked for this
  conflicting-enchantments         fix       Sharpness with Smite and the like; the higher one stays
  unbreakable                      fix       the item can break again
  attribute-modifiers              fix       hand-made modifiers are replaced by the item's normal ones
  custom-potion-effects            fix       effects no brewing stand makes are removed
  entity-data                      remove    spawn eggs and other items that carry entity data

  Each check can be fix, remove, report (alert only) or off. Shulker boxes, bundles and other containers carried as
  items are checked inside too.


Where items are checked
-----------------------
  a player's inventory and ender chest on join and every 60 seconds, any container as it opens, both inventories right
  after a click, items as they are picked up, items dropped into the world, creative-inventory actions of players who
  are checked, and unobtainable blocks as they are placed.


Never touched
-------------
  - items another plugin stored its own data on (exempt.items-with-plugin-data)
  - items with a custom model from a resource pack (exempt.items-with-custom-models)
  - item types, names or lore you list under exempt
  - players with nyritemguard.bypass, players in creative mode (ignore-creative-players), and disabled worlds
  - stacks staff gave back from quarantine: they carry a mark signed with a key only your server holds
    (plugins/NYR-ItemGuard/review-key), so a modified client cannot put it on items of its own


Commands
--------
  /itemguard inspect                why the item in your hand is or is not illegal; changes nothing
  /itemguard scan <player>          check an online player's inventory and ender chest now
  /itemguard quarantine [page]      the newest stacks taken, with who had them, where and why
  /itemguard restore <id>           give yourself a quarantined stack back (once)
  /itemguard status                 checks, what was fixed and removed since start
  /itemguard reload                 reload config.yml
  Alias: /nyritemguard


Permissions
-----------
  nyritemguard.admin       every /itemguard command                           default: op
  nyritemguard.alerts      staff alerts when an illegal item is found         default: op
  nyritemguard.bypass      this player's items are never checked              default: nobody


Staff alerts and records
------------------------
  Staff with nyritemguard.alerts are told whose item changed, what was wrong with it, where, and the quarantine ids.
  The same line goes to the console and to plugins/NYR-ItemGuard/alerts.log. Quarantined stacks are one file
  each in plugins/NYR-ItemGuard/quarantine.


Coming from NYR Illegal Items
-----------------------------
  NYR Item Guard is the same plugin under a new name. On its first start it moves plugins/NYR-IllegalItems to
  plugins/NYR-ItemGuard, so your config, quarantine, review key and alerts log carry over. Remove the old jar, grant
  nyritemguard.* wherever you granted nyrillegalitems.*, and use /itemguard in place of /illegalitems.
