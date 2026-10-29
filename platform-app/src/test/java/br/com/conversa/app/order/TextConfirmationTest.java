package br.com.conversa.app.order;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TextConfirmationTest {

    @ParameterizedTest
    @ValueSource(strings = {"Pode mandar", "pode mandar!", "Confirmo", "confirmado", "Fechado 👍", "fechou",
            "Sim", "sim, pode confirmar", "Isso mesmo, pode mandar por favor", "Tá certo, pode fazer o pedido",
            "ok pode enviar", "beleza, manda", "Perfeito! Pode preparar, obrigado", "Tudo certo sim", "Bora"})
    void clearConfirmations(String text) {
        assertThat(TextConfirmation.isClear(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sim, mas sem cebola", "acho que sim", "não", "pode mandar sem cebola",
            "confirmo, mas troca a bebida", "pode mandar no outro endereço", "quanto fica?", "ok?",
            "Não pode mandar ainda", "pera", "", "👍"})
    void anythingElseIsNotAConfirmation(String text) {
        assertThat(TextConfirmation.isClear(text)).as(text).isFalse();
    }
}
