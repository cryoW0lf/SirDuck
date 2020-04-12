package pw.cryow0lf.sirduck;

import discord4j.core.DiscordClient;

public final class SirDuck {

    /**
     * Starts the bot by building the discord client, registering the events, connecting to the discord gateway and
     * then blocking indefinitely.
     *
     * @param args start arguments, where the first element should be a valid discord bot token
     */
    public static void main(final String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java -jar SirRobot.jar <bottoken>");
            System.exit(1);
        }

        final DiscordClient client = DiscordClient.create(args[0]);

        new TemporaryTextChannels(client.getEventDispatcher());
        new AdditionalTalkChannels(client.getEventDispatcher());
        client.login().block();
    }
}
