package br.com.conversa.app.business;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvTableTest {

    @Test
    void readsSemicolonSeparatedExcelExportsWithBomAndQuotes() {
        CsvTable table = CsvTable.parse("menu.csv", "﻿Code;Name\r\nP01;\"Calabresa; \"\"especial\"\"\"\r\n;\r\nP02;Margherita\r\n",
                Set.of("code", "name"), Set.of());

        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).get("name")).isEqualTo("Calabresa; \"especial\"");
        assertThat(table.rows().get(1).line()).isEqualTo(4);
    }

    @Test
    void readsCommaSeparatedFilesAndQuotedLineBreaks() {
        CsvTable table = CsvTable.parse("menu.csv", "code,description\nP01,\"two\nlines\"\nP02,x",
                Set.of("code"), Set.of("description"));

        assertThat(table.rows()).extracting(row -> row.get("description")).containsExactly("two\nlines", "x");
        assertThat(table.rows().get(1).line()).isEqualTo(4);
    }

    @Test
    void rejectsUnknownMissingAndRepeatedColumns() {
        assertThatThrownBy(() -> CsvTable.parse("menu.csv", "code;preco;code\n", Set.of("code", "price"), Set.of()))
                .isInstanceOfSatisfying(CsvImportException.class, e -> assertThat(e.errors())
                        .anySatisfy(error -> assertThat(error).contains("unknown column 'preco'"))
                        .anySatisfy(error -> assertThat(error).contains("appears twice"))
                        .anySatisfy(error -> assertThat(error).contains("missing column 'price'")));
    }

    @Test
    void rejectsAnUnclosedQuoteAndExtraFields() {
        assertThatThrownBy(() -> CsvTable.parse("menu.csv", "code\n\"P01", Set.of("code"), Set.of()))
                .isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> CsvTable.parse("menu.csv", "code,name\nP01,x,extra", Set.of("code", "name"), Set.of()))
                .isInstanceOf(CsvImportException.class);
    }

    @Test
    void parsesAmountsInBrazilianAndPlainFormats() {
        assertThat(money("39,90")).isEqualByComparingTo("39.90");
        assertThat(money("39.90")).isEqualByComparingTo("39.90");
        assertThat(money("R$ 1.234,50")).isEqualByComparingTo("1234.50");
        assertThat(money("8")).isEqualByComparingTo("8");
        assertThat(money("abc")).isNull();
        assertThat(money("-1")).isNull();
        assertThat(money("1,999")).isNull();
    }

    private static BigDecimal money(String value) {
        List<String> errors = new ArrayList<>();
        return BusinessDataImporter.money(new CsvTable.Row(2, Map.of("price", value)), "price", true, errors);
    }
}
