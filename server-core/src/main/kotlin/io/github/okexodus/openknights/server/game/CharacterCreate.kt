package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.PyTime
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.sha256Hex
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.server.Entropy
import io.github.okexodus.openknights.server.store.AccountRegistry
import io.github.okexodus.openknights.server.store.Publish
import io.github.okexodus.openknights.server.store.SaveWriter
import io.github.okexodus.openknights.server.store.SqlDriver
import io.github.okexodus.openknights.server.store.WorldDirectory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creating a fresh character (`character_create.py`): world reservation → generated save → checkpoint → registry →
 * world. An ordered sequence over separate databases, not one transaction: a failure after the reservation marks the
 * world row `abandoned` (its name and id are never reused silently).
 */
object CharacterCreate {
    /** A consistent SQLite copy for the registration checkpoint, published under its name (complete or absent). */
    private fun backup(source: Path, destination: Path, driver: SqlDriver) {
        require(!Files.exists(destination)) { "Checkpoint path already exists" }
        val temporary = Publish.temporaryBeside(destination, "init", ".sqlite3")
        try {
            Files.delete(temporary)
            driver.backup(source, temporary)
            Publish.publishNew(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * `clock` (release mode): the device S14 payload; the creation clock and the rolling Fate roulette window come from
     * it instead of the template's capture.
     */
    fun createCharacter(registry: AccountRegistry, world: WorldDirectory, template: FreshProfile.Template, accountId: String, name: String,
                        gender: Long, starter: Long, charactersDir: Path, driver: SqlDriver, actor: String = "local-client",
                        clock: (() -> ByteArray)? = null): JObj {
        val document = template.document
        val normalized = FreshProfile.normalizeName(name)
        val g = FreshProfile.validateGender(gender)
        val s = FreshProfile.validateStarter(document, starter)
        registry.getAccount(accountId)                      // the owner must exist before anything is reserved
        val folderBase = charactersDir.toAbsolutePath().normalize()
        val reservation = world.reserve(normalized, accountId, s, g, actor)
        var statePath: Path? = null
        try {
            val characterId = "char_" + Entropy.current.uuid4Hex()
            val signatureKey = FreshProfile.pickSignature(document)
            val clockPayload = clock?.invoke()
            var window: Pair<Long, Long>? = null
            val rw = document["roulette_window"] as? JObj
            if (clockPayload != null && rw?.strOrNull("policy") == "rolling") {
                val start = ByteBuffer.wrap(clockPayload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                window = start to minOf(0x7FFFFFFFL, start + rw.long("seconds"))
            }
            val wire = reservation.long("wire_account_id")
            val payload = FreshProfile.buildPlayerInit(document, normalized, g, s, wire, signatureKey, rouletteWindow = window)
            val creationSha = sha256Hex(payload)
            val created = PyTime.nowIsoMillis()
            val profile = FreshProfile.profileDocument(template, characterId, accountId, normalized, g, s, wire, signatureKey, creationSha, created,
                clockPayload?.toHexString())
            val god = FreshProfile.godSkillDocument(document, characterId, s, created, creationSha)
            val folder = folderBase.resolve(characterId)
            statePath = folder.resolve("save.sqlite3")
            val checkpointPath = folder.resolve("checkpoint-0001.sqlite3")
            SaveWriter.initializeFresh(statePath, payload, profile, god, jobj(
                "actor" to actor, "reason" to "Fresh character created through the local character form",
                "contract" to "docs/CHARACTER_CREATE_CONTRACT.md", "character_id" to characterId,
                "starter" to s, "gender" to g, "signature_key" to signatureKey,
                "wire_account_id" to wire, "world_entry_id" to reservation.str("entry_id"),
                "template_sha256" to template.sha256, "evidence_class" to "capture_template_with_labeled_policies"), driver)
            backup(statePath, checkpointPath, driver)
            val registered = registry.registerCharacter(accountId, normalized, statePath, 1, creationSha, creationSha, checkpointPath, actor, characterId)
            world.activate(reservation.str("entry_id"), characterId, statePath, actor)
            return jobj("character_id" to registered.characterId, "name" to normalized, "starter" to s, "gender" to g,
                "wire_account_id" to wire, "signature_key" to signatureKey, "state_path" to statePath.toString(),
                "world_entry_id" to reservation.str("entry_id"))
        } catch (e: Exception) {
            val published = statePath != null && Files.exists(statePath)
            world.abandon(reservation.str("entry_id"), actor, "${e.javaClass.simpleName}; save=${if (published) "published" else "none"}")
            throw e
        }
    }
}
