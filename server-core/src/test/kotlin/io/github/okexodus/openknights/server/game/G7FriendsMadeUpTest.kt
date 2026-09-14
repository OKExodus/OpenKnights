package io.github.okexodus.openknights.server.game

import io.github.okexodus.openknights.exact.JArr
import io.github.okexodus.openknights.exact.JObj
import io.github.okexodus.openknights.exact.JValue
import io.github.okexodus.openknights.exact.Json
import io.github.okexodus.openknights.exact.jarr
import io.github.okexodus.openknights.exact.jobj
import io.github.okexodus.openknights.exact.toHexString
import io.github.okexodus.openknights.gamedata.GameTables
import io.github.okexodus.openknights.gamedata.TableSource
import io.github.okexodus.openknights.server.DeviceClock
import io.github.okexodus.openknights.server.game.WorldParticipants.LineupEntry
import io.github.okexodus.openknights.server.game.WorldParticipants.Participant
import io.github.okexodus.openknights.server.store.StateStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger

/**
 * CI checks of the friends slice on made-up tables, a made-up save and made-up participants: each expected value is the
 * reference's output for the same made-up input. The full proof against the recorded saves, worlds and the APK tables
 * is [G7FriendsVectorsTest] (local).
 */
class G7FriendsMadeUpTest {
    private class TextTables(private val files: Map<String, String>) : TableSource {
        override fun names() = files.keys.sorted()
        override fun raw(name: String): ByteArray {
            val file = if (name.endsWith(".csv")) name else "$name.csv"
            return (files[file] ?: throw NoSuchElementException("Missing table: $file")).toByteArray()
        }
    }

    private val inputs = DailyInputs(GameTables(TextTables(mapOf(
        "item.csv" to "101,102,104,105,106,107,110,203,204,205,206,207,209,210,211,212,305\n9201,1,2,0,0,0,0,0,0,0,0,,0,0,0,0,1\n",
        "property.csv" to "101,102\n123,600\n13,300\n700,4\n701,8\n702,12\n",
        "text.csv" to "101,102\n1113,Nice work\n1115,Thumbs up\n",
        "viplv.csv" to "101,102,112\n1,0,0\n2,3,7\n",
        "arena.csv" to "101,102,103,104,105\n1,900,40,9201,2\n4,500,20,0,0\n",
        "title.csv" to "101,104,105\n2,30,0\n3,100,0\n"))))

    private val now = 1_700_050_000L

    @BeforeEach
    fun clock() {
        DeviceClock.active = DeviceClock(null, timeSource = { 0L }, offsetSource = { 3600 })
    }

    @AfterEach
    fun reset() {
        DeviceClock.active = null
    }

    private fun field(id: Int, tag: Int, bits: Long) = jobj("id" to id, "value" to jobj("tag" to tag, "bits" to bits))

    private fun current(): StateStore.Current {
        val state = jobj("role_properties" to jarr(field(0, 5, 9001), jobj("id" to 2, "value" to jobj("tag" to 7, "raw_hex" to "4b6e69676874")),
            field(3, 5, 12), field(6, 7, 50000), field(11, 5, 70), field(12, 5, 25), field(15, 5, 3), field(22, 5, 1), field(27, 5, 3)),
            "items" to jarr(jobj("wire_values" to jarr(11, 9201, 5), "timed_flag" to 0)), "item_capacity_values" to jarr(50, 50, 50),
            "heroes" to JArr(), "formation" to JArr(),
            "subsystems" to jobj("achievements" to jobj("count" to 2, "entries" to jarr(jobj("wire_values" to jarr(26, 1, 4)),
                jobj("wire_values" to jarr(31, 2, 25))))))
        return StateStore.Current(3, "", "", state, ByteArray(0), 0, null, JArr(), emptyList(), JArr(), emptyList(), null, null, null,
            jobj("document" to jobj("created_at_utc" to "2023-11-01T00:00:00.000+00:00")), null, LinkedHashMap<String, JValue?>())
    }

    private fun person(pid: Long, name: ByteArray, level: Long, power: Long) = Participant(pid, "character", name, level,
        power = BigInteger.valueOf(power), leaderTemplate = 501, lineup = listOf(LineupEntry(1, 501, 9)), characterId = "c$pid",
        created = "2023-11-01T00:00:00.000+00:00")

    private val a = person(9001, "Knight".toByteArray(), 12, 700)
    private val b = person(9002, "Rook".toByteArray(), 15, 900)
    private val c = person(9003, "Zoë".toByteArray(), 12, 500)
    private val presence = jobj("9002" to jobj("online" to true, "last_seen" to now - 10), "9003" to jobj("online" to false, "last_seen" to now - 100))

    private fun social() = jobj("friends" to jobj("9001" to jarr(9002)), "requests" to jobj("9001" to jarr(jobj("from" to 9003, "at" to now - 5))),
        "praise" to jobj("9001:9002" to now - 100), "helper" to jobj("9001:9003" to now - 60))

