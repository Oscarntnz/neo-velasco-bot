package oscarntnz.telegram.neovelasco

import clockvapor.markov.MarkovChain
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.webhook
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import io.ktor.http.HttpStatusCode
import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.litote.kmongo.KMongo
import java.io.File
import java.util.Date
import kotlin.random.Random

class MarkovTelegramBot(private val token: String, private val botPort: Int,
                        private val webhookURL: String, private val databaseURL: String,
                        private val databaseName: String, private val replyFrecuence: Int,
                        private val chatFrequence: Int, private val ownerChatId: ChatId) {
    private var myId: Long? = null
    private lateinit var myUsername: String
    private val wantToDeleteOwnData = mutableMapOf<String, MutableSet<String>>()
    private val wantToDeleteUserData = mutableMapOf<String, MutableMap<String, String>>()
    private val wantToDeleteMessageData = mutableMapOf<String, MutableMap<String, String>>()
    private val rand = Random(Date().time)
    private lateinit var client: MongoClient
    private lateinit var database: MongoDatabase
    private lateinit var markovFunctions: MarkovFunctions
    private lateinit var botInstace: Bot

    companion object {
        private const val YES: String = "yes"
        private const val CONFIG_FILE_PATH = "config.json"

        @JvmStatic
        fun main(args: Array<String>) {
            val config = Config.read(CONFIG_FILE_PATH)

            val bot = MarkovTelegramBot(config.telegramBotToken,  System.getenv("PORT").toInt(), config.webhookURL,
            System.getenv("MONGODB_URI"), config.databaseName, config.replyFrecuence, config.chatFrecuence,
            ChatId.fromId(config.ownerChatId))

            try {
                bot.run()
            }
            catch (e: Exception) {
                e.message?.let { bot.notifyException(e.message!!) }
            }
        }
    }

    fun run() {
        client = KMongo.createClient(databaseURL)
        database = client.getDatabase(databaseName)
        markovFunctions = MarkovFunctions(database)

        botInstace = bot {
            this.token = this@MarkovTelegramBot.token
            dispatch {
                addHandler(object : Handler {
                    override fun checkUpdate(update: Update) = update.message != null
                    override fun handleUpdate(bot: Bot, update: Update) = this@MarkovTelegramBot.handleUpdate(update)
                })
            }

            webhook {
                url = "$webhookURL/${this@MarkovTelegramBot.token}"
            }
        }
        botInstace.startWebhook()

        val me = botInstace.getMe()
        val id = me.first?.body()?.result?.id
        val username = me.first?.body()?.result?.username

        if (id == null || username == null) throw Exception("Failed to retrieve bot's username/id")

        myId = id
        myUsername = username
        log("Bot ID = $myId")
        log("Bot username = $myUsername")
        log("Bot started")

        val server = embeddedServer(Netty, port = this@MarkovTelegramBot.botPort) {
            routing {
                post("/${this@MarkovTelegramBot.token}") {
                    val txt: String = call.receiveText()
                    botInstace.processUpdate(txt)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        this.notifyStartup()
        server.start(wait = true)
    }

    private fun handleUpdate(update: Update) {
        if(update.message != null) {
            tryOrLog { handleMessage(update.message!!) }

            log(update.toString())
        }
    }

    private fun handleMessage(message: Message) {
        val chatId = message.chat.id.toString()

        message.newChatMembers?.takeIf { it[0].id == myId!! }?.let { log("Added to group $chatId") }
        message.leftChatMember?.takeIf { it.id == myId!! }?.let {
            log("Removed from group $chatId")

            tryOrLog { markovFunctions.deleteChat(chatId) }
        }

        message.from?.let { handleMessage(message, chatId, it) }
    }

    private fun handleMessage(message: Message, chatId: String, from: User) {
        val senderId = from.id.toString()

        from.username?.let { tryOrLog { markovFunctions.storeUsername(it, senderId) } }

        val text = message.text
        val caption = message.caption

        if (text != null)
            handleMessage(message, chatId, from, senderId, text)
        else if (caption != null)
            handleMessage(message, chatId, from, senderId, caption)
        else if (message.animation != null || message.audio != null || message.photo != null || message.sticker != null)
            respond(message, chatId, from)
    }

    private fun handleMessage(message: Message, chatId: String, from: User, senderId: String, text: String) {
        var shouldAnalyzeMessage = handleQuestionResponse(message, chatId, senderId, text)

        if (shouldAnalyzeMessage) {
            message.entities?.takeIf { it.isNotEmpty() }?.let {
                shouldAnalyzeMessage = handleMessage(message, chatId, from, senderId, text, it)
            }

            if (message.entities.isNullOrEmpty())
                respond(message, chatId, from)
        }

        if (shouldAnalyzeMessage)
            markovFunctions.analyzeMessage(chatId, senderId, text)
    }

    @Suppress("SameParameterValue")
    private fun handleMessage(message: Message, chatId: String, from: User, senderId: String, text: String,
                              entities: List<MessageEntity>): Boolean {
        var shouldAnalyzeMessage = true
        val firstEntity = entities[0]

        if (firstEntity.type == MessageEntity.Type.BOT_COMMAND && firstEntity.offset == 0) {
            shouldAnalyzeMessage = false
            val command = getMessageEntityText(message, firstEntity)

            @Suppress("SameParameterValue")
            when {
                matchesCommand(command, "msg") ->
                    doMessageCommand(message, chatId, text, entities)

                matchesCommand(command, "msgall") ->
                    doMessageTotalCommand(message, chatId, text, entities)

                matchesCommand(command, "stats") ->
                    doStatisticsCommand(message, chatId)

                matchesCommand(command, "deletemydata") ->
                    doDeleteMyDataCommand(message, chatId, senderId)

                matchesCommand(command, "deleteuserdata") ->
                    doDeleteUserDataCommand(message, chatId, from, senderId, entities)

                matchesCommand(command, "deletemessagedata") ->
                    doDeleteMessageDataCommand(message, chatId, senderId)

                matchesCommand(command, "insult") ->
                    sendAndMention(message, "TU PUTA MADRE, AMIC", entities)
            }
        }
        else if (isBotMentioned(message, entities))
            respond(message, chatId, from, true)

        return shouldAnalyzeMessage
    }

    private fun handleQuestionResponse(message: Message, chatId: String, senderId: String,
                                       text: String): Boolean {
        var shouldAnalyzeMessage = true
        val deleteOwnData = wantToDeleteOwnData[chatId]
        val deleteUserData = wantToDeleteUserData[chatId]
        val deleteMessageData = wantToDeleteMessageData[chatId]

        if (deleteOwnData?.contains(senderId) == true) {
            this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)

            shouldAnalyzeMessage = false
            deleteOwnData -= senderId

            if (deleteOwnData.isEmpty())
                wantToDeleteOwnData -= chatId

            val replyText = if (text.trim().lowercase() == YES) {
                if (tryOrDefault(false) { markovFunctions.deleteMarkov(chatId, senderId) })
                    "Okay. I deleted your Markov chain data in this group."
                else
                    "Hmm. I tried to delete your Markov chain data in this group, but something went wrong."
            }
            else
                "Okay. I won't delete your Markov chain data in this group then."

            reply(message, replyText)
        }
        else if (deleteUserData?.contains(senderId) == true) {
            this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)

            shouldAnalyzeMessage = false

            val userIdToDelete = deleteUserData[senderId]!!
            deleteUserData -= senderId

            if (deleteUserData.isEmpty())
                wantToDeleteUserData -= chatId

            val replyText = if (text.trim().lowercase() == YES) {
                if (tryOrDefault(false) { markovFunctions.deleteMarkov(chatId, userIdToDelete) })
                    "Okay. I deleted their Markov chain data in this group."
                else
                    "Hmm. I tried to delete their Markov chain data in this group, but something went wrong."
            }
            else
                "Okay. I won't delete their Markov chain data in this group then."

            reply(message, replyText)
        }
        else if (deleteMessageData?.contains(senderId) == true) {
            this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)

            shouldAnalyzeMessage = false

            val messageToDelete = deleteMessageData[senderId]!!
            deleteMessageData -= senderId

            if (deleteMessageData.isEmpty())
                wantToDeleteMessageData -= chatId

            val replyText = if (text.trim().lowercase() == YES) {
                if (trySuccessful { markovFunctions.deleteMessage(chatId, senderId, messageToDelete) })
                    "Okay. I deleted that message from your Markov chain data in this group."
                else
                    "Hmm. I tried to delete that message from your Markov chain data in this group, but something " +
                            "went wrong."
            }
            else
                "Okay. I won't delete that message from your Markov chain data in this group then."

            reply(message, replyText)
        }

        return shouldAnalyzeMessage
    }

    private fun doMessageCommand(message: Message, chatId: String, text: String,
                                 entities: List<MessageEntity>) {
        this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)
        var parseMode: ParseMode? = null

        val replyText = if (entities.size < 2)
            null
        else {
            val mention = entities[1]

            if (mention.isMention()) {
                val (mentionUserId, e1Text) = getMentionUserId(message, mention)

                val formattedUsername = if (mentionUserId != null && mention.type == MessageEntity.Type.TEXT_MENTION) {
                    parseMode = ParseMode.MARKDOWN

                    createInlineMention(e1Text, mentionUserId)
                }
                else
                    e1Text

                if (mentionUserId == null)
                    null
                else {
                    val remainingTexts = text.substring(mention.offset + mention.length).trim()
                        .takeIf { it.isNotBlank() }?.split(whitespaceRegex).orEmpty()

                    when (remainingTexts.size) {
                        0 -> markovFunctions.generateMessage(chatId, mentionUserId)

                        1 -> markovFunctions.generateMessage(chatId, mentionUserId, remainingTexts.first())
                            ?.let { result ->
                            when (result) {
                                is MarkovChain.GenerateWithSeedResult.NoSuchSeed ->
                                    "<no such seed exists for $formattedUsername>"
                                is MarkovChain.GenerateWithSeedResult.Success ->
                                    result.message.takeIf { it.isNotEmpty() }?.joinToString(" ")
                            }
                        }

                        else -> "<expected only one seed word>"
                    }
                } ?: "<no data available for $formattedUsername>"
            }
            else
                null
        } ?: "<expected a user mention>"

        reply(message, replyText, parseMode)
    }

    private fun doMessageTotalCommand(message: Message, chatId: String, text: String,
                                      entities: List<MessageEntity>) {
        this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)

        val command = entities[0]
        val remainingTexts = text.substring(command.offset + command.length).trim()
            .takeIf { it.isNotBlank() }?.split(whitespaceRegex).orEmpty()

        val replyText = when (remainingTexts.size) {
            0 -> markovFunctions.generateMessageTotal(chatId)

            1 -> markovFunctions.generateMessageTotal(chatId, remainingTexts.first())?.let { result ->
                when (result) {
                    is MarkovChain.GenerateWithSeedResult.NoSuchSeed ->
                        "<no such seed exists>"
                    is MarkovChain.GenerateWithSeedResult.Success ->
                        result.message.takeIf { it.isNotEmpty() }?.joinToString(" ")
                }
            }

            else -> "<expected only one seed word>"
        } ?: "<no data available>"

        reply(message, replyText)
    }

    private fun doStatisticsCommand(message: Message, chatId: String) {
        this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)
        val markovPaths = markovFunctions.getAllPersonalMarkovPaths(chatId)

        val userIdToWordCountsMap = markovPaths
            .mapNotNull { path ->
                tryOrNull { MarkovChain.read(path) }
                    ?.let { Pair(File(path).nameWithoutExtension, it.wordCounts) }
            }
            .toMap()

        val universe = computeUniverse(userIdToWordCountsMap.values)
        val listText = userIdToWordCountsMap.mapNotNull { (userId, wordCounts) ->
            val response = this.botInstace.getChatMember(ChatId.fromId(chatId.toLong()), userId.toLong())
            val chatMember = response.getOrNull()

            if (chatMember != null) {
                val mostDistinguishingWords = scoreMostDistinguishingWords(wordCounts, universe).keys.take(5)
                "${
                    chatMember.user.displayName.takeIf { it.isNotBlank() } 
                        ?: chatMember.user.username?.takeIf { it.isNotBlank() }
                        ?: "User ID: $userId"
                }\n" +
                        mostDistinguishingWords.mapIndexed { i, word -> "${i + 1}. $word" }.joinToString("\n")
            }
            else
                null
        }.filter { it.isNotBlank() }.joinToString("\n\n")

        val replyText = if (listText.isBlank()) "<no data available>" else "Most distinguishing words:\n\n$listText"

        reply(message, replyText)
    }

    private fun doDeleteMyDataCommand(message: Message, chatId: String, senderId: String) {
        val replyText: String
        this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)

        if (isCreator(this.botInstace, ChatId.fromId(chatId.toLong()), senderId.toLong())) {
            wantToDeleteOwnData.getOrPut(chatId) { mutableSetOf() } += senderId

            replyText = "Are you sure you want to delete your Markov chain data in this group? " +
                    "Say \"yes\" to confirm, or anything else to cancel."
        }
        else
            replyText = "NO eres el JEFE, AMIC"

        reply(message, replyText)
    }

    private fun doDeleteUserDataCommand(message: Message, chatId: String, from: User, senderId: String,
                                        entities: List<MessageEntity>) {
        this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)
        var parseMode: ParseMode? = null

        val replyText = if (isCreator(this.botInstace, ChatId.fromId(message.chat.id), from.id)) {
            if (entities.size < 2)
                null
            else {
                val mention = entities[1]

                if (mention.isMention()) {
                    val (mentionUserId, e1Text) = getMentionUserId(message, mention)
                    val formattedUsername = if (mentionUserId != null && mention.type == MessageEntity.Type.TEXT_MENTION) {
                        parseMode = ParseMode.MARKDOWN
                        createInlineMention(e1Text, mentionUserId)
                    }
                    else
                        e1Text

                    if (mentionUserId == null)
                        null
                    else {
                        wantToDeleteUserData.getOrPut(chatId) { mutableMapOf() }[senderId] = mentionUserId
                        "Are you sure you want to delete $formattedUsername's Markov chain data in " +
                                "this group? Say \"yes\" to confirm, or anything else to cancel."
                    } ?: "I don't have any data for $formattedUsername."
                }
                else
                    null
            } ?: "You need to tell me which user's data to delete."
        }
        else
            "NO eres el JEFE, AMIC"

        reply(message, replyText, parseMode)
    }

    private fun doDeleteMessageDataCommand(message: Message, chatId: String, senderId: String) {
        val replyText: String

        this.botInstace.sendChatAction(ChatId.fromId(chatId.toLong()), ChatAction.TYPING)
        if (isCreator(this.botInstace, ChatId.fromId(message.chat.id), senderId.toLong())) {
            replyText = message.replyToMessage?.let { replyToMessage ->
                replyToMessage.from?.takeIf {
                    it.id.toString() == senderId && isCreator(this.botInstace, ChatId.fromId(senderId.toLong()), message.from!!.id)
                }?.let { _ ->
                    wantToDeleteMessageData.getOrPut(chatId) { mutableMapOf() }[senderId] = replyToMessage.text ?: ""
                    "Are you sure you want to delete that message from your Markov chain " +
                            "data in this group? Say \"yes\" to confirm, or anything else to cancel."
                } ?: "That isn't your message."
            } ?: "You need to reply to your message whose data you want to delete."
        }
        else
            replyText = "NO eres el JEFE, AMIC"

        reply(message, replyText)
    }

    private fun reply(message: Message, text: String, parseMode: ParseMode? = null) =
        this.botInstace.sendMessage(ChatId.fromId(message.chat.id), text, replyToMessageId = message.messageId,
            parseMode = parseMode)

    private fun sendAndMention(message: Message, text: String, entities: List<MessageEntity>) {
        this.botInstace.sendChatAction(ChatId.fromId(message.chat.id), ChatAction.TYPING)
        var parseMode: ParseMode? = null
        var sendText = text

        if (entities.size > 1) {
            val mention = entities[1]

            if (mention.isMention()) {
                val (mentionUserId, e1Text) = getMentionUserId(message, mention)

                val formattedUsername = if (mentionUserId != null && mention.type == MessageEntity.Type.TEXT_MENTION) {
                    parseMode = ParseMode.MARKDOWN
                    createInlineMention(e1Text, mentionUserId)
                }
                else
                    e1Text

                if (mentionUserId == null)
                    sendText = "NO me has dado un usuario, AMIC"
                else
                    sendText += formattedUsername
            }
        }
        else
            sendText = "NO me has dado un usuario, AMIC"

        this.botInstace.sendMessage(ChatId.fromId(message.chat.id), sendText, parseMode = parseMode)
    }

    private fun respond(message: Message, chatId: String, from: User, ignoreRNG: Boolean = false) {
        if (myId != from.id) {
            if (message.replyToMessage?.takeIf { it.from?.id == myId } != null)
                if (rand.nextInt(100) in 0..replyFrecuence || ignoreRNG)
                    reply(message, markovFunctions.generateMessageTotal(chatId)!!)

            else if (rand.nextInt(100) in 0..chatFrequence || ignoreRNG)
                reply(message, markovFunctions.generateMessageTotal(chatId)!!)
        }
    }

    private fun isBotMentioned(message: Message, entities: List<MessageEntity>): Boolean {
        var isMentioned = false

        for (it in entities) {
            if (it.isMention()) {
                val (mentionUserId, eText) = getMentionUserId(message, it)

                val formattedUsername = if (mentionUserId != null && it.type == MessageEntity.Type.TEXT_MENTION)
                    createInlineMention(eText, mentionUserId)
                else
                    eText

                if (formattedUsername == "@$myUsername") {
                    isMentioned = true

                    break
                }
            }
        }

        return isMentioned
    }

    private fun getMentionUserId(message: Message, entity: MessageEntity): Pair<String?, String> {
        val text = getMessageEntityText(message, entity)

        val id = when (entity.type) {
            MessageEntity.Type.MENTION -> markovFunctions.getUserIdForUsername(text.drop(1))
            MessageEntity.Type.TEXT_MENTION -> entity.user?.id?.toString()
            else -> null
        }

        return Pair(id, text)
    }

    private fun matchesCommand(text: String, command: String): Boolean = text == "/$command" ||
            text == "/$command@$myUsername"

    private fun notifyException(text: String) = this.botInstace.sendMessage(this.ownerChatId, "\u26A0\uFE0F $text")

    private fun notifyStartup() = this.botInstace.sendMessage(this.ownerChatId, "Bot started")
}