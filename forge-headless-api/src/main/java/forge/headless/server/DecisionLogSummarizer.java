package forge.headless.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline tool, not part of the live decision path: turns DecisionLogger's
 * verbose JSONL (one full protocol-shaped DecisionRequest/DecisionResponse
 * per line - every player's full battlefield/graveyard/exile, every
 * keyword, every id) into a compact, human/LLM-readable text transcript -
 * one short block per decision, extracting only what actually matters for
 * judging the decision instead of repeating the whole board on every line.
 *
 * Confirmed live why this is needed, not just guessed: two consecutive
 * priority-pass decisions within the same turn differ by exactly "one
 * land moved from hand to battlefield" (~120 bytes of real information),
 * but each one independently re-serializes the *entire* game state
 * (~5-18KB) to express that. The raw JSONL stays the ground-truth source
 * (nothing is thrown away there); this is a derived, lossy-on-purpose
 * view for actually reading the games.
 *
 * Usage: java forge.headless.server.DecisionLogSummarizer <in.jsonl> <out.txt>
 */
public final class DecisionLogSummarizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DecisionLogSummarizer() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: DecisionLogSummarizer <in.jsonl> <out.txt>");
            System.exit(1);
        }
        try (BufferedReader in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                out.write(summarize(MAPPER.readTree(line)));
                out.write("\n");
            }
        }
    }

    private static String summarize(JsonNode rec) {
        JsonNode req = rec.path("request");
        JsonNode state = req.path("state");
        JsonNode you = findViewer(state.path("players"));

        StringBuilder sb = new StringBuilder();
        sb.append("[#").append(rec.path("seq").asLong()).append("] ");
        sb.append("Turn ").append(state.path("turnNumber").asInt()).append(" ");
        sb.append(state.path("phase").asText("")).append(" - ");
        sb.append(rec.path("seatName").asText("?")).append(" - ");
        sb.append(req.path("type").asText("?")).append("\n");

        for (JsonNode p : state.path("players")) {
            sb.append("  ").append(p.path("isYou").asBoolean() ? "you" : "opp").append(" (")
                    .append(p.path("name").asText("?")).append("): life=").append(p.path("life").asInt())
                    .append(" board=[").append(cardNames(p.path("battlefield"))).append("]");
            if (p.path("isYou").asBoolean()) {
                sb.append(" hand=[").append(cardNames(p.path("hand"))).append("]");
            } else {
                sb.append(" handCount=").append(p.path("handCount").asInt());
            }
            sb.append("\n");
        }
        if (state.path("stack").size() > 0) {
            sb.append("  stack=[").append(stackDescriptions(state.path("stack"))).append("]\n");
        }

        List<String> optionLabels = new ArrayList<>();
        java.util.Map<String, String> idToLabel = new java.util.HashMap<>();
        for (JsonNode o : req.path("options")) {
            String label = o.path("label").asText("?");
            optionLabels.add(label);
            idToLabel.put(o.path("id").asText(""), label);
        }
        if (!optionLabels.isEmpty()) {
            sb.append("  options: ").append(String.join(" | ", optionLabels)).append("\n");
        }
        if (req.path("min").isInt() || req.path("max").isInt()) {
            sb.append("  min=").append(req.path("min").asText("?")).append(" max=").append(req.path("max").asText("?")).append("\n");
        }
        if (!req.path("mulliganCardsToReturn").isMissingNode() && !req.path("mulliganCardsToReturn").isNull()) {
            sb.append("  cardsToReturn=").append(req.path("mulliganCardsToReturn").asInt()).append("\n");
        }

        JsonNode resp = rec.path("response");
        sb.append("  CHOSE: ");
        if (!resp.path("booleanValue").isNull() && !resp.path("booleanValue").isMissingNode()) {
            sb.append(resp.path("booleanValue").asBoolean());
        } else if (resp.path("chosenIds").size() > 0) {
            List<String> chosenLabels = new ArrayList<>();
            for (JsonNode id : resp.path("chosenIds")) {
                chosenLabels.add(idToLabel.getOrDefault(id.asText(), id.asText()));
            }
            sb.append(String.join(", ", chosenLabels));
        } else if (resp.path("groupChoices").size() > 0) {
            sb.append(resp.path("groupChoices").toString());
        } else {
            sb.append("(nothing/pass)");
        }
        sb.append("\n");
        return sb.toString();
    }

    private static JsonNode findViewer(JsonNode players) {
        for (JsonNode p : players) {
            if (p.path("isYou").asBoolean()) {
                return p;
            }
        }
        return players.isEmpty() ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : players.get(0);
    }

    private static String cardNames(JsonNode cards) {
        List<String> names = new ArrayList<>();
        for (JsonNode c : cards) {
            String name = c.path("name").asText("?");
            if (c.path("typeLine").asText("").contains("Creature")) {
                name += "(" + c.path("power").asText("?") + "/" + c.path("toughness").asText("?") + ")";
            }
            if (c.path("tapped").asBoolean()) {
                name += "[tapped]";
            }
            names.add(name);
        }
        return String.join(", ", names);
    }

    private static String stackDescriptions(JsonNode stack) {
        List<String> descs = new ArrayList<>();
        for (JsonNode item : stack) {
            descs.add(item.path("description").asText("?"));
        }
        return String.join(", ", descs);
    }
}
