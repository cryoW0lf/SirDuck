package pw.cryow0lf.sirduck;

import discord4j.core.event.EventDispatcher;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.object.PermissionOverwrite;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Channel;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.TextChannel;
import discord4j.core.object.entity.VoiceChannel;
import discord4j.core.object.util.PermissionSet;
import discord4j.core.object.util.Snowflake;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static discord4j.core.object.PermissionOverwrite.forRole;
import static discord4j.core.object.util.Permission.ADD_REACTIONS;
import static discord4j.core.object.util.Permission.READ_MESSAGE_HISTORY;
import static discord4j.core.object.util.Permission.SEND_MESSAGES;
import static discord4j.core.object.util.Permission.VIEW_CHANNEL;

/**
 * This class creates temporary text channels for voice channel members. Normally, nobody is allowed to read the text
 * channel, except users in the voice channel. If the last user disconnects from the voice channel, the text channel
 * is deleted.
 *
 * If the bot was offline when the last user disconnected, but reconnected, he will check for every leftover text
 * channel and delete if necessary.
 *
 * A text channel is named like the voice channel, as translated by {@link #temporaryChannelName}, by transform into
 * lowercase and replacing spaces with dashes. Also the topic of the text channel is set to the id of the voice
 * channel, to allow multiple text channels with the same name without deleting the wrong text channel.
 */
final class TemporaryTextChannels {

    /**
     * Subscribes to the events to provide the functionality.
     *
     * @param dispatcher The {@link EventDispatcher} to which the subscriptions are made.
     */
    TemporaryTextChannels(final EventDispatcher dispatcher) {
        dispatcher.on(GuildCreateEvent.class)
                .map(GuildCreateEvent::getGuild)
                .flatMap(Guild::getChannels)
                .filter(channel -> channel.getType() == Channel.Type.GUILD_VOICE)
                .cast(VoiceChannel.class)
                .filterWhen(this::isEmpty)
                .flatMap(this::textChannel)
                .flatMap(channel -> channel.delete("Automated Temporary Text Channel"))
                .subscribe();

        dispatcher.on(VoiceStateUpdateEvent.class)
                .map(VoiceStateUpdateEvent::getCurrent)
                .filterWhen(this::isTextChannelMissing)
                .flatMap(VoiceState::getChannel)
                .flatMap(this::createTemporaryChannel)
                .subscribe();

        dispatcher.on(VoiceStateUpdateEvent.class)
                .filter(event -> event.getOld().isPresent())
                .map(event -> event.getOld().orElse(null))
                .filterWhen(this::hasTemporaryChannel)
                .flatMap(VoiceState::getChannel)
                .filterWhen(this::isEmpty)
                .flatMap(this::textChannel)
                .flatMap(channel -> channel.delete("Automated Temporary Text Channel"))
                .subscribe();
    }

    /**
     * Requests a boolean indicating if the voice channel is empty.
     *
     * @param state The {@link VoiceChannel} to be checked if empty.
     * @return A {@link Mono} emitting a boolean if the voice channel is empty.
     */
    private Mono<Boolean> isEmpty(final VoiceChannel channel) {
        return channel.getVoiceStates().count().map(count -> count <= 0);
    }

    /**
     * Requests a boolean indicating if the text channel corresponding to the voice channel provided by
     * {@link VoiceState#getChannel()} is missing. If there is a text channel, give the userid of the {@link VoiceState}
     * permissions for the existing text channel as created by {@link #temporaryChannelPermissions(Snowflake)}.
     *
     * @param state The {@link VoiceState} to be used.
     * @return A {@link Mono} emitting a boolean if the corresponding text channel exists.
     */
    private Mono<Boolean> isTextChannelMissing(final VoiceState state) {
        return state.getChannel()
                .flatMap(this::textChannel)
                .flatMap(channel -> channel.addMemberOverwrite(state.getUserId(),
                        temporaryChannelPermissions(state.getUserId()))
                        .thenReturn(channel)
                )
                .hasElement()
                .map(bool -> !bool);
    }

