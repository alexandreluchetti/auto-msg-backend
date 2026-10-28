package br.com.conversa.app.inbound;

import tools.jackson.databind.JsonNode;

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

    private static String withCaption(String marker, JsonNode media) {
        String caption = media.path("caption").asString("");
        return caption.isBlank() ? marker : marker + " " + caption;
    }
}
