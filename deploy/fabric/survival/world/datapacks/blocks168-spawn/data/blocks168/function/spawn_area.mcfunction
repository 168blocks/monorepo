# Runs on every server start and /reload, as the server, positioned at the world spawn.
#
# Keeps the chunks a new (or bed-less respawning) player can be placed in loaded for good (issue #53).
# Vanilla's PlayerSpawnFinder loads each spawn candidate chunk with a short-lived spawn_search ticket
# and then reads it. Under C2ME's chunk system that load can end with the chunk being unloaded again
# (C2ME downgrades it), and the join fails in the prepare_spawn configuration task with "Chunk not
# there when requested: Unloaded chunk". With every candidate chunk force-loaded, finding a spawn
# never has to load a chunk.
#
# Candidates are at most respawn_radius blocks from the world spawn: pinned to 10 here, which the
# 3x3 chunks below always cover. Raise both together.
gamerule respawn_radius 10
forceload add ~-16 ~-16 ~16 ~16
