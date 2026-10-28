package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.catalog.CatalogSearch;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu items and prices. Without a query or category it lists the categories, so the LLM can
 * describe the menu without dumping every item.
 */
public final class SearchMenuTool implements Tool {

    static final int LIMIT = 15;

    private final BusinessDirectory directory;

    public SearchMenuTool(BusinessDirectory directory) {
        this.directory = directory;
    }

    @Override
    public String name() {
        return "search_menu";
    }

    @Override
    public String description() {
        return """
                Busca itens do cardápio do restaurante, com preço, disponibilidade e opções \
                (tamanhos, adicionais). Use sempre que o cliente perguntar se há um prato, quanto \
                custa ou o que tem em uma categoria; nunca informe preço ou item que não veio desta \
                ferramenta. Sem "query" nem "category", devolve só as categorias do cardápio. \
                Itens com "available": false existem, mas estão em falta hoje.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object", "additionalProperties": false, "properties": {
                  "query": {"type": "string", "description": "O que o cliente procura, ex.: \\"pizza calabresa\\""},
                  "category": {"type": "string", "description": "Categoria do cardápio, ex.: \\"Bebidas\\""}
                }}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        JsonNode input = ToolJson.input(invocation.inputJson());
        String query = ToolJson.text(input, "query");
        String category = ToolJson.text(input, "category");
        List<CatalogItem> catalog = directory.catalog(invocation.tenantId());

        ObjectNode result = ToolJson.object();
        if (catalog.isEmpty()) {
            result.put("menuRegistered", false);
            return ToolResult.ok(ToolJson.json(result));
        }
        if (query == null && category == null) {
            result.set("categories", categories(catalog));
            return ToolResult.ok(ToolJson.json(result));
        }
        List<CatalogItem> found = CatalogSearch.search(catalog, query, category, LIMIT + 1);
        ArrayNode items = result.putArray("items");
        found.stream().limit(LIMIT).forEach(item -> items.add(item(item)));
        result.put("moreResults", found.size() > LIMIT);
        if (found.isEmpty()) {
            result.set("categories", categories(catalog));
        }
        return ToolResult.ok(ToolJson.json(result));
    }

    private static ArrayNode categories(List<CatalogItem> catalog) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        catalog.forEach(item -> counts.merge(item.category(), 1, Integer::sum));
        ArrayNode categories = ToolJson.MAPPER.createArrayNode();
        counts.forEach((name, count) -> categories.addObject().put("name", name).put("items", count));
        return categories;
    }

    private static ObjectNode item(CatalogItem item) {
        ObjectNode node = ToolJson.object()
                .put("code", item.code())
                .put("category", item.category())
                .put("name", item.name());
        if (item.description() != null && !item.description().isBlank()) {
            node.put("description", item.description());
        }
        ToolJson.putMoney(node, "price", item.price());
        node.put("available", item.available());
        if (!item.options().isEmpty()) {
            ArrayNode options = node.putArray("options");
            for (CatalogOption option : item.options()) {
                ObjectNode optionNode = options.addObject().put("group", option.group()).put("name", option.name());
                ToolJson.putMoney(optionNode, "priceDelta", option.priceDelta());
            }
        }
        return node;
    }
}
