package br.com.conversa.app;

import java.util.regex.Pattern;

/** Tests that check answers ignore the privacy notice the first answer to a customer carries. */
public final class PrivacyNotices {

    private static final Pattern NOTICE = Pattern.compile("(?s)_Este é o atendimento automático .*?_(?:\\n\\n)?");

    private PrivacyNotices() {
    }

    public static String strip(String text) {
        return NOTICE.matcher(text).replaceFirst("");
    }

    public static boolean has(String text) {
        return NOTICE.matcher(text).find();
    }
}
