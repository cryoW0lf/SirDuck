package pw.cryow0lf.sirduck;

import discord4j.core.DiscordClient;

public final class SirDuck {

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java -jar SirRobot.jar <bottoken>");
            System.exit(1);
        }

        final DiscordClient client = DiscordClient.create(args[0]);

        new AdditionalTalkChannels(client.getEventDispatcher());
        client.login().block();
    }
}