    private fun compact(v: JValue?): String = Json.dumps(v ?: io.github.okexodus.openknights.exact.JNull, itemSeparator = ",", keySeparator = ":")

    private fun frames(packets: List<Frame>) = compact(JArr(packets.mapTo(ArrayList()) { jarr(it.first, it.second.toHexString()) }))

    private fun roleChanges(owned: Owned) = compact(JArr(owned.roleChanges.entries.mapTo(ArrayList()) { (f, v) -> jarr(f, v.first, v.second) }))

    @Test
    fun `recommendations, the player card and the friend list changes`() {
        val people = linkedMapOf(9001L to a, 9002L to b, 9003L to c)
        assertEquals("012b2300005a6fc3ab000c000000ecb35465f501000009000000f401000000000000f000000000000000",
            Friends.recommendPayload(social(), 9001, people, presence, now, 10, 2, 12, 0, inputs).toHexString())
        assertEquals("292300004b6e69676874003c9541650c00000000000000000000000000000000000000000000000000000000000000bc02000000000000010000" +
            "0000f50100001900000003000000", Friends.playerInfoPayload(a, current().state, presence, now, 60).toHexString())
        val added = social().also { Friends.add(it, 9001, 9003, Friends.maxFriends(3, inputs), now) }
        assertEquals("""{"friends":{"9001":[9002,9003]},"requests":{"9001":[],"9003":[{"from":9001,"at":1700050000}]},"praise":{"9001:9002":1700049900},""" +
            """"helper":{"9001:9003":1700049940}}""", compact(added))
        val replied = social().also { it.obj("requests")["9002"] = jarr(jobj("from" to 9003, "at" to now)) }
        assertEquals(0, Friends.reply(replied, 9002, 9003, true, 30, 30))
        assertEquals("""{"friends":{"9001":[9002],"9002":[9003],"9003":[9002]},"requests":{"9001":[{"from":9003,"at":1700049995}],"9002":[]},""" +
            """"praise":{"9001:9002":1700049900},"helper":{"9001:9003":1700049940}}""", compact(replied))
        val removed = social().also { Friends.remove(it, 9001, 9002) }
        assertEquals("""{"friends":{"9001":[],"9002":[]},"requests":{"9001":[{"from":9003,"at":1700049995}]},"praise":{"9001:9002":1700049900},""" +
            """"helper":{"9001:9003":1700049940}}""", compact(removed))
        assertEquals(Friends.ERR_SELF, assertThrows(Acquisition.Rejected::class.java) { Friends.add(social(), 9001, 9001, 30, now) }.code)
        assertEquals(Friends.ERR_NOT_FRIEND, assertThrows(Acquisition.Rejected::class.java) { Friends.remove(social(), 9001, 9003) }.code)
        assertEquals(Friends.ERR_NO_REQUEST, assertThrows(Acquisition.Rejected::class.java) { Friends.reply(social(), 9002, 9001, true, 30, 30) }.code)
    }

    @Test
    fun `praise and its cooldown`() {
        val owned = Owned(current(), inputs)
        val doc = social()
        val praise = Friends.planPraise(doc, 9001, 9002, 2, owned, inputs, now + 600)
        assertEquals(true, praise.praised)
        val empty = "0e" + "00".repeat(97)
        val paid = "0e" + "00".repeat(35) + "28" + "00".repeat(61)
        assertEquals("""[[128,"010b056e000000"],[578,"1a0105000000"],[418,"00$paid"]]""", frames(praise.packets))
        assertEquals("""[[11,70,110]]""", roleChanges(owned))
        assertEquals("1700050600", doc.obj("praise")["9001:9002"].toString())
        val cooldown = Friends.planPraise(social(), 9001, 9002, 2, Owned(current(), inputs), inputs, now + 499)
        assertEquals(false, cooldown.praised)
        assertEquals("""[[418,"01$empty"]]""", frames(cooldown.packets))
    }

