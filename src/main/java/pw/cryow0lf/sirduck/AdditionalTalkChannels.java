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

    private Mono<Boolean> missingNextChannel(final TalkChannel channel) {
        return channel.voiceChannel.getVoiceStates()
                .count()
                .map(count -> count <= 0)
                .flatMap(empty -> empty ? Mono.just(true) :
                        createNextChannel(channel).map(c -> false)
                );
    }

    private Flux<TalkChannel> talkChannel(final Flux<VoiceChannel> flux) {
        return flux.handle((channel, sink) -> {
            final Matcher matcher = CHANNEL_PATTERN.matcher(channel.getName());
            if (matcher.matches())
                sink.next(new TalkChannel(channel, matcher.group(1), Integer.parseInt(matcher.group(2))));
        });
    }

    private Mono<Boolean> voiceChannelEmpty(final VoiceChannel channel) {
        return channel.getVoiceStates()
                .count()
                .map(count -> count <= 0);
    }

    private Flux<VoiceChannel> voiceChannels(final Flux<Guild> flux) {
        return flux
                .flatMap(Guild::getChannels)
                .filter(channel -> channel.getType() == Channel.Type.GUILD_VOICE)
                .cast(VoiceChannel.class);
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private Function<Flux<Guild>, Flux<VoiceChannel>> talkChannel(final String channelName,
                                                                  final Optional<Snowflake> category) {
        return flux -> voiceChannels(flux)
                .filter(channel -> channel.getCategoryId().equals(category))
                .filter(channel -> channel.getName().equals(channelName));
    }

    private Function<TalkChannel, Mono<Boolean>> nextChannelExist(final int offset) {
        return channel -> talkChannel(
                channel.topic + (channel.current + offset), channel.voiceChannel.getCategoryId()
        )
                .apply(channel.voiceChannel.getGuild().flux())
                .hasElements()
                .map(bool -> !bool);
    }

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

    private Mono<TalkChannel> highestTalkChannel(TalkChannel channel) {
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
