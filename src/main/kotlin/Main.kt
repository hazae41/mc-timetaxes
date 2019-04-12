package hazae41.minecraft.timetaxes

import hazae41.minecraft.kotlin.bukkit.*
import hazae41.minecraft.kotlin.catch
import net.md_5.bungee.api.ChatColor.LIGHT_PURPLE
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandException
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit.HOURS

const val hourly: Long = 60 * 60
const val daily: Long = hourly * 24
const val weekly: Long = daily * 7
const val monthly: Long = weekly * 4

object Config: ConfigFile("config.yml"){
    val taxes get() = sections.map { Tax(it.name) }
}

class Tax(path: String): ConfigSection(Config, path) {

    var last by long("last", 0)

    val type by string("type", "weekly")

    val _price by string("price", "100")

    val price: Pair<String, Double> get() {
        if (!_price.endsWith("%"))
            return Pair("fixed", _price.toDouble())
        return Pair("percent", _price.dropLast(1).toDouble())
    }

    val timestamp get() = when(type){
        "hourly" -> hourly
        "daily" -> daily
        "weekly" -> weekly
        "monthly" -> monthly
        else -> weekly
    }
}

class Plugin: BukkitPlugin(){

    override fun onEnable() = catch<Exception>(::warning) {
        update(61319, LIGHT_PURPLE)
        Config.init(this)
        load()
        registerCommands()
    }

    fun reload() {
        cancelTasks()
        Config.reload()
        load()
    }

    fun load() {
        eco ?: warning("&cCould not find any economy provider")
        Config.taxes.forEach { it.schedule() }
    }

    fun PluginCommand.requirePerm(sender: CommandSender, perm: String) {
        if(sender.hasPermission("timetaxes.$perm")) return
        sender.msg(permissionMessage)
        throw CommandException()
    }

    fun registerCommands() = command("timetaxes"){
        sender, args -> when(args.getOrNull(0)){
            "reload" -> {
                requirePerm(sender, "reload")
                reload()
                sender.msg("&aConfig reloaded")
            }
            "list" -> {
                requirePerm(sender, "list")
                Config.taxes.forEachIndexed { i, tax ->
                    sender.msg("&b#$i: ${tax.path}")
                }
            }
            "test" -> {
                requirePerm(sender, "test")
                eco ?: return@command sender.msg("&cCould not find any economy provider")
                val id = args.getOrNull(1)?.toIntOrNull()
                ?: return@command sender.msg("/timetaxes test <id>")
                val tax = Config.taxes[id]
                sender.msg("&bTesting ${tax.path}...")
                server.offlinePlayers.forEach { it.withdraw(tax) }
                sender.msg("&bTested ${tax.path}")
            }
            else -> {
                requirePerm(sender, "help")
                sender.msg("&bAvailable subcommands: reload, list, test")
            }
        }
    }

    val eco: Economy? get() =
        server.servicesManager.getRegistration(Economy::class.java)?.provider

    fun OfflinePlayer.withdraw(tax: Tax){
        val eco = eco ?: return
        val (type, value) = tax.price

        val amount = when(type){
            "fixed" -> value
            "percent" -> value * eco.getBalance(this) / 100
            else -> return
        }

        eco.withdrawPlayer(this, amount)

        if (isOnline) {
            player.msg("&cYou gave ${eco.format(amount)} to ${tax.path}")
        }
    }

    fun Tax.schedule() = also{ tax ->

        schedule(
            async = true, period = 1, unit = HOURS
        ) task@ {
            eco ?: return@task
            val last = Instant.ofEpochSecond(tax.last)
            val now = Instant.now()

            val dlast = LocalDateTime.ofInstant(last, ZoneId.systemDefault())
            val dnow = LocalDateTime.ofInstant(now, ZoneId.systemDefault())

            val dnext = dlast.plusSeconds(tax.timestamp)
            if(dnext.isAfter(dnow)) return@task

            tax.last = now.epochSecond

            info("Performing ${tax.path}...")
            server.offlinePlayers.forEach { it.withdraw(tax) }
            info("Performed ${tax.path}")
        }

        info("Loaded ${tax.path}")
    }
}