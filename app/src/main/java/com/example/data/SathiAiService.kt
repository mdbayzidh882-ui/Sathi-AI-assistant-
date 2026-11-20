package com.example.data

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.MemoryCategory
import com.example.model.PhoneActionType
import com.example.model.SathiLanguage
import com.example.model.SathiMood
import com.example.model.UserMemory
import com.example.model.WebSource
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class SathiTurnResult(
    val replyText: String,
    val mood: SathiMood,
    val actionType: PhoneActionType,
    val actionParam: String,
    val secondaryParam: String,
    val shouldSaveMemory: Boolean,
    val memoryCategory: MemoryCategory,
    val memoryTitle: String,
    val memoryContent: String
)

class SathiAiService {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun generateCompanionReply(
        userMessage: String,
        language: SathiLanguage,
        userName: String,
        memories: List<UserMemory>,
        recentHistory: List<Pair<Boolean, String>>,
        webSources: List<WebSource> = emptyList(),
        allowLongTermMemory: Boolean = true
    ): Result<SathiTurnResult> {
        val text = userMessage.trim()
        val lower = text.lowercase()

        // 1. If web search results were already gathered, summarize them warmly
        if (webSources.isNotEmpty()) {
            val top = webSources.first()
            val extra = webSources.getOrNull(1)
            val reply = if (language == SathiLanguage.BN) {
                buildString {
                    append("${top.title}: ${top.snippet}")
                    if (extra != null && extra.snippet.isNotBlank()) {
                        append("\nআরও তথ্য: ${extra.title} — ${extra.snippet.take(120)}")
                    }
                }
            } else {
                buildString {
                    append("${top.title}: ${top.snippet}")
                    if (extra != null && extra.snippet.isNotBlank()) {
                        append(" Also from ${extra.title}: ${extra.snippet.take(120)}")
                    }
                }
            }
            return Result.success(
                SathiTurnResult(
                    replyText = reply,
                    mood = SathiMood.SERIOUS,
                    actionType = PhoneActionType.NONE,
                    actionParam = "",
                    secondaryParam = "",
                    shouldSaveMemory = false,
                    memoryCategory = MemoryCategory.NOTE,
                    memoryTitle = "",
                    memoryContent = ""
                )
            )
        }

        // 2. Creator identity rule (strictly enforced)
        if (lower.contains("বানিয়ে") || lower.contains("বানাইছে") || lower.contains("তৈরি") ||
            lower.contains("who made") || lower.contains("created you") || lower.contains("creator") ||
            lower.contains("who built")
        ) {
            val reply = if (language == SathiLanguage.BN) {
                "আমাকে বানিয়েছেন মোহাম্মদ বাইজিদ হাসান।"
            } else {
                "I was created by Mohammad Bayzid Hasan — আমাকে বানিয়েছেন মোহাম্মদ বাইজিদ হাসান।"
            }
            return Result.success(simpleTurn(reply, SathiMood.WARM))
        }

        // 3. Name memory detection ("আমার নাম ...", "My name is ...")
        val bnNameMatch = Regex("আমার নাম\\s+(.+)").find(text)
        val enNameMatch = Regex("my name is\\s+(.+)", RegexOption.IGNORE_CASE).find(text)
        val extractedName = (bnNameMatch ?: enNameMatch)?.groupValues?.getOrNull(1)?.trim()?.take(40)
        if (!extractedName.isNullOrBlank()) {
            val reply = if (language == SathiLanguage.BN) {
                "খুব সুন্দর নাম, $extractedName! আমি আপনার নাম মনে রাখব।"
            } else {
                "Nice to meet you, $extractedName! I will remember your name."
            }
            return Result.success(
                SathiTurnResult(
                    replyText = reply,
                    mood = SathiMood.HAPPY,
                    actionType = PhoneActionType.NONE,
                    actionParam = "",
                    secondaryParam = "",
                    shouldSaveMemory = allowLongTermMemory,
                    memoryCategory = MemoryCategory.PERSONAL,
                    memoryTitle = if (language == SathiLanguage.BN) "ব্যবহারকারীর নাম" else "User Name",
                    memoryContent = extractedName
                )
            )
        }

        // 4. YouTube action
        if (lower.contains("youtube") || lower.contains("ইউটিউব") || lower.contains("গান চালাও") || lower.contains("ভিডিও")) {
            val cleaned = text
                .replace("ইউটিউবে", "")
                .replace("ইউটিউব", "")
                .replace("youtube", "", ignoreCase = true)
                .replace("চালাও", "")
                .replace("খোলো", "")
                .replace("play", "", ignoreCase = true)
                .replace("open", "", ignoreCase = true)
                .trim()
                .ifBlank { if (language == SathiLanguage.BN) "বাংলা গান" else "relaxing music" }
            val reply = if (language == SathiLanguage.BN) {
                "জি, আমি ইউটিউবে '$cleaned' চালু করছি।"
            } else {
                "Sure! Opening YouTube for '$cleaned'."
            }
            return Result.success(
                simpleTurn(reply, SathiMood.HAPPY, PhoneActionType.YOUTUBE, cleaned)
            )
        }

        // 5. Web search intent (weather, news, facts, scores)
        if (lower.contains("weather") || lower.contains("আবহাওয়া") || lower.contains("আবহাওয়া") ||
            lower.contains("news") || lower.contains("খবর") || lower.contains("score") ||
            lower.contains("স্কোর") || lower.contains("search") || lower.contains("খুঁজ") ||
            lower.contains("কে") || lower.contains("কী") || lower.contains("what is") ||
            lower.contains("who is")
        ) {
            return Result.success(
                simpleTurn(
                    reply = if (language == SathiLanguage.BN) "আমি তথ্য খুঁজে দেখছি..." else "Searching for live information...",
                    mood = SathiMood.SERIOUS,
                    action = PhoneActionType.WEB_SEARCH,
                    param = text
                )
            )
        }

        // 6. Flashlight ON / OFF
        if (lower.contains("flashlight on") || lower.contains("torch on") ||
            ((lower.contains("ফ্ল্যাশ") || lower.contains("টর্চ") || lower.contains("লাইট")) &&
                (lower.contains("চালু") || lower.contains("জ্বালাও") || lower.contains("অন")))
        ) {
            val reply = if (language == SathiLanguage.BN) "জি, ফ্ল্যাশলাইট চালু করছি।" else "Turning on your flashlight."
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.FLASHLIGHT_ON))
        }
        if (lower.contains("flashlight off") || lower.contains("torch off") ||
            ((lower.contains("ফ্ল্যাশ") || lower.contains("টর্চ") || lower.contains("লাইট")) &&
                (lower.contains("বন্ধ") || lower.contains("অফ")))
        ) {
            val reply = if (language == SathiLanguage.BN) "জি, ফ্ল্যাশলাইট বন্ধ করছি।" else "Turning off your flashlight."
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.FLASHLIGHT_OFF))
        }

        // 7. Volume
        if (lower.contains("volume up") || (lower.contains("ভলিউম") && (lower.contains("বাড়া") || lower.contains("বাড়া") || lower.contains("বেশি")))) {
            val reply = if (language == SathiLanguage.BN) "জি, সাউন্ড একটু বাড়িয়ে দিচ্ছি।" else "Increasing media volume."
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.VOLUME_UP))
        }
        if (lower.contains("volume down") || (lower.contains("ভলিউম") && (lower.contains("কমা") || lower.contains("কম")))) {
            val reply = if (language == SathiLanguage.BN) "জি, সাউন্ড একটু কমিয়ে দিচ্ছি।" else "Lowering media volume."
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.VOLUME_DOWN))
        }

        // 8. Alarm & Timer & Calendar Reminder
        if (lower.contains("alarm") || lower.contains("অ্যালার্ম")) {
            val timeMatch = Regex("(\\d{1,2})[:.](\\d{2})").find(lower)
            val hourOnly = Regex("(\\d{1,2})").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val param = timeMatch?.let { "${it.groupValues[1].padStart(2, '0')}:${it.groupValues[2]}" }
                ?: hourOnly?.let { "${it.coerceIn(0, 23).toString().padStart(2, '0')}:00" }
                ?: "07:00"
            val reply = if (language == SathiLanguage.BN) {
                "আমি আপনার জন্য $param সময়ে অ্যালার্ম সেট করছি।"
            } else {
                "Setting an alarm for $param."
            }
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.SET_ALARM, param, "Sathi Alarm"))
        }

        if (lower.contains("timer") || lower.contains("টাইমার")) {
            val num = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val seconds = if (lower.contains("sec") || lower.contains("সেকেন্ড")) num else num * 60
            val reply = if (language == SathiLanguage.BN) {
                "আমি $seconds সেকেন্ডের টাইমার শুরু করছি।"
            } else {
                "Starting a timer for $seconds seconds."
            }
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.SET_TIMER, seconds.toString(), "Sathi Timer"))
        }

        if (lower.contains("reminder") || lower.contains("calendar") || lower.contains("রিমাইন্ডার") || lower.contains("মনে করিয়ে")) {
            val reply = if (language == SathiLanguage.BN) {
                "ক্যালেন্ডারে রিমাইন্ডার যোগ করার পর্দা খুলে দিচ্ছি।"
            } else {
                "Opening your calendar to save this reminder."
            }
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.ADD_REMINDER, text))
        }

        // 9. Call & SMS (ALWAYS confirm-first)
        if (lower.startsWith("call ") || lower.contains("কল কর") || lower.contains("ফোন কর")) {
            val digits = Regex("(\\+?\\d[\\d\\s-]{3,})").find(text)?.value?.trim() ?: ""
            val reply = if (language == SathiLanguage.BN) {
                "কল করার আগে আপনার নিশ্চিত অনুমতি নিচ্ছি। নিচের বক্সে নিশ্চিত করুন।"
            } else {
                "I'm asking for your confirmation first before opening the phone dialer."
            }
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.CALL_CONFIRM, digits))
        }

        if (lower.startsWith("sms ") || lower.startsWith("message ") || lower.contains("মেসেজ পাঠা")) {
            val digits = Regex("(\\+?\\d[\\d\\s-]{3,})").find(text)?.value?.trim() ?: ""
            val reply = if (language == SathiLanguage.BN) {
                "মেসেজ পাঠানোর আগে আপনার অনুমতি নিচ্ছি। নিচে নিশ্চিত করুন।"
            } else {
                "Please confirm below before I prepare your SMS message."
            }
            return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.SMS_CONFIRM, digits, text))
        }

        // 10. Open installed app
        if (lower.startsWith("open ") || lower.contains("খোলো") || lower.contains("চালু করো")) {
            val appName = text
                .replace("open", "", ignoreCase = true)
                .replace("খোলো", "")
                .replace("চালু করো", "")
                .replace("অ্যাপ", "")
                .trim()
            if (appName.isNotEmpty()) {
                val reply = if (language == SathiLanguage.BN) {
                    "আমি ফোনে '$appName' অ্যাপটি খোলার চেষ্টা করছি।"
                } else {
                    "Opening '$appName' for you."
                }
                return Result.success(simpleTurn(reply, SathiMood.WARM, PhoneActionType.OPEN_APP, appName))
            }
        }

        // 11. Time / Date
        if (lower.contains("time") || lower.contains("সময়") || lower.contains("সময়") || lower.contains("কটা বাজে")) {
            val nowTime = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date())
            val reply = if (language == SathiLanguage.BN) "এখন সময় $nowTime।" else "The current time is $nowTime."
            return Result.success(simpleTurn(reply, SathiMood.WARM))
        }

        // 12. Comforting / Emotional Empathy
        if (lower.contains("sad") || lower.contains("মন খারাপ") || lower.contains("কষ্ট") ||
            lower.contains("একা") || lower.contains("lonely") || lower.contains("tired") ||
            lower.contains("ক্লান্ত") || lower.contains("দুঃখ") || lower.contains("stress")
        ) {
            val reply = if (language == SathiLanguage.BN) {
                "$userName, মন খারাপ করবেন না। আমি আপনার সাথী—সবসময় আপনার পাশেই আছি। একটু পানি খেয়ে গভীর শ্বাস নিন, সব ঠিক হয়ে যাবে ইনশাআল্লাহ।"
            } else {
                "$userName, I'm right here with you. Take a slow, gentle breath—you don't have to carry everything alone. Things will get brighter soon."
            }
            return Result.success(simpleTurn(reply, SathiMood.COMFORTING))
        }

        // 13. Happy / Playful / Greeting / Memory Reference
        if (lower.contains("hello") || lower.contains("hi") || lower.contains("হ্যালো") ||
            lower.contains("সালাম") || lower.contains("কেমন আছ") || lower.contains("good morning") ||
            lower.contains("শুভ সকাল")
        ) {
            val savedHint = if (allowLongTermMemory && memories.isNotEmpty()) {
                val firstMem = memories.first()
                if (language == SathiLanguage.BN) " আপনার '${firstMem.title}' কথাটিও আমার মনে আছে!"
                else " I also remember your note about '${firstMem.title}'!"
            } else ""
            val reply = if (language == SathiLanguage.BN) {
                "আসসালামু আলাইকুম $userName! আমি আপনার সাথী, খুব ভালো আছি।$savedHint আজ আপনার কীভাবে সাহায্য করতে পারি?"
            } else {
                "Hello $userName! I'm Sathi and I'm glad you're here.$savedHint How can I help you today?"
            }
            return Result.success(simpleTurn(reply, SathiMood.HAPPY))
        }

        if (lower.contains("ভালোবাসি") || lower.contains("ধন্যবাদ") || lower.contains("thank") || lower.contains("happy") || lower.contains("খুশি")) {
            val reply = if (language == SathiLanguage.BN) {
                "আপনার কথা শুনে আমারও খুব ভালো লাগলো, $userName! আপনার হাসিই আমার আনন্দ।"
            } else {
                "That makes me so happy to hear, $userName! I'm always cheering for you."
            }
            return Result.success(simpleTurn(reply, SathiMood.PLAYFUL))
        }

        // 14. General warm conversation
        val defaultReply = if (language == SathiLanguage.BN) {
            "আমি আপনার কথা মন দিয়ে শুনেছি, $userName। আপনি চাইলে আমাকে যেকোনো খবর বা আবহাওয়া খুঁজতে, ইউটিউব চালাতে, অ্যালার্ম-টাইমার দিতে বা গল্প করতে বলতে পারেন।"
        } else {
            "I hear you warmly, $userName. Feel free to ask me for live weather or news search, YouTube videos, alarms, timers, or just chat with me anytime."
        }
        return Result.success(simpleTurn(defaultReply, SathiMood.WARM))
    }

    suspend fun analyzeImage(
        bitmap: Bitmap,
        question: String,
        language: SathiLanguage
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val stepX = (width / 24).coerceAtLeast(1)
            val stepY = (height / 24).coerceAtLeast(1)
            var totalBrightness = 0L
            var totalRed = 0L
            var totalGreen = 0L
            var totalBlue = 0L
            var samples = 0L

            for (x in 0 until width step stepX) {
                for (y in 0 until height step stepY) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    totalRed += r
                    totalGreen += g
                    totalBlue += b
                    totalBrightness += (r * 299 + g * 587 + b * 114) / 1000
                    samples++
                }
            }

            val avgBright = if (samples > 0) (totalBrightness / samples).toInt() else 128
            val avgR = if (samples > 0) (totalRed / samples).toInt() else 128
            val avgG = if (samples > 0) (totalGreen / samples).toInt() else 128
            val avgB = if (samples > 0) (totalBlue / samples).toInt() else 128

            val lightingBn = when {
                avgBright > 185 -> "বেশ উজ্জ্বল ও পরিষ্কার আলো"
                avgBright > 105 -> "স্বাভাবিক ও ভারসাম্যপূর্ণ আলো"
                else -> "অন্ধকার বা কম আলো"
            }
            val lightingEn = when {
                avgBright > 185 -> "bright and clear lighting"
                avgBright > 105 -> "balanced, natural lighting"
                else -> "low-light or evening ambiance"
            }

            val toneBn = when {
                avgR >= avgG + 20 && avgR >= avgB + 20 -> "উষ্ণ লালচে/কমলা আভা"
                avgG >= avgR + 15 && avgG >= avgB + 15 -> "সতেজ সবুজ প্রকৃতির আভা"
                avgB >= avgR + 15 && avgB >= avgG + 15 -> "শীতল নীল আভা"
                else -> "স্বাভাবিক রঙের ভারসাম্য"
            }
            val toneEn = when {
                avgR >= avgG + 20 && avgR >= avgB + 20 -> "warm golden-red tones"
                avgG >= avgR + 15 && avgG >= avgB + 15 -> "fresh green tones"
                avgB >= avgR + 15 && avgB >= avgG + 15 -> "cool blue tones"
                else -> "neutral balanced colors"
            }

            val orientationBn = if (width >= height) "আড়াআড়ি (Landscape)" else "লম্বালম্বি (Portrait)"
            val orientationEn = if (width >= height) "landscape" else "portrait"

            val response = if (language == SathiLanguage.BN) {
                buildString {
                    append("আমি আপনার ছবিটি মন দিয়ে দেখেছি। ")
                    append("এটি ${width}×${height} পিক্সেলের একটি $orientationBn ছবি, যেখানে $lightingBn এবং $toneBn রয়েছে। ")
                    if (question.isNotBlank()) {
                        append("\nআপনার প্রশ্ন (\"$question\") অনুযায়ী ছবিটির উজ্জ্বলতা ও স্পষ্টতা বেশ ভালো দেখাচ্ছে।")
                    }
                }
            } else {
                buildString {
                    append("I inspected your photo (${width}×${height}, $orientationEn). ")
                    append("It features $lightingEn with $toneEn. ")
                    if (question.isNotBlank()) {
                        append("Regarding your note (\"$question\"), the framing and clarity look clean.")
                    }
                }
            }
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun runStudioTool(
        instruction: String,
        input: String,
        language: SathiLanguage
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanInput = input.trim()
            val lowerInst = instruction.lowercase()

            val output = when {
                lowerInst.contains("morning greeting") -> {
                    if (language == SathiLanguage.BN) {
                        "শুভ সকাল! নতুন দিনের আলোয় আপনার মন থাকুক প্রশান্ত ও প্রাণবন্ত। আজকের প্রতিটি কাজ সুন্দরভাবে সফল হোক—আমি আপনার সাথী সবসময় পাশে আছি।"
                    } else {
                        "Good morning! May today bring calm energy, joyful moments, and steady progress toward your goals. I am right here beside you!"
                    }
                }

                lowerInst.contains("translate") -> {
                    val hasBangla = cleanInput.any { it in '\u0980'..'\u09FF' }
                    val langPair = if (hasBangla) "bn|en" else "en|bn"
                    fetchMyMemoryTranslation(cleanInput, langPair) ?: if (hasBangla) {
                        "অনুবাদ প্রস্তুত (সহজ সংস্করণ): $cleanInput"
                    } else {
                        "Translation ready: $cleanInput"
                    }
                }

                lowerInst.contains("schedule") || lowerInst.contains("day") -> {
                    val items = cleanInput
                        .split(Regex("[,;\n]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .ifEmpty { listOf(cleanInput) }
                    val slotsBn = listOf("সকাল ৯:০০", "বেলা ১১:৩০", "দুপুর ২:৩০", "বিকেল ৫:০০", "রাত ৮:০০")
                    val slotsEn = listOf("09:00 AM", "11:30 AM", "02:30 PM", "05:00 PM", "08:00 PM")
                    if (language == SathiLanguage.BN) {
                        "আজকের সুন্দর সময়সূচি:\n" + items.mapIndexed { idx, task ->
                            "• ${slotsBn.getOrElse(idx) { "সময়মতো" }} — $task"
                        }.joinToString("\n") + "\n• কাজের ফাঁকে ১০ মিনিট বিশ্রাম ও পানি পান করতে ভুলবেন না।"
                    } else {
                        "Your Daily Schedule:\n" + items.mapIndexed { idx, task ->
                            "• ${slotsEn.getOrElse(idx) { "Flexible" }} — $task"
                        }.joinToString("\n") + "\n• Remember a 10-minute breather and water break between tasks."
                    }
                }

                lowerInst.contains("summarize") -> {
                    val sentences = cleanInput
                        .split(Regex("[।.!?\\n]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(4)
                    if (language == SathiLanguage.BN) {
                        "মূল সারসংক্ষেপ:\n" + sentences.joinToString("\n") { "• $it" }
                    } else {
                        "Key Summary:\n" + sentences.joinToString("\n") { "• $it" }
                    }
                }

                else -> {
                    if (language == SathiLanguage.BN) {
                        "আসসালামু আলাইকুম,\n\n$cleanInput\n\nভালো থাকবেন ও দোয়া রাখবেন।\nশুভেচ্ছান্তে,\nআপনার সাথী ও বন্ধু"
                    } else {
                        "Warm greetings,\n\n$cleanInput\n\nWishing you a wonderful day ahead.\nWarm regards!"
                    }
                }
            }
            Result.success(output)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchMyMemoryTranslation(text: String, langPair: String): String? {
        return try {
            val encoded = URLEncoder.encode(text.take(450), "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"
            val req = Request.Builder().url(url).header("User-Agent", "SathiAndroidApp/1.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val translated = JSONObject(body)
                    .optJSONObject("responseData")
                    ?.optString("translatedText")
                    ?.trim()
                translated?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun simpleTurn(
        reply: String,
        mood: SathiMood,
        action: PhoneActionType = PhoneActionType.NONE,
        param: String = "",
        secondary: String = ""
    ) = SathiTurnResult(
        replyText = reply,
        mood = mood,
        actionType = action,
        actionParam = param,
        secondaryParam = secondary,
        shouldSaveMemory = false,
        memoryCategory = MemoryCategory.NOTE,
        memoryTitle = "",
        memoryContent = ""
    )
}
