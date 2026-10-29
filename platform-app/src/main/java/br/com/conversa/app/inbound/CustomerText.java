package br.com.conversa.app.inbound;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * What a customer message says, as text for the history. Media the bot cannot read become a
 * marker the assistant prompt knows ("[áudio]"), so it asks the customer to type.
 */
final class CustomerText {

    private CustomerText() {
    }

    static String of(JsonNode message) {
        String type = message.path("type").asString();
        return switch (type) {
            case "text" -> message.path("text").path("body").asString();
            case "button" -> message.path("button").path("text").asString();
            case "interactive" -> {
                JsonNode interactive = message.path("interactive");
                JsonNode reply = interactive.has("button_reply") ? interactive.path("button_reply")
                        : interactive.path("list_reply");
                yield reply.path("title").asString("[resposta de botão]");
            }
            case "image" -> withCaption("[imagem]", message.path("image"));
            case "video" -> withCaption("[vídeo]", message.path("video"));
            case "document" -> withCaption("[documento]", message.path("document"));
            case "audio" -> "[áudio]";
            case "sticker" -> "[figurinha]";
            case "location" -> "[localização enviada]";
            case "contacts" -> "[contato compartilhado]";
            case "reaction" -> "[reação " + message.path("reaction").path("emoji").asString("") + "]";
            default -> "[mensagem do tipo " + type + "]";
        };
    }

    /** Id of the reply button or list row the customer tapped, or {@code null}. */
    static String replyId(JsonNode message) {
        JsonNode interactive = message.path("interactive");
        for (String kind : new String[] {"button_reply", "list_reply"}) {
            if (interactive.path(kind).path("id").isString()) {
                return interactive.path(kind).path("id").asString();
            }
        }
        return null;
    }

    /** A voice note or image the reply worker should download (H6), with its Cloud API id. */
    record Media(String kind, String id, String mimeType) {
    }

    static Optional<Media> media(JsonNode message) {
        String type = message.path("type").asString();
        if (!"audio".equals(type) && !"image".equals(type)) {
            return Optional.empty();
        }
        JsonNode media = message.path(type);
        return media.path("id").isString()
                ? Optional.of(new Media(type, media.path("id").asString(), media.path("mime_type").asString("")))
                : Optional.empty();
    }

    private static String withCaption(String marker, JsonNode media) {
        String caption = media.path("caption").asString("");
        return caption.isBlank() ? marker : marker + " " + caption;
    }
}
