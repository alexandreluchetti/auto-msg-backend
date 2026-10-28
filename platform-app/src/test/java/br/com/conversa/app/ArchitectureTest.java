package br.com.conversa.app;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fronteiras entre módulos (ADR-007): segmentos e adaptadores dependem só do core.
 */
@AnalyzeClasses(packages = "br.com.conversa", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreIsPlainJava = noClasses()
            .that().resideInAPackage("br.com.conversa.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..",
                    "br.com.conversa.channel..", "br.com.conversa.llm..",
                    "br.com.conversa.segment..", "br.com.conversa.app..");

    @ArchTest
    static final ArchRule segmentsDependOnlyOnCore = noClasses()
            .that().resideInAPackage("br.com.conversa.segment..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "br.com.conversa.channel..", "br.com.conversa.llm..", "br.com.conversa.app..");

    @ArchTest
    static final ArchRule channelsDoNotKnowLlmOrSegments = noClasses()
            .that().resideInAPackage("br.com.conversa.channel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "br.com.conversa.llm..", "br.com.conversa.segment..", "br.com.conversa.app..");

    @ArchTest
    static final ArchRule llmDoesNotKnowChannelsOrSegments = noClasses()
            .that().resideInAPackage("br.com.conversa.llm..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "br.com.conversa.channel..", "br.com.conversa.segment..", "br.com.conversa.app..");
}
