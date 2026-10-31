package dev.victor.plug.cli;

/*
 * Entry point for the packaged executable (the shaded jar's
 * Main-Class). It only applies the CLI's exit code; everything
 * else happens in Cli, which stays testable without spawning
 * a process.
 */
public final class CliMain {

    public static void main(String[] args) {

        System.exit(new Cli().run(args));
    }

    private CliMain() {
    }
}
