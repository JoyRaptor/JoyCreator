package com.fadcam.ui.faditor.ai.bridge;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * What the pairing screen tells the person to say or paste (BOT-6). The commands were checked against
 * Claude Code's and Codex's docs on 2026-10-28; those tools change often, so the plain-words line (which
 * the AI acts on itself) is the main path. Pure Java (BridgeTest).
 */
public final class BridgeWords {
    private BridgeWords() { }

    /** The sentence to say to Claude Code or Codex; it adds the server itself. Also what the QR holds. */
    @NonNull
    public static String sayThis(@NonNull String url, @NonNull String code) {
        return "Please connect to Joy Creator on my phone. It's an MCP server over HTTP at " + url
                + " and needs the header \"Authorization: Bearer " + code + "\". Add it as \"joycreator\".";
    }

    /** {title, text} pairs for "Do it yourself". */
    @NonNull
    public static List<String[]> commands(@NonNull String url, int port, @NonNull String code) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"Claude Code (in a terminal)",
                "claude mcp add --transport http joycreator " + url + " --header \"Authorization: Bearer " + code + "\""});
        out.add(new String[]{"Codex (in ~/.codex/config.toml, then set JOYCREATOR_CODE=" + code + ")",
                "[mcp_servers.joycreator]\nurl = \"" + url + "\"\nbearer_token_env_var = \"JOYCREATOR_CODE\""});
        out.add(new String[]{"Any other MCP app (OpenCode, Hermes…)",
                "HTTP (streamable) server: " + url + "\nHeader: Authorization: Bearer " + code});
        out.add(new String[]{"No shared Wi-Fi? A USB cable (needs adb on the computer)",
                "adb reverse tcp:" + port + " tcp:" + port + "\nthen use http://localhost:" + port + "/mcp"});
        return out;
    }
}
