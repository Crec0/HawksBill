package club.mindtech.mindbot.commands.poll

import club.mindtech.mindbot.MindBot
import club.mindtech.mindbot.commands.BaseCommand
import club.mindtech.mindbot.database.Poll
import club.mindtech.mindbot.helpers.Colors
import club.mindtech.mindbot.helpers.image
import club.mindtech.mindbot.helpers.rect
import club.mindtech.mindbot.helpers.sortToRanks
import club.mindtech.mindbot.helpers.text
import club.mindtech.mindbot.util.bold
import club.mindtech.mindbot.util.button
import club.mindtech.mindbot.util.menu
import club.mindtech.mindbot.util.zFill
import com.mongodb.client.MongoCollection
import dev.minn.jda.ktx.interactions.commands.option
import dev.minn.jda.ktx.interactions.commands.subcommand
import dev.minn.jda.ktx.messages.Embed
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.SelectMenuInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle
import net.dv8tion.jda.api.interactions.components.selections.SelectMenu
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import org.litote.kmongo.* // ktlint-disable no-wildcard-imports
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class CommandPoll : BaseCommand("poll", "Create a poll", "poll <question> [<option>...]") {
    override fun getCommandData(): SlashCommandData {

        val options =
            IntRange(1, 20)
                .map {
                    OptionData(OptionType.STRING, "option-${zFill(it, 2)}", "Option $it for the poll")
                }
                .toTypedArray()

        return super.getCommandData()
            .subcommand("create", "Create a poll") {
                option<String>("question", "The question for the poll", true)
                    .addOptions(*options)
            }
            .subcommand("end", "End a poll") {
                option<String>("poll-id", "The id of the poll to end")
            }
    }

    override fun onSlashCommand(event: SlashCommandInteractionEvent) {
        when (event.commandPath) {
            "poll/create" -> handlePollCreate(event)
            "poll/end" -> handlePollEnd(event)
        }
    }

    private fun handlePollCreate(event: SlashCommandInteractionEvent) {
        val allOptions = event.options.map { it.asString }.toList()

        val question = allOptions[0]
        val distinctOptions = allOptions.subList(1, allOptions.size).distinct()
        val selectMenuOptions = getSelectOptions(distinctOptions)
        val labels = selectMenuOptions.map { it.label }

        event
            .deferReply()
            .flatMap { it.editOriginal(EmbedBuilder.ZERO_WIDTH_SPACE) }
            .flatMap {
                val id = it.id
                val embed = Embed {
                    title = question
                    color = 0x06B6D4
                    description = getJoinedOptions(distinctOptions)
                    footer { name = "Poll ID: $id" }
                }
                return@flatMap it
                    .editMessageEmbeds(embed)
                    .setActionRow(
                        getVoteButton(id, question, selectMenuOptions),
                        getRetractButton(id)
                    )
            }
            .queue { createPollEntry(messageId = it.id, labels = labels) }
    }

    private fun getVoteButton(id: String, question: String, options: List<SelectOption>): Button {
        return button(ButtonStyle.PRIMARY, "Vote") {
            it.deferReply(true)
                .setContent("Vote for a $question")
                .addActionRow(getSelectMenu(id, options))
                .queue()
            return@button false
        }
    }

    private fun getRetractButton(pollID: String): Button {
        return button(ButtonStyle.DANGER, "Retract Vote") {
            it.deferReply(true).setContent("Vote removed").queue()
            removePollEntry(pollID, it.user.id)
            return@button false
        }
    }

    private fun getSelectMenu(id: String, options: List<SelectOption>): SelectMenu {
        return menu(options) {
            if (it is SelectMenuInteractionEvent) {
                val memberId = it.user.id
                val selected = it.selectedOptions.first().label
                updatePollEntry(id, memberId, selected)
                it.deferEdit().setContent("Your vote has been recorded").setActionRows().queue()
            }
            return@menu true
        }
    }

    private val defaultOptions = listOf(
        SelectOption.of("Upvote", "upvote"),
        SelectOption.of("Downvote", "downvote")
    )

    private fun getSelectOptions(options: List<String>): List<SelectOption> {
        return if (options.size < 2) {
            defaultOptions
        } else {
            IntRange(0, options.size - 1)
                .map {
                    val char = ('A'.code + it).toChar()
                    SelectOption.of("$char", options[it])
                }
        }
    }

    private fun getJoinedOptions(options: List<String>): String {
        return IntRange(0, options.size - 1)
            .joinToString("\n") {
                ":regional_indicator_${('a'.code + it).toChar()}: ${bold("❱")} ${options[it]}"
            }
    }

    private fun handlePollEnd(event: SlashCommandInteractionEvent) {
        val id: String = event.getOption("poll-id")!!.asString
        val poll: Poll? = fetchAndDelete(id)

        if (poll == null) {
            event.deferReply(true).setContent("Poll not found").queue()
            return
        }

        event.channel.retrieveMessageById(id).queue { message ->
            val resultEmbed = Embed {
                color = 0x06B6D4
                image = "attachment://poll.png"
            }
            message
                .editMessageEmbeds(*message.embeds.toTypedArray(), resultEmbed)
                .addFile(getPollResultsImage(poll), "poll.png")
                .flatMap { it.editMessageComponents() }
                .queue()
        }

        event.deferReply(true).setContent("Poll $id successfully ended").queue()
    }

    private fun rankingColor(rank: Int): Colors {
        return when (rank) {
            1 -> Colors.LIGHT_BLUE_400
            2 -> Colors.EMERALD_400
            3 -> Colors.AMBER_400
            else -> Colors.VIOLET_200
        }
    }

    private fun getPollResultsImage(poll: Poll): ByteArray {
        val spacing = 8
        val fontSize = 18
        val imgWidth = 512

        val rectSliceSize = 3

        val imgHeight = fontSize + spacing * 2 + (poll.labels.size * (fontSize + spacing))

        val groupedVotes = poll
            .votes
            .entries
            .groupBy { it.value }
            .mapValues { it.value.size }

        val totalVotes = groupedVotes.values.sum().let { if (it == 0) 1 else it }

        val sortedVoterVotePairs = poll
            .labels
            .map { it to (groupedVotes[it] ?: 0) }
            .sortedByDescending { it.second }

        val rankings = sortToRanks(sortedVoterVotePairs)

        val image = image(imgWidth, imgHeight) {
            rect(x = 0, y = 0, width = imgWidth, height = imgHeight, color = Colors.GRAY_100, fill = true)

            text(
                x = spacing,
                y = spacing + fontSize,
                text = "Poll Results",
                font = "Montserrat SemiBold",
                color = Colors.BLUE_GRAY_600
            )

            val maxWidthLabel = poll.labels.maxByOrNull { it.length } ?: ""
            val maxLabelWidth = fontMetrics.stringWidth(maxWidthLabel)

            poll.labels.forEachIndexed { index, label ->

                val votes = groupedVotes[label] ?: 0
                val percentage = (votes * 100) / totalVotes
                val rectWidth = percentage * rectSliceSize

                rect(
                    x = maxLabelWidth + spacing * 2,
                    y = fontSize + (spacing * 2.5).toInt() + (index * (fontSize + spacing)) - 4,
                    width = rectWidth,
                    height = fontSize + 4,
                    color = rankingColor(rankings[label]!!),
                    fill = true
                )

                val textY = (fontSize + spacing) * (2 + index)
                val textWidth = fontMetrics.stringWidth(label)

                text(
                    x = maxLabelWidth - textWidth + spacing,
                    y = textY,
                    text = label,
                    font = "Montserrat SemiBold",
                    color = Colors.BLUE_GRAY_600
                )

                text(
                    x = maxLabelWidth + spacing * 3 + rectWidth,
                    y = textY,
                    text = "$votes [ $percentage % ]",
                    font = "Montserrat SemiBold",
                    color = Colors.BLUE_GRAY_600
                )
            }
        }
        val buffer = ByteArrayOutputStream()
        ImageIO.write(image, "png", buffer)

        return buffer.toByteArray()
    }

    private fun getCollection(): MongoCollection<Poll> {
        return MindBot.db.getCollection()
    }

    private fun createPollEntry(messageId: String, labels: List<String>) {
        getCollection().insertOne(Poll(vote_id = messageId, labels = labels))
    }

    private fun updatePollEntry(voteId: String, userId: String, selectedOption: String) {
        getCollection()
            .updateOne(
                Poll::vote_id eq voteId,
                Poll::votes.keyProjection(key = userId) setTo selectedOption
            )
    }

    private fun removePollEntry(voteId: String, userId: String) {
        getCollection()
            .updateOne(
                Poll::vote_id eq voteId,
                unset(Poll::votes.keyProjection(userId))
            )
    }

    private fun fetchAndDelete(voteId: String): Poll? {
        return getCollection().findOneAndDelete(Poll::vote_id eq voteId)
    }
}
