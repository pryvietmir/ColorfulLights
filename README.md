# Colorful Lights

Colored lighting for Minecraft 1.21.1 (NeoForge). Every light source glows in the color of its own texture: soul
torches light caves cyan, redstone torches red, crying obsidian purple. No configuration or resource pack needed,
and modded light sources are picked up automatically.

## Features

- **Light colors from textures.** Each light-emitting block state gets its color from the bright, saturated pixels
  of its textures (the flame of a torch, not its stick). Per-block overrides in the config.
- **Colored terrain.** Surfaces lit by colored light are tinted in the color of the lights around them, blended by
  distance. Walls still block light, and sky light washes the color out in daylight.
- **Smooth dynamic lights.** Held and dropped light items, burning entities, blazes, fireballs and glow squids emit
  colored light. With Sodium it is computed per pixel every frame: it moves smoothly, spreads as a sphere and stops
  at walls, without rebuilding chunks.
- **Directional lamps.** Headlights and similar lamps of other mods shine as a cone up to 40 blocks along their
  facing, stopped by walls (a shadow map of the cone), also on moving Create contraptions and Aeronautics ships.
  Where a placed lamp's beam hits, the server adds real (invisible) block light. Supported: Create: Bells &
  Whistles headlight. Other mods can add spot lights through `DynamicLights.Collector.addSpot`.
- **Tinted entities.** Mobs, players, items in hand, chests, signs and particles take the color of the light they
  are in.
- **Create.** Lamps on moving contraptions light the world around them, and contraptions are tinted block by block
  by the light they move through.
- **Create Aeronautics (Sable).** Lamps on ships light the world, and ships are lit and tinted by the lights they
  fly past (with extra reach for lights open to the sky).
- **True darkness.** Places without block or sky light are (almost) black whatever the gamma; the moon lights
  the surface a little by its phase, night vision and conduit power cancel it. The Nether (also Big Globe's) has
  no ambient light. Dynamic lights pull things out of the dark.
- **Iris.** Works with shader packs: colored terrain light is kept, and dynamic lights fall back to being baked
  into chunks (they then move block by block).

## Known limitations

- Light colors blend by distance to the light, so a color can reach a surface lit by a different light on the
  other side of a thin wall.
- With a shader pack, dynamic lights and ships' lighting update in steps (baked into meshes) instead of smoothly.
- Block entities drawn by Flywheel (e.g. chests and bells when Create is installed) are not tinted.
- Shader packs on integrated graphics need a lot of memory; together with large modpacks, keep an eye on RAM.

## Requirements

- Minecraft 1.21.1, NeoForge 21.1+
- Install on both the client and the server. The server decides darkness for everyone, and its network channel is
  required: clients without the mod can't join a server with it, and clients with it can't join servers without it.
- Optional: Sodium 0.8+, Iris 1.8+, Create 6.0+, Create Aeronautics / Sable 2.0+

## Configuration

`config/colorful_lights-client.toml`, also editable in game (Mods → Colorful Lights → Config). Changes apply
immediately.

| Option | Default | |
|---|---|---|
| `enabled` | `true` | Master switch |
| `intensity` | `1.0` | How strongly light colors tint surfaces |
| `saturation` | `1.6` | Saturation boost of light colors (hue is kept) |
| `brightness` | `1.2` | Multiplies block light on terrain |
| `skyReduction` | `0.5` | How much sky light washes out the color |
| `dynamicLights` | `true` | Held items, burning entities, contraption and ship lamps emit light |
| `objectLightRange` | `2.0` | Extra reach of lights onto contraptions and ships |
| `tintEntities` | `true` | Tint mobs, items, block entities and particles |
| `textureColors` | `true` | Derive colors from textures |
| `defaultColor` | `#FFFFFF` | Color when a block has no usable texture |
| `colors` | | Overrides, e.g. `"minecraft:torch=#FFB060"` |

Darkness is set by the server in its `config/colorful_lights-server.toml` (for singleplayer, the game's own file)
and sent to players when they join or the file changes. Players can't turn it off; only night vision and conduit
power cancel it, as in vanilla.

| Option (`[darkness]`) | Default | |
|---|---|---|
| `enabled` | `true` | Master switch for darkness |
| `strength` | `1.0` | 0 = vanilla lighting, 1 = light level 0 is pitch black |
| `moonlight` | `0.25` | How much the full moon lights the surface |
| `overworld` / `nether` / `end` / `otherDimensions` | `true` | Darkness per dimension |
| `excludedDimensions` | | Dimension ids without darkness |

## Building

```
gradlew fetchLibs   # downloads the jars the optional integrations compile against into ./libs
gradlew build       # the mod jar ends up in build/libs
```

Requires JDK 21. For testing, `gradlew runClient`, or `gradlew runClientWorld` to join the singleplayer world
"New World" directly; the latter also logs how long each part of the mod takes every 10 seconds. Put Sodium, Iris,
Create or Aeronautics jars into `run/mods` to test with them.

## License

MIT
