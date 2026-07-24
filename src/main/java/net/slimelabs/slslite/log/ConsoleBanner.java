package net.slimelabs.slslite.log;

import net.slimelabs.slslite.BuildInfo;
import org.slf4j.Logger;

import java.util.List;

public final class ConsoleBanner {

    private static final List<String> LOGO = List.of(
            "  ___ _    ___       _    ___ _____ ___ ",
            " / __| |  / __| ___ | |  |_ _|_   _| __|",
            " \\__ \\ |__\\__ \\|___|| |__ | |  | | | _| ",
            " |___/____|___/     |____|___| |_| |___|"
    );

    private ConsoleBanner() {
    }

    public static void logStartup(Logger logger) {
        logger.info("");
        LOGO.forEach(logger::info);
        logger.info(" SLS-LITE v{} | Standalone Server Launch System", BuildInfo.VERSION);
        logger.info(" Copyright (C) 2020 - 2026 Protoxon, Yeetoxic & Contributors");
        logger.info("");
        logger.info(" Source:  {}", BuildInfo.SOURCE_URL);
        logger.info(" License: {}", BuildInfo.LICENSE_URL);
        logger.info("");
        logger.info(" Licensed under the GNU Affero General Public License v3.0.");
        logger.info(" SLS-LITE runs locally in Velocity without full SLS infrastructure.");
        logger.info("");
    }

    public static void logShutdown(Logger logger) {
        logger.info("");
        logger.info(" SLS-LITE v{} is shutting down.", BuildInfo.VERSION);
        logger.info(" Thank you for using SLS-LITE.");
        logger.info(" Source: {}", BuildInfo.SOURCE_URL);
        logger.info("");
    }

    static List<String> logo() {
        return LOGO;
    }
}
