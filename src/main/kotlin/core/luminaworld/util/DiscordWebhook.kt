package core.luminaworld.util

import core.luminaworld.LuminaCore
import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

class DiscordWebhook(val url: String) {
    private var content: String? = null
    private var username: String? = null
    private var avatarUrl: String? = null
    private var tts = false
    private val embeds = ArrayList<EmbedObject>()

    fun setContent(content: String?) = apply { this.content = content }
    fun setUsername(username: String?) = apply { this.username = username }
    fun setAvatarUrl(avatarUrl: String?) = apply { this.avatarUrl = avatarUrl }
    fun setTts(tts: Boolean) = apply { this.tts = tts }
    fun addEmbed(embed: EmbedObject) = apply { this.embeds.add(embed) }

    /**
     * ส่ง Webhook ไปที่ Discord แบบ Asynchronous (ไม่บล็อก TPS)
     */
    fun sendAsync(plugin: LuminaCore) {
        if (url.isBlank() || url == "https://discord.com/api/webhooks/YOUR_WEBHOOK_URL") {
            return
        }

        val jsonPayload = toJson()

        // ใช้ AsyncScheduler ของ Folia เสมอ
        Bukkit.getAsyncScheduler().runNow(plugin) { _ ->
            try {
                val client = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build()

                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept { response ->
                        if (response.statusCode() !in 200..299) {
                            plugin.logger.warning("Failed to send Discord Webhook. Status: ${response.statusCode()} | Response: ${response.body()}")
                        }
                    }
                    .exceptionally { ex ->
                        plugin.logger.severe("Exception when sending Discord Webhook: ${ex.message}")
                        null
                    }
            } catch (e: Exception) {
                plugin.logger.severe("Error preparing HttpClient: ${e.message}")
            }
        }
    }

    /**
     * ทำการแปลงวัตถุ Webhook ไปเป็น JSON String ด้วย StringBuilder
     */
    fun toJson(): String {
        val builder = StringBuilder()
        builder.append("{")
        var addComma = false

        content?.let {
            builder.append("\"content\":").append(escapeJson(it))
            addComma = true
        }

        username?.let {
            if (addComma) builder.append(",")
            builder.append("\"username\":").append(escapeJson(it))
            addComma = true
        }

        avatarUrl?.let {
            if (addComma) builder.append(",")
            builder.append("\"avatar_url\":").append(escapeJson(it))
            addComma = true
        }

        if (tts) {
            if (addComma) builder.append(",")
            builder.append("\"tts\":true")
            addComma = true
        }

        if (embeds.isNotEmpty()) {
            if (addComma) builder.append(",")
            builder.append("\"embeds\":[")
            for ((index, embed) in embeds.withIndex()) {
                if (index > 0) builder.append(",")
                builder.append(embed.toJson())
            }
            builder.append("]")
        }

        builder.append("}")
        return builder.toString()
    }

    companion object {
        /**
         * แปลง ConfigurationSection จาก Config ไปเป็น DiscordWebhook อิมเมจเดียล
         */
        fun fromConfig(
            webhookUrl: String,
            section: ConfigurationSection?,
            replacer: (String) -> String
        ): DiscordWebhook {
            val webhook = DiscordWebhook(webhookUrl)
            if (section == null) return webhook

            if (section.contains("content")) {
                webhook.setContent(replacer(section.getString("content") ?: ""))
            }

            val embed = EmbedObject()
            var hasEmbedData = false

            if (section.contains("setTitle")) {
                embed.setTitle(replacer(section.getString("setTitle") ?: ""))
                hasEmbedData = true
            }
            if (section.contains("setDescription")) {
                embed.setDescription(replacer(section.getString("setDescription") ?: ""))
                hasEmbedData = true
            }
            if (section.contains("setUrl")) {
                embed.setUrl(replacer(section.getString("setUrl") ?: ""))
                hasEmbedData = true
            }
            if (section.contains("setColor")) {
                embed.setColor(section.getInt("setColor"))
                hasEmbedData = true
            }
            if (section.contains("setThumbnail")) {
                embed.setThumbnail(replacer(section.getString("setThumbnail") ?: ""))
                hasEmbedData = true
            }
            if (section.contains("setImage")) {
                embed.setImage(replacer(section.getString("setImage") ?: ""))
                hasEmbedData = true
            }
            if (section.contains("setTimestamp") && section.getBoolean("setTimestamp")) {
                embed.setTimestamp(Instant.now().toString())
                hasEmbedData = true
            }

            // ตั้งค่า Footer
            if (section.contains("setFooter")) {
                val text = replacer(section.getString("setFooter") ?: "")
                val icon = if (section.contains("setFooterIcon")) replacer(section.getString("setFooterIcon") ?: "") else null
                embed.setFooter(text, icon)
                hasEmbedData = true
            }

            // ตั้งค่า Author
            if (section.contains("setAuthor")) {
                val name = replacer(section.getString("setAuthor") ?: "")
                val url = if (section.contains("setAuthorUrl")) replacer(section.getString("setAuthorUrl") ?: "") else null
                val icon = if (section.contains("setAuthorIcon")) replacer(section.getString("setAuthorIcon") ?: "") else null
                embed.setAuthor(name, url, icon)
                hasEmbedData = true
            }

            // ตั้งค่า Fields
            val fieldsSection = section.getConfigurationSection("fields")
            if (fieldsSection != null) {
                for (key in fieldsSection.getKeys(false)) {
                    val fieldConfig = fieldsSection.getConfigurationSection(key)
                    if (fieldConfig != null) {
                        val name = replacer(fieldConfig.getString("name", "") ?: "")
                        val value = replacer(fieldConfig.getString("value", "") ?: "")
                        val inline = fieldConfig.getBoolean("inline", false)
                        embed.addField(name, value, inline)
                        hasEmbedData = true
                    }
                }
            } else {
                for (i in 1..20) {
                    val fieldKey = "fields$i"
                    if (section.contains(fieldKey)) {
                        val fieldConfig = section.getConfigurationSection(fieldKey)
                        if (fieldConfig != null) {
                            val name = replacer(fieldConfig.getString("name", "") ?: "")
                            val value = replacer(fieldConfig.getString("value", "") ?: "")
                            val inline = fieldConfig.getBoolean("inline", false)
                            embed.addField(name, value, inline)
                            hasEmbedData = true
                        }
                    }
                }
            }

            if (hasEmbedData) {
                webhook.addEmbed(embed)
            }

            return webhook
        }

        fun escapeJson(value: String?): String {
            if (value == null) return "null"
            val builder = StringBuilder()
            builder.append("\"")
            for (c in value) {
                when (c) {
                    '"' -> builder.append("\\\"")
                    '\\' -> builder.append("\\\\")
                    '\b' -> builder.append("\\b")
                    '\u000C' -> builder.append("\\f")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> {
                        if (c < ' ') {
                            val t = "000" + Integer.toHexString(c.code)
                            builder.append("\\u").append(t.substring(t.length - 4))
                        } else {
                            builder.append(c)
                        }
                    }
                }
            }
            builder.append("\"")
            return builder.toString()
        }
    }

