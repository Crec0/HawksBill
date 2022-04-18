package club.mindtech.mindbot.events

import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent

interface ComponentCallback {
    fun call(event: GenericComponentInteractionCreateEvent?): Boolean
}
