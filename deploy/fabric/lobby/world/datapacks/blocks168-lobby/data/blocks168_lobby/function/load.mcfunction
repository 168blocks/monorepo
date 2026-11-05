# Runs at every lobby start (and /reload). The world is a flat grass layer at y=-64 (server.properties).
#
# Spawn on top of it at 0.5/-63/0.5. Minecraft 26.3 places adventure-mode players at the world spawn
# without loading its chunk first (PlayerSpawnFinder.fixupSpawnHeight): in an unloaded chunk nothing
# collides, so it walks down to the bottom of the world and the player ends up at y=-63 inside the
# terrain (#26). Keeping the chunks around the spawn loaded (64x64 blocks, the walking area of the
# end-to-end tests) avoids that; with the floor at y=-64 even the unloaded case lands on top of it.
setworldspawn 0 -63 0
forceload add -32 -32 31 31
gamerule respawn_radius 0
# the same picture at every visit (stable screenshots in the end-to-end tests)
gamerule advance_time false
gamerule advance_weather false
time set noon
weather clear
gamerule spawn_mobs false
gamerule spawn_monsters false
gamerule spawn_patrols false
gamerule spawn_phantoms false
gamerule spawn_wandering_traders false
