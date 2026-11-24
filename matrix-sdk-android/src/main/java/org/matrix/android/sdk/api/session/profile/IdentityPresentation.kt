/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.util.MatrixJsonParser
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@JsonClass(generateAdapter = true)
data class AnonymizationRule(
        val enabled: Boolean = false,
        /** Whether only [users] are anonymized, rather than everyone except them. */
        val onlyListed: Boolean = false,
        val users: Set<String> = emptySet(),
) {
    fun includes(userId: String): Boolean = enabled && MatrixPatterns.isUserId(userId) && (userId in users) == onlyListed
}

@JsonClass(generateAdapter = true)
data class AnonymizationConfig(
        val rule: AnonymizationRule = AnonymizationRule(),
        /** Rooms with a setting of their own; any other room uses [rule]. */
        val rooms: Map<String, AnonymizationRule> = emptyMap(),
        val naming: String = "WORDS",
        val sharedLabel: String = "",
        val selfLabel: String = "",
        val sharedAvatar: Boolean = false,
) {
    fun ruleFor(roomId: String?): AnonymizationRule = roomId?.let(rooms::get) ?: rule

    fun includes(userId: String, roomId: String? = null): Boolean = ruleFor(roomId).includes(userId)

    fun selfName(): String = selfLabel.ifBlank { IdentityPresentation.defaultLabel(true) }
    fun sharedName(): String = sharedLabel.ifBlank { IdentityPresentation.defaultLabel(false) }

    fun name(userId: String, selfId: String): String = when {
        userId == selfId -> selfName()
        naming == "SHARED" -> sharedName()
        else -> IdentityAliases.name(userId)
    }
}

object IdentityAliases {
    private val names = ConcurrentHashMap<String, String>()

