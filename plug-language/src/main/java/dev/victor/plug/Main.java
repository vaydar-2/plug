package dev.victor.plug;

/*
 * Legacy entry point for manual classpath runs
 * (java -cp target/classes dev.victor.plug.Main examples/test.plug).
 * Behavior now funnels through the CLI, which keeps the bare
 * <file.plug> invocation working and adds run/check/--version/
 * --help dispatch on top.
 */
public class Main {

    public static void main(String[] args) {

        System.exit(
                new dev.victor.plug.cli.Cli().run(args)
        );
    }
}
