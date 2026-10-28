package br.com.conversa.core.catalog;

import br.com.conversa.core.text.SearchText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSearchTest {

    private final List<CatalogItem> catalog = List.of(
            item("P01", "Pizzas", "Pizza Calabresa", "Calabresa fatiada, cebola e azeitonas",
                    new CatalogOption("Tamanho", "Grande", new BigDecimal("10.00"))),
            item("P02", "Pizzas", "Pizza Margherita", "Mussarela, tomate e manjericão"),
            item("B01", "Bebidas", "Refrigerante Lata", "350 ml"),
            item("L01", "Lanches", "X-Calabresa", "Pão, hambúrguer e calabresa"),
            item("S01", "Sobremesas", "Pudim de Leite", "Fatia"));

    @Test
    void ignoresCaseAccentsAndStopWords() {
        assertThat(codes(CatalogSearch.search(catalog, "pizza de CALABRÉSA", null, 10))).containsExactly("P01");
        assertThat(codes(CatalogSearch.search(catalog, "manjericao", null, 10))).containsExactly("P02");
    }

    @Test
    void pluralsMatchTheSingular() {
        assertThat(codes(CatalogSearch.search(catalog, "pizzas", null, 10))).containsExactly("P01", "P02");
        assertThat(codes(CatalogSearch.search(catalog, "refrigerantes", null, 10))).containsExactly("B01");
    }

    @Test
    void nameMatchesRankBeforeDescriptionMatches() {
        assertThat(codes(CatalogSearch.search(catalog, "calabresa", null, 10))).containsExactly("P01", "L01");
    }

    @Test
    void everyWordMustMatch() {
        assertThat(CatalogSearch.search(catalog, "pizza de chocolate", null, 10)).isEmpty();
    }

    @Test
    void optionsAreSearchedToo() {
        assertThat(codes(CatalogSearch.search(catalog, "pizza grande", null, 10))).containsExactly("P01");
    }

    @Test
    void filtersByCategoryAndListsItWithoutAQuery() {
        assertThat(codes(CatalogSearch.search(catalog, "calabresa", "lanches", 10))).containsExactly("L01");
        assertThat(codes(CatalogSearch.search(catalog, null, "Pizza", 10))).containsExactly("P01", "P02");
    }

    @Test
    void respectsTheLimit() {
        assertThat(CatalogSearch.search(catalog, "", null, 2)).hasSize(2);
    }

    @Test
    void normalizesText() {
        assertThat(SearchText.normalize("  Pão de Açúcar! ")).isEqualTo("pao de acucar");
        assertThat(SearchText.distance("jardim botanico", "jardim botanica")).isEqualTo(1);
    }

    private static CatalogItem item(String code, String category, String name, String description,
                                    CatalogOption... options) {
        return new CatalogItem(UUID.randomUUID(), code, category, name, description, new BigDecimal("30.00"),
                true, List.of(options));
    }

    private static List<String> codes(List<CatalogItem> items) {
        return items.stream().map(CatalogItem::code).toList();
    }
}