    // About 90,000 pairs, so two people in one room rarely share a name.
    private val adjectives = ("Agile Airy Alpine Amber Ancient Apricot Aqua Arctic Ashen Aurora Autumn Azure Balmy Bamboo Bashful Beige Birch " +
            "Blazing Blissful Blithe Blue Blushing Bold Bouncy Brave Breezy Bright Brisk Bronze Bubbly Calm Candid Caramel " +
            "Carefree Cedar Charming Cheerful Cherry Chestnut Chilly Cinnamon Citrus Clear Clever Cloudy Cobalt Copper Coral " +
            "Cosmic Cozy Creamy Crimson Crisp Crystal Curious Cyan Dapper Daring Dawn Dazzling Deep Desert Dewy Distant Dotted " +
            "Dreamy Dusky Dusty Eager Early Earthy Easy Ebony Elegant Emerald Endless Fair Fancy Feathery Fern Fiery Flint Floral " +
            "Fluffy Foggy Forest Frosty Fuzzy Gallant Garnet Gentle Giddy Gilded Ginger Glad Gleaming Glossy Glowing Golden " +
            "Graceful Grand Granite Grassy Green Happy Hazel Hazy Hearty Heather Herbal Hidden Honest Honey Hopeful Humble Icy " +
            "Indigo Ivory Jade Jasper Jaunty Jazzy Jolly Jovial Joyful Juniper Keen Khaki Kind Lavender Leafy Lemon Light Lilac " +
            "Lime Little Lively Lofty Lucky Lunar Lush Magic Mango Maple Marble Mellow Merry Mighty Mild Minty Misty Modest Molten " +
            "Morning Mossy Muddy Nimble Noble Northern Nutmeg Oaken Ocean Olive Onyx Opal Orange Pale Pastel Patient Peach " +
            "Peaceful Pearl Peppy Perky Pine Pink Plucky Plum Plush Polar Polished Proud Purple Quick Quiet Quirky Radiant Rainy " +
            "Rapid Red River Rosy Royal Ruby Rustic Saffron Sage Salty Sandy Scarlet Serene Shady Shiny Sienna Silent Silky Silver " +
            "Sleepy Smoky Smooth Snappy Snowy Soft Solar Sparkly Speedy Spicy Spotted Spring Starry Steady Still Stormy Striped " +
            "Sturdy Sugary Sunlit Sunny Swift Tawny Teal Tender Thistle Thrifty Tidal Timber Tiny Topaz Tranquil Tropical Trusty " +
            "Turquoise Twilight Upbeat Velvet Violet Vivid Wandering Warm Wavy Whimsical Wild Windy Winter Wise Witty Woolly Zesty " +
            "Zippy Calico Dainty Downy Fleet Frisky Gusty Hushed Starlit Kindly Lazy Lilting Lucid Mirthful Glimmering Nifty " +
            "Dappled Pebbly Prancing Rippling Roaming Sable Shimmering Snug Spry Frosted Tangy Toasty Velvety Wispy Zealous " +
            "Amiable Buoyant Cordial Feisty Friendly Gleeful Moonlit Peachy Sunkissed Sprightly Sweet").split(' ')
    private val nouns = ("Acorn Albatross Alpaca Antelope Armadillo Aspen Avocet Axolotl Badger Basil Bat Bear Beaver Bee Beetle Bird Bison " +
            "Blossom Bluebell Bluebird Boulder Brook Buffalo Bunny Buttercup Butterfly Cactus Camel Canary Cardinal Caribou " +
            "Cascade Cat Catfish Cedar Chameleon Cheetah Chickadee Chipmunk Cicada Cloud Clover Comet Condor Coral Cormorant " +
            "Cougar Coyote Crab Crane Cricket Crow Cypress Daisy Dandelion Deer Dingo Dolphin Dove Dragon Dragonfly Duck Duckling " +
            "Dune Eagle Eel Egret Elk Elm Emu Ermine Falcon Fawn Feather Ferret Fig Finch Firefly Fish Fjord Flamingo Flower Fox " +
            "Frog Gazelle Gecko Gerbil Geyser Gibbon Giraffe Glacier Goose Gopher Gorilla Grasshopper Grove Grouse Gull Guppy " +
            "Hamster Harbor Hare Hawk Hazelnut Hedgehog Heron Hibiscus Hippo Horse Hummingbird Husky Ibex Ibis Iguana Impala Iris " +
            "Jackal Jaguar Jay Jellyfish Kangaroo Kestrel Kingfisher Kitten Kiwi Koala Ladybug Lagoon Lantern Lark Leaf Lemur " +
            "Leopard Lichen Lily Lion Llama Lobster Lotus Lynx Macaw Magpie Mallard Manatee Mantis Maple Marigold Marmot Marten " +
            "Meadow Meerkat Mink Mole Mongoose Monkey Moose Moth Mouse Narwhal Newt Nightingale Oak Ocelot Octopus Orca Orchid " +
            "Oriole Osprey Ostrich Otter Owl Oyster Panda Panther Parrot Pebble Pelican Penguin Peony Petal Pheasant Pigeon Pine " +
            "Platypus Plover Pony Poppy Porcupine Possum Prairie Puffin Quail Quokka Rabbit Raccoon Rainbow Raven Reed Reindeer " +
            "Rhino Ripple Robin Rook Rose Salamander Salmon Sandpiper Sapling Seahorse Seal Sequoia Shark Sheep Shell Shrew " +
            "Skylark Sloth Snail Sparrow Spruce Squirrel Star Starling Stoat Stork Sunflower Swallow Swan Tadpole Tapir Tern " +
            "Thrush Tiger Toad Toucan Trout Tulip Tundra Turtle Valley Vole Walrus Warbler Weasel Whale Wildcat Willow Wolf Wombat " +
            "Woodpecker Wren Yak Zebra Bobcat Bumblebee Butterfish Chinchilla Cockatoo Dormouse Gannet Goldfinch Halibut Heath " +
            "Hornbill Jackdaw Kelp Kookaburra Lapwing Lilypad Loon Marlin Merlin Minnow Moss Muskrat Nuthatch Opossum Orchard " +
            "Pangolin Parakeet Peacock Pinecone Puma Redwood Riverbank Sardine Scallop Seedling Snowdrop Sparrowhawk Wigeon " +
            "Stingray Sunbird Swift Thistle Tortoise Treefrog Wagtail Waxwing Whippet Wildflower Yarrow Zephyr").split(' ')

    private val hex = "0123456789abcdef".toCharArray()

    fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            out[index * 2] = hex[(byte.toInt() shr 4) and 15]
            out[index * 2 + 1] = hex[byte.toInt() and 15]
        }
        return String(out)
    }

    fun name(userId: String): String = names.getOrPut(userId) {
        val hash = digest(userId)
        adjectives[hash.take(8).toLong(16).rem(adjectives.size).toInt()] + " " +
                nouns[hash.substring(8, 16).toLong(16).rem(nouns.size).toInt()]
    }

    /** A stand-in Matrix id: [alias] written as a localpart ("Lazy Dog 2" is `@lazy_dog_2`), else a short fingerprint of [userId]. */
    fun id(userId: String, alias: String? = null): String = "@" + (alias?.let(::localpart) ?: digest(userId).take(8)) + ":" + DOMAIN

    private fun localpart(alias: String): String? =
            alias.lowercase().replace(' ', '_').filter { it in 'a'..'z' || it in '0'..'9' || it in "._=-" }.takeIf { it.isNotEmpty() }

    /** The server name of every stand-in id, for users and for a hidden room alike. */
    const val DOMAIN = "anonymized.local"
}

