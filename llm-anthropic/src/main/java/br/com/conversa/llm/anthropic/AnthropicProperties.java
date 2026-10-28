package br.com.conversa.llm.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do adaptador Claude. O modelo pode ser sobrescrito por tenant. Ver ADR-005.
 *
 * @param apiKey       chave da API (variável de ambiente, nunca versionada)
 * @param defaultModel modelo padrão quando o tenant não define um
 */
@ConfigurationProperties("conversa.llm.anthropic")
public record AnthropicProperties(String apiKey, String defaultModel) {
}
