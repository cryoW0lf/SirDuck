package pw.cryow0lf.sirduck;

import discord4j.core.event.EventDispatcher;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.guild.GuildCreateEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Channel;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.VoiceChannel;
import discord4j.core.object.util.Snowflake;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class creates additional voice channels, if the channel name meets the condition "<topic><number>".
 * <p>
 * If a user joins a channel, the bot checks if the next channel already exists, if not, the channel is created with
 * the next number in the channel name, as well as with the same permission overrides, user limits, bitrate and in
 * the same category, below the last channel.
 * <p>
 * If a user leaves a voice channel or the bot reconnects, all unnecessary voice channels will be deleted. The
 * channel with the number "1" will always be kept.
 */
final class AdditionalTalkChannels {
    private final static Pattern CHANNEL_PATTERN = Pattern.compile("(.+?)(\\d+)");

    AdditionalTalkChannels(final EventDispatcher eventDispatcher) {
        eventDispatcher.on(GuildCreateEvent.class)
                .map(GuildCreateEvent::getGuild)
                .compose(this::voiceChannels)
                .filter(channel -> channel.getName().endsWith("1"))
                .compose(this::talkChannel)
                .filter(channel -> channel.current == 1)
                .flatMap(this::highestTalkChannel)
                .filterWhen(this::missingNextChannel)
                .flatMap(this::deleteUnusedTalkChannels)
                .subscribe();

        eventDispatcher.on(VoiceStateUpdateEvent.class)
                .map(VoiceStateUpdateEvent::getCurrent)
                .flatMap(VoiceState::getChannel)
                .compose(this::talkChannel)
                .filterWhen(nextChannelExist(1))
                .flatMap(this::createNextChannel)
                .subscribe();

        eventDispatcher.on(VoiceStateUpdateEvent.class)
                .filter(event -> event.getOld().isPresent())
                .map(event -> event.getOld().orElse(null))
                .flatMap(VoiceState::getChannel)
                .filterWhen(this::voiceChannelEmpty)
                .compose(this::talkChannel)
                .delayElements(Duration.ofSeconds(1))
                .filterWhen(nextChannelExist(2))
                .flatMap(this::deleteUnusedTalkChannels)
                .subscribe();
    }

    /**
     * Requests a boolean indicating if the voice channel is empty. If the voice channel is not empty, a request to
     * create the next channel with {@link #createNextChannel} is made.
     *
     * @param channel The {@link TalkChannel} providing the {@link VoiceChannel} to check if its empty.
     * @return A {@link Mono} indicating if the {@link VoiceChannel} is empty.
     */
    private Mono<Boolean> missingNextChannel(final TalkChannel channel) {
        return channel.voiceChannel.getVoiceStates()
                .count()
                .map(count -> count <= 0)
                .flatMap(empty -> empty ? Mono.just(true) :
                        createNextChannel(channel).map(c -> false)
                );
    }

    /**
     * Tries to parse the channel names for the pattern, if the name matches emits a corresponding
     * {@link TalkChannel} object for each {@link VoiceChannel}, else it gets dropped.
     *
     * @param flux The {@link Flux} emitting the {@link VoiceChannel}s
     * @return A {@link Flux} emitting all {@link TalkChannel}s.
     */
    private Flux<TalkChannel> talkChannel(final Flux<VoiceChannel> flux) {
        return flux.handle((channel, sink) -> {
            final Matcher matcher = CHANNEL_PATTERN.matcher(channel.getName());
            if (matcher.matches())
                sink.next(new TalkChannel(channel, matcher.group(1), Integer.parseInt(matcher.group(2))));
        });
    }

    /**
     * Requests a boolean indicating if the voice channel is empty.
     *
     * @param channel The {@link VoiceChannel} to be checked.
     * @return A {@link Mono} indicating if the voice channel is empty.
     */
    private Mono<Boolean> voiceChannelEmpty(final VoiceChannel channel) {
        return channel.getVoiceStates()
                .count()
                .map(count -> count <= 0);
    }

    /**
     * Requests all voice channels of the corresponding guild.
     *
     * @param flux The {@link Flux} emitting the guild.
     * @return A {@link Flux} emitting all {@link VoiceChannel}s.
     */
    private Flux<VoiceChannel> voiceChannels(final Flux<Guild> flux) {
        return flux
                .flatMap(Guild::getChannels)
                .filter(channel -> channel.getType() == Channel.Type.GUILD_VOICE)
                .cast(VoiceChannel.class);
    }