    /**
     * Requests the creation of a text channel for a voice channel, which normally no one can see/write into, except
     * users in the specific voice chat. The text channel gets the name of the voice channel modified by
     * {@link #temporaryChannelName(VoiceChannel)}, the same permission overrides as the voice channel and positioned
     * over the voice channel in the same category. The topic of the text channel is set to the id of the voice channel.
     *
     * @param channel The {@link VoiceChannel} to be used.
     * @return A {@link Mono} emitting the corresponding {@link TextChannel}.
     */
    private Mono<TextChannel> createTemporaryChannel(final VoiceChannel channel) {
        return channel.getVoiceStates()
                .map(VoiceState::getUserId)
                .map(this::temporaryChannelPermissions)
                .collect(Collectors.toCollection(HashSet::new))
                .doOnNext(permissionOverwrites -> permissionOverwrites.add(
                        forRole(channel.getGuildId(), PermissionSet.none(), PermissionSet.of(VIEW_CHANNEL))
                ))
                .zipWith(channel.getGuild(), (permissionOverwrites, guild) -> guild.createTextChannel(spec ->
                        spec.setName(temporaryChannelName(channel))
                                .setPosition(Math.max(0, channel.getRawPosition() - 1))
                                .setPermissionOverwrites(permissionOverwrites)
                                .setParentId(channel.getCategoryId().orElse(null))
                                .setTopic(channel.getId().asString())
                                .setReason("Automated Temporary Text Channel")
                ))
                .flatMap(Function.identity());
    }

    /**
     * Requests a boolean indicating if there is a text channel corresponding to the voice channel provided by
     * {@link VoiceState#getChannel()}. If there is a text channel, give the userid of the {@link VoiceState}
     * permissions for the existing text channel as created by {@link #temporaryChannelPermissions(Snowflake)}.
     *
     * @param state The {@link VoiceState} to be used.
     * @return A {@link Mono} emitting a boolean if the corresponding text channel exists.
     */
    private Mono<Boolean> hasTemporaryChannel(final VoiceState state) {
        return state.getChannel()
                .flatMap(this::textChannel)
                .doOnNext(channel -> channel.addMemberOverwrite(state.getUserId(),
                        PermissionOverwrite.forMember(state.getUserId(),
                                PermissionSet.none(),
                                PermissionSet.none()
                        ))
                        .thenReturn(channel)
                )
                .hasElement();
    }

    /**
     * Returns a {@link PermissionOverwrite} for the provided user, which allows him to
     * {@code VIEW_CHANNEL, READ_MESSAGE_HISTORY, SEND_MESSAGES, ADD_REACTIONS}.
     *
     * @param user The {@link Snowflake} of the user which should receive temporary channel permissions.
     * @return The constructed {@link PermissionOverwrite} bound to the provided {@link Snowflake}.
     */
    private PermissionOverwrite temporaryChannelPermissions(final Snowflake user) {
        return PermissionOverwrite.forMember(user,
                PermissionSet.of(VIEW_CHANNEL, READ_MESSAGE_HISTORY, SEND_MESSAGES, ADD_REACTIONS),
                PermissionSet.none()
        );
    }

    /**
     * Requests to retrieve the {@link TextChannel} for a specific {@link VoiceChannel}
     * The {@link TextChannel} needs to be in the same Category as the {@link VoiceChannel}, if in any, as well as
     * have the SnowflakeID of the {@link VoiceChannel} in its topic. The name of the channel needs to conform to
     * {@link #temporaryChannelName(VoiceChannel)} of the provided {@link VoiceChannel}.
     *
     * @param voiceChannel The {@link VoiceChannel} to be used.
     * @return A {@link Mono} that emits the corresponding {@link TextChannel}, if available.
     */
    private Mono<TextChannel> textChannel(final VoiceChannel voiceChannel) {
        final String channelName = temporaryChannelName(voiceChannel);
        return voiceChannel.getGuild()
                .flatMapMany(Guild::getChannels)
                .filter(channel -> channel.getName().equals(channelName))
                .filter(channel -> channel.getType() == Channel.Type.GUILD_TEXT)
                .cast(TextChannel.class)
                .filter(channel -> channel.getCategoryId().equals(voiceChannel.getCategoryId()))
                .filter(channel -> channel.getTopic().map(voiceChannel.getId().asString()::equals).orElse(false))
                .singleOrEmpty();
    }

    /**
     * Returns a valid text channel name for a provided voice channel name.
     *
     * @param voiceChannel the {@link VoiceChannel} which name will be used
     * @return the corresponding text channel name
     */
    private String temporaryChannelName(final VoiceChannel voiceChannel) {
        return voiceChannel.getName().toLowerCase().replace(' ', '-');
    }

}
