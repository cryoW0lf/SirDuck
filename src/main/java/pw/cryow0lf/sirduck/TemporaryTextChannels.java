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
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static discord4j.core.object.PermissionOverwrite.forRole;
import static discord4j.core.object.util.Permission.ADD_REACTIONS;
import static discord4j.core.object.util.Permission.READ_MESSAGE_HISTORY;
import static discord4j.core.object.util.Permission.SEND_MESSAGES;
import static discord4j.core.object.util.Permission.VIEW_CHANNEL;

final class TemporaryTextChannels {

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

    private Mono<Boolean> isEmpty(final VoiceChannel channel) {
        return channel.getVoiceStates().count().map(count -> count <= 0);
    }

    private Publisher<Boolean> isTextChannelMissing(final VoiceState state) {
        return state.getChannel()
                .flatMap(this::textChannel)
                .flatMap(channel -> channel.addMemberOverwrite(state.getUserId(),
                        temporaryChannelPermissions(state.getUserId()))
                        .thenReturn(channel)
                )
                .hasElement()
                .map(bool -> !bool);
    }

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

    private Publisher<Boolean> hasTemporaryChannel(final VoiceState state) {
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

    private PermissionOverwrite temporaryChannelPermissions(final Snowflake user) {
        return PermissionOverwrite.forMember(user,
                PermissionSet.of(VIEW_CHANNEL, READ_MESSAGE_HISTORY, SEND_MESSAGES, ADD_REACTIONS),
                PermissionSet.none()
        );
    }

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

    private String temporaryChannelName(final VoiceChannel voiceChannel) {
        return voiceChannel.getName().toLowerCase().replace(' ', '-');
    }

}