    /**
     * Returns a function accepting a {@link Flux} emitting a guild and requests all {@link VoiceChannel}s with a
     * specific channel name and category id.
     *
     * @param channelName The channel name to be filtered for.
     * @param category    A {@link Optional} with the {@link Snowflake} of the category, if available.
     * @return The {@link Function} bound to the specific parameters.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Function<Flux<Guild>, Flux<VoiceChannel>> talkChannel(final String channelName,
                                                                  final Optional<Snowflake> category) {
        return flux -> voiceChannels(flux)
                .filter(channel -> channel.getCategoryId().equals(category))
                .filter(channel -> channel.getName().equals(channelName));
    }

    /**
     * Returns a function accepting a {@link TalkChannel} and requests a boolean indicating if the next talk channel
     * (by the passed offset) exists
     *
     * @param offset The offset to be used. For checking the next {@link TalkChannel}, pass 1.
     * @return The {@link Function} bound to the specific parameters.
     */
    private Function<TalkChannel, Mono<Boolean>> nextChannelExist(final int offset) {
        return channel -> talkChannel(
                channel.topic + (channel.current + offset), channel.voiceChannel.getCategoryId()
        )
                .apply(channel.voiceChannel.getGuild().flux())
                .hasElements()
                .map(bool -> !bool);
    }

    /**
     * Requests to delete all unused {@link TalkChannel}s except the lowest.
     *
     * @param channel The {@link TalkChannel} with the topic to use.
     * @return The {@link Flux} requesting the deletion.
     */
    private Flux<Void> deleteUnusedTalkChannels(final TalkChannel channel) {
        final String topic = channel.topic;
        final int current = channel.current;
        final VoiceChannel voice = channel.voiceChannel;

        return Flux.range(1, current - 1)
                .map(number -> current - number)
                .filterWhen(number ->
                        voice.getGuild()
                                .flux()
                                .compose(talkChannel(topic + number, voice.getCategoryId()))
                                .flatMap(this::voiceChannelEmpty)
                                .map(bool -> !bool)
                )
                .single(0)
                .flatMapMany(lastFull -> Flux.range(lastFull + 2, current - lastFull))
                .flatMap(number -> voice.getGuild()
                        .flux()
                        .compose(talkChannel(topic + number, voice.getCategoryId()))
                )
                .flatMap(c -> c.delete("Automated Voice Channel"));
    }


    /**
     * Requests the creation of the next {@link TalkChannel}.
     *
     * @param channel The current {@link TalkChannel}.
     * @return A {@link Mono} returning the new created {@link VoiceChannel}.
     */
    private Mono<VoiceChannel> createNextChannel(final TalkChannel channel) {
        final VoiceChannel voice = channel.voiceChannel;
        return voice.getGuild()
                .flatMap(guild -> guild.createVoiceChannel(spec ->
                        spec.setName(channel.topic + (channel.current + 1))
                                .setBitrate(voice.getBitrate())
                                .setParentId(voice.getCategoryId().orElse(null))
                                .setUserLimit(voice.getUserLimit())
                                .setPermissionOverwrites(voice.getPermissionOverwrites())
                                .setPosition(voice.getRawPosition() + 1)
                                .setReason("Automated Voice Channel")
                ));
    }

    /**
     * Requests the highest {@link TalkChannel} with the specific topic.
     *
     * @param channel The {@link TalkChannel} providing the topic to use.
     * @return {@link Mono} returning the highest {@link TalkChannel}.
     */
    private Mono<TalkChannel> highestTalkChannel(final TalkChannel channel) {
        return channel.voiceChannel.getGuild()
                .flux()
                .compose(this::voiceChannels)
                .filter(voiceChannel -> voiceChannel.getCategoryId().equals(channel.voiceChannel.getCategoryId()))
                .compose(this::talkChannel)
                .filter(talkChannel -> talkChannel.topic.equals(channel.topic))
                .reduce((channel1, channel2) -> channel1.current < channel2.current ? channel2 : channel1);
    }

    private static class TalkChannel {
        private final VoiceChannel voiceChannel;
        private final String topic;
        private final int current;

        private TalkChannel(final VoiceChannel voiceChannel, final String topic, final int current) {
            this.voiceChannel = voiceChannel;
            this.topic = topic;
            this.current = current;
        }

        @Override
        public String toString() {
            return "TalkChannel{" +
                    "voiceChannel=" + voiceChannel +
                    ", topic='" + topic + '\'' +
                    ", current=" + current +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TalkChannel that = (TalkChannel) o;
            return current == that.current &&
                    Objects.equals(voiceChannel, that.voiceChannel) &&
                    Objects.equals(topic, that.topic);
        }

        @Override
        public int hashCode() {
            return Objects.hash(voiceChannel, topic, current);
        }
    }
}