    @Test
    fun `praise mail read, a second claim, mail write and chat`() {
        val reward = Acquisition.emptyReward().also { it["friend_point"] = io.github.okexodus.openknights.exact.JInt(10) }
        val mail = jobj("id" to 7, "type" to 4, "sender" to 9002, "sender_name_hex" to "526f6f6b", "title_hex" to "54", "body_hex" to "42",
            "reward" to reward, "at" to now, "state" to 1)
        val read = Mail.planPraiseRead(mail, Owned(current(), inputs), inputs, null)
        assertEquals("""[[128,"010b0550000000"]]""", frames(read.packets))
        assertEquals("""{"profile":"mail_state_v1","claimed":[],"praise_paid":[7]}""", compact(read["mail_state_after"]))
        // the reference pays an opened praise mail again when it is claimed (reported; ported as it is)
        val owned = Owned(current(), inputs)
        val again = Mail.planClaim(mail, owned, inputs, jobj("profile" to "mail_state_v1", "claimed" to jarr(3), "praise_paid" to jarr(7)))
        assertEquals("""[[11,70,80]]""", roleChanges(owned))
        assertEquals("""{"profile":"mail_state_v1","claimed":[3,7],"praise_paid":[7]}""", compact(again["mail_state_after"]))
        assertEquals(listOf(128, 266, 262), again.packets.map { it.first })

        val box = jobj("next_id" to 8, "boxes" to jobj("9001" to jarr(mail)), "blacklist" to jobj("9002" to jarr("4b6e69676874")))
        assertEquals(4, Mail.write(box, a, b, "Hi".toByteArray(), "Body".toByteArray(), inputs, now).first)
        assertEquals(0, Mail.write(box, a, c, "Hello".toByteArray(), "x".toByteArray(), inputs, now).first)
        assertEquals(0, Mail.write(box, a, c, "Hi".toByteArray(), "123456789".toByteArray(), inputs, now).first)
        assertEquals("""{"id":10,"type":1,"sender":9001,"sender_name_hex":"4b6e69676874","title_hex":"4869","body_hex":"6f6b","reward":null,""" +
            """"at":1700050000,"state":0}""", compact(Mail.write(box, a, c, "Hi".toByteArray(), "ok".toByteArray(), inputs, now).second))
        assertEquals(11L, box.long("next_id"))

        val chatDoc = jobj("world" to JArr(), "guild" to JObj(), "private" to JObj())
        val byName = linkedMapOf(Chat.nameKey(a.nameRaw) to a, Chat.nameKey(b.nameRaw) to b, Chat.nameKey(c.nameRaw) to c)
        val (deliveries, line) = Chat.send(chatDoc, Chat.Request(2, "ZOË".toByteArray(), "hey".toByteArray()), a, 0, emptyList(), byName, inputs, now)
        assertEquals(listOf("9003:0200292300004b6e69676874006865790000", "null:02012b2300005a6fc3ab006865790000"),
            deliveries.map { "${it.recipient}:${it.frame.second.toHexString()}" })
        assertEquals("""{"channel":2,"sender":9001,"name_hex":"4b6e69676874","text_hex":"686579","at":1700050000,"target":{"id":9003,""" +
            """"name_hex":"5a6fc3ab"},"gm":false}""", compact(line))
        val (command, stored) = Chat.send(chatDoc, Chat.Request(3, ByteArray(0), "/give x".toByteArray()), a, 0, emptyList(), byName, inputs, now)
        assertEquals(null, stored)
        assertEquals("03000000000053797374656d002f676976653a2061646d696e20636f6d6d616e647320617265206e6f7420617661696c61626c65207965740001",
            command.single().frame.second.toHexString())
        assertEquals(Chat.ERR_TOO_LONG, assertThrows(Acquisition.Rejected::class.java) {
            Chat.send(chatDoc, Chat.Request(1, ByteArray(0), "x".repeat(13).toByteArray()), a, 0, emptyList(), byName, inputs, now)
        }.code)
        assertEquals(102, assertThrows(Acquisition.Rejected::class.java) { Chat.decodeChat(byteArrayOf(1, 0, 0x68, 0x69)) }.code)
    }

    @Test
    fun `the Arena daily reward`() {
        val owned = Owned(current(), inputs)
        val doc = jobj("profile" to "arena_state_v1", "joined_at" to 0, "settled_at" to null, "reward_rank" to 0, "claimed" to 0, "history" to JArr())
        val plan = Arena.planReward(owned, inputs, doc, 2, now, listOf(1L to b))
        assertEquals("""[[578,"1f0241000000"],[68,"010b00000007000000"],[128,"020607d4c60000000000000c0541000000"],[128,"01160502000000"],""" +
            """[452,"0e000000000000000000000000000000840300000000000000000000000000000000000000000000280000000000000001f123000002000000""" +
            "00".repeat(49) + """"],[448,"020000000a0000000a0000000000000000010200000000012a230000526f6f6b000f000000010000008403000000000000f5010000"]]""",
            frames(plan.packets))
        assertEquals("""{"reward_rank":2,"tier":1,"arena_state_after":{"profile":"arena_state_v1","joined_at":0,"settled_at":1699995600,"reward_rank":2,""" +
            """"claimed":1,"history":[]},"evidence_class":"capture_observed_csv_calculation"}""", compact(plan.data))
        assertEquals("""[[6,50000,50900],[12,25,65],[22,1,2]]""", roleChanges(owned))
        val again = plan["arena_state_after"]!!
        assertEquals(Arena.ERROR_CLAIMED, assertThrows(Acquisition.Rejected::class.java) {
            Arena.planReward(Owned(current(), inputs), inputs, again, 2, now, emptyList())
        }.code)
    }
}
