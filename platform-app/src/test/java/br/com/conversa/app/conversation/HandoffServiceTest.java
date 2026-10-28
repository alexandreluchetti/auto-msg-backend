package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.ConversationView;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffServiceTest {

    @Test
    void recognizesPlainRequestsForAPerson() {
        assertThat(HandoffService.asksForPerson("Quero falar com um atendente!")).isTrue();
        assertThat(HandoffService.asksForPerson("atendente")).isTrue();
        assertThat(HandoffService.asksForPerson("HUMANO por favor")).isTrue();
        assertThat(HandoffService.asksForPerson("pode chamar alguém da equipe?")).isTrue();
        assertThat(HandoffService.asksForPerson("me passa pro gerente")).isTrue();
        assertThat(HandoffService.asksForPerson("Quero conversar com uma pessoa de verdade")).isTrue();
    }

    @Test
    void leavesEverythingElseToTheAssistant() {
        assertThat(HandoffService.asksForPerson("Quero uma pizza pra uma pessoa")).isFalse();
        assertThat(HandoffService.asksForPerson("tem atendente aí?")).isFalse();
        assertThat(HandoffService.asksForPerson("Qual o horário do atendimento?")).isFalse();
        assertThat(HandoffService.asksForPerson("Oi")).isFalse();
    }

    @Test
    void staffSeeTheCustomerNameAndOnlyTheLastDigits() {
        assertThat(HandoffService.customerLabel(view("5516990000001", "Ana Souza"))).isEqualTo("Ana Souza (final 0001)");
        assertThat(HandoffService.customerLabel(view("5516990000001", null))).isEqualTo("Cliente (final 0001)");
    }

    private static ConversationView view(String waId, String name) {
        return new ConversationView(UUID.randomUUID(), "200000000000001", waId, name, "BOT", 0);
    }
}