    class EmbedObject {
        private var title: String? = null
        private var description: String? = null
        private var url: String? = null
        private var color: Int? = null
        private var timestamp: String? = null
        private var footer: Footer? = null
        private var thumbnail: Thumbnail? = null
        private var image: Image? = null
        private var author: Author? = null
        private val fields = ArrayList<Field>()

        fun setTitle(title: String?) = apply { this.title = title }
        fun setDescription(description: String?) = apply { this.description = description }
        fun setUrl(url: String?) = apply { this.url = url }
        fun setColor(color: Int) = apply { this.color = color }
        fun setTimestamp(timestamp: String?) = apply { this.timestamp = timestamp }

        fun setFooter(text: String, iconUrl: String?) = apply {
            this.footer = Footer(text, iconUrl)
        }

        fun setThumbnail(url: String?) = apply {
            this.thumbnail = Thumbnail(url)
        }

        fun setImage(url: String?) = apply {
            this.image = Image(url)
        }

        fun setAuthor(name: String, url: String?, iconUrl: String?) = apply {
            this.author = Author(name, url, iconUrl)
        }

        fun addField(name: String, value: String, inline: Boolean) = apply {
            this.fields.add(Field(name, value, inline))
        }

        fun toJson(): String {
            val builder = StringBuilder()
            builder.append("{")
            var addComma = false

            title?.let {
                builder.append("\"title\":").append(escapeJson(it))
                addComma = true
            }

            description?.let {
                if (addComma) builder.append(",")
                builder.append("\"description\":").append(escapeJson(it))
                addComma = true
            }

            url?.let {
                if (addComma) builder.append(",")
                builder.append("\"url\":").append(escapeJson(it))
                addComma = true
            }

            color?.let {
                if (addComma) builder.append(",")
                builder.append("\"color\":").append(it)
                addComma = true
            }

            timestamp?.let {
                if (addComma) builder.append(",")
                builder.append("\"timestamp\":").append(escapeJson(it))
                addComma = true
            }

            footer?.let {
                if (addComma) builder.append(",")
                builder.append("\"footer\":").append(it.toJson())
                addComma = true
            }

            thumbnail?.let {
                if (addComma) builder.append(",")
                builder.append("\"thumbnail\":").append(it.toJson())
                addComma = true
            }

            image?.let {
                if (addComma) builder.append(",")
                builder.append("\"image\":").append(it.toJson())
                addComma = true
            }

            author?.let {
                if (addComma) builder.append(",")
                builder.append("\"author\":").append(it.toJson())
                addComma = true
            }

            if (fields.isNotEmpty()) {
                if (addComma) builder.append(",")
                builder.append("\"fields\":[")
                for ((index, field) in fields.withIndex()) {
                    if (index > 0) builder.append(",")
                    builder.append(field.toJson())
                }
                builder.append("]")
            }

            builder.append("}")
            return builder.toString()
        }

        private class Footer(val text: String, val iconUrl: String?) {
            fun toJson(): String {
                val builder = StringBuilder()
                builder.append("{")
                builder.append("\"text\":").append(escapeJson(text))
                iconUrl?.let {
                    builder.append(",\"icon_url\":").append(escapeJson(it))
                }
                builder.append("}")
                return builder.toString()
            }
        }

        private class Thumbnail(val url: String?) {
            fun toJson() = "{\"url\":" + escapeJson(url) + "}"
        }

        private class Image(val url: String?) {
            fun toJson() = "{\"url\":" + escapeJson(url) + "}"
        }

        private class Author(val name: String, val url: String?, val iconUrl: String?) {
            fun toJson(): String {
                val builder = StringBuilder()
                builder.append("{")
                builder.append("\"name\":").append(escapeJson(name))
                url?.let { builder.append(",\"url\":").append(escapeJson(it)) }
                iconUrl?.let { builder.append(",\"icon_url\":").append(escapeJson(it)) }
                builder.append("}")
                return builder.toString()
            }
        }

        private class Field(val name: String, val value: String, val inline: Boolean) {
            fun toJson() = "{" +
                    "\"name\":" + escapeJson(name) + "," +
                    "\"value\":" + escapeJson(value) + "," +
                    "\"inline\":" + inline +
                    "}"
        }
    }
}