object IdentityPresentation {
    private val configs = ConcurrentHashMap<String, AnonymizationConfig>()

    /** Per room and name, the people who got that name, in the order they were first shown. */
    private val aliasOwners = ConcurrentHashMap<String, MutableList<String>>()
    @Volatile private var preferences: SharedPreferences? = null
    private var activeSession: String? = null
    @Volatile var activeAccount: String? = null
        private set
    private val mutableChanges = MutableStateFlow(0L)
    val changes: StateFlow<Long> = mutableChanges
    private val adapter by lazy { MatrixJsonParser.getMoshi().adapter(AnonymizationConfig::class.java) }

    /** Localized default for an unset self (true) or shared (false) label; the SDK has no string resources. */
    @Volatile var defaultLabel: (self: Boolean) -> String = { self -> if (self) "Me" else "Anonymous" }

    @Synchronized fun initialize(context: Context, account: String, sessionId: String) {
        preferences = context.applicationContext.getSharedPreferences("voyage_identity", Context.MODE_PRIVATE)
        aliasOwners.clear()
        activeSession = sessionId
        activeAccount = account
        config(account)
        mutableChanges.value++
    }

    @Synchronized fun release(sessionId: String) {
        if (activeSession != sessionId) return
        activeSession = null
        activeAccount = null
        aliasOwners.clear()
        mutableChanges.value++
    }

    fun config(account: String? = activeAccount): AnonymizationConfig {
        account ?: return AnonymizationConfig()
        val stored = preferences ?: return AnonymizationConfig()
        return configs.getOrPut(account) {
            val json = stored.getString(IdentityAliases.digest(account), null)
            runCatching { json?.let { adapter.fromJson(it) } }.getOrNull() ?: AnonymizationConfig()
        }
    }

    @Synchronized fun update(account: String, config: AnonymizationConfig) {
        configs[account] = config
        preferences?.edit()
                ?.putString(IdentityAliases.digest(account), adapter.toJson(config))?.apply()
        DebugLog.i { "IDPDBG update enabled=${config.rule.enabled} onlyListed=${config.rule.onlyListed} users=${config.rule.users.size} rooms=${config.rooms.size}" }
        notifyChanged()
    }

    @Synchronized private fun notifyChanged() {
        ProfileOverrides.presentationChanged()
        mutableChanges.value++
    }

    /** A presentation room id under which everyone shows as themselves, for screens that manage anonymization. */
    const val REVEALED_SCOPE = "!revealed:anonymization.invalid"

    fun isAnonymous(userId: String?, roomId: String? = null, account: String? = activeAccount): Boolean =
            userId != null && account != null && roomId != REVEALED_SCOPE && config(account).includes(userId, roomId)

    fun name(userId: String?, roomId: String? = null, account: String? = activeAccount): String? {
        if (!isAnonymous(userId, roomId, account)) return null
        val settings = config(account)
        val name = settings.name(userId!!, account!!)
        if (settings.naming != "WORDS" || userId == account) return name
        // Only people sharing a room are told apart, by number in the order they appear: the first keeps the
        // bare name, so nobody already shown is renamed when someone else turns up with it.
        val owners = aliasOwners.getOrPut("${roomId.orEmpty()}\u0000$name") { mutableListOf() }
        val index = synchronized(owners) { owners.indexOf(userId).takeIf { it >= 0 } ?: owners.size.also { owners.add(userId) } }
        return if (index == 0) name else "$name ${index + 1}"
    }

    /**
     * How [userId] looks under [config] in [roomId], null when shown as themselves. Comparing it across two configs
     * tells whether anything drawn for that person has to change.
     */
    fun presentation(config: AnonymizationConfig, userId: String, roomId: String?, account: String? = activeAccount): Any? =
            if (account == null || !config.includes(userId, roomId)) null
            else config.name(userId, account) to config.sharedAvatar

    // Generated names tell people apart, numbered within a room, so the id can follow the name; one shared name can't.
    fun displayedId(userId: String, roomId: String? = null, account: String? = activeAccount): String {
        if (!isAnonymous(userId, roomId, account)) return userId
        val alias = name(userId, roomId, account)?.takeIf { config(account).naming == "WORDS" || userId == account }
        return IdentityAliases.id(userId, alias)
    }
}
