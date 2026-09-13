package io.github.okexodus.openknights.server.session

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.Entropy
import io.github.okexodus.openknights.server.game.CharacterCreate
import io.github.okexodus.openknights.server.game.FreshProfile
import io.github.okexodus.openknights.server.store.BotsDatabase
import io.github.okexodus.openknights.server.store.DataRoot
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The character factory of release mode (`release_mode.ReleaseFactory`): the first creation bears the world (registry
 * + world + bots + character in one generation directory, committed by one manifest replace); later creations join it.
 */
class ReleaseFactory(
    private val service: Service,
    private val root: DataRoot,
    private val generation: Path,
    private val template: FreshProfile.Template,
) {
    /** Called once, right after the world is born with the first character (the future bot population, plan §5b). */
    private fun onWorldBorn(world: WorldDirectory, seed: String?) = Unit

    fun create(accountId: String, name: String, gender: Int, starter: Long, actor: String): JObj {
        val paths = root.generationPaths(generation.fileName.toString())
        val bornNow = service.world == null
        var seed: String? = null
        val world: WorldDirectory
        if (bornNow) {
            if (Files.exists(paths.getValue("world"))) {        // an earlier attempt of this process stopped after the world file
                world = WorldDirectory(paths.getValue("world"), service.driver, strictPaths = true)
                seed = world.worldSeed()
            } else {
                seed = Entropy.current.tokenHex(16)
                WorldDirectory.initialize(paths.getValue("world"), service.driver, seed)
                world = WorldDirectory(paths.getValue("world"), service.driver, strictPaths = true)
            }
            if (!Files.exists(paths.getValue("bots"))) BotsDatabase.initialize(paths.getValue("bots"), service.driver, seed!!)
        } else {
            world = service.world!!
        }
        val clock = DeviceClock.active!!
        val created = CharacterCreate.createCharacter(service.auth.registry, world, template, accountId, name, gender.toLong(), starter,
            paths.getValue("characters"), service.driver, actor, clock = { clock.s14() })
        if (bornNow) {
            onWorldBorn(world, seed)
            root.commitActive(generation.fileName.toString(), reason = "world born with the first character")
            world.powerOf = service.powerOf
            service.world = world
            service.log.log("world_born", "generation" to generation.fileName.toString(), "contract" to "docs/RELEASE_MODE_CONTRACT.md §7")
        }
        return created
    }
}
